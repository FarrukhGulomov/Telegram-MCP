package io.tgmcp.support;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.tgmcp.chat.ActivationState;
import io.tgmcp.chat.Chat;
import io.tgmcp.chat.ChatActivation;
import io.tgmcp.chat.ChatActivationRepository;
import io.tgmcp.chat.ChatParticipant;
import io.tgmcp.chat.ChatParticipantRepository;
import io.tgmcp.chat.ChatRepository;
import io.tgmcp.common.Ids;
import io.tgmcp.consent.ConsentMethod;
import io.tgmcp.consent.ConsentRecord;
import io.tgmcp.consent.ConsentRepository;
import io.tgmcp.security.McpScopes;
import io.tgmcp.tenancy.ActorType;
import io.tgmcp.tenancy.AppUser;
import io.tgmcp.tenancy.AppUserRepository;
import io.tgmcp.tenancy.AuthenticatedActor;
import io.tgmcp.tenancy.Tenant;
import io.tgmcp.tenancy.TenantRepository;
import io.tgmcp.telegram.TelegramConnection;
import io.tgmcp.telegram.TelegramConnectionRepository;
import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.telegram.gateway.RemoteParticipant;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import io.tgmcp.telegram.mock.SyntheticDataset;

import org.springframework.stereotype.Component;

/**
 * Builds complete, realistic fixtures: a tenant with a user, a ready connection, a chat with
 * participants, an activation and per-participant consent.
 */
@Component
public class Fixtures {

    public static final String PURPOSE = "Answer questions about team decisions using this chat as evidence";
    public static final String CONSENT_VERSION = "2026-09-v1";

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final TelegramConnectionRepository connections;
    private final ChatRepository chats;
    private final ChatParticipantRepository participants;
    private final ChatActivationRepository activations;
    private final ConsentRepository consents;

    public Fixtures(TenantRepository tenants, AppUserRepository users,
                    TelegramConnectionRepository connections, ChatRepository chats,
                    ChatParticipantRepository participants, ChatActivationRepository activations,
                    ConsentRepository consents) {
        this.tenants = tenants;
        this.users = users;
        this.connections = connections;
        this.chats = chats;
        this.participants = participants;
        this.activations = activations;
        this.consents = consents;
    }

    public record Team(Tenant tenant, AppUser user, TelegramConnection connection, AuthenticatedActor actor) {
    }

    /**
     * A tenant and a sign-in-able owner, with no Telegram connection yet — the state a real
     * operator starts from.
     */
    public Team signInReadyTeam(String slug, String email, String encodedPassword) {
        UUID tenantId = Ids.newId();
        this.tenants.insert(new Tenant(tenantId, slug, slug + " team", true, null));
        AppUser user = new AppUser(Ids.newId(), tenantId, email, encodedPassword,
                slug + " owner", "OWNER", true);
        this.users.insert(user);
        AuthenticatedActor actor = new AuthenticatedActor(tenantId, user.id(), user.email(),
                ActorType.UI_USER, java.util.Set.of("ui:manage"), null);
        return new Team(new Tenant(tenantId, slug, slug, true, null), user, null, actor);
    }

    public Team team(String slug) {
        UUID tenantId = Ids.newId();
        this.tenants.insert(new Tenant(tenantId, slug, slug + " team", true, null));
        AppUser user = new AppUser(Ids.newId(), tenantId, "owner@" + slug + ".invalid",
                "{noop}pw", slug + " owner", "OWNER", true);
        this.users.insert(user);

        TelegramConnection connection = new TelegramConnection(Ids.newId(), tenantId, user.id(),
                TelegramAdapterKind.MOCK, LoginStage.READY, 400001L, "synthetic_owner",
                "test", null, null, null);
        this.connections.insert(connection);
        this.connections.updateStatus(tenantId, connection.id(), LoginStage.READY, 400001L,
                "synthetic_owner", null);

        AuthenticatedActor actor = new AuthenticatedActor(tenantId, user.id(), user.email(),
                ActorType.MCP_CLIENT, java.util.Set.of(McpScopes.READ, McpScopes.SCOPE_ANTHROPIC_CLAUDE),
                "test-client");
        return new Team(new Tenant(tenantId, slug, slug, true, null), user, connection, actor);
    }

    public Chat chat(Team team, long tgChatId, String title, String username) {
        Chat chat = new Chat(Ids.newId(), team.tenant().id(), team.connection().id(), tgChatId,
                title, "SUPERGROUP", username, null, null);
        this.chats.upsert(chat);
        return this.chats.findByTgId(team.tenant().id(), team.connection().id(), tgChatId).orElseThrow();
    }

    public void participants(Team team, Chat chat, List<RemoteParticipant> people) {
        for (RemoteParticipant person : people) {
            this.participants.upsert(new ChatParticipant(Ids.newId(), team.tenant().id(), chat.id(),
                    person.tgUserId(), person.displayName(), person.bot(), null, null));
        }
        this.participants.markLeft(team.tenant().id(), chat.id(),
                people.stream().map(RemoteParticipant::tgUserId).toList());
    }

