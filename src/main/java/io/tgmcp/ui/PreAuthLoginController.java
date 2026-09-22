package io.tgmcp.ui;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.tgmcp.security.PreAuthLoginService;
import io.tgmcp.telegram.gateway.LoginMethod;
import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.telegram.gateway.LoginState;
import io.tgmcp.telegram.gateway.TelegramGatewayException;

import org.springframework.context.MessageSource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The only way into the application: signing in is the same act as connecting Telegram. A
 * phone number (or, for a brand-new account, a QR scan) plus whatever Telegram itself demands
 * next -- a code, and a two-factor password if one is set -- is the whole login. See {@link
 * PreAuthLoginService} for why a returning account is disconnected and fully re-authenticated
 * on every sign-in, and why QR-code login only ever creates a new account.
 *
 * <p>Handles its own errors locally, like {@code UiExceptionHandler}'s advice does for the
 * authenticated controllers, but redirecting to {@code /login} rather than {@code /}: this
 * page is reachable without any session, so there is no dashboard to fall back to.
 */
@Controller
public class PreAuthLoginController {

    private final PreAuthLoginService loginService;
    private final MessageSource messages;
    private final QrCodeRenderer qrCodeRenderer;

    public PreAuthLoginController(PreAuthLoginService loginService, MessageSource messages,
                                  QrCodeRenderer qrCodeRenderer) {
        this.loginService = loginService;
        this.messages = messages;
        this.qrCodeRenderer = qrCodeRenderer;
    }

    @GetMapping("/login")
    public String show(HttpServletRequest request, Model model) {
        LoginState state;
        try {
            state = this.loginService.status(request);
        }
        catch (IllegalStateException ex) {
            state = null;
        }
        model.addAttribute("state", state);
        model.addAttribute("stage", state == null ? "PHONE_ENTRY" : state.stage().name());
        if (state != null && state.message() != null) {
            model.addAttribute("stateHint", Messages.text(this.messages, state.message()));
        }
        if (state != null && state.qrLoginLink() != null) {
            model.addAttribute("qrCodeImage", this.qrCodeRenderer.toDataUri(state.qrLoginLink()));
        }
        if (state != null && state.nextCodeChannelKey() != null) {
            // Confirmed from a real follow-up report: a visitor stuck on a channel they cannot
            // check (e.g. the Telegram app on a device they don't have) had no way to learn that
            // Telegram itself -- not this app -- will switch to a different, named channel once
            // resendAuthenticationCode is allowed again. Telegram refuses that call outright
            // ("Authentication code can't be resend") until code_info.timeout has elapsed, so
            // this is the exact wait this visitor needs before "Resend" will actually help.
            String channelName = Messages.text(this.messages, "channel." + state.nextCodeChannelKey());
            model.addAttribute("resendChannelHint", Messages.text(this.messages,
                    "login.tdlib.nextCodeChannelHint", state.resendAvailableInSeconds(), channelName));
        }
        return "login";
    }

