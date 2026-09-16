package io.tgmcp.ui;

import io.tgmcp.config.AppProperties;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    private final AppProperties properties;

    public LoginController(AppProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("demoMode", this.properties.demo().seedSyntheticData());
        model.addAttribute("adapter", this.properties.telegram().adapter().name());
        model.addAttribute("demoEmail", this.properties.demo().ownerEmail());
        model.addAttribute("telegramLoginEnabled", this.properties.telegramLogin().enabled());
        return "login";
    }
}
