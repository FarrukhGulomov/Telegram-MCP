package io.tgmcp.ui;

import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import io.tgmcp.config.AppProperties;
import io.tgmcp.security.LoginTwoFactorService;
import io.tgmcp.security.TelegramBotMessageException;
import io.tgmcp.security.TelegramLinkToken;
import io.tgmcp.security.TelegramLinkingService;
import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.SelfRegistrationService;
import io.tgmcp.tenancy.SelfRegistrationService.PendingRegistration;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Self-service sign-up: a new team creates its own tenant and OWNER account instead of an
 * operator seeding one by hand. Handles its own errors locally (rather than through {@link
 * UiExceptionHandler}, which is scoped to the authenticated-only controllers and redirects to
 * {@code /}) so a failed attempt lands the visitor back on this same, still-unauthenticated
 * form with the reason shown.
 *
 * <p>The account is not actually created by this form alone: {@link #register} validates the
 * fields and holds them as a {@link PendingRegistration} in the session ({@link #SESSION_KEY}),
 * then sends the visitor to link a Telegram identity -- by opening the bot and sending
 * {@code /start <token>} ({@link TelegramLinkingService}, {@code TelegramBotWebhookController}),
 * not a widget click -- before {@link #linkStatus} completes the registration and sends the
 * first sign-in code. There is no other way to reach an account (no password exists), so this
 * means an abandoned attempt (the visitor never finishes the Telegram step) leaves no
 * half-created account behind -- nothing was ever inserted.
 */
@Controller
public class RegistrationController {

    static final String SESSION_KEY = "tgmcp.pendingRegistration";
    private static final String TOKEN_SESSION_KEY = "tgmcp.pendingLinkToken";

    private final AppProperties properties;
    private final SelfRegistrationService registrationService;
    private final TelegramLinkingService linkingService;
    private final LoginTwoFactorService twoFactorService;
    private final MessageSource messages;
    private final String botUsername;

    public RegistrationController(AppProperties properties, SelfRegistrationService registrationService,
                                  TelegramLinkingService linkingService, LoginTwoFactorService twoFactorService,
                                  MessageSource messages) {
        this.properties = properties;
        this.registrationService = registrationService;
        this.linkingService = linkingService;
        this.twoFactorService = twoFactorService;
        this.messages = messages;
        this.botUsername = properties.telegramLogin().botUsername();
    }

    @GetMapping("/register")
    public String showForm(Model model) {
        model.addAttribute("telegramLoginEnabled", this.properties.telegramLogin().enabled());
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String workspaceName, @RequestParam String email,
                           HttpServletRequest request, RedirectAttributes redirectAttributes) {
        if (!this.properties.telegramLogin().enabled()) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, "register.unavailable"));
            return "redirect:/register";
        }
        PendingRegistration pending;
        try {
            pending = this.registrationService.validate(workspaceName, email);
        }
        catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
            redirectAttributes.addFlashAttribute("workspaceName", workspaceName);
            redirectAttributes.addFlashAttribute("email", email);
            return "redirect:/register";
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_KEY, pending);
        session.removeAttribute(TOKEN_SESSION_KEY);
        return "redirect:/register/link-telegram";
    }

    @GetMapping("/register/link-telegram")
    public String linkTelegram(HttpServletRequest request, Model model) {
        if (!this.properties.telegramLogin().enabled() || pendingRegistration(request) == null) {
            return "redirect:/register";
        }
        HttpSession session = request.getSession(true);
        String token = (String) session.getAttribute(TOKEN_SESSION_KEY);
        if (token == null) {
            token = this.linkingService.createLinkToken();
            session.setAttribute(TOKEN_SESSION_KEY, token);
        }
        model.addAttribute("deepLink", "https://t.me/" + this.botUsername + "?start=" + token);
        model.addAttribute("token", token);
        return "register-link-telegram";
    }

    /**
     * Polled by {@code /js/link-telegram.js} while the visitor is away in their Telegram app.
     * Idempotent: once registration actually completes, {@link #SESSION_KEY} is cleared, so a
     * poll that arrives after that (a slow browser, or two in flight at once) takes the "nothing
     * left to do" branch below instead of creating a second account.
     */
    @GetMapping("/register/link-telegram/status")
    @ResponseBody
    public Map<String, String> linkStatus(@RequestParam String token, HttpServletRequest request) {
        PendingRegistration pending = pendingRegistration(request);
        if (pending == null) {
            return Map.of("redirectTo", "/register");
        }
        Optional<TelegramLinkToken> linked = this.linkingService.check(token);
        if (linked.isEmpty()) {
            return Map.of("redirectTo", "");
        }
        AppUser user;
        try {
            user = this.registrationService.completeRegistration(pending, linked.get().telegramUserId(),
                    linked.get().telegramUsername());
        }
        catch (IllegalArgumentException ex) {
            request.getSession().removeAttribute(SESSION_KEY);
            return Map.of("redirectTo", "/register");
        }
        request.getSession().removeAttribute(SESSION_KEY);
        try {
            this.twoFactorService.beginVerification(user, request);
        }
        catch (TelegramBotMessageException ex) {
            return Map.of("redirectTo", "/login?telegramCodeError");
        }
        return Map.of("redirectTo", "/login/verify");
    }

    static PendingRegistration pendingRegistration(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(SESSION_KEY);
        return value instanceof PendingRegistration pending ? pending : null;
    }
}
