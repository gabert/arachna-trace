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
import java.util.ArrayDeque;
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
        // /api/sessions/{id}/{threads|calltree|requests|size}
        if (parts.length == 5 && parts[1].equals("api") && parts[2].equals("sessions")) {
            String sessionId = parts[3];
            switch (parts[4]) {
                case "threads": return listThreads(sessionId);
                case "calltree": return callTree(sessionId, params);
                case "requests": return listRequests(sessionId);
                case "size": return sessionSize(sessionId);
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
        // /api/analysis/value-search?session_id=...[&request_id=...]&value=...
        if (path.equals("/api/analysis/value-search")) {
            return analysisValueSearch(params);
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

    /**
     * Session-scoped storage footprint, surfaced in the UI's status
     * bar so the developer can see at a glance how much data this
     * session has produced. Reports the uncompressed JSON payload
     * bytes (the bulk of what the session stores) plus row counts
     * for context. ClickHouse's actual on-disk footprint after
     * compression is ~10× smaller for typical JSON, but the
     * "uncompressed payload bytes" number maps best to the
     * developer's mental model of "how big is the trace I just
     * generated."
     */
    private Map<String, Object> sessionSize(String sessionId) throws Exception {
        String safe = sqlString(sessionId);
        List<Map<String, Object>> payloadAgg = ch.query("""
                SELECT count() AS payload_rows,
                       sum(payload_size) AS payload_bytes
                FROM payloads
                WHERE session_id = """ + safe + """

                """);
        List<Map<String, Object>> callAgg = ch.query("""
                SELECT count() AS call_rows
                FROM calls
                WHERE session_id = """ + safe + """

                """);
        Map<String, Object> p = payloadAgg.isEmpty() ? Map.of() : payloadAgg.get(0);
        Map<String, Object> c = callAgg.isEmpty() ? Map.of() : callAgg.get(0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_id", sessionId);
        out.put("payload_rows", asLong(p.get("payload_rows")));
        out.put("payload_bytes", asLong(p.get("payload_bytes")));
        out.put("call_rows", asLong(c.get("call_rows")));
        return out;
    }

    private static long asLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (NumberFormatException e) { return 0; }
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
                    ) AS mutated_ids,
                    arrayFilter(
                        oid -> indexOf(ar.object_ids, oid) <= 0,
                        ax.object_ids
                    ) AS added_ids
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
                WHERE length(mutated_ids) > 0 OR length(added_ids) > 0
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

        // Per-call summary of object-level changes. UI's in-tree marks
        // (mutated / added envelope highlights inside JsonTree) read
        // from this directly instead of re-walking the parsed payloads
        // client-side. Same SQL pass already classifies the ids; we
        // just expose them per call_id alongside the grouped view.
        // ClickHouse Int64 → JSON: serialised as strings by the CH
        // client (avoiding JS precision loss for full Int64). UI Sets
        // are keyed by numeric envelope ids, so coerce to Long here
        // — Jackson then writes them as JSON numbers.
        List<Map<String, Object>> perCall = new ArrayList<>(sqlRows.size());
        for (Map<String, Object> r : sqlRows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("call_id", r.get("call_id"));
            entry.put("mutated", toLongList(r.get("mutated_ids")));
            entry.put("added", toLongList(r.get("added_ids")));
            perCall.add(entry);
        }

        Map<String, Object> summary = Map.of(
                "total_mutations", totalMutations,
                "total_groups", groups.size(),
                "session_id", sessionId,
                "request_id", requestId
        );
        return Map.of("summary", summary, "groups", groups, "perCall", perCall);
    }

    /**
     * Find every appearance of a scalar value within a session (or one
     * request when {@code request_id} is given). Powers the Origin /
     * value-search panel without forcing the client to walk every parsed
     * payload — the bloom-filter skip-index over {@code payload_tokens}
     * lets the row scan run as an indexed probe, and the JSON walk to
     * resolve exact paths happens once on the server per matching row
     * rather than per-payload-on-every-click on the client.
     *
     * <p>Required: {@code session_id}, {@code value}.
     * Optional: {@code request_id} (omit for session-wide).</p>
     *
     * <p>Response: {@code [{ call_id, kind, signature, ts_in, request_id,
     * path }]} sorted by {@code ts_in}. {@code path} is a JSON array of
     * string-or-int segments from the payload root to the matching leaf.
     * One row per leaf occurrence — the same value at multiple paths in
     * one payload yields multiple rows.</p>
     */
    private List<Map<String, Object>> analysisValueSearch(Map<String, List<String>> params) throws Exception {
        String sessionId = required(params, "session_id");
        String value = required(params, "value");
        String requestIdStr = singleParam(params, "request_id");
        String mode = singleParam(params, "mode");
        boolean substring = "substring".equalsIgnoreCase(mode);

        StringBuilder where = new StringBuilder("WHERE session_id = ").append(sqlString(sessionId));
        if (requestIdStr != null) {
            where.append(" AND request_id = ").append(Long.parseLong(requestIdStr));
        }
        if (substring) {
            // Full-scan substring filter. positionCaseInsensitive returns
            // 1-based offset, 0 if not found. No bloom-filter help — this
            // mode is the deliberate slow path for "Tolkien matches J.R.R.
            // Tolkien" cases the exact-match index can't catch.
            where.append(" AND positionCaseInsensitive(payload_json, ")
                 .append(sqlString(value)).append(") > 0");
        } else {
            // Bloom-filter probe over payload_tokens — fast at session
            // / cross-session scale. Exact value equality only.
            where.append(" AND has(payload_tokens, ").append(sqlString(value)).append(")");
        }

        String sql = """
                SELECT call_id, kind, signature, ts_in, request_id, payload_json
                FROM payloads
                """ + where + """

                ORDER BY ts_in
                LIMIT 5000
                """;
        List<Map<String, Object>> rows = ch.query(sql);

        String needleLower = substring ? value.toLowerCase(java.util.Locale.ROOT) : null;
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String json = (String) r.get("payload_json");
            if (json == null) continue;
            JsonNode root = MAPPER.readTree(json);
            List<List<Object>> paths = new ArrayList<>();
            if (substring) {
                findPathsContainingScalar(root, needleLower, new ArrayDeque<>(), paths);
            } else {
                findPathsToScalar(root, value, new ArrayDeque<>(), paths);
            }
            for (List<Object> path : paths) {
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("call_id", r.get("call_id"));
                hit.put("kind", r.get("kind"));
                hit.put("signature", r.get("signature"));
                hit.put("ts_in", r.get("ts_in"));
                hit.put("request_id", r.get("request_id"));
                hit.put("path", path);
                hits.add(hit);
            }
        }
        return hits;
    }

    /**
     * DFS over the hashed payload tree. For every leaf scalar whose
     * canonical string form equals {@code target}, append a copy of
     * the current path to {@code out}. Skips {@code __meta__} blocks
     * (envelope identity, not user data) and cycle refs (graph
     * pointers). Mirrors {@code ScalarTokenCollector}'s walk shape
     * so that "what's in the bloom filter" and "what we surface here"
     * stay aligned.
     */
    private static void findPathsToScalar(
            JsonNode node, String target,
            ArrayDeque<Object> path, List<List<Object>> out) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            JsonNode cycleRef = node.get("cycle_ref");
            if (cycleRef != null && cycleRef.isBoolean() && cycleRef.asBoolean()) return;
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                if ("__meta__".equals(e.getKey())) continue;
                path.addLast(e.getKey());
                findPathsToScalar(e.getValue(), target, path, out);
                path.removeLast();
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                path.addLast(i);
                findPathsToScalar(node.get(i), target, path, out);
                path.removeLast();
            }
        } else {
            String canonical;
            if (node.isTextual()) canonical = node.asText();
            else if (node.isNumber()) canonical = node.numberValue().toString();
            else if (node.isBoolean()) canonical = Boolean.toString(node.asBoolean());
            else return;
            if (canonical.equals(target)) {
                out.add(new ArrayList<>(path));
            }
        }
    }

    /**
     * Substring variant of {@link #findPathsToScalar}. Used when the
     * search ran in {@code mode=substring} — emits paths to leaves
     * whose canonical string form CONTAINS the (already lowercased)
     * needle, case-insensitive. Slower than the indexed exact path
     * by definition; offered as a fallback when the bloom-filter
     * exact match returns nothing useful (e.g. {@code "Tolkien"}
     * versus {@code "J.R.R. Tolkien"}).
     */
    private static void findPathsContainingScalar(
            JsonNode node, String needleLower,
            ArrayDeque<Object> path, List<List<Object>> out) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            JsonNode cycleRef = node.get("cycle_ref");
            if (cycleRef != null && cycleRef.isBoolean() && cycleRef.asBoolean()) return;
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                if ("__meta__".equals(e.getKey())) continue;
                path.addLast(e.getKey());
                findPathsContainingScalar(e.getValue(), needleLower, path, out);
                path.removeLast();
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                path.addLast(i);
                findPathsContainingScalar(node.get(i), needleLower, path, out);
                path.removeLast();
            }
        } else {
            String canonical;
            if (node.isTextual()) canonical = node.asText();
            else if (node.isNumber()) canonical = node.numberValue().toString();
            else if (node.isBoolean()) canonical = Boolean.toString(node.asBoolean());
            else return;
            if (canonical.toLowerCase(java.util.Locale.ROOT).contains(needleLower)) {
                out.add(new ArrayList<>(path));
            }
        }
    }

    private static String textOrNull(JsonNode n) {
        return (n == null || n.isMissingNode() || n.isNull()) ? null : n.asText();
    }

    /**
     * Coerces the ClickHouse client's array-of-something into a typed
     * {@code List<Long>} so Jackson serialises it as JSON numbers
     * rather than strings. The CH client renders {@code Array(Int64)}
     * as a list of String to preserve precision; the UI's Sets compare
     * with numeric envelope ids, so we round-trip through {@code Long}.
     */
    private static List<Long> toLongList(Object raw) {
        if (raw == null) return List.of();
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) raw;
        List<Long> out = new ArrayList<>(list.size());
        for (Object o : list) out.add(Long.parseLong(String.valueOf(o)));
        return out;
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
