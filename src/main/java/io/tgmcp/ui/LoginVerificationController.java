package io.tgmcp.ui;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.tgmcp.security.LoginTwoFactorService;
import io.tgmcp.security.ManualSessionEstablisher;
import io.tgmcp.security.TelegramBotMessageException;
import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;
import io.tgmcp.telegram.TelegramConnectionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The mandatory second factor: whoever {@link LoginTwoFactorService#beginVerification} sent a
 * code to must enter it here before a session actually exists. Reachable without any
 * authentication (like {@link RegistrationController}), so it handles its own errors locally
 * rather than through {@link UiExceptionHandler}.
 */
@Controller
public class LoginVerificationController {

    private static final Logger log = LoggerFactory.getLogger(LoginVerificationController.class);

    /** Set by {@code TelegramLoginController} for a brand-new "Sign in with Telegram" signup only. */
    static final String AUTOSTART_QR_SESSION_KEY = "tgmcp.autoStartQrAfterVerification";

    private final LoginTwoFactorService twoFactorService;
    private final AppUserRepository users;
    private final ManualSessionEstablisher sessionEstablisher;
    private final TelegramConnectionService connectionService;
    private final MessageSource messages;

    public LoginVerificationController(LoginTwoFactorService twoFactorService, AppUserRepository users,
                                       ManualSessionEstablisher sessionEstablisher,
                                       TelegramConnectionService connectionService, MessageSource messages) {
        this.twoFactorService = twoFactorService;
        this.users = users;
        this.sessionEstablisher = sessionEstablisher;
        this.connectionService = connectionService;
        this.messages = messages;
    }

    @GetMapping("/login/verify")
    public String show(HttpServletRequest request) {
        if (this.twoFactorService.pendingUserId(request).isEmpty()) {
            return "redirect:/login";
        }
        return "login-verify";
    }

    @PostMapping("/login/verify")
    public String verify(@RequestParam String code, HttpServletRequest request, HttpServletResponse response,
                         RedirectAttributes redirectAttributes) {
        UUID userId;
        try {
            userId = this.twoFactorService.verify(request, code);
        }
        catch (IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
            if ("login.verify.noPendingLogin".equals(ex.getMessage())) {
                return "redirect:/login";
            }
            return "redirect:/login/verify";
        }
        AppUser user = this.users.findById(userId).orElse(null);
        if (user == null || !user.enabled()) {
            return "redirect:/login?telegramError";
        }
        this.sessionEstablisher.signIn(user, request, response);
        Object autoStartQr = request.getSession().getAttribute(AUTOSTART_QR_SESSION_KEY);
        request.getSession().removeAttribute(AUTOSTART_QR_SESSION_KEY);
        if (Boolean.TRUE.equals(autoStartQr)) {
            return TelegramLoginController.startQrConnectionFor(user, this.connectionService, this.messages,
                    redirectAttributes);
        }
        return "redirect:/";
    }

    @PostMapping("/login/verify/resend")
    public String resend(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        UUID userId = this.twoFactorService.pendingUserId(request).orElse(null);
        if (userId == null) {
            return "redirect:/login";
        }
        AppUser user = this.users.findById(userId).orElse(null);
        if (user == null) {
            return "redirect:/login";
        }
        try {
            this.twoFactorService.resend(request, user);
            redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, "login.verify.resent"));
        }
        catch (IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
        }
        catch (TelegramBotMessageException ex) {
            log.warn("Could not resend a second-factor code to Telegram user {}: {}", user.telegramUserId(),
                    ex.getMessage());
            redirectAttributes.addFlashAttribute("error",
                    Messages.text(this.messages, "login.telegramCodeError"));
        }
        return "redirect:/login/verify";
    }
}
