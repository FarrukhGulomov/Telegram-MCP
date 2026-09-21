package io.tgmcp.ui;

import java.time.Clock;
import java.time.Duration;

import io.tgmcp.config.AppProperties;
import io.tgmcp.ratelimit.RateLimiter;
import io.tgmcp.tenancy.AuthenticatedActor;
import io.tgmcp.telegram.TelegramConnectionService;
import io.tgmcp.telegram.gateway.LoginState;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The Telegram connection's status: read-only now that signing in is the same act as
 * connecting ({@code PreAuthLoginController}). What remains here -- refreshing the visible
 * chat list, and disconnecting -- are the only things left to do about an existing connection
 * without a fresh login.
 */
@Controller
public class ConnectionController {

    private final CurrentActor currentActor;
    private final TelegramConnectionService connectionService;
    private final AppProperties properties;
    private final MessageSource messages;
    /**
     * Security-audit finding SEC-10: onboarding actions can trigger real, costly native TDLib
     * work (a full chat discovery pass). They previously shared no budget distinct from
     * ordinary authenticated page views, so a compromised or careless owner account could
     * repeatedly trigger that work with no independent limit of its own.
     */
    private final RateLimiter connectLimiter;

    public ConnectionController(CurrentActor currentActor,
                                TelegramConnectionService connectionService,
                                AppProperties properties,
                                MessageSource messages,
                                Clock clock) {
        this.currentActor = currentActor;
        this.connectionService = connectionService;
        this.properties = properties;
        this.messages = messages;
        this.connectLimiter = new RateLimiter(properties.abuseControls().connectActionsPerMinutePerUser(),
                Duration.ofMinutes(1), clock);
    }

    /** @return true (and sets the flash error) if the caller's onboarding budget is spent. */
    private boolean rateLimited(AuthenticatedActor actor, RedirectAttributes redirectAttributes) {
        if (this.connectLimiter.tryAcquire(actor.tenantId() + ":" + actor.userId())) {
            return false;
        }
        redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, "error.rateLimited"));
        return true;
    }

    @GetMapping("/connect")
    public String connect(Model model) {
        AuthenticatedActor actor = this.currentActor.require();
        LoginState state = this.connectionService.status(actor);
        model.addAttribute("connection", this.connectionService.getOrCreate(actor));
        model.addAttribute("state", state);
        // state.message() is a short code (see LoginState); resolved here so the template
        // never has to know the difference between a translated status and a flash message.
        model.addAttribute("statusMessage",
                state.message() == null ? null : Messages.text(this.messages, state.message()));
        model.addAttribute("adapter", this.properties.telegram().adapter().name());
        model.addAttribute("demoMode", this.properties.telegram().adapter() == TelegramAdapterKind.MOCK);
        return "connect";
    }

    @PostMapping("/connect/discover")
    public String discover(RedirectAttributes redirectAttributes) {
        AuthenticatedActor actor = this.currentActor.requireAdmin();
        if (rateLimited(actor, redirectAttributes)) {
            return "redirect:/";
        }
        int count = this.connectionService.discoverChats(actor).size();
        redirectAttributes.addFlashAttribute("message",
                Messages.text(this.messages, "connect.discover.success", count));
        return "redirect:/";
    }

    @PostMapping("/connect/disconnect")
    public String disconnect(RedirectAttributes redirectAttributes) {
        AuthenticatedActor actor = this.currentActor.requireAdmin();
        if (rateLimited(actor, redirectAttributes)) {
            return "redirect:/connect";
        }
        this.connectionService.disconnect(actor);
        redirectAttributes.addFlashAttribute("message",
                Messages.text(this.messages, "connect.disconnect.success"));
        return "redirect:/connect";
    }
}
