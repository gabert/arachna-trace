package com.github.gabert.arachna.trace.recorder.destination;

import com.github.gabert.arachna.trace.recorder.AgentRun;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HttpDestination} — the agent's centralised exfil path.
 * The class was completely untested before Round 3. Uses
 * {@link com.sun.net.httpserver.HttpServer} as a deterministic stub for the
 * collector so we can observe what the destination actually sent.
 */
class HttpDestinationTest {

    private StubServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new StubServer();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

    // ============================================================
    //  Bug-suspect (VERIFY-3): data loss on network failure
    // ============================================================

    @Test
    void recordsAreNotLostWhenServerIsUnreachable() throws Exception {
        // Stop the stub so the first send hits a refused connection.
        // The destination must NOT drop the payload on the failed send —
        // a downstream retry/flush must still carry those bytes.
        int port = server.port();
        server.stop();

        HttpDestination dest = newDestination(Map.of(
                "http_server_url", "http://localhost:" + port + "/records",
                "http_flush_threshold", "16"));

        byte[] firstBatch = bytes(0, 32); // > threshold → forces send-on-accept
        dest.accept(firstBatch);

        // Now bring the server back up on the same port and force another flush.
        StubServer revived = new StubServer(port);
        try {
            dest.flush();
            revived.awaitRequest(2000);
            byte[] received = revived.lastBody;
            assertNotNull(received, "second flush should have delivered the bytes");
            assertTrue(containsSubsequence(received, firstBatch),
                    "first batch must survive a transient network failure (currently lost)");
        } finally {
            revived.stop();
            dest.close();
        }
    }

    // ============================================================
    //  Buffering & threshold
    // ============================================================

    @Test
    void acceptBelowThresholdDoesNotSend() throws Exception {
        HttpDestination dest = newDestination(Map.of(
                "http_server_url", server.url(),
                "http_flush_threshold", "1024"));

        dest.accept(bytes(0, 64));
        dest.accept(bytes(64, 64));
        // Give a slow server thread time to mistakenly receive something.
        Thread.sleep(50);

        assertEquals(0, server.requestCount.get());
        dest.close();
    }

    @Test
    void acceptAtThresholdSendsBuffer() throws Exception {
        HttpDestination dest = newDestination(Map.of(
                "http_server_url", server.url(),
                "http_flush_threshold", "64"));

        dest.accept(bytes(0, 32));            // buffered
        assertEquals(0, server.requestCount.get());

        dest.accept(bytes(32, 32));           // hits threshold (64) → triggers send
        server.awaitRequest(2000);

        assertEquals(1, server.requestCount.get());
        assertEquals(64, server.lastBody.length);
        dest.close();
    }

    @Test
    void flushForcesSendOfBufferedRecords() throws Exception {
        HttpDestination dest = newDestination(Map.of(
                "http_server_url", server.url(),
                "http_flush_threshold", "1024"));

        dest.accept(bytes(0, 10));
        dest.flush();
        server.awaitRequest(2000);

        assertEquals(1, server.requestCount.get());
        assertEquals(10, server.lastBody.length);
        dest.close();
    }

    @Test
    void closeForcesSendOfBufferedRecords() throws Exception {
        HttpDestination dest = newDestination(Map.of(
                "http_server_url", server.url(),
                "http_flush_threshold", "1024"));

        dest.accept(bytes(0, 10));
        dest.close();
        server.awaitRequest(2000);

        assertEquals(1, server.requestCount.get());
        assertEquals(10, server.lastBody.length);
    }

    @Test
    void flushOnEmptyBufferIsNoop() throws Exception {
        HttpDestination dest = newDestination(Map.of("http_server_url", server.url()));

        dest.flush();
        Thread.sleep(50);
        assertEquals(0, server.requestCount.get(),
                "empty-buffer flush must not generate an HTTP request");
        dest.close();
    }

    // ============================================================
    //  Agent-run headers
    // ============================================================

