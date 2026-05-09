package com.github.gabert.deepflow.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only endpoints rooted at sessions: listing, per-session breakdowns
 * (threads, requests, calltree, size), and per-call / per-request payload
 * fetches. SQL stays here; routing is in {@link QueryHandler}.
 */
class SessionsApi {

    private final ClickHouseClient ch;

    SessionsApi(ClickHouseClient ch) {
        this.ch = ch;
    }

    List<Map<String, Object>> listSessions() throws Exception {
        // ReplacingMergeTree dedup via FINAL; cheap because the table is small.
        return ch.query("""
                SELECT session_id, agent_run_id, first_seen, last_seen, retain
                FROM sessions FINAL
                ORDER BY last_seen DESC
                LIMIT 200
                """);
    }

    /**
     * List the requests in a session for the UI's "session overview"
     * pane. Reads from the {@code requests_view} materialized rollup
     * (call_count / exception_count / time range / thread / signature
     * already aggregated per request) instead of grouping the raw
     * calls table on every page-load — see the schema's rationale
     * comment on the rollup table.
     *
     * <p>Field names kept aligned with what the UI's
     * {@code RequestRow} type expects: {@code first_call},
     * {@code last_call}, {@code span_ms}.</p>
     */
    List<Map<String, Object>> listRequests(String sessionId) throws Exception {
        return ch.query("""
                SELECT request_id,
                       thread_name,
                       call_count,
                       exception_count,
                       started_at  AS first_call,
                       ended_at    AS last_call,
                       duration_ms AS span_ms
                FROM requests_view
                WHERE session_id = {session_id:String}
                ORDER BY started_at
                """, Map.of("session_id", sessionId));
    }

    /**
     * For a given (session, object_id), return the distinct (call_id,
     * request_id) pairs whose payloads mention that object id. Powers
     * the instance-trace inverted index in the UI: the call_id selects
     * the row to highlight, the request_id tells the lazy loader which
     * request's call tree to fetch first if not already cached.
     *
     * <p>Path: {@code GET /api/sessions/{id}/object-trace?object_id=N}
     * <br>Response: {@code [{ "call_id": "...", "request_id": N }]}</p>
     */
    List<Map<String, Object>> objectTrace(String sessionId, Map<String, List<String>> params) throws Exception {
        long objectId = Long.parseLong(Params.required(params, "object_id"));
        return ch.query("""
                SELECT DISTINCT call_id, request_id
                FROM payloads
                WHERE session_id = {session_id:String}
                  AND has(object_ids, {object_id:Int64})
                """, Map.of(
                    "session_id", sessionId,
                    "object_id", String.valueOf(objectId)
                ));
    }

    /**
     * Ordered list of every call in the session that ended with an
     * exception. The agent flags each call's {@code return_type}; this
     * endpoint surfaces the exception-bearing rows with enough metadata
     * for the UI's exception nav to cycle through them and lazy-load
     * the containing request when the user steps into one.
     *
     * <p>Walks {@code calls} table directly because the rollup only
     * carries per-request counts, not per-call ids. Indexed by the
     * primary key on session_id; materialized {@code is_exception}
     * column is the filter predicate.</p>
     *
     * <p>Path: {@code GET /api/sessions/{id}/exception-calls}
     * <br>Response: {@code [{ call_id, request_id, signature, ts_in,
     * thread_name }]} sorted by {@code (request_id, seq, ts_in)} —
     * matches DFS pre-order within a request and chronological across
     * requests.</p>
     */
    List<Map<String, Object>> exceptionCalls(String sessionId) throws Exception {
        return ch.query("""
                SELECT call_id, request_id, signature, ts_in, thread_name
                FROM calls
                WHERE session_id = {session_id:String}
                  AND is_exception
                ORDER BY request_id, seq, ts_in
                """, Map.of("session_id", sessionId));
    }

