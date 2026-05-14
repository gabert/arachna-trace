package com.github.gabert.arachna.trace.processor;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks a {@link com.github.gabert.arachna.trace.codec.Hasher}-produced JSON tree
 * and collects every distinct scalar leaf value (string, number, boolean) it
 * encounters, canonicalized to a {@link String}.
 *
 * <p>The result populates the {@code payload_tokens} column on the
 * {@code payloads} table; together with the bloom-filter skip-index over
 * that column, it lets {@code WHERE has(payload_tokens, V)} run as an
 * indexed probe instead of {@code payload_json LIKE '%V%'} doing a JSON
 * substring scan over every row in scope. That's the substrate value-search
 * and provenance lookups need to scale beyond a single request.</p>
 *
 * <p><b>What is and isn't a token:</b>
 * <ul>
 *   <li>Strings, numbers and booleans at any depth become tokens.</li>
 *   <li>Numbers and booleans are canonicalized via their JSON string form
 *       (e.g. {@code 1937 → "1937"}, {@code true → "true"}). This matches
 *       the canonicalization used UI-side for value matching.</li>
 *   <li>Inside an envelope's {@code __meta__} block — id / class / hash /
 *       own_hash — values are skipped. They are envelope identity, not user
 *       data; indexing them would pollute the term space.</li>
 *   <li>Cycle refs ({@code {"cycle_ref": true, "ref_id": N}}) are skipped
 *       entirely — the {@code ref_id} is a graph pointer, not a value to
 *       search for.</li>
 *   <li>Plain Map keys are not tokens (they're field names, not values).</li>
 * </ul>
 *
 * <p>Duplicates within one payload tree are deduplicated — the bloom-filter
 * index cares about presence, not count.</p>
 *
 * <p><b>Lives in the processor module, not core/codec.</b> Server-only by
 * design: keeps the agent jar free of bytecode it would never invoke.</p>
 */
public final class ScalarTokenCollector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScalarTokenCollector() {}

    /**
     * Collects distinct scalar leaf values from a hashed payload JSON.
     * Returns first-seen order — order is not load-bearing for the
     * bloom-filter index but is stable for tests.
     */
    public static List<String> collect(String hashedJson) throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        walk(MAPPER.readValue(hashedJson, Object.class), seen);
        return new ArrayList<>(seen);
    }

    private static void walk(Object node, Set<String> seen) {
        if (node == null) return;
        if (node instanceof Map<?, ?> map) {
            // Cycle ref — graph pointer, not a value. Skip the entire node.
            Object cycleRef = map.get("cycle_ref");
            if (Boolean.TRUE.equals(cycleRef)) return;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                // Skip the __meta__ subtree entirely. Its scalars (id,
                // class, hash, own_hash) are envelope metadata, not user
                // data, and would otherwise dominate the token space.
                if ("__meta__".equals(e.getKey())) continue;
                walk(e.getValue(), seen);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) walk(item, seen);
        } else if (node instanceof String s) {
            seen.add(s);
        } else if (node instanceof Number n) {
            seen.add(n.toString());
        } else if (node instanceof Boolean b) {
            seen.add(b.toString());
        }
        // Other types (shouldn't occur in Jackson-default tree) are skipped.
    }
}
