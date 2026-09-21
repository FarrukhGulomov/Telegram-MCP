package io.tgmcp.ui;

import jakarta.servlet.http.HttpServletRequest;

import io.tgmcp.config.AppProperties;
import io.tgmcp.security.LoginTwoFactorService;
import io.tgmcp.security.TelegramBotMessageException;
import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * "Sign in with a Telegram code": passwordless -- an already-linked account (see {@link
 * RegistrationController}) can sign in with only a code sent to its Telegram chat, identified by
 * either its email or its Telegram username. Unlike the mandatory second factor a password
 * check triggers ({@code WebSecurityConfig}'s form-login success handler), nothing further is
 * required once the code checks out: proving control of that Telegram account already is the
 * whole login.
 *
 * <p>Deliberately gives the same response whether or not the identifier matches an account (an
 * unlinked or nonexistent one just never receives a code): revealing that distinction would let
 * this form be used to test which emails or Telegram usernames have accounts here at all, the
 * same concern {@link io.tgmcp.tenancy.SelfRegistrationService} already documents for
 * registration.
 */
@Controller
public class TelegramCodeLoginController {

    private static final Logger log = LoggerFactory.getLogger(TelegramCodeLoginController.class);

    private final AppProperties properties;
    private final AppUserRepository users;
    private final LoginTwoFactorService twoFactorService;
    private final MessageSource messages;

    public TelegramCodeLoginController(AppProperties properties, AppUserRepository users,
                                       LoginTwoFactorService twoFactorService, MessageSource messages) {
        this.properties = properties;
        this.users = users;
        this.twoFactorService = twoFactorService;
        this.messages = messages;
    }

    @GetMapping("/login/telegram-code")
    public String show() {
        if (!this.properties.telegramLogin().enabled()) {
            return "redirect:/login";
        }
        return "login-telegram-code";
    }

    @PostMapping("/login/telegram-code")
    public String send(@RequestParam String identifier, HttpServletRequest request,
                       RedirectAttributes redirectAttributes) {
        if (!this.properties.telegramLogin().enabled()) {
            return "redirect:/login";
        }
        String normalized = identifier == null ? "" : identifier.strip();
        // A leading "@" marks a Telegram username, not an email -- checked first so "@handle"
        // is never mistaken for one just because it contains an "@" character.
        AppUser user = normalized.startsWith("@")
                ? this.users.findByTelegramUsername(normalized.substring(1)).orElse(null)
                : normalized.contains("@")
                        ? this.users.findByEmail(normalized).orElse(null)
                        : this.users.findByTelegramUsername(normalized).orElse(null);

        if (user != null && user.enabled() && user.telegramUserId() != null) {
            try {
                this.twoFactorService.beginVerification(user, request);
                return "redirect:/login/verify";
            }
            catch (TelegramBotMessageException ex) {
                log.warn("Could not deliver a \"sign in with a Telegram code\" code to Telegram user {}: {}",
                        user.telegramUserId(), ex.getMessage());
                // Falls through to the same generic message as a non-match, on purpose.
            }
        }
        redirectAttributes.addFlashAttribute("message",
                Messages.text(this.messages, "login.telegramCode.sentIfMatched"));
        return "redirect:/login/telegram-code";
    }
}