    /**
     * Session-wide payloads that mention a given object_id, with their
     * full payload_json. Used by tools that need to walk the actual
     * envelope snapshots (Watch panel's appearance rows, Origin
     * panel's next-mutation lookup) without forcing every session
     * payload into the client cache. Bloom-filter indexed; bounded
     * to entries that actually mention the object.
     *
     * <p>Path: {@code GET /api/sessions/{id}/object-payloads?object_id=N}
     * <br>Response: same shape as {@link #requestPayloads}, with
     * {@code call_id, kind, payload_json, payload_size, root_hash,
     * object_ids, ts_in, signature, seq, request_id} sorted causally
     * by {@code (seq, kind, ts_in)}.</p>
     */
    List<Map<String, Object>> objectPayloads(String sessionId, Map<String, List<String>> params) throws Exception {
        long objectId = Long.parseLong(Params.required(params, "object_id"));
        return ch.query("""
                SELECT call_id, kind, payload_json, payload_size, root_hash,
                       object_ids, ts_in, signature, seq, request_id
                FROM payloads
                WHERE session_id = {session_id:String}
                  AND has(object_ids, {object_id:Int64})
                ORDER BY seq, kind, ts_in
                LIMIT 5000
                """, Map.of(
                    "session_id", sessionId,
                    "object_id", String.valueOf(objectId)
                ));
    }

    List<Map<String, Object>> listThreads(String sessionId) throws Exception {
        return ch.query("""
                SELECT thread_name,
                       count() AS call_count,
                       min(ts_in) AS first_call,
                       max(ts_out) AS last_call
                FROM calls
                WHERE session_id = {session_id:String}
                GROUP BY thread_name
                ORDER BY first_call
                """, Map.of("session_id", sessionId));
    }

    List<Map<String, Object>> callTree(String sessionId, Map<String, List<String>> params) throws Exception {
        String thread = Params.singleParam(params, "thread");
        String requestIdStr = Params.singleParam(params, "request_id");

        StringBuilder where = new StringBuilder("WHERE session_id = {session_id:String}");
        Map<String, String> bind = new LinkedHashMap<>();
        bind.put("session_id", sessionId);
        if (thread != null) {
            where.append(" AND thread_name = {thread:String}");
            bind.put("thread", thread);
        }
        if (requestIdStr != null) {
            long rid = Long.parseLong(requestIdStr);
            where.append(" AND request_id = {request_id:UInt64}");
            bind.put("request_id", String.valueOf(rid));
        }

        return ch.query("""
                SELECT call_id, parent_call_id, request_id, thread_name,
                       ts_in, ts_out, duration_ms, signature, return_type,
                       is_exception, this_id, seq
                FROM calls
                """ + where + """

                ORDER BY seq, ts_in
                LIMIT 5000
                """, bind);
    }

    List<Map<String, Object>> callPayloads(String callId) throws Exception {
        java.util.UUID.fromString(callId);
        return ch.query("""
                SELECT kind, payload_json, payload_size, root_hash, object_ids, ts_in, signature
                FROM payloads
                WHERE call_id = {call_id:UUID}
                ORDER BY kind
                """, Map.of("call_id", callId));
    }

    List<Map<String, Object>> requestPayloads(String sessionId, String requestId) throws Exception {
        long rid = Long.parseLong(requestId);
        return ch.query("""
                SELECT call_id, kind, payload_json, payload_size, root_hash, object_ids, ts_in, signature, seq
                FROM payloads
                WHERE session_id = {session_id:String} AND request_id = {request_id:UInt64}
                ORDER BY seq, kind, ts_in
                LIMIT 10000
                """, Map.of("session_id", sessionId, "request_id", String.valueOf(rid)));
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
    Map<String, Object> sessionSize(String sessionId) throws Exception {
        Map<String, String> bind = Map.of("session_id", sessionId);
        List<Map<String, Object>> payloadAgg = ch.query("""
                SELECT count() AS payload_rows,
                       sum(payload_size) AS payload_bytes
                FROM payloads
                WHERE session_id = {session_id:String}
                """, bind);
        List<Map<String, Object>> callAgg = ch.query("""
                SELECT count() AS call_rows
                FROM calls
                WHERE session_id = {session_id:String}
                """, bind);
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
}
