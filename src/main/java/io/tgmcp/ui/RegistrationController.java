package io.tgmcp.ui;

import jakarta.servlet.http.HttpServletRequest;

import io.tgmcp.config.AppProperties;
import io.tgmcp.tenancy.SelfRegistrationService;
import io.tgmcp.tenancy.SelfRegistrationService.PendingRegistration;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Self-service sign-up: a new team creates its own tenant and OWNER account instead of an
 * operator seeding one by hand. Handles its own errors locally (rather than through {@link
 * UiExceptionHandler}, which is scoped to the authenticated-only controllers and redirects to
 * {@code /}) so a failed attempt lands the visitor back on this same, still-unauthenticated
 * form with the reason shown.
 *
 * <p>When "Sign in with Telegram" is configured, the account is not actually created by this
 * form alone: {@link #register} validates the fields and holds them as a {@link
 * PendingRegistration} in the session ({@link #SESSION_KEY}), then sends the visitor to link a
 * Telegram identity before {@link io.tgmcp.ui.TelegramLoginController#callback} completes the
 * registration and starts the mandatory second-factor code. This keeps a password alone from
 * ever being enough to create a usable account once Telegram sign-in exists as a deployment
 * option, and it means an abandoned attempt (the visitor never finishes the Telegram step)
 * leaves no half-created account behind -- nothing was ever inserted.
 */
@Controller
public class RegistrationController {

    static final String SESSION_KEY = "tgmcp.pendingRegistration";

    private final AppProperties properties;
    private final SelfRegistrationService registrationService;
    private final MessageSource messages;

    public RegistrationController(AppProperties properties, SelfRegistrationService registrationService,
                                  MessageSource messages) {
        this.properties = properties;
        this.registrationService = registrationService;
        this.messages = messages;
    }

    @GetMapping("/register")
    public String showForm(Model model) {
        model.addAttribute("telegramLoginEnabled", this.properties.telegramLogin().enabled());
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String workspaceName, @RequestParam String email,
                           @RequestParam String password, @RequestParam String passwordConfirm,
                           HttpServletRequest request, RedirectAttributes redirectAttributes) {
        PendingRegistration pending;
        try {
            if (!password.equals(passwordConfirm)) {
                throw new IllegalArgumentException("register.passwordMismatch");
            }
            pending = this.registrationService.validate(workspaceName, email, password);
        }
        catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
            redirectAttributes.addFlashAttribute("workspaceName", workspaceName);
            redirectAttributes.addFlashAttribute("email", email);
            return "redirect:/register";
        }
        if (!this.properties.telegramLogin().enabled()) {
            this.registrationService.completeRegistration(pending, null);
            return "redirect:/login?registered";
        }
        request.getSession(true).setAttribute(SESSION_KEY, pending);
        return "redirect:/register/link-telegram";
    }

    @GetMapping("/register/link-telegram")
    public String linkTelegram(HttpServletRequest request, Model model) {
        if (!this.properties.telegramLogin().enabled() || pendingRegistration(request) == null) {
            return "redirect:/register";
        }
        model.addAttribute("telegramTaken", request.getParameter("telegramTaken") != null);
        return "register-link-telegram";
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