    public ChatActivation activation(Team team, Chat chat, ActivationState state, Instant from, Instant to,
                                     int retentionDays, String... providers) {
        String scope = providers.length == 0 ? McpScopes.ANTHROPIC_CLAUDE : String.join(",", providers);
        ChatActivation activation = new ChatActivation(Ids.newId(), team.tenant().id(), chat.id(), state,
                PURPOSE, scope, CONSENT_VERSION, from, to, retentionDays,
                this.participants.membershipFingerprint(team.tenant().id(), chat.id()), null,
                Instant.parse("2026-06-01T00:00:00Z"), team.user().id());
        this.activations.upsert(activation);
        return this.activations.find(team.tenant().id(), chat.id()).orElseThrow();
    }

    /** Records valid consent for every non-bot participant of a chat. */
    public void consentAll(Team team, Chat chat, ChatActivation activation, Instant grantedAt,
                           Instant expiresAt) {
        for (ChatParticipant participant : this.participants.findActive(team.tenant().id(), chat.id())) {
            if (participant.bot()) {
                continue;
            }
            this.consents.insert(new ConsentRecord(Ids.newId(), team.tenant().id(), chat.id(),
                    participant.tgUserId(), participant.displayName(), activation.purpose(),
                    activation.providerScope(), activation.consentVersion(), ConsentMethod.DEMO_SYNTHETIC,
                    "test-fixture", grantedAt, expiresAt, null, null, team.user().id()));
        }
    }

    /** The standard happy-path fixture: engineering chat, all consented, ACTIVE. */
    public record ReadyChat(Team team, Chat chat, ChatActivation activation) {
    }

    /**
     * The real membership of the synthetic engineering chat. Fixtures must use this exact set:
     * sync recomputes the membership fingerprint from the gateway and pauses the chat if it
     * differs, which is correct behaviour but makes a partial fixture look like a bug.
     */
    public static final List<RemoteParticipant> ENGINEERING_MEMBERS = List.of(
            new RemoteParticipant(SyntheticDataset.ALICE, "Alice Nakamura (synthetic)", false),
            new RemoteParticipant(SyntheticDataset.BEN, "Ben Okafor (synthetic)", false),
            new RemoteParticipant(SyntheticDataset.CHIDI, "Chidi Vasquez (synthetic)", false),
            new RemoteParticipant(SyntheticDataset.DANA, "Dana Lindqvist (synthetic)", false),
            new RemoteParticipant(SyntheticDataset.HELPER_BOT, "StandupBot (synthetic)", true));

    public static final List<RemoteParticipant> LAUNCH_OPS_MEMBERS = List.of(
            new RemoteParticipant(SyntheticDataset.ALICE, "Alice Nakamura (synthetic)", false),
            new RemoteParticipant(SyntheticDataset.BEN, "Ben Okafor (synthetic)", false),
            new RemoteParticipant(SyntheticDataset.DANA, "Dana Lindqvist (synthetic)", false));

    /** The public launch-ops chat, which also carries the deliberately adversarial message. */
    public ReadyChat readyLaunchOpsChat(String slug, Instant now) {
        Team team = team(slug);
        Chat chat = chat(team, SyntheticDataset.LAUNCH_OPS_CHAT,
                "[SYNTHETIC DEMO] Orbital Widgets — Launch Ops", "synthetic_launch_ops");
        participants(team, chat, LAUNCH_OPS_MEMBERS);
        ChatActivation activation = activation(team, chat, ActivationState.ACTIVE,
                now.minus(Duration.ofDays(365)), now.plus(Duration.ofDays(365)), 365,
                McpScopes.ANTHROPIC_CLAUDE);
        consentAll(team, chat, activation, now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(300)));
        return new ReadyChat(team, chat, activation);
    }

    public ReadyChat readyEngineeringChat(String slug, Instant now) {
        return readyEngineeringChat(slug, now, now.minus(Duration.ofDays(365)),
                now.plus(Duration.ofDays(365)), 365, McpScopes.ANTHROPIC_CLAUDE);
    }

    public ReadyChat readyEngineeringChat(String slug, Instant now, Instant approvedFrom,
                                          Instant approvedTo, int retentionDays, String... providers) {
        Team team = team(slug);
        Chat chat = chat(team, SyntheticDataset.ENGINEERING_CHAT,
                "[SYNTHETIC DEMO] Orbital Widgets — Engineering", null);
        participants(team, chat, ENGINEERING_MEMBERS);
        ChatActivation activation = activation(team, chat, ActivationState.ACTIVE,
                approvedFrom, approvedTo, retentionDays, providers);
        consentAll(team, chat, activation, now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(300)));
        return new ReadyChat(team, chat, activation);
    }
}
