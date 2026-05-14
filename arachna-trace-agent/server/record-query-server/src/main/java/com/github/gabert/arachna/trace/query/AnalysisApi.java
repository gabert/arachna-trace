package com.github.gabert.arachna.trace.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;

/**
 * Cross-call analysis endpoints. Today: within-request mutation detection
 * (AR vs AX own_hash diff) and scalar value-search across a session or
 * single request. Both walk the hashed payload JSON server-side so the
 * client renders without re-walking already-loaded data.
 */
class AnalysisApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ClickHouseClient ch;

    AnalysisApi(ClickHouseClient ch) {
        this.ch = ch;
    }

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
    Map<String, Object> mutations(Map<String, List<String>> params) throws Exception {
        String sessionId = Params.required(params, "session_id");
        // request_id is optional now: omit it for session-wide mutation
        // detection. Tools fan out across the whole session by default;
        // the caller can still scope per-request when needed.
        String requestIdStr = Params.singleParam(params, "request_id");
        Long requestId = (requestIdStr == null || requestIdStr.isBlank())
                ? null
                : Long.parseLong(requestIdStr);
        String requestFilter = (requestId == null)
                ? ""
                : " AND request_id = {request_id:UInt64}";

        String sql = """
                SELECT
                    ar.call_id      AS call_id,
                    ar.request_id   AS request_id,
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
                    (SELECT call_id, request_id, signature, ts_in,
                            object_ids, own_hashes, payload_json
                     FROM payloads
                     WHERE session_id = {session_id:String}
                       %s
                       AND kind = 'AR') ar
                INNER JOIN
                    (SELECT call_id, object_ids, own_hashes, payload_json
                     FROM payloads
                     WHERE session_id = {session_id:String}
                       %s
                       AND kind = 'AX') ax
                ON ar.call_id = ax.call_id
                WHERE length(mutated_ids) > 0 OR length(added_ids) > 0
                ORDER BY ar.ts_in
                """.formatted(requestFilter, requestFilter);

        Map<String, String> bind = new LinkedHashMap<>();
        bind.put("session_id", sessionId);
        if (requestId != null) bind.put("request_id", String.valueOf(requestId));
        List<Map<String, Object>> sqlRows = ch.query(sql, bind);

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
                    group.put("request_id", r.get("request_id"));
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

        // Map.of refuses null values; fall back to a LinkedHashMap so
        // the summary can carry a null request_id (session-wide query).
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_mutations", totalMutations);
        summary.put("total_groups", groups.size());
        summary.put("session_id", sessionId);
        summary.put("request_id", requestId);
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
    List<Map<String, Object>> valueSearch(Map<String, List<String>> params) throws Exception {
        String sessionId = Params.required(params, "session_id");
        String value = Params.required(params, "value");
        String requestIdStr = Params.singleParam(params, "request_id");
        String mode = Params.singleParam(params, "mode");
        boolean substring = "substring".equalsIgnoreCase(mode);

        StringBuilder where = new StringBuilder("WHERE session_id = {session_id:String}");
        Map<String, String> bind = new LinkedHashMap<>();
        bind.put("session_id", sessionId);
        if (requestIdStr != null) {
            long rid = Long.parseLong(requestIdStr);
            where.append(" AND request_id = {request_id:UInt64}");
            bind.put("request_id", String.valueOf(rid));
        }
        if (substring) {
            // Full-scan substring filter. positionCaseInsensitive returns
            // 1-based offset, 0 if not found. No bloom-filter help — this
            // mode is the deliberate slow path for "Tolkien matches J.R.R.
            // Tolkien" cases the exact-match index can't catch.
            where.append(" AND positionCaseInsensitive(payload_json, {needle:String}) > 0");
        } else {
            // Bloom-filter probe over payload_tokens — fast at session
            // / cross-session scale. Exact value equality only.
            where.append(" AND has(payload_tokens, {needle:String})");
        }
        bind.put("needle", value);

        String sql = """
                SELECT call_id, kind, signature, ts_in, request_id, payload_json
                FROM payloads
                """ + where + """

                ORDER BY ts_in
                LIMIT 5000
                """;
        List<Map<String, Object>> rows = ch.query(sql, bind);

        String needleLower = substring ? value.toLowerCase(Locale.ROOT) : null;
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
            if (canonical.toLowerCase(Locale.ROOT).contains(needleLower)) {
                out.add(new ArrayList<>(path));
            }
        }
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
}
