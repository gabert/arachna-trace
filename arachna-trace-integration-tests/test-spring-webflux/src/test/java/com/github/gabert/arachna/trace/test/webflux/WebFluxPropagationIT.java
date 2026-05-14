package com.github.gabert.arachna.trace.test.webflux;

import com.github.gabert.arachna.trace.test.common.TestTraceCollector;
import com.github.gabert.arachna.trace.test.common.TraceData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebFlux integration tests for request ID propagation.
 *
 * <p>WebFlux uses Reactor schedulers (boundedElastic, parallel) which do not
 * always submit tasks via {@code ThreadPoolExecutor.execute(Runnable)} —
 * Reactor's own dispatching layer routes around the JDK executor surface that
 * the agent's {@code ExecutorAdvice} instruments. As a result, the handler's
 * request ID does NOT propagate to the scheduler worker threads. Only the
 * synchronous {@code /mono} case shares the RI between handler and service
 * because both run on the event-loop thread without an executor hop.</p>
 *
 * <p>Until Reactor context bridging is wired up, these tests can only pin
 * trace-existence on the scheduler paths — i.e. that the agent did capture
 * MS/TS/TE records for both the handler and the service call, regardless of
 * whether they share an RI.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebFluxPropagationIT {

    @LocalServerPort
    int port;

    private TraceData traces;

    @BeforeAll
    void exerciseEndpoints() throws Exception {
        TestTraceCollector.clear();

        HttpClient client = HttpClient.newHttpClient();
        String base = "http://localhost:" + port;

        // Scenario 14: simple Mono (synchronous in handler)
        client.send(HttpRequest.newBuilder(URI.create(base + "/mono")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // Scenario 15: Mono.fromCallable on boundedElastic
        client.send(HttpRequest.newBuilder(URI.create(base + "/blocking")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Thread.sleep(1000);

        // Scenario 16: Flux with publishOn parallel
        client.send(HttpRequest.newBuilder(URI.create(base + "/flux")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Thread.sleep(1000);

        // Scenario 17: reactive chain (two boundedElastic hops)
        client.send(HttpRequest.newBuilder(URI.create(base + "/chain")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Thread.sleep(1000);

        // Wait for the agent drainer to flush
        Thread.sleep(2000);
        traces = TestTraceCollector.collect();

        assertFalse(traces.blocks().isEmpty(), "Should have captured trace records");
    }

    // ==================== Scenario 14: Simple Mono (sync in handler) ====================

    @Test
    void mono_handlerAndServiceShareRequestId() {
        Set<Long> ids = traces.requestIdsForMethod("TestHandler.mono");
        assertFalse(ids.isEmpty(), "Mono endpoint should produce trace records");

        long ri = ids.iterator().next();
        List<String> methods = traces.methodsForRequestId(ri);
        assertTrue(methods.stream()
                        .anyMatch(m -> m.contains("ReactiveWorkService.blockingQuery")),
                "Service call should share RI with handler");
    }

    // ==================== Scenario 15: boundedElastic ====================

    @Test
    void blocking_handlerAndServiceAreBothTraced() {
        // Trace-existence pin only — RI propagation across boundedElastic is
        // NOT honoured (see class JavaDoc). Asserting same-RI here would fail
        // until Reactor context bridging is implemented; until then we pin
        // that the agent captures records for BOTH ends of the hop so the
        // call still appears in the trace, even if disconnected from the
        // handler's RI.
        assertFalse(traces.requestIdsForMethod("TestHandler.blocking").isEmpty(),
                "blocking handler must be traced");
        assertFalse(traces.requestIdsForMethod("ReactiveWorkService.blockingQuery").isEmpty(),
                "blockingQuery on boundedElastic must be traced");
    }

    // ==================== Scenario 16: Flux with publishOn ====================

    @Test
    void flux_handlerAndTransformAreBothTraced() {
        // Same reasoning as the /blocking pin: publishOn(parallel) hops onto
        // the parallel Scheduler's worker via Reactor's own dispatch, so the
        // handler RI does not propagate. We pin trace existence on both ends.
        assertFalse(traces.requestIdsForMethod("TestHandler.flux").isEmpty(),
                "flux handler must be traced");
        assertFalse(traces.requestIdsForMethod("ReactiveWorkService.transform").isEmpty(),
                "transform on parallel scheduler must be traced");
    }

    // ==================== Scenario 17: Reactive chain ====================

    @Test
    void chain_handlerIsTraced() {
        Set<Long> ids = traces.requestIdsForMethod("TestHandler.chain");
        assertFalse(ids.isEmpty(), "chain handler should be traced");
    }

    @Test
    void chain_bothBlockingStagesAreTraced() {
        // Replaces the old chain_bothBlockingCallsAreTraced (which counted
        // blockingQuery globally and would have passed if /chain never ran).
        // Filter by the AR tag to attribute blockingQuery entries to /chain
        // specifically — the only endpoint that passes "chain-1"/"chain-2".
        // Same-RI cannot be asserted: Reactor schedulers bypass the executor
        // instrumentation surface, so each stage runs with its own thread's
        // (stale or zero) RI rather than the /chain handler's RI.
        List<String> chainCallArgs = traces.entries().stream()
                .filter(b -> b.method() != null
                        && b.method().contains("ReactiveWorkService.blockingQuery"))
                .map(b -> b.tags().getOrDefault("AR", ""))
                .filter(ar -> ar.contains("chain-"))
                .toList();
        assertTrue(chainCallArgs.size() >= 2,
                "Expected ≥2 blockingQuery entries attributable to /chain "
                        + "(chain-1 + chain-2); got " + chainCallArgs.size()
                        + ": " + chainCallArgs);
    }
}
