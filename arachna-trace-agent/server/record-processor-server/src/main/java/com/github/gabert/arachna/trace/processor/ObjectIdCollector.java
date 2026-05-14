package com.github.gabert.arachna.trace.processor;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks a {@link com.github.gabert.arachna.trace.codec.Hasher}-produced JSON tree
 * and collects, for every envelope
 * encountered, its {@code __meta__.id} and (when present) its
 * {@code __meta__.own_hash}.
 *
 * <p>Used to populate the {@code object_ids} and {@code own_hashes} columns in
 * the payloads table for fast object-identity search and per-object
 * mutation queries.</p>
 *
 * <p>Duplicate ids in different positions are deduplicated; first-occurrence
 * order is preserved. The two output arrays are always length-aligned —
 * index {@code i} of {@code own_hashes} is the own hash of {@code object_ids[i]}
 * at its first appearance.</p>
 */
public final class ObjectIdCollector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectIdCollector() {}

    /** Paired, length-aligned result. */
    public record Result(List<Long> ids, List<String> ownHashes) {
        public static Result empty() {
            return new Result(List.of(), List.of());
        }
    }

    /**
     * Collect both ids and own_hashes in a single walk.
     *
     * <p>Returned lists are length-aligned: {@code ids.get(i)} corresponds to
     * {@code ownHashes.get(i)}. Own-hash slots are empty strings if a payload
     * predates the own_hash enrichment (graceful for stored data written
     * before the column existed).</p>
     */
    public static Result collectBoth(String hashedJson) throws IOException {
        Map<Long, String> seen = new LinkedHashMap<>();
        walk(MAPPER.readValue(hashedJson, Object.class), seen);
        List<Long> ids = new ArrayList<>(seen.size());
        List<String> ownHashes = new ArrayList<>(seen.size());
        for (Map.Entry<Long, String> e : seen.entrySet()) {
            ids.add(e.getKey());
            ownHashes.add(e.getValue());
        }
        return new Result(ids, ownHashes);
    }

    /**
     * Backwards-compatible convenience: just the id set, in first-occurrence
     * order. Existing callers (e.g. tests) keep working unchanged.
     */
    public static Set<Long> collect(String hashedJson) throws IOException {
        return new java.util.LinkedHashSet<>(collectBoth(hashedJson).ids());
    }

    private static void walk(Object node, Map<Long, String> seen) {
        if (node instanceof Map<?, ?> map) {
            Object meta = map.get("__meta__");
            if (meta instanceof Map<?, ?> metaMap) {
                Object id = metaMap.get("id");
                if (id instanceof Number n) {
                    Long idLong = n.longValue();
                    if (!seen.containsKey(idLong)) {
                        Object oh = metaMap.get("own_hash");
                        seen.put(idLong, oh instanceof String s ? s : "");
                    }
                }
            }
            for (Object value : map.values()) {
                walk(value, seen);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                walk(item, seen);
            }
        }
    }
}
