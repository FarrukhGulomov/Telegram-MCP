package io.tgmcp.ui;

import io.tgmcp.config.AppProperties;
import io.tgmcp.tenancy.SelfRegistrationService;

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
 */
@Controller
public class RegistrationController {

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
                           RedirectAttributes redirectAttributes) {
        try {
            if (!password.equals(passwordConfirm)) {
                throw new IllegalArgumentException("register.passwordMismatch");
            }
            this.registrationService.register(workspaceName, email, password);
        }
        catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
            redirectAttributes.addFlashAttribute("workspaceName", workspaceName);
            redirectAttributes.addFlashAttribute("email", email);
            return "redirect:/register";
        }
        return "redirect:/login?registered";
    }
}
