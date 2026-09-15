package io.tgmcp.ui;

import io.tgmcp.config.AppProperties;
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

    public ConnectionController(CurrentActor currentActor,
                                TelegramConnectionService connectionService,
                                AppProperties properties,
                                MessageSource messages) {
        this.currentActor = currentActor;
        this.connectionService = connectionService;
        this.properties = properties;
        this.messages = messages;
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
        LoginState state = this.connectionService.beginLogin(this.currentActor.requireAdmin(), method, phoneNumber);
        redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, state.message()));
        return "redirect:/connect";
    }

    @PostMapping("/connect/code")
    public String code(@RequestParam("code") String code, RedirectAttributes redirectAttributes) {
        LoginState state = this.connectionService.submitCode(this.currentActor.requireAdmin(), code);
        redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, state.message()));
        return "redirect:/connect";
    }

    @PostMapping("/connect/password")
    public String password(@RequestParam("password") String password, RedirectAttributes redirectAttributes) {
        LoginState state = this.connectionService.submitPassword(this.currentActor.requireAdmin(), password);
        redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, state.message()));
        return "redirect:/connect";
    }

    @PostMapping("/connect/discover")
    public String discover(RedirectAttributes redirectAttributes) {
        int count = this.connectionService.discoverChats(this.currentActor.requireAdmin()).size();
        redirectAttributes.addFlashAttribute("message",
                Messages.text(this.messages, "connect.discover.success", count));
        return "redirect:/";
    }

    @PostMapping("/connect/disconnect")
    public String disconnect(RedirectAttributes redirectAttributes) {
        this.connectionService.disconnect(this.currentActor.requireAdmin());
        redirectAttributes.addFlashAttribute("message",
                Messages.text(this.messages, "connect.disconnect.success"));
        return "redirect:/connect";
    }
}
