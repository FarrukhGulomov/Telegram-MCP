package io.tgmcp.ui;

import java.time.Clock;
import java.time.Duration;

import io.tgmcp.config.AppProperties;
import io.tgmcp.ratelimit.RateLimiter;
import io.tgmcp.tenancy.AuthenticatedActor;
import io.tgmcp.telegram.TelegramConnectionService;
import io.tgmcp.telegram.gateway.LoginMethod;
import io.tgmcp.telegram.gateway.LoginState;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Telegram onboarding.
 *
 * <p>These are the only endpoints that ever see a login code, a 2FA password or a QR token.
 * They are behind the authenticated, CSRF-protected UI filter chain, they pass the value
 * straight to the adapter, and they never place it in a model attribute, a redirect
 * parameter or a log line.
 */
@Controller
public class ConnectionController {

    private final CurrentActor currentActor;
    private final TelegramConnectionService connectionService;
    private final AppProperties properties;
    private final MessageSource messages;
    /**
     * Security-audit finding SEC-10: onboarding actions can trigger real, costly native TDLib
     * work (a login attempt, a full chat discovery pass). They previously shared no budget
     * distinct from ordinary authenticated page views, so a compromised or careless owner
     * account could repeatedly trigger that work with no independent limit of its own.
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

    @PostMapping("/connect/start")
    public String start(@RequestParam("method") LoginMethod method,
                        @RequestParam(name = "phoneNumber", required = false) String phoneNumber,
                        RedirectAttributes redirectAttributes) {
        AuthenticatedActor actor = this.currentActor.requireAdmin();
        if (rateLimited(actor, redirectAttributes)) {
            return "redirect:/connect";
        }
        LoginState state = this.connectionService.beginLogin(actor, method, phoneNumber);
        redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, state.message()));
        return "redirect:/connect";
    }

    @PostMapping("/connect/code")
    public String code(@RequestParam("code") String code, RedirectAttributes redirectAttributes) {
        AuthenticatedActor actor = this.currentActor.requireAdmin();
        if (rateLimited(actor, redirectAttributes)) {
            return "redirect:/connect";
        }
        LoginState state = this.connectionService.submitCode(actor, code);
        redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, state.message()));
        return "redirect:/connect";
    }

    @PostMapping("/connect/password")
    public String password(@RequestParam("password") String password, RedirectAttributes redirectAttributes) {
        AuthenticatedActor actor = this.currentActor.requireAdmin();
        if (rateLimited(actor, redirectAttributes)) {
            return "redirect:/connect";
        }
        LoginState state = this.connectionService.submitPassword(actor, password);
        redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, state.message()));
        return "redirect:/connect";
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
