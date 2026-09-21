package io.tgmcp.telegram.tdlib;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirmed from live production logs: {@code td_receive} is a single, process-wide call, not a
 * per-client one -- giving every tenant's {@link NativeTdlibClient} its own private receive
 * thread against the same loaded native library, as {@link RoutedTdlibTelegramGateway} first
 * shipped with, made a second tenant's connection start a second thread calling {@code
 * td_receive} concurrently with the first. TDLib's own internal check answered that with a
 * native {@code abort()} ("Receive must not be called simultaneously from two different
 * threads"), killing the whole process outright rather than just failing that request.
 *
 * <p>These tests exercise the fix -- one shared thread, demultiplexing by the {@code @client_id}
 * field TDLib stamps on every message -- against a fake {@link TdJsonLibrary}, with two {@link
 * NativeTdlibClient}s standing in for two different tenants.
 */
class TdlibReceiveLoopTests {

    private static final long REQUEST_TIMEOUT_SECONDS = 2;

    @Test
    void twoClientsSharingOneLoopEachReceiveOnlyTheirOwnMessages() throws InterruptedException {
        FakeTdJsonLibrary fake = new FakeTdJsonLibrary();
        TdlibReceiveLoop loop = new TdlibReceiveLoop(fake, new ObjectMapper());

        NativeTdlibClient clientA = new NativeTdlibClient(loop, new ObjectMapper(), REQUEST_TIMEOUT_SECONDS);
        NativeTdlibClient clientB = new NativeTdlibClient(loop, new ObjectMapper(), REQUEST_TIMEOUT_SECONDS);

        CopyOnWriteArrayList<String> receivedByA = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> receivedByB = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        clientA.onUpdate(update -> {
            receivedByA.add(update.path("marker").asString(""));
            latch.countDown();
        });
        clientB.onUpdate(update -> {
            receivedByB.add(update.path("marker").asString(""));
            latch.countDown();
        });
        clientA.start();
        clientB.start();

        try {
            fake.pushForClient(1, "{\"@type\":\"updateOption\",\"marker\":\"for-a\"}");
            fake.pushForClient(2, "{\"@type\":\"updateOption\",\"marker\":\"for-b\"}");

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(receivedByA).containsExactly("for-a");
            assertThat(receivedByB).containsExactly("for-b");
        }
        finally {
            clientA.close();
            clientB.close();
            loop.shutdown();
        }
    }

    @Test
    void onlyOneReceiveThreadEverExistsAcrossManySharedClients() {
        // Counts the delta rather than an absolute number, since this test runs in the same JVM
        // as every other test class and must not assume it is the only "tdlib-receive" thread
        // that has ever existed -- only that sharing one TdlibReceiveLoop across many tenants
        // never starts more than the one thread that loop itself owns.
        long baseline = countReceiveThreads();

        FakeTdJsonLibrary fake = new FakeTdJsonLibrary();
        TdlibReceiveLoop loop = new TdlibReceiveLoop(fake, new ObjectMapper());

        NativeTdlibClient[] clients = new NativeTdlibClient[5];
        try {
            for (int i = 0; i < clients.length; i++) {
                clients[i] = new NativeTdlibClient(loop, new ObjectMapper(), REQUEST_TIMEOUT_SECONDS);
                clients[i].onUpdate(update -> { });
                clients[i].start();
            }

            assertThat(countReceiveThreads() - baseline)
                    .as("five tenants sharing one TdlibReceiveLoop must add exactly one "
                            + "td_receive-draining thread, never one per tenant")
                    .isEqualTo(1);
        }
        finally {
            for (NativeTdlibClient client : clients) {
                if (client != null) {
                    client.close();
                }
            }
            loop.shutdown();
        }
    }

    private static long countReceiveThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> "tdlib-receive".equals(t.getName()) && t.isAlive())
                .count();
    }

    @Test
    void requestsFromDifferentSharedClientsAreRoutedByClientId() {
        FakeTdJsonLibrary fake = new FakeTdJsonLibrary();
        fake.autoRespond((clientId, request) -> "{\"@type\":\"ok\",\"@client_id\":" + clientId
                + ",\"@extra\":\"" + request.path("@extra").asString("") + "\"}");
        TdlibReceiveLoop loop = new TdlibReceiveLoop(fake, new ObjectMapper());

        NativeTdlibClient clientA = new NativeTdlibClient(loop, new ObjectMapper(), REQUEST_TIMEOUT_SECONDS);
        NativeTdlibClient clientB = new NativeTdlibClient(loop, new ObjectMapper(), REQUEST_TIMEOUT_SECONDS);
        clientA.onUpdate(update -> { });
        clientB.onUpdate(update -> { });
        clientA.start();
        clientB.start();

        try {
            JsonNode responseA = clientA.request("getChats", Map.of());
            JsonNode responseB = clientB.request("getChats", Map.of());

            assertThat(responseA.path("@type").asString("")).isEqualTo("ok");
            assertThat(responseB.path("@type").asString("")).isEqualTo("ok");
            assertThat(fake.sentClientIds()).containsExactly(1, 2);
        }
        finally {
            clientA.close();
            clientB.close();
            loop.shutdown();
        }
    }

    /** Minimal fake of the native TDLib JSON interface, stamping {@code @client_id} the way the
     * real {@code td_receive} multiplexes updates and responses for every client in the process. */
    private static final class FakeTdJsonLibrary implements TdJsonLibrary {

        private final ObjectMapper mapper = new ObjectMapper();
        private final BlockingQueue<String> incoming = new LinkedBlockingQueue<>();
        private final CopyOnWriteArrayList<Integer> sentClientIds = new CopyOnWriteArrayList<>();
        private final AtomicInteger nextClientId = new AtomicInteger();
        private volatile java.util.function.BiFunction<Integer, JsonNode, String> autoResponder;

        /** Mirrors how real {@code td_receive} stamps every message with the client it belongs to. */
        void pushForClient(int clientId, String json) {
            this.incoming.offer(json.replaceFirst("\\{", "{\"@client_id\":" + clientId + ","));
        }

        void autoRespond(java.util.function.BiFunction<Integer, JsonNode, String> responder) {
            this.autoResponder = responder;
        }

        List<Integer> sentClientIds() {
            return this.sentClientIds;
        }

        @Override
        public int td_create_client_id() {
            return this.nextClientId.incrementAndGet();
        }

        @Override
        public void td_send(int clientId, String request) {
            this.sentClientIds.add(clientId);
            JsonNode node = this.mapper.readTree(request);
            java.util.function.BiFunction<Integer, JsonNode, String> responder = this.autoResponder;
            if (responder != null) {
                String response = responder.apply(clientId, node);
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
