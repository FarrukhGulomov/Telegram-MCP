package io.tgmcp.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.chat.ChatActivationRepository;
import io.tgmcp.chat.ChatParticipantRepository;
import io.tgmcp.consent.ConsentCoverage;
import io.tgmcp.consent.ConsentService;
import io.tgmcp.message.MessageRepository;
import io.tgmcp.sync.SyncCheckpointRepository;
import io.tgmcp.tenancy.AuthenticatedActor;

import org.springframework.stereotype.Component;

@Component
public class ChatViewFactory {

    private final ChatActivationRepository activationRepository;
    private final ChatParticipantRepository participantRepository;
    private final SyncCheckpointRepository checkpointRepository;
    private final MessageRepository messageRepository;
    private final ConsentService consentService;

    public ChatViewFactory(ChatActivationRepository activationRepository,
                           ChatParticipantRepository participantRepository,
                           SyncCheckpointRepository checkpointRepository,
                           MessageRepository messageRepository,
                           ConsentService consentService) {
        this.activationRepository = activationRepository;
        this.participantRepository = participantRepository;
        this.checkpointRepository = checkpointRepository;
        this.messageRepository = messageRepository;
        this.consentService = consentService;
    }

    public ChatView of(AuthenticatedActor actor, Chat chat) {
        Optional<ChatActivation> activation = this.activationRepository.find(actor.tenantId(), chat.id());
        ConsentCoverage coverage = activation.map(this.consentService::coverageFor).orElse(null);
        return new ChatView(chat,
                activation.orElse(null),
                coverage,
                this.checkpointRepository.find(actor.tenantId(), chat.id()).orElse(null),
                this.messageRepository.coverage(actor.tenantId(), chat.id()),
                this.participantRepository.findActive(actor.tenantId(), chat.id()).size());
    }

    public List<ChatView> of(AuthenticatedActor actor, List<Chat> chats) {
        List<ChatView> views = new ArrayList<>();
        chats.forEach(chat -> views.add(of(actor, chat)));
        return views;
    }
}
