package com.github.gabert.deepflow.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

/**
 * Single Netty handler that owns the read-only HTTP API for the UI.
 *
 * <p>Endpoints (all return {@code application/json}):
 * <ul>
 *   <li>{@code GET /api/sessions} — list sessions, most recent first</li>
 *   <li>{@code GET /api/sessions/{id}/threads} — threads observed in a session</li>
 *   <li>{@code GET /api/sessions/{id}/calltree?thread=...&request_id=...}
 *       — paired call rows for one (session, thread, request)</li>
 *   <li>{@code GET /api/calls/{call_id}/payloads}
 *       — TI/AR/AX/RE payload rows for a single call</li>
 *   <li>{@code GET /api/objects/{object_id}/history}
 *       — every payload row that mentions the given object id</li>
 *   <li>{@code GET /api/analysis/mutations?session_id=...&request_id=...}
 *       — within-call argument mutations (AR vs AX own_hash diff) for
 *         a request, with AR/AX envelope snapshots per mutated object</li>
 * </ul>
 *
 * <p>SQL is concentrated here (not in the UI) so the browser stays a thin
 * renderer and credentials never leave the server.</p>
 */
public class QueryHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ClickHouseClient ch;
    private final String corsOrigin;

    public QueryHandler(ClickHouseClient ch, String corsOrigin) {
        this.ch = ch;
        this.corsOrigin = corsOrigin;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() == HttpMethod.OPTIONS) {
            sendNoContent(ctx, req);
            return;
        }
        if (req.method() != HttpMethod.GET) {
            sendError(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, "GET only");
            return;
        }

        QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
        String path = decoder.path();

        try {
            Object body = dispatch(path, decoder.parameters());
            if (body == null) {
                sendError(ctx, req, HttpResponseStatus.NOT_FOUND, "no route for " + path);
            } else {
                sendJson(ctx, req, HttpResponseStatus.OK, body);
            }
        } catch (IllegalArgumentException e) {
            sendError(ctx, req, HttpResponseStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            sendError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private Object dispatch(String path, Map<String, List<String>> params) throws Exception {
        if (path.equals("/api/health")) {
            return Map.of("status", "ok");
        }
        if (path.equals("/api/sessions")) {
            return listSessions();
        }
        String[] parts = path.split("/");
        // /api/sessions/{id}/{threads|calltree|requests}
        if (parts.length == 5 && parts[1].equals("api") && parts[2].equals("sessions")) {
            String sessionId = parts[3];
            switch (parts[4]) {
                case "threads": return listThreads(sessionId);
                case "calltree": return callTree(sessionId, params);
                case "requests": return listRequests(sessionId);
                default: return null;
            }
        }
        // /api/calls/{id}/payloads
        if (parts.length == 5 && parts[1].equals("api") && parts[2].equals("calls")
                && parts[4].equals("payloads")) {
            return callPayloads(parts[3]);
        }
        // /api/sessions/{id}/requests/{rid}/payloads
        if (parts.length == 7 && parts[1].equals("api") && parts[2].equals("sessions")
                && parts[4].equals("requests") && parts[6].equals("payloads")) {
            return requestPayloads(parts[3], parts[5]);
        }
        // /api/objects/{id}/history
        if (parts.length == 5 && parts[1].equals("api") && parts[2].equals("objects")
                && parts[4].equals("history")) {
            return objectHistory(parts[3]);
        }
        // /api/analysis/mutations?session_id=...&request_id=...
        if (path.equals("/api/analysis/mutations")) {
            return analysisMutations(params);
        }
        return null;
    }

    private List<Map<String, Object>> listSessions() throws Exception {
        // ReplacingMergeTree dedup via FINAL; cheap because the table is small.
        return ch.query("""
                SELECT session_id, agent_run_id, first_seen, last_seen, retain
                FROM sessions FINAL
                ORDER BY last_seen DESC
                LIMIT 200
                """);
    }

    private List<Map<String, Object>> listRequests(String sessionId) throws Exception {
        String safe = sqlString(sessionId);
        return ch.query("""
                SELECT request_id,
                       any(thread_name) AS thread_name,
                       count() AS call_count,
                       min(ts_in) AS first_call,
                       max(ts_out) AS last_call,
                       dateDiff('millisecond', min(ts_in), max(ts_out)) AS span_ms
                FROM calls
                WHERE session_id = """ + safe + """

                GROUP BY request_id
                ORDER BY first_call
                """);
    }

    private List<Map<String, Object>> listThreads(String sessionId) throws Exception {
        String safe = sqlString(sessionId);
        return ch.query("""
                SELECT thread_name,
                       count() AS call_count,
                       min(ts_in) AS first_call,
                       max(ts_out) AS last_call
                FROM calls
                WHERE session_id = """ + safe + """
                GROUP BY thread_name
                ORDER BY first_call
                """);
    }

    private List<Map<String, Object>> callTree(String sessionId, Map<String, List<String>> params) throws Exception {
        String thread = singleParam(params, "thread");
        String requestId = singleParam(params, "request_id");
        StringBuilder where = new StringBuilder("WHERE session_id = ").append(sqlString(sessionId));
        if (thread != null) where.append(" AND thread_name = ").append(sqlString(thread));
        if (requestId != null) where.append(" AND request_id = ").append(Long.parseLong(requestId));

        return ch.query("""
                SELECT call_id, parent_call_id, request_id, thread_name,
                       ts_in, ts_out, duration_ms, signature, return_type,
                       is_exception, this_id, seq
                FROM calls
                """ + where + """

                ORDER BY seq, ts_in
                LIMIT 5000
                """);
    }

    private List<Map<String, Object>> callPayloads(String callId) throws Exception {
        validateUuid(callId);
        return ch.query("""
                SELECT kind, payload_json, payload_size, root_hash, object_ids, ts_in, signature
                FROM payloads
                WHERE call_id = """ + sqlUuid(callId) + """

                ORDER BY kind
                """);
    }

    private List<Map<String, Object>> requestPayloads(String sessionId, String requestId) throws Exception {
        long rid = Long.parseLong(requestId);
        return ch.query("""
                SELECT call_id, kind, payload_json, root_hash, ts_in, signature, seq
                FROM payloads
                WHERE session_id = """ + sqlString(sessionId) + " AND request_id = " + rid + """

                ORDER BY seq, kind, ts_in
                LIMIT 10000
                """);
    }

    private List<Map<String, Object>> objectHistory(String objectId) throws Exception {
        long parsed = Long.parseLong(objectId);
        return ch.query("""
                SELECT call_id, session_id, request_id, ts_in, signature, kind, root_hash, payload_json
                FROM payloads
                WHERE has(object_ids, """ + parsed + """
                )
                ORDER BY ts_in
                LIMIT 1000
                """);
    }

    // --- analysis ---------------------------------------------------------

    /**
     * Within-request mutation detection. For each call where AR and AX
     * payloads are both present, compare own_hashes index-aligned with
     * object_ids. Any object whose own_hash moved between AR and AX is
     * a mutation: same call, before-method state vs after-method state.
     *
     * <p>The SQL does the indexed lookup (bloom-filter on object_ids,
     * length-aligned own_hashes scan) and returns the AR/AX payload
     * JSON plus the list of mutated ids per call. Java then walks the
     * JSON to extract the per-id envelope snapshots so the client can
     * render diffs without re-fetching anything.</p>
     *
     * <p>Pre-req: the agent must be emitting AX. Default emit_tags
     * omits it; calls without AX silently produce no rows here.</p>
     */
    private Map<String, Object> analysisMutations(Map<String, List<String>> params) throws Exception {
        String sessionId = required(params, "session_id");
        long requestId = Long.parseLong(required(params, "request_id"));

        String sessionLit = sqlString(sessionId);
        String sql = """
                SELECT
                    ar.call_id      AS call_id,
                    ar.signature    AS signature,
                    ar.ts_in        AS ts_in,
                    ar.payload_json AS ar_json,
                    ax.payload_json AS ax_json,
                    arrayFilter(
                        (oid, oh) ->
                            indexOf(ax.object_ids, oid) > 0
                            AND ax.own_hashes[indexOf(ax.object_ids, oid)] != oh,
                        ar.object_ids, ar.own_hashes
                    ) AS mutated_ids
                FROM
                    (SELECT call_id, signature, ts_in,
                            object_ids, own_hashes, payload_json
                     FROM payloads
                     WHERE session_id = """ + sessionLit + " AND request_id = " + requestId + """

                       AND kind = 'AR') ar
                INNER JOIN
                    (SELECT call_id, object_ids, own_hashes, payload_json
                     FROM payloads
                     WHERE session_id = """ + sessionLit + " AND request_id = " + requestId + """

                       AND kind = 'AX') ax
                ON ar.call_id = ax.call_id
                WHERE length(mutated_ids) > 0
                ORDER BY ar.ts_in
                """;

        List<Map<String, Object>> sqlRows = ch.query(sql);

        // Group by (call_id, signature, class, changed-path-set). Three
        // BookEntity rows that all changed `isbn` collapse to one group
        // with occurrences=3; an Author with `name` mutation stays its
        // own group. Scales to bulk-transform requests where naive
        // per-occurrence rendering would be unreadable.
        //
        // Per group: keep all member object_ids (so the user can drill),
        // plus the first member's AR/AX snapshots as a sample for the
        // default rendering. Detailed per-instance diffs are computed
        // on the client from already-loaded request payloads.
        Map<String, Map<String, Object>> groupsByKey = new LinkedHashMap<>();
        long totalMutations = 0;

        for (Map<String, Object> r : sqlRows) {
            JsonNode arRoot = MAPPER.readTree((String) r.get("ar_json"));
            JsonNode axRoot = MAPPER.readTree((String) r.get("ax_json"));
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) r.get("mutated_ids");
            for (Object idObj : ids) {
                long oid = Long.parseLong(String.valueOf(idObj));
                JsonNode arSnapshot = findEnvelope(arRoot, oid);
                JsonNode axSnapshot = findEnvelope(axRoot, oid);
                if (arSnapshot == null || axSnapshot == null) continue;
                totalMutations++;

                String cls = textOrNull(arSnapshot.path("__meta__").path("class"));
                if (cls == null) cls = textOrNull(axSnapshot.path("__meta__").path("class"));
                SortedSet<EnvelopeDiff.DiffPath> changed = EnvelopeDiff.changedPaths(arSnapshot, axSnapshot);

                String key = r.get("call_id") + "|" + cls + "|" + changed.toString();
                Map<String, Object> group = groupsByKey.get(key);
                if (group == null) {
                    List<Map<String, Object>> fieldPaths = new ArrayList<>();
                    for (EnvelopeDiff.DiffPath p : changed) {
                        fieldPaths.add(Map.of("path", p.path(), "kind", p.kind()));
                    }
                    group = new LinkedHashMap<>();
                    group.put("call_id", r.get("call_id"));
                    group.put("signature", r.get("signature"));
                    group.put("ts_in", r.get("ts_in"));
                    group.put("class", cls);
                    group.put("field_paths", fieldPaths);
                    group.put("object_ids", new ArrayList<Long>());
                    group.put("sample", Map.of(
                            "object_id", oid,
                            "ar_snapshot", arSnapshot,
                            "ax_snapshot", axSnapshot
                    ));
                    groupsByKey.put(key, group);
                }
                @SuppressWarnings("unchecked")
                List<Long> memberIds = (List<Long>) group.get("object_ids");
                memberIds.add(oid);
            }
        }

        List<Map<String, Object>> groups = new ArrayList<>(groupsByKey.values());
        for (Map<String, Object> g : groups) {
            @SuppressWarnings("unchecked")
            List<Long> memberIds = (List<Long>) g.get("object_ids");
            g.put("occurrences", memberIds.size());
        }

        Map<String, Object> summary = Map.of(
                "total_mutations", totalMutations,
                "total_groups", groups.size(),
                "session_id", sessionId,
                "request_id", requestId
        );
        return Map.of("summary", summary, "groups", groups);
    }

    private static String textOrNull(JsonNode n) {
        return (n == null || n.isMissingNode() || n.isNull()) ? null : n.asText();
    }

    /**
     * Depth-first walk for the first envelope in {@code node} whose
     * {@code __meta__.id} equals {@code targetId}. Returns null if the
     * id never appears as a full envelope in this tree (cycle refs,
     * which carry {@code ref_id} not {@code __meta__}, are not full
     * envelopes and are skipped).
     */
    private static JsonNode findEnvelope(JsonNode node, long targetId) {
        if (node == null) return null;
        if (node.isObject()) {
            JsonNode meta = node.get("__meta__");
            if (meta != null) {
                JsonNode idNode = meta.get("id");
                if (idNode != null && idNode.isNumber() && idNode.asLong() == targetId) {
                    return node;
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                JsonNode found = findEnvelope(fields.next().getValue(), targetId);
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findEnvelope(child, targetId);
                if (found != null) return found;
            }
        }
        return null;
    }

    // --- helpers -----------------------------------------------------------

    private static String required(Map<String, List<String>> params, String name) {
        String v = singleParam(params, name);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing required parameter: " + name);
        }
        return v;
    }

    private static String singleParam(Map<String, List<String>> params, String name) {
        List<String> vs = params.get(name);
        return (vs == null || vs.isEmpty()) ? null : vs.get(0);
    }

    private static String sqlString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String sqlUuid(String value) {
        validateUuid(value);
        return "toUUID('" + value + "')";
    }

    private static void validateUuid(String value) {
        try {
            java.util.UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not a UUID: " + value);
        }
    }

    // --- response writers --------------------------------------------------

    private void sendJson(ChannelHandlerContext ctx, FullHttpRequest req,
                          HttpResponseStatus status, Object body) {
        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            sendError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            return;
        }
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        addCors(resp);
        write(ctx, req, resp);
    }

    private void sendError(ChannelHandlerContext ctx, FullHttpRequest req,
                           HttpResponseStatus status, String message) {
        try {
            sendJson(ctx, req, status, Map.of("error", message == null ? status.reasonPhrase() : message));
        } catch (Exception ignored) {
            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                    Unpooled.wrappedBuffer(status.reasonPhrase().getBytes(StandardCharsets.UTF_8)));
            addCors(resp);
            write(ctx, req, resp);
        }
    }

    private void sendNoContent(ChannelHandlerContext ctx, FullHttpRequest req) {
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        addCors(resp);
        write(ctx, req, resp);
    }

    private void addCors(FullHttpResponse resp) {
        resp.headers().set("Access-Control-Allow-Origin", corsOrigin);
        resp.headers().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        resp.headers().set("Access-Control-Allow-Headers", "Content-Type");
        resp.headers().set("Access-Control-Max-Age", "600");
    }

    private void write(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse resp) {
        boolean keepAlive = io.netty.handler.codec.http.HttpUtil.isKeepAlive(req);
        if (keepAlive) {
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(resp);
        } else {
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
