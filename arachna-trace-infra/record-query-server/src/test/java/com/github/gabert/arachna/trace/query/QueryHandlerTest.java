package com.github.gabert.arachna.trace.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryHandlerTest {

    private static final String CORS_ORIGIN = "https://ui.example.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void optionsRequestReturns204WithCorsHeaders() {
        EmbeddedChannel ch = new EmbeddedChannel(new QueryHandler(new StubClient(), CORS_ORIGIN));
        ch.writeInbound(req(HttpMethod.OPTIONS, "/api/sessions"));

        FullHttpResponse resp = ch.readOutbound();
        assertEquals(HttpResponseStatus.NO_CONTENT, resp.status());
        assertEquals(CORS_ORIGIN, resp.headers().get("Access-Control-Allow-Origin"));
        assertEquals("GET, OPTIONS", resp.headers().get("Access-Control-Allow-Methods"));
        resp.release();
    }

    @Test
    void nonGetMethodReturns405() {
        EmbeddedChannel ch = new EmbeddedChannel(new QueryHandler(new StubClient(), CORS_ORIGIN));
        ch.writeInbound(req(HttpMethod.POST, "/api/sessions"));

        FullHttpResponse resp = ch.readOutbound();
        assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, resp.status());
        resp.release();
    }

    @Test
    void healthEndpointReturns200WithStatusOk() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(new QueryHandler(new StubClient(), CORS_ORIGIN));
        ch.writeInbound(req(HttpMethod.GET, "/api/health"));

        FullHttpResponse resp = ch.readOutbound();
        assertEquals(HttpResponseStatus.OK, resp.status());
        JsonNode body = MAPPER.readTree(bodyText(resp));
        assertEquals("ok", body.path("status").asText());
        resp.release();
    }

    @Test
    void unknownPathReturns404() {
        EmbeddedChannel ch = new EmbeddedChannel(new QueryHandler(new StubClient(), CORS_ORIGIN));
        ch.writeInbound(req(HttpMethod.GET, "/api/nope"));

        FullHttpResponse resp = ch.readOutbound();
        assertEquals(HttpResponseStatus.NOT_FOUND, resp.status());
        resp.release();
    }

    @Test
    void sessionsListEndpointReturnsCannedRowsAsJsonArray() throws Exception {
        // Routing-through smoke test: GET /api/sessions reaches
        // SessionsApi.listSessions() which calls ch.query(...). The stubbed
        // result must round-trip back to the response body verbatim.
        StubClient stub = new StubClient();
        stub.next = List.of(Map.of("session_id", "s-1"), Map.of("session_id", "s-2"));
        EmbeddedChannel ch = new EmbeddedChannel(new QueryHandler(stub, CORS_ORIGIN));
        ch.writeInbound(req(HttpMethod.GET, "/api/sessions"));

        FullHttpResponse resp = ch.readOutbound();
        assertEquals(HttpResponseStatus.OK, resp.status());
        assertEquals(CORS_ORIGIN, resp.headers().get("Access-Control-Allow-Origin"));
        JsonNode body = MAPPER.readTree(bodyText(resp));
        assertTrue(body.isArray());
        assertEquals(2, body.size());
        assertEquals("s-1", body.get(0).path("session_id").asText());
        resp.release();
    }

    @Test
    void missingRequiredParamReturns400WithMessageFromException() throws Exception {
        // IllegalArgumentException from Params.required must map to 400 and
        // the user-facing body must carry the actionable message so the UI
        // can surface "missing required parameter: session_id" directly.
        EmbeddedChannel ch = new EmbeddedChannel(new QueryHandler(new StubClient(), CORS_ORIGIN));
        ch.writeInbound(req(HttpMethod.GET, "/api/analysis/mutations"));

        FullHttpResponse resp = ch.readOutbound();
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
        JsonNode body = MAPPER.readTree(bodyText(resp));
        assertEquals("missing required parameter: session_id", body.path("error").asText());
        resp.release();
    }

    @Test
    void invalidCallIdUuidReturns400() {
        // SessionsApi.callPayloads runs UUID.fromString on the path segment;
        // the IllegalArgumentException must surface as 400, not 500, so the
        // UI distinguishes user input errors from server failures.
        EmbeddedChannel ch = new EmbeddedChannel(new QueryHandler(new StubClient(), CORS_ORIGIN));
        ch.writeInbound(req(HttpMethod.GET, "/api/calls/not-a-uuid/payloads"));

        FullHttpResponse resp = ch.readOutbound();
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());
        resp.release();
    }

    private static FullHttpRequest req(HttpMethod method, String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri, Unpooled.EMPTY_BUFFER);
    }

    private static String bodyText(FullHttpResponse resp) {
        return resp.content().toString(StandardCharsets.UTF_8);
    }

    private static final class StubClient extends ClickHouseClient {
        List<Map<String, Object>> next = List.of();

        @Override
        public List<Map<String, Object>> query(String sql) {
            return next;
        }

        @Override
        public List<Map<String, Object>> query(String sql, Map<String, String> params) {
            return next;
        }
    }
}
