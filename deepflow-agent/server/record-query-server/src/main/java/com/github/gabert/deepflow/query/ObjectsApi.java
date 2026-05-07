package com.github.gabert.deepflow.query;

import java.util.List;
import java.util.Map;

/**
 * Endpoints rooted at object identity. Today: per-object history (every
 * payload that mentions a given envelope id). Likely growth area as the
 * provenance / Part C work lands.
 */
class ObjectsApi {

    private final ClickHouseClient ch;

    ObjectsApi(ClickHouseClient ch) {
        this.ch = ch;
    }

    List<Map<String, Object>> objectHistory(String objectId) throws Exception {
        long parsed = Long.parseLong(objectId);
        return ch.query("""
                SELECT call_id, session_id, request_id, ts_in, signature, kind, root_hash, payload_json
                FROM payloads
                WHERE has(object_ids, {object_id:Int64})
                ORDER BY ts_in
                LIMIT 1000
                """, Map.of("object_id", String.valueOf(parsed)));
    }
}
