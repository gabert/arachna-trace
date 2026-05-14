package com.github.gabert.arachna.trace.server;

import com.github.gabert.arachna.trace.recorder.AgentRun;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecordHandlerTest {

    @Test
    void unknownPathReturnsNotFoundAndDoesNotForward() {
        CapturingForwarder fwd = new CapturingForwarder();
        EmbeddedChannel ch = new EmbeddedChannel(new RecordHandler(fwd));

        ch.writeInbound(post("/wrong-path", new byte[]{1, 2, 3}, Map.of()));

        FullHttpResponse resp = ch.readOutbound();
        assertEquals(HttpResponseStatus.NOT_FOUND, resp.status());
        assertNull(fwd.lastBody, "404 path must not forward to Kafka");
        resp.release();
    }

    @Test
    void nonPostMethodReturnsMethodNotAllowedAndDoesNotForward() {
        CapturingForwarder fwd = new CapturingForwarder();
        EmbeddedChannel ch = new EmbeddedChannel(new RecordHandler(fwd));

        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/records", Unpooled.EMPTY_BUFFER);
        ch.writeInbound(req);

        FullHttpResponse resp = ch.readOutbound();
        assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, resp.status());
        assertNull(fwd.lastBody, "405 path must not forward to Kafka");
        resp.release();
    }

    @Test
    void postRecordsRespondsOkAndForwardsBodyBytes() {
        CapturingForwarder fwd = new CapturingForwarder();
        EmbeddedChannel ch = new EmbeddedChannel(new RecordHandler(fwd));

        byte[] body = "binary-record-bytes".getBytes(StandardCharsets.UTF_8);
        ch.writeInbound(post("/records", body, Map.of()));

        FullHttpResponse resp = ch.readOutbound();
        assertEquals(HttpResponseStatus.OK, resp.status());
        assertArrayEquals(body, fwd.lastBody, "body bytes must be forwarded byte-for-byte");
        resp.release();
    }

    @Test
    void postRecordsForwardsAllSevenAgentRunHeaders() {
        CapturingForwarder fwd = new CapturingForwarder();
        EmbeddedChannel ch = new EmbeddedChannel(new RecordHandler(fwd));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(AgentRun.Headers.AGENT_RUN_ID,  "11111111-2222-3333-4444-555555555555");
        headers.put(AgentRun.Headers.HOSTNAME,      "host-1");
        headers.put(AgentRun.Headers.AGENT_VERSION, "1.2.3");
        headers.put(AgentRun.Headers.CODE_VERSION,  "abc123");
        headers.put(AgentRun.Headers.ENV,           "prod");
        headers.put(AgentRun.Headers.JVM_PID,       "4242");
        headers.put(AgentRun.Headers.STARTED_AT_MS, "9000000");

        ch.writeInbound(post("/records", new byte[]{0}, headers));

        FullHttpResponse resp = ch.readOutbound();
        assertEquals(HttpResponseStatus.OK, resp.status());
        assertEquals(headers, fwd.lastHeaders,
                "all 7 agent-run headers must be copied verbatim to the Kafka forwarder");
        resp.release();
    }

    @Test
    void postRecordsOmitsAbsentAgentRunHeadersAndIgnoresUnrelatedOnes() {
        CapturingForwarder fwd = new CapturingForwarder();
        EmbeddedChannel ch = new EmbeddedChannel(new RecordHandler(fwd));

        // Send 2 of the 7 known agent-run headers, plus an unrelated header.
        Map<String, String> reqHeaders = new LinkedHashMap<>();
        reqHeaders.put(AgentRun.Headers.AGENT_RUN_ID, "11111111-2222-3333-4444-555555555555");
        reqHeaders.put(AgentRun.Headers.HOSTNAME,     "host-2");
        reqHeaders.put("X-Some-Other-Header",         "should-be-dropped");

        ch.writeInbound(post("/records", new byte[]{0}, reqHeaders));

        FullHttpResponse resp = ch.readOutbound();
        assertEquals(HttpResponseStatus.OK, resp.status());
        assertEquals(2, fwd.lastHeaders.size(), "only present agent-run headers should be forwarded");
        assertEquals("11111111-2222-3333-4444-555555555555",
                fwd.lastHeaders.get(AgentRun.Headers.AGENT_RUN_ID));
        assertEquals("host-2", fwd.lastHeaders.get(AgentRun.Headers.HOSTNAME));
        assertFalse(fwd.lastHeaders.containsKey("X-Some-Other-Header"),
                "non-agent-run headers must not leak through to Kafka");
        assertFalse(fwd.lastHeaders.containsKey(AgentRun.Headers.AGENT_VERSION),
                "absent agent-run headers must not appear with null/empty value");
    }

    private static FullHttpRequest post(String uri, byte[] body, Map<String, String> headers) {
        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, uri, Unpooled.wrappedBuffer(body));
        req.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            req.headers().set(e.getKey(), e.getValue());
        }
        return req;
    }

    private static final class CapturingForwarder extends KafkaRecordForwarder {
        byte[] lastBody;
        Map<String, String> lastHeaders;

        @Override
        public void send(byte[] rawRecords, Map<String, String> agentHeaders) {
            this.lastBody = rawRecords;
            this.lastHeaders = new LinkedHashMap<>(agentHeaders);
        }

        @Override
        public void close() {
        }
    }
}
