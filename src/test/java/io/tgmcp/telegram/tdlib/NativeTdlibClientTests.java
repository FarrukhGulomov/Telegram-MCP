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

    /**
     * Security-audit finding SEC-14: the listener dispatch queue used to be an unbounded {@code
     * LinkedBlockingQueue} (via {@code Executors.newSingleThreadExecutor()}), so a listener
     * blocked while updates keep arriving could accumulate unbounded work. Uses the
     * package-visible constructor to shrink the queue to a size a test can actually overflow,
     * and deliberately blocks the listener on the very first update while a burst well past
     * that capacity is pushed.
     */
    @Test
    void aSustainedBurstWithABlockedListenerNeitherDropsUpdatesNorKillsTheReceiveThread()
            throws InterruptedException {
        int queueCapacity = 8;
        int burstSize = queueCapacity * 5;
        FakeTdJsonLibrary fake = new FakeTdJsonLibrary();
        NativeTdlibClient client = new NativeTdlibClient(fake, new ObjectMapper(), REQUEST_TIMEOUT_SECONDS,
                queueCapacity);

        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger firstSeen = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        client.onUpdate(update -> {
            if (firstSeen.getAndIncrement() == 0) {
                try {
                    // Deliberately blocked: exactly the "slow or blocked listener" scenario the
                    // audit finding describes, held well past the time it takes to enqueue (or,
                    // once the bounded queue is full, run via backpressure) the rest of the
                    // burst below.
                    releaseFirst.await(10, TimeUnit.SECONDS);
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            delivered.incrementAndGet();
        });
        client.start();

        for (int i = 0; i < burstSize; i++) {
            fake.push("{\"@type\":\"updateOption\",\"n\":" + i + "}");
        }

        try {
            // The queue (capacity 8) plus the one already dispatched to the blocked listener
            // cannot hold all 40 updates; the remainder must be applying backpressure to the
            // receive thread (CallerRunsPolicy) rather than being rejected or dropped. If
            // execute() had instead thrown RejectedExecutionException, it would have propagated
            // out of receiveLoop() uncaught and silently killed the receive thread -- in which
            // case the wait below would time out with delivered stuck well short of burstSize.
            releaseFirst.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (delivered.get() < burstSize && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(delivered.get())
                    .as("every update in the burst must eventually be delivered -- backpressure, "
                            + "not silent loss, is how a bounded queue must behave here")
                    .isEqualTo(burstSize);
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
        private volatile Function<JsonNode, String> autoResponder;

        void push(String json) {
            this.incoming.offer(json);
        }

        java.util.List<String> sentMethods() {
            return this.sentMethods;
        }

        void autoRespondOk() {
            autoRespond(request -> "{\"@type\":\"ok\",\"@extra\":\"" + request.path("@extra").asString("") + "\"}");
        }

        void autoRespond(Function<JsonNode, String> responder) {
            this.autoResponder = responder;
        }

        @Override
        public int td_create_client_id() {
            return 1;
        }

        @Override
        public void td_send(int clientId, String request) {
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
