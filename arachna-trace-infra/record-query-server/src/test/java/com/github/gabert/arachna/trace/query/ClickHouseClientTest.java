package com.github.gabert.arachna.trace.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickHouseClientTest {

    private ClickHouseClient client;

    @BeforeEach
    void setUp() throws IOException {
        QueryServerConfig config = QueryServerConfig.load(new String[]{
                "clickhouse_url=http://ch.example:8123",
                "clickhouse_user=u",
                "clickhouse_password=p"
        });
        client = new ClickHouseClient(config);
    }

    @Test
    void bodyIsSqlFollowedByFormatJsonEachRow() {
        HttpRequest req = client.buildRequest("SELECT 1", Map.of());
        assertEquals("SELECT 1\nFORMAT JSONEachRow", bodyOf(req));
    }

    @Test
    void boundValuesDoNotAppearInBody() {
        // Load-bearing SQL-injection contract: every user-supplied value must
        // travel via the param_<name>= URL channel, not be spliced into the
        // SQL body. A regression here is a SQL-injection risk.
        // See feedback_sql_parameter_binding.md.
        String malicious = "'; DROP TABLE payloads --";
        HttpRequest req = client.buildRequest(
                "SELECT * FROM payloads WHERE session_id = {session_id:String}",
                Map.of("session_id", malicious));

        String body = bodyOf(req);
        assertFalse(body.contains(malicious),
                "user-supplied value must not appear in the SQL body — got: " + body);
        assertFalse(body.contains("DROP TABLE"),
                "SQL-injection payload must not be spliced into the body");
    }

    @Test
    void paramsAppearAsParamPrefixedQueryStringEntries() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("session_id", "abc");
        params.put("request_id", "42");
        HttpRequest req = client.buildRequest("SELECT 1", params);

        String query = req.uri().getRawQuery();
        assertTrue(query.contains("param_session_id=abc"), "got: " + query);
        assertTrue(query.contains("param_request_id=42"), "got: " + query);
    }

    @Test
    void uriContainsDatabaseSelector() {
        HttpRequest req = client.buildRequest("SELECT 1", Map.of());
        assertEquals("database=arachna_trace", req.uri().getRawQuery());
    }

    @Test
    void specialCharactersInValueAreUrlEncoded() {
        // ' ', '&', '=', '+', '%' all change SQL/URL meaning if not escaped;
        // pin that buildRequest puts them through URLEncoder rather than
        // letting them split the query string or mutate the SQL bind name.
        HttpRequest req = client.buildRequest("SELECT 1",
                Map.of("v", "a b&c=d+e%f"));

        String query = req.uri().getRawQuery();
        assertTrue(query.contains("param_v=a+b%26c%3Dd%2Be%25f"),
                "value must be URL-encoded; got: " + query);
    }

    @Test
    void emptyParamsYieldDatabaseOnlyQueryString() {
        HttpRequest req = client.buildRequest("SELECT 1", Map.of());
        assertEquals("/?database=arachna_trace", req.uri().getRawPath() + "?" + req.uri().getRawQuery());
    }

    @Test
    void parseJsonEachRowHandlesMultipleLinesBlankLinesAndCrlf() throws IOException {
        // ClickHouse JSONEachRow output is one row per line. Real responses
        // may end with a trailing newline (→ blank tail line) and may use
        // CRLF rather than LF; both must parse to N rows, no empty map.
        String body = "{\"a\":1}\r\n{\"a\":2}\n\n{\"a\":3}\n";
        List<Map<String, Object>> rows = ClickHouseClient.parseJsonEachRow(body);
        assertEquals(3, rows.size());
        assertEquals(1, ((Number) rows.get(0).get("a")).intValue());
        assertEquals(2, ((Number) rows.get(1).get("a")).intValue());
        assertEquals(3, ((Number) rows.get(2).get("a")).intValue());
    }

    @Test
    void parseJsonEachRowReturnsEmptyListForEmptyBody() throws IOException {
        assertTrue(ClickHouseClient.parseJsonEachRow("").isEmpty());
        assertTrue(ClickHouseClient.parseJsonEachRow("\n\n").isEmpty());
    }

    private static String bodyOf(HttpRequest req) {
        CapturingSubscriber sub = new CapturingSubscriber();
        req.bodyPublisher().orElseThrow().subscribe(sub);
        return sub.body();
    }

    private static final class CapturingSubscriber
            implements java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
        private final StringBuilder out = new StringBuilder();

        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
            s.request(Long.MAX_VALUE);
        }
        @Override public void onNext(java.nio.ByteBuffer bb) {
            byte[] bytes = new byte[bb.remaining()];
            bb.get(bytes);
            out.append(new String(bytes, StandardCharsets.UTF_8));
        }
        @Override public void onError(Throwable t) { throw new RuntimeException(t); }
        @Override public void onComplete() {}

        String body() { return out.toString(); }
    }
}
