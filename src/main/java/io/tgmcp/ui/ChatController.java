package io.tgmcp.ui;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivationService;
import io.tgmcp.chat.ChatParticipantRepository;
import io.tgmcp.chat.ChatRepository;
import io.tgmcp.config.AppProperties;
import io.tgmcp.consent.ConsentMethod;
import io.tgmcp.consent.ConsentService;
import io.tgmcp.security.McpScopes;
import io.tgmcp.sync.ChatSyncService;
import io.tgmcp.sync.SyncOutcome;
import io.tgmcp.tenancy.AuthenticatedActor;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ChatController {

    private final CurrentActor currentActor;
    private final ChatRepository chatRepository;
    private final ChatActivationService activationService;
    private final ChatParticipantRepository participantRepository;
    private final ConsentService consentService;
    private final ChatSyncService syncService;
    private final ChatViewFactory chatViewFactory;
    private final AppProperties properties;
    private final MessageSource messages;

    public ChatController(CurrentActor currentActor,
                          ChatRepository chatRepository,
                          ChatActivationService activationService,
                          ChatParticipantRepository participantRepository,
                          ConsentService consentService,
                          ChatSyncService syncService,
                          ChatViewFactory chatViewFactory,
                          AppProperties properties,
                          MessageSource messages) {
        this.currentActor = currentActor;
        this.chatRepository = chatRepository;
        this.activationService = activationService;
        this.participantRepository = participantRepository;
        this.consentService = consentService;
        this.syncService = syncService;
        this.chatViewFactory = chatViewFactory;
        this.properties = properties;
        this.messages = messages;
    }

    @GetMapping("/chats/{chatId}")
    public String chat(@PathVariable UUID chatId, Model model) {
        AuthenticatedActor actor = this.currentActor.require();
        Chat chat = this.chatRepository.find(actor.tenantId(), chatId)
                .orElseThrow(() -> new IllegalArgumentException("error.noSuchChat"));

        // importParticipants() otherwise only ever runs once, from select() at the moment a
        // chat is first proposed. A chat left sitting in AWAITING_CONSENT (or one proposed
        // before a participant-discovery fix landed) had no way back to a current snapshot
        // short of re-submitting Step 1 from scratch -- ChatSyncService.sync() refuses to run
        // at all before the chat is ACTIVE, which consent coverage can never let it reach on a
        // stale snapshot. Refreshing here means simply reopening the page is enough.
        this.activationService.find(actor, chatId)
                .filter(activation -> activation.state() == ActivationState.AWAITING_CONSENT)
                .ifPresent(activation -> this.activationService.importParticipants(actor, chat));

        model.addAttribute("view", this.chatViewFactory.of(actor, chat));
        model.addAttribute("participants", this.participantRepository.findActive(actor.tenantId(), chatId));
        model.addAttribute("consents", this.consentService.forChat(actor.tenantId(), chatId));
        model.addAttribute("consentVersion", this.properties.consent().currentVersion());
        model.addAttribute("allProviders", List.of(McpScopes.ANTHROPIC_CLAUDE, McpScopes.OPENAI_CHATGPT));
        model.addAttribute("defaultFrom", LocalDate.now(ZoneOffset.UTC).minusDays(90).toString());
        model.addAttribute("defaultTo", LocalDate.now(ZoneOffset.UTC).plusDays(90).toString());
        model.addAttribute("demoMode", this.properties.telegram().adapter()
                == io.tgmcp.telegram.gateway.TelegramAdapterKind.MOCK);
        return "chat";
    }

    @PostMapping("/chats/{chatId}/select")
    public String select(@PathVariable UUID chatId,
                         @RequestParam String purpose,
                         @RequestParam(required = false) List<String> providers,
                         @RequestParam String approvedFrom,
                         @RequestParam String approvedTo,
                         @RequestParam int retentionDays,
                         RedirectAttributes redirectAttributes) {
        AuthenticatedActor actor = this.currentActor.require();
        try {
            this.activationService.propose(actor, chatId, purpose, providersOrEmpty(providers),
                    startOfDay(approvedFrom), endOfDay(approvedTo), retentionDays);
            redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, "chat.select.success"));
        }
        catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
        }
        return "redirect:/chats/" + chatId;
    }

    @PostMapping("/chats/{chatId}/settings")
    public String settings(@PathVariable UUID chatId,
                           @RequestParam String purpose,
                           @RequestParam(required = false) List<String> providers,
                           @RequestParam String approvedFrom,
                           @RequestParam String approvedTo,
                           @RequestParam int retentionDays,
                           RedirectAttributes redirectAttributes) {
        AuthenticatedActor actor = this.currentActor.require();
        try {
            this.activationService.updateSettings(actor, chatId, purpose, providersOrEmpty(providers),
                    startOfDay(approvedFrom), endOfDay(approvedTo), retentionDays);
            redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, "chat.settings.success"));
        }
        catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
        }
        return "redirect:/chats/" + chatId;
    }

    @PostMapping("/chats/{chatId}/consent")
    public String consent(@PathVariable UUID chatId,
                          @RequestParam long participantTgUserId,
                          @RequestParam String participantDisplayName,
                          @RequestParam ConsentMethod method,
                          @RequestParam(required = false) String evidenceRef,
                          RedirectAttributes redirectAttributes) {
        AuthenticatedActor actor = this.currentActor.require();
        var activation = this.activationService.find(actor, chatId)
                .orElseThrow(() -> new IllegalArgumentException("validation.selectChatFirst"));
        try {
            this.consentService.record(actor, chatId, participantTgUserId, participantDisplayName,
                    activation.purpose(), activation.providerScope(), activation.consentVersion(),
                    activation.contextRevision(), method, evidenceRef,
                    Instant.now().plus(this.properties.consent().defaultValidity()));
            redirectAttributes.addFlashAttribute("message",
                    Messages.text(this.messages, "chat.consent.success", participantDisplayName));
        }
        catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", Messages.text(this.messages, ex.getMessage()));
        }
        return "redirect:/chats/" + chatId;
    }

    @PostMapping("/chats/{chatId}/consent/revoke")
    public String revokeConsent(@PathVariable UUID chatId,
                                @RequestParam long participantTgUserId,
                                RedirectAttributes redirectAttributes) {
        AuthenticatedActor actor = this.currentActor.require();
        this.consentService.revokeParticipant(actor, chatId, participantTgUserId, "Revoked from management UI");
        this.activationService.pause(actor, chatId, "A participant withdrew consent");
        redirectAttributes.addFlashAttribute("message",
                Messages.text(this.messages, "chat.consentRevoke.success"));
        return "redirect:/chats/" + chatId;
    }

    @PostMapping("/chats/{chatId}/activate")
    public String activate(@PathVariable UUID chatId, RedirectAttributes redirectAttributes) {
        AuthenticatedActor actor = this.currentActor.require();
        try {
            this.activationService.activate(actor, chatId);
            redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, "chat.activate.success"));
        }
        catch (IllegalStateException ex) {
            // ex.getMessage() is just the participant count; see ChatActivationService.activate().
            redirectAttributes.addFlashAttribute("error",
                    Messages.text(this.messages, "validation.activateIncomplete", ex.getMessage()));
        }
        return "redirect:/chats/" + chatId;
    }

    @PostMapping("/chats/{chatId}/pause")
    public String pause(@PathVariable UUID chatId, RedirectAttributes redirectAttributes) {
        this.activationService.pause(this.currentActor.require(), chatId, "Paused by operator");
        redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, "chat.pause.success"));
        return "redirect:/chats/" + chatId;
    }

    @PostMapping("/chats/{chatId}/revoke")
    public String revoke(@PathVariable UUID chatId, RedirectAttributes redirectAttributes) {
        this.activationService.revoke(this.currentActor.require(), chatId, "Revoked by operator");
        redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, "chat.revoke.success"));
        return "redirect:/chats/" + chatId;
    }

    @PostMapping("/chats/{chatId}/delete-data")
    public String deleteData(@PathVariable UUID chatId, RedirectAttributes redirectAttributes) {
        int deleted = this.activationService.deleteStoredData(this.currentActor.require(), chatId);
        redirectAttributes.addFlashAttribute("message",
                Messages.text(this.messages, "chat.deleteData.success", deleted));
        return "redirect:/chats/" + chatId;
    }

    /** Manual sync trigger, useful for demos and for verifying a fix without waiting for the timer. */
    @PostMapping("/chats/{chatId}/sync")
    public String sync(@PathVariable UUID chatId, RedirectAttributes redirectAttributes) {
        AuthenticatedActor actor = this.currentActor.require();
        SyncOutcome outcome = this.syncService.sync(actor.tenantId(), chatId);
        redirectAttributes.addFlashAttribute("message", Messages.text(this.messages, "chat.sync.result",
                outcome.status(), outcome.messagesIngested(), outcome.revisionsApplied(), outcome.detail()));
        return "redirect:/chats/" + chatId;
    }

    /**
     * An HTML checkbox group submits no {@code providers} field at all when every box is left
     * unchecked, which Spring treats as a missing required parameter (raw 400) rather than an
     * empty list. Falling back to an empty list here lets the existing "select at least one
     * provider" validation in {@link ChatActivationService} produce a friendly message instead.
     */
    private static List<String> providersOrEmpty(List<String> providers) {
        return providers == null ? List.of() : providers;
    }

    private static Instant startOfDay(String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant endOfDay(String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant()
                .plus(Duration.ofDays(1)).minusMillis(1);
    }
}
