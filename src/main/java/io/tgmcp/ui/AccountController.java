package io.tgmcp.ui;

import java.time.Clock;
import java.time.Duration;

import io.tgmcp.config.AppProperties;
import io.tgmcp.ratelimit.RateLimiter;
import io.tgmcp.security.ConnectedAppRepository;
import io.tgmcp.tenancy.AccountService;
import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AuthenticatedActor;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Self-service account security: a password change, and visibility into (with the ability to
 * revoke) which MCP clients -- Claude, ChatGPT, or anything else dynamically registered -- hold
 * a standing OAuth authorization for this account. Open to any signed-in tenant member
 * regardless of role, unlike {@code /chats/**}/{@code /connect/**}: these actions only ever
 * touch the caller's own credentials and grants, never another member's or the tenant's shared
 * Telegram connection.
 */
@Controller
public class AccountController {

    private final CurrentActor currentActor;
    private final AccountService accountService;
    private final ConnectedAppRepository connectedApps;
    private final MessageSource messages;
    private final RateLimiter accountActionLimiter;

    public AccountController(CurrentActor currentActor, AccountService accountService,
                             ConnectedAppRepository connectedApps, MessageSource messages,
                             AppProperties properties, Clock clock) {
        this.currentActor = currentActor;
        this.accountService = accountService;
        this.connectedApps = connectedApps;
        this.messages = messages;
        this.accountActionLimiter = new RateLimiter(properties.abuseControls().accountActionsPerMinutePerUser(),
                Duration.ofMinutes(1), clock);
    }

    private boolean rateLimited(String key, RedirectAttributes redirectAttributes) {
        if (this.accountActionLimiter.tryAcquire(key)) {
            return false;
        }
        redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, "error.rateLimited"));
        return true;
    }

    @GetMapping("/account")
    public String show(Model model) {
        AuthenticatedActor actor = this.currentActor.require();
        model.addAttribute("connectedApps", this.connectedApps.findByPrincipal(actor.subject()));
        return "account";
    }

    @PostMapping("/account/password")
    public String changePassword(@RequestParam String currentPassword, @RequestParam String newPassword,
                                 @RequestParam String newPasswordConfirm, RedirectAttributes redirectAttributes) {
        AppUser user = this.currentActor.requireUser();
        if (rateLimited(user.tenantId() + ":" + user.id(), redirectAttributes)) {
            return "redirect:/account";
        }
        try {
            this.accountService.changePassword(user, currentPassword, newPassword, newPasswordConfirm);
        }
        catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
            return "redirect:/account";
        }
        redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, "account.password.success"));
        return "redirect:/account";
    }

    @PostMapping("/account/connections/revoke")
    public String revokeConnection(@RequestParam String clientId, RedirectAttributes redirectAttributes) {
        AuthenticatedActor actor = this.currentActor.require();
        if (rateLimited(actor.tenantId() + ":" + actor.userId(), redirectAttributes)) {
            return "redirect:/account";
        }
        this.connectedApps.revoke(actor.subject(), clientId);
        redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, "account.connections.revoked"));
        return "redirect:/account";
    }
}