    @PostMapping("/login/start")
    public String start(@RequestParam LoginMethod method,
                        @RequestParam(name = "phoneNumber", required = false) String phoneNumber,
                        HttpServletRequest request, HttpServletResponse response,
                        RedirectAttributes redirectAttributes) {
        try {
            LoginState state = this.loginService.begin(request, method, phoneNumber);
            if (state.ready() && this.loginService.finishIfReady(request, response)) {
                return "redirect:/";
            }
        }
        catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
        }
        catch (TelegramGatewayException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, "login.telegramUnavailable"));
        }
        catch (DataAccessException ex) {
            // A brand-new phone number's eager tenant/account creation racing an identical
            // concurrent attempt (e.g. a double-tap before the resubmit guard disables the
            // button) hits the same unique constraint that protects it -- retrying now finds the
            // account the other attempt just created instead of colliding with it again.
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, "error.unexpected"));
        }
        return "redirect:/login";
    }

    @PostMapping("/login/code")
    public String code(@RequestParam String code, HttpServletRequest request, HttpServletResponse response,
                       RedirectAttributes redirectAttributes) {
        try {
            LoginState state = this.loginService.submitCode(request, code);
            if (state.ready() && this.loginService.finishIfReady(request, response)) {
                return "redirect:/";
            }
        }
        catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
        }
        catch (TelegramGatewayException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, "login.telegramUnavailable"));
        }
        return "redirect:/login";
    }

    /**
     * Telegram itself refuses a resend requested too soon after the original code (or after a
     * previous resend) -- {@code TelegramGatewayException} surfaces that refusal here rather
     * than as an uncaught 500, which from the visitor's side looked identical to the code never
     * having been sent at all.
     */
    @PostMapping("/login/code/resend")
    public String resendCode(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        try {
            this.loginService.resendCode(request);
            redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, "login.verify.resent"));
        }
        catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
        }
        catch (TelegramGatewayException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, "login.telegramUnavailable"));
        }
        return "redirect:/login";
    }

    @PostMapping("/login/password")
    public String password(@RequestParam String password, HttpServletRequest request, HttpServletResponse response,
                           RedirectAttributes redirectAttributes) {
        try {
            LoginState state = this.loginService.submitPassword(request, password);
            if (state.ready() && this.loginService.finishIfReady(request, response)) {
                return "redirect:/";
            }
        }
        catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
        }
        catch (TelegramGatewayException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, "login.telegramUnavailable"));
        }
        return "redirect:/login";
    }

    @PostMapping("/login/cancel")
    public String cancel(HttpServletRequest request) {
        this.loginService.cancel(request);
        return "redirect:/login";
    }

    /**
     * Polled by {@code /js/login.js} while the rendered stage is one that can still move on
     * asynchronously (a QR scan) -- see {@code ConnectionController#status} for the identical
     * reasoning on the authenticated Connect page.
     */
    @GetMapping("/login/status")
    @ResponseBody
    public Map<String, String> status(HttpServletRequest request) {
        try {
            LoginState state = this.loginService.status(request);
            if (state.stage() == LoginStage.READY) {
                // Security-audit finding SEC-R07: the nonce /login/finish will require is
                // handed out here, and only once the stage really is READY -- never any
                // earlier, so nothing observing this response before then could ever learn it.
                String nonce = this.loginService.pendingFinishNonce(request).orElse(null);
                return nonce == null ? Map.of("stage", state.stage().name())
                        : Map.of("stage", state.stage().name(), "nonce", nonce);
            }
            return Map.of("stage", state.stage().name());
        }
        catch (IllegalStateException ex) {
            return Map.of("stage", "NONE");
        }
    }

    /**
     * Reached once client-side polling sees the stage move to READY (a QR scan confirmed out of
     * band) -- {@code /login/code}/{@code /login/password} already call the same {@link
     * PreAuthLoginService#finishIfReady} synchronously for the phone-number method, since that
     * transition normally completes within the same request there.
     *
     * <p>Security-audit finding SEC-R07: a POST behind the same CSRF protection every other
     * state-changing step here already has, rather than the plain, side-effecting GET this used
     * to be -- a GET can be triggered by a browser prefetcher, a link-preview crawler, or a
     * same-site {@code <img>}/navigation with no user action at all, none of which should be
     * able to complete a sign-in. The nonce {@code /login/status} handed out is required on top
     * of CSRF: it ties this specific submission back to the specific READY observation that
     * justified it, and is consumed (the whole pending login is cleared) the instant it works.
     */
    @PostMapping("/login/finish")
    public String finish(@RequestParam String nonce, HttpServletRequest request, HttpServletResponse response,
                         RedirectAttributes redirectAttributes) {
        try {
            if (this.loginService.finishIfReady(request, response, nonce)) {
                return "redirect:/";
            }
        }
        catch (IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
        }
        return "redirect:/login";
    }
}
