package io.tgmcp.ui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivationService;
import io.tgmcp.chat.ChatRepository;
import io.tgmcp.config.AppProperties;
import io.tgmcp.security.McpScopes;
import io.tgmcp.tenancy.AuthenticatedActor;
import io.tgmcp.telegram.TelegramConnection;
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

@Controller
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final CurrentActor currentActor;
    private final TelegramConnectionService connectionService;
    private final ChatRepository chatRepository;
    private final ChatActivationService activationService;
    private final ChatViewFactory chatViewFactory;
    private final AppProperties properties;
    private final MessageSource messages;

    public DashboardController(CurrentActor currentActor,
                               TelegramConnectionService connectionService,
                               ChatRepository chatRepository,
                               ChatActivationService activationService,
                               ChatViewFactory chatViewFactory,
                               AppProperties properties,
                               MessageSource messages) {
        this.currentActor = currentActor;
        this.connectionService = connectionService;
        this.chatRepository = chatRepository;
        this.activationService = activationService;
        this.chatViewFactory = chatViewFactory;
        this.properties = properties;
        this.messages = messages;
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

    /**
     * The one step needed to start reading a chat: picks every checked chat, proposes it with
     * the same default purpose/providers/date range/retention the single-chat "Select chat"
     * step defaults to (importing participants along the way), and immediately activates it --
     * one submission turns any number of chats fully on. Per this deployment's explicit
     * operator decision (see {@link io.tgmcp.consent.ConsentService#coverageFor}), activation no
     * longer waits on per-participant consent, so there is no separate step left to skip here.
     */
    @PostMapping("/chats/bulk-prepare")
    public String bulkPrepare(@RequestParam(name = "chatId", required = false) List<UUID> chatIds,
                              RedirectAttributes redirectAttributes) {
        AuthenticatedActor actor = this.currentActor.requireAdmin();
        List<UUID> ids = chatIds == null ? List.of() : chatIds;
        if (ids.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    Messages.text(this.messages, "dashboard.bulkPrepare.noneSelected"));
            return "redirect:/";
        }
        String defaultPurpose = Messages.text(this.messages, "chat.purpose.default");
        List<String> defaultProviders = List.of(McpScopes.ANTHROPIC_CLAUDE, McpScopes.OPENAI_CHATGPT);
        Instant approvedFrom = LocalDate.now(ZoneOffset.UTC).minusDays(90).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant approvedTo = LocalDate.now(ZoneOffset.UTC).plusDays(90).atStartOfDay(ZoneOffset.UTC).toInstant();

        int activated = 0;
        for (UUID chatId : ids) {
            if (this.chatRepository.find(actor.tenantId(), chatId).isEmpty()) {
                continue;
            }
            // Already selected (or further along) -- bulk-prepare only ever does the first,
            // repetitive step; re-selecting would reset an already-in-progress chat's settings.
            if (this.activationService.find(actor, chatId).isPresent()) {
                continue;
            }
            try {
                this.activationService.propose(actor, chatId, defaultPurpose, defaultProviders, approvedFrom,
                        approvedTo, 90);
                this.activationService.activate(actor, chatId);
                activated++;
            }
            catch (IllegalArgumentException | IllegalStateException ex) {
                log.warn("Bulk-prepare skipped chat {}: {}", chatId, ex.getMessage());
            }
        }
        redirectAttributes.addFlashAttribute("message",
                Messages.text(this.messages, "dashboard.bulkPrepare.success", activated));
        return "redirect:/";
    }
}
