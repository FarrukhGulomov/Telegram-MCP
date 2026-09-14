package io.tgmcp.ui;

import java.util.List;

import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatRepository;
import io.tgmcp.config.AppProperties;
import io.tgmcp.tenancy.AuthenticatedActor;
import io.tgmcp.telegram.TelegramConnection;
import io.tgmcp.telegram.TelegramConnectionService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final CurrentActor currentActor;
    private final TelegramConnectionService connectionService;
    private final ChatRepository chatRepository;
    private final ChatViewFactory chatViewFactory;
    private final AppProperties properties;

    public DashboardController(CurrentActor currentActor,
                               TelegramConnectionService connectionService,
                               ChatRepository chatRepository,
                               ChatViewFactory chatViewFactory,
                               AppProperties properties) {
        this.currentActor = currentActor;
        this.connectionService = connectionService;
        this.chatRepository = chatRepository;
        this.chatViewFactory = chatViewFactory;
        this.properties = properties;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        AuthenticatedActor actor = this.currentActor.require();
        TelegramConnection connection = this.connectionService.getOrCreate(actor);
        List<Chat> chats = this.chatRepository.findByConnection(actor.tenantId(), connection.id());

        model.addAttribute("user", this.currentActor.requireUser());
        model.addAttribute("connection", connection);
        model.addAttribute("chats", this.chatViewFactory.of(actor, chats));
        model.addAttribute("adapter", this.properties.telegram().adapter().name());
        model.addAttribute("demoMode", this.properties.telegram().adapter()
                == io.tgmcp.telegram.gateway.TelegramAdapterKind.MOCK);
        model.addAttribute("consentVersion", this.properties.consent().currentVersion());
        model.addAttribute("resourceIdentifier", this.properties.mcp().resourceIdentifier());
        return "dashboard";
    }
}
