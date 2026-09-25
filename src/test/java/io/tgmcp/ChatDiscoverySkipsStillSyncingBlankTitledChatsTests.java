package io.tgmcp;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.tgmcp.chat.Chat;
import io.tgmcp.support.AbstractIntegrationTest;
import io.tgmcp.support.Fixtures;
import io.tgmcp.telegram.TelegramConnectionService;
import io.tgmcp.telegram.gateway.ConnectionRef;
import io.tgmcp.telegram.gateway.LoginMethod;
import io.tgmcp.telegram.gateway.LoginStage;
import io.tgmcp.telegram.gateway.LoginState;
import io.tgmcp.telegram.gateway.RemoteChat;
import io.tgmcp.telegram.gateway.TelegramAdapterKind;
import io.tgmcp.telegram.gateway.TelegramGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live-report finding: right after a fresh login, TDLib's own chat list is still populated
 * asynchronously from Telegram's servers -- {@code listAvailableChats} can (briefly) return a
 * chat whose title TDLib has not filled in yet, before an {@code updateChatTitle} (or the
 * counterpart user's own name) has arrived. {@code discoverChats} used to upsert those exactly
 * as returned, producing permanently blank, unidentifiable rows in the chat picker -- confirmed
 * live: a real account's picker accumulated more of these on every successive "refresh chat
 * list" click while TDLib was still catching up after the login.
 */
class ChatDiscoverySkipsStillSyncingBlankTitledChatsTests extends AbstractIntegrationTest {

    @Autowired
    private TelegramConnectionService connectionService;

    @MockitoSpyBean
    private TelegramGateway gateway;

    @SuppressWarnings("unchecked")
    private Consumer<LoginState> captureRegisteredListener() {
        AtomicReference<Consumer<LoginState>> captured = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return null;
        }).when(this.gateway).onStateChange(ArgumentMatchers.any(), ArgumentMatchers.any());
        return state -> captured.get().accept(state);
    }

    @Test
    void aChatWhoseTitleTdlibHasNotPopulatedYetIsSkippedUntilItIs() {
        Mockito.when(this.gateway.kind()).thenReturn(TelegramAdapterKind.TDLIB);
        Fixtures.Team team = this.fixtures.signInReadyTeam("blank-title", "owner@blank-title.invalid");
        Consumer<LoginState> deliverToListener = captureRegisteredListener();
        this.connectionService.beginLogin(team.actor(), LoginMethod.QR_CODE, null);
        deliverToListener.accept(new LoginState(LoginStage.READY, null, 555L, "real_owner",
                "+15550000000", "login.tdlib.ready", null, null));

        Mockito.doReturn(List.of(
                        new RemoteChat(111L, "", "PRIVATE", null, null),
                        new RemoteChat(222L, "Real Title", "PRIVATE", null, null)))
                .when(this.gateway).listAvailableChats(ArgumentMatchers.any(ConnectionRef.class));

        List<Chat> firstPass = this.connectionService.discoverChats(team.actor());

        assertThat(firstPass).extracting(Chat::tgChatId)
                .as("the still-blank chat must not be persisted as an unidentifiable row")
                .containsExactly(222L);

        // Once TDLib has finally filled the title in, the exact same tg_chat_id must appear
        // normally on a later call -- skipping it now must never cost it permanently.
        Mockito.doReturn(List.of(
                        new RemoteChat(111L, "Finally Named", "PRIVATE", null, null),
                        new RemoteChat(222L, "Real Title", "PRIVATE", null, null)))
                .when(this.gateway).listAvailableChats(ArgumentMatchers.any(ConnectionRef.class));

        List<Chat> secondPass = this.connectionService.discoverChats(team.actor());

        assertThat(secondPass).extracting(Chat::tgChatId).containsExactlyInAnyOrder(111L, 222L);
    }
}