    @Test
    void agentRunHeadersAreSentOnEveryRequest() throws Exception {
        HttpDestination dest = newDestination(Map.of(
                "http_server_url", server.url(),
                "http_flush_threshold", "1024"));

        UUID runId = UUID.randomUUID();
        dest.setAgentRun(new AgentRun(
                runId, "host-1", "1.2.3", "abc123", "prod", 4242L, 9_000_000L));

        dest.accept(bytes(0, 4));
        dest.flush();
        server.awaitRequest(2000);

        Map<String, String> hdrs = server.lastHeaders;
        assertEquals(runId.toString(), hdrs.get(AgentRun.Headers.AGENT_RUN_ID.toLowerCase()));
        assertEquals("host-1",  hdrs.get(AgentRun.Headers.HOSTNAME.toLowerCase()));
        assertEquals("1.2.3",   hdrs.get(AgentRun.Headers.AGENT_VERSION.toLowerCase()));
        assertEquals("abc123",  hdrs.get(AgentRun.Headers.CODE_VERSION.toLowerCase()));
        assertEquals("prod",    hdrs.get(AgentRun.Headers.ENV.toLowerCase()));
        assertEquals("4242",    hdrs.get(AgentRun.Headers.JVM_PID.toLowerCase()));
        assertEquals("9000000", hdrs.get(AgentRun.Headers.STARTED_AT_MS.toLowerCase()));
        dest.close();
    }

    @Test
    void nullOptionalAgentRunHeadersAreOmitted() throws Exception {
        // codeVersion and env are optional — when null, the header must
        // not be sent at all (sending empty string would be wrong; the
        // processor distinguishes "absent" from "empty").
        HttpDestination dest = newDestination(Map.of(
                "http_server_url", server.url(),
                "http_flush_threshold", "1024"));

        dest.setAgentRun(new AgentRun(
                UUID.randomUUID(), "host-x", "0.0.1",
                null, null, 1L, 1L));

        dest.accept(bytes(0, 4));
        dest.flush();
        server.awaitRequest(2000);

        assertNull(server.lastHeaders.get(AgentRun.Headers.CODE_VERSION.toLowerCase()),
                "null codeVersion must not be sent");
        assertNull(server.lastHeaders.get(AgentRun.Headers.ENV.toLowerCase()),
                "null env must not be sent");
        // Required headers still present.
        assertEquals("host-x", server.lastHeaders.get(AgentRun.Headers.HOSTNAME.toLowerCase()));
        dest.close();
    }

    @Test
    void noAgentRunSetSendsNoAgentRunHeaders() throws Exception {
        // Defensive: contract says setAgentRun is called once before any record,
        // but if the agent path skipped it (or a misconfiguration), the destination
        // must not NPE — and must not send AgentRun headers with bogus values.
        HttpDestination dest = newDestination(Map.of(
                "http_server_url", server.url(),
                "http_flush_threshold", "1024"));

        dest.accept(bytes(0, 4));
        dest.flush();
        server.awaitRequest(2000);

        assertNull(server.lastHeaders.get(AgentRun.Headers.AGENT_RUN_ID.toLowerCase()));
        assertNull(server.lastHeaders.get(AgentRun.Headers.HOSTNAME.toLowerCase()));
        dest.close();
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private static HttpDestination newDestination(Map<String, String> cfg) {
        return new HttpDestination(cfg);
    }

    private static byte[] bytes(int start, int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = (byte) (start + i);
        return b;
    }

    private static boolean containsSubsequence(byte[] haystack, byte[] needle) {
        if (needle.length == 0) return true;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    // ---- Stub HTTP server ----

    private static final class StubServer {
        private final HttpServer server;
        private final int boundPort;
        final AtomicInteger requestCount = new AtomicInteger(0);
        volatile byte[] lastBody;
        volatile Map<String, String> lastHeaders;
        private final Object lock = new Object();

        StubServer() throws IOException {
            this(0);
        }

        StubServer(int port) throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            this.server.createContext("/records", new RecordsHandler());
            this.server.start();
            this.boundPort = server.getAddress().getPort();
        }

        int port() {
            return boundPort;
        }

        String url() {
            return "http://127.0.0.1:" + boundPort + "/records";
        }

        void stop() {
            server.stop(0);
        }

        void awaitRequest(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            synchronized (lock) {
                while (requestCount.get() == 0 && System.currentTimeMillis() < deadline) {
                    lock.wait(Math.max(1, deadline - System.currentTimeMillis()));
                }
            }
        }

        private final class RecordsHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                try (exchange) {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    Map<String, String> hdrs = new HashMap<>();
                    for (Map.Entry<String, List<String>> e : exchange.getRequestHeaders().entrySet()) {
                        hdrs.put(e.getKey().toLowerCase(), e.getValue().isEmpty() ? "" : e.getValue().get(0));
                    }
                    lastBody = body;
                    lastHeaders = hdrs;
                    requestCount.incrementAndGet();
                    exchange.sendResponseHeaders(200, 0);
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }
            }
        }
    }
}
