package io.tgmcp.telegram.tdlib;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.tgmcp.telegram.gateway.TelegramGatewayException;
import io.tgmcp.telegram.gateway.TelegramTransientException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security-audit finding F11: {@link NativeTdlibClient} used to run every update listener
 * inline on its single receive thread, so a listener that itself called {@link
 * NativeTdlibClient#request} -- exactly what {@code TdlibTelegramGateway}'s auth-state listener
 * does to send {@code setTdlibParameters} -- would deadlock: the thread that alone can ever
 * deliver that request's response was the one blocked waiting for it. These tests exercise the
 * fix (CompletableFuture-based correlation, listener dispatch off the receive thread, and a
 * start()-after-onUpdate() lifecycle) against a fake {@link TdJsonLibrary}, with no native
 * library required.
 */
class NativeTdlibClientTests {

    private static final long REQUEST_TIMEOUT_SECONDS = 2;

    @Test
    void aFastRequestReceivesItsCorrelatedResponse() {
        FakeTdJsonLibrary fake = new FakeTdJsonLibrary();
        fake.autoRespondOk();
        NativeTdlibClient client = new NativeTdlibClient(fake, new ObjectMapper(), REQUEST_TIMEOUT_SECONDS);
        client.start();
        try {
            JsonNode response = client.request("getChats", Map.of());
            assertThat(response.path("@type").asString("")).isEqualTo("ok");
        }
        finally {
            client.close();
        }
    }

    @Test
    void aListenerThatItselfSendsARequestFromInsideAnUpdateDoesNotDeadlock() throws InterruptedException {
        FakeTdJsonLibrary fake = new FakeTdJsonLibrary();
        fake.autoRespondOk();
        NativeTdlibClient client = new NativeTdlibClient(fake, new ObjectMapper(), REQUEST_TIMEOUT_SECONDS);

        AtomicReference<JsonNode> nestedResponse = new AtomicReference<>();
        AtomicReference<RuntimeException> nestedFailure = new AtomicReference<>();
        CountDownLatch handled = new CountDownLatch(1);
        client.onUpdate(update -> {
            if ("updateAuthorizationState".equals(update.path("@type").asString(""))) {
                try {
                    // Exactly what TdlibTelegramGateway.handleUpdate() does: call request()
                    // from inside the listener that the receive thread is dispatching.
                    nestedResponse.set(client.request("setTdlibParameters", Map.of()));
                }
                catch (RuntimeException ex) {
                    nestedFailure.set(ex);
                }
                finally {
                    handled.countDown();
                }
            }
        });
        client.start();

        fake.push("{\"@type\":\"updateAuthorizationState\"}");

        boolean completed = handled.await(REQUEST_TIMEOUT_SECONDS + 2, TimeUnit.SECONDS);
        try {
            assertThat(completed)
                    .as("the nested request must be handled well within its own timeout, not hang")
                    .isTrue();
            assertThat(nestedFailure.get())
                    .as("a listener calling request() on itself must not time out or deadlock")
                    .isNull();
            assertThat(nestedResponse.get()).isNotNull();
            assertThat(fake.sentMethods()).contains("setTdlibParameters");
        }
        finally {
            client.close();
        }
    }

    @Test
    void aRequestWithNoMatchingResponseTimesOutRatherThanHangingForever() {
        FakeTdJsonLibrary fake = new FakeTdJsonLibrary();
        // No auto-responder: every request goes unanswered.
        NativeTdlibClient client = new NativeTdlibClient(fake, new ObjectMapper(), 1);
        client.start();
        try {
            assertThatThrownBy(() -> client.request("getChats", Map.of()))
                    .isInstanceOf(TelegramTransientException.class)
                    .hasMessageContaining("timed out");
        }
        finally {
            client.close();
        }
    }

    @Test
    void closingTheClientFailsAnyStillPendingRequestInsteadOfLeavingItHanging() throws InterruptedException {
        FakeTdJsonLibrary fake = new FakeTdJsonLibrary();
        // No auto-responder, and a long request timeout: the only way this request ever
        // returns is via close() failing it out from under the waiting caller.
        NativeTdlibClient client = new NativeTdlibClient(fake, new ObjectMapper(), 30);
        client.start();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread requester = new Thread(() -> {
            started.countDown();
            try {
                client.request("getChats", Map.of());
            }
            catch (RuntimeException ex) {
                failure.set(ex);
            }
            finally {
                finished.countDown();
            }
        });
        requester.start();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100); // let the request actually register itself as pending

        client.close();

        assertThat(finished.await(2, TimeUnit.SECONDS))
                .as("close() must release a request that would otherwise wait for the full 30s timeout")
                .isTrue();
        assertThat(failure.get()).isInstanceOf(TelegramGatewayException.class);
    }

    @Test
    void concurrentRequestsEachReceiveOnlyTheirOwnCorrelatedResponse() throws InterruptedException {
        FakeTdJsonLibrary fake = new FakeTdJsonLibrary();
        fake.autoRespond(request -> "{\"@type\":\"ok\",\"echo\":\"" + request.path("@type").asString("")
                + "\",\"@extra\":\"" + request.path("@extra").asString("") + "\"}");
        NativeTdlibClient client = new NativeTdlibClient(fake, new ObjectMapper(), REQUEST_TIMEOUT_SECONDS);
        client.start();

        int requestCount = 20;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch go = new CountDownLatch(1);
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        java.util.List<String> methods = new CopyOnWriteArrayList<>();
        java.util.List<Throwable> failures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            String method = "method-" + i;
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    JsonNode response = client.request(method, Map.of());
                    methods.add(response.path("echo").asString(""));
                }
                catch (Throwable ex) {
                    failures.add(ex);
                }
            });
            threads.add(t);
            t.start();
        }
        assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Thread t : threads) {
            t.join(5000);
        }

        try {
            assertThat(failures).isEmpty();
            java.util.List<String> expected = new java.util.ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                expected.add("method-" + i);
            }
            assertThat(methods).containsExactlyInAnyOrderElementsOf(expected);
        }
        finally {
            client.close();
        }
    }

    @Test
    void aClosedAuthorizationStateGetsAFreshClientIdForTheNextRequest() throws InterruptedException {
        // Real-account testing found that TDLib answers logOut with authorizationStateClosed,
        // which permanently ends that client id's actor -- not "logged out, ready to log back
        // in". Before this fix, every request after a disconnect failed forever with the same
        // immediate error, since nothing ever created a new client id to replace the dead one.
        FakeTdJsonLibrary fake = new FakeTdJsonLibrary();
        fake.autoRespondOk();
        NativeTdlibClient client = new NativeTdlibClient(fake, new ObjectMapper(), REQUEST_TIMEOUT_SECONDS);

        CountDownLatch closedHandled = new CountDownLatch(1);
        client.onUpdate(update -> {
            if ("authorizationStateClosed".equals(update.path("authorization_state").path("@type").asString(""))) {
                closedHandled.countDown();
            }
        });
        client.start();

        try {
            client.request("getChats", Map.of());
            assertThat(fake.sentClientIds()).containsExactly(1);

            fake.push("{\"@type\":\"updateAuthorizationState\","
                    + "\"authorization_state\":{\"@type\":\"authorizationStateClosed\"}}");
            assertThat(closedHandled.await(REQUEST_TIMEOUT_SECONDS + 2, TimeUnit.SECONDS)).isTrue();

            client.request("setAuthenticationPhoneNumber", Map.of());
            assertThat(fake.sentClientIds())
                    .as("a request after authorizationStateClosed must target a freshly created "
                            + "client, not the now-permanently-dead one")
                    .containsExactly(1, 2);
        }
        finally {
            client.close();
        }
    }

    /** Minimal fake of the native TDLib JSON interface, backed by an in-memory queue. */
    private static final class FakeTdJsonLibrary implements TdJsonLibrary {

        private final ObjectMapper mapper = new ObjectMapper();
        private final BlockingQueue<String> incoming = new LinkedBlockingQueue<>();
        private final CopyOnWriteArrayList<String> sentMethods = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Integer> sentClientIds = new CopyOnWriteArrayList<>();
        private final AtomicInteger nextClientId = new AtomicInteger();
        private volatile Function<JsonNode, String> autoResponder;

        void push(String json) {
            this.incoming.offer(json);
        }

        java.util.List<String> sentMethods() {
            return this.sentMethods;
        }

        java.util.List<Integer> sentClientIds() {
            return this.sentClientIds;
        }

        void autoRespondOk() {
            autoRespond(request -> "{\"@type\":\"ok\",\"@extra\":\"" + request.path("@extra").asString("") + "\"}");
        }

        void autoRespond(Function<JsonNode, String> responder) {
            this.autoResponder = responder;
        }

        @Override
        public int td_create_client_id() {
            return this.nextClientId.incrementAndGet();
        }

        @Override
        public void td_send(int clientId, String request) {
            this.sentClientIds.add(clientId);
            JsonNode node = this.mapper.readTree(request);
            this.sentMethods.add(node.path("@type").asString(""));
            Function<JsonNode, String> responder = this.autoResponder;
            if (responder != null) {
                String response = responder.apply(node);
                if (response != null) {
                    this.incoming.offer(response);
                }
            }
        }

        @Override
        public String td_receive(double timeoutSeconds) {
            try {
                return this.incoming.poll((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        @Override
        public String td_execute(String request) {
            return null;
        }
    }
}
