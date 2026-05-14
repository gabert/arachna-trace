package com.github.gabert.arachna.trace.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds Merkle-style content hashes to a humanized object tree produced by
 * {@link Codec#toReadableJson(Object)}.
 *
 * <p>Each envelope node — a map carrying both {@code object_id} and
 * {@code class} keys — is rewritten to:</p>
 *
 * <pre>{ "__meta__": { "id": &lt;object_id&gt;, "class": "&lt;class&gt;", "hash": "&lt;md5&gt;", "own_hash": "&lt;md5&gt;" }, ...userFields }</pre>
 *
 * <p>Two hashes are emitted per envelope, answering different questions:</p>
 *
 * <ul>
 *   <li><b>{@code hash} — deep / Merkle.</b> MD5 of a canonical (sorted-keys)
 *       JSON serialization of the envelope's user fields, where each child
 *       envelope is replaced by its own deep hash string. Changes anywhere
 *       in the subtree change this hash. Right shape for tree drilling
 *       ("something changed below — walk down to find it").</li>
 *   <li><b>{@code own_hash} — own state.</b> MD5 of the same canonical form,
 *       except each child envelope is replaced by {@code {"__ref__": &lt;id&gt;}}.
 *       Changes only when this object's own scalar fields change, or when
 *       its set / order of child references changes. Right shape for
 *       per-row mutation detection ("did THIS object's own state move
 *       between two appearances"). Class label is not included — matches
 *       the UI's {@code WatchPanel} own-state collapse rule.</li>
 * </ul>
 *
 * <p>List ordering is preserved (not sorted): list order is data, and sorting
 * would hide real mutations of ordered collections.</p>
 *
 * <p>Cycle references (maps with {@code ref_id}) pass through unchanged for
 * the deep walk and collapse to {@code {"__ref__": &lt;ref_id&gt;}} for the own
 * walk — same shape as a non-root envelope, so own_hash is invariant under
 * cycle-entry direction.</p>
 */
public final class Hasher {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Hasher() {}

    /**
     * Pair of "transformed JSON (with {@code __meta__} envelopes)" and a
     * "root hash" — a single MD5 that captures the entire payload's content,
     * regardless of whether the root is an envelope, an array, a plain map,
     * or a scalar.
     *
     * <p>For an envelope root, {@code rootHash} is the envelope's own
     * {@code __meta__.hash}. For an array root, it is computed over the
     * canonical form of the array where each envelope element is replaced by
     * its own hash — so any change in any element propagates up. Scalars and
     * plain maps are hashed similarly over their canonical form.</p>
     */
    public record HashResult(String hashedJson, String rootHash) {}

    public static HashResult hashWithRoot(String json) throws IOException {
        Object parsed = MAPPER.readValue(json, Object.class);
        Walked w = walk(parsed);
        String hashedJson = MAPPER.writeValueAsString(w.transformed);
        String rootHash = isEnvelope(parsed)
                ? (String) w.hashInput
                : md5(MAPPER.writeValueAsString(w.hashInput));
        return new HashResult(hashedJson, rootHash);
    }

    public static String hash(String json) throws IOException {
        return hashWithRoot(json).hashedJson();
    }

    public static Object hash(Object humanized) throws IOException {
        return walk(humanized).transformed;
    }

    /**
     * Compute a Merkle root hash for an already-hashed payload (i.e. the
     * output of {@link #hash(String)}, which has {@code __meta__} embedded
     * on every envelope). This is the same value that {@link #hashWithRoot}
     * would have produced for the original input.
     *
     * <p>Lets the sink derive a root hash for payloads whose top level is an
     * array or plain map — cases where there is no {@code __meta__.hash}
     * sitting at the root to read directly.</p>
     */
    public static String extractRootHashFromHashed(String hashedJson) throws IOException {
        Object parsed = MAPPER.readValue(hashedJson, Object.class);
        if (parsed instanceof Map<?, ?> map) {
            Object meta = map.get("__meta__");
            // Trust only the hasher's own meta blocks: those always carry
            // BOTH "hash" and "own_hash". A user-supplied {"__meta__":
            // {"hash": "..."}} at the top of a plain map must NOT be
            // interpreted as a pre-computed root hash — fall back to
            // recomputing in that case.
            if (meta instanceof Map<?, ?> m
                    && m.get("hash") instanceof String s
                    && m.get("own_hash") instanceof String) {
                return s;
            }
        }
        return md5(MAPPER.writeValueAsString(toHashInput(parsed)));
    }

    private static Object toHashInput(Object node) {
        if (node instanceof Map<?, ?> map) {
            Object meta = map.get("__meta__");
            // Same trust rule as extractRootHashFromHashed: only collapse to
            // the hash string when both "hash" and "own_hash" are present —
            // the joint signature of a hasher-emitted meta block.
            if (meta instanceof Map<?, ?> m
                    && m.get("hash") instanceof String h
                    && m.get("own_hash") instanceof String) {
                return h;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), toHashInput(e.getValue()));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(toHashInput(item));
            return out;
        }
        return node;
    }

    private static boolean isEnvelope(Object node) {
        return node instanceof Map<?, ?> map
                && map.containsKey("object_id")
                && map.containsKey("class");
    }

    private static Walked walk(Object node) throws IOException {
        if (node instanceof Map<?, ?> map) return walkMap(map);
        if (node instanceof List<?> list) return walkList(list);
        return new Walked(node, node);
    }

    private static Walked walkMap(Map<?, ?> map) throws IOException {
        if (map.containsKey("ref_id")) {
            return new Walked(map, map);
        }

        boolean isEnvelope = map.containsKey("object_id") && map.containsKey("class");

        Map<String, Object> transformed = new LinkedHashMap<>();
        Map<String, Object> hashInput = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (isEnvelope && (key.equals("object_id") || key.equals("class"))) {
                continue;
            }
            // "__meta__" is reserved: the hasher writes its own meta block on
            // every envelope. A user field with that name would silently
            // overwrite (or be overwritten by) the hasher's block depending
            // on insertion order. Dropping it on the way in makes the contract
            // unambiguous and the hash invariant under its presence.
            if (key.equals("__meta__")) {
                continue;
            }
            Walked child = walk(entry.getValue());
            transformed.put(key, child.transformed);
            hashInput.put(key, child.hashInput);
        }

        if (!isEnvelope) {
            return new Walked(transformed, hashInput);
        }

        String hashHex = md5(MAPPER.writeValueAsString(hashInput));
        // Own hash: same canonical form, but children collapsed to id-refs
        // instead of hashes. Computed from the original node so we don't
        // see the freshly-written __meta__ blocks of children.
        String ownHashHex = md5(MAPPER.writeValueAsString(ownHashInput(map, true)));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", map.get("object_id"));
        meta.put("class", map.get("class"));
        meta.put("hash", hashHex);
        meta.put("own_hash", ownHashHex);

        Map<String, Object> finalNode = new LinkedHashMap<>();
        finalNode.put("__meta__", meta);
        finalNode.putAll(transformed);
        return new Walked(finalNode, hashHex);
    }

    /**
     * Build the input for own-state hashing of an envelope (or a non-envelope
     * sub-tree of an envelope).
     *
     * <p>At the root of an envelope's own-hash walk ({@code atRoot=true}),
     * {@code object_id} and {@code class} are excluded — the hash is over
     * the user fields only — so two same-class instances with identical
     * scalar fields collide on own_hash. That collision is intentional:
     * own_hash answers "did this object's own data move", and id is the
     * only stable identity at envelope boundaries.</p>
     */
    private static Object ownHashInput(Object node, boolean atRoot) {
        if (node instanceof Map<?, ?> map) {
            // Cycle ref → {__ref__: id}. Same shape as a non-root envelope
            // collapse, so own_hash doesn't move under cycle-direction flips.
            if (map.containsKey("ref_id")) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("__ref__", map.get("ref_id"));
                return ref;
            }
            boolean isEnvelope = map.containsKey("object_id") && map.containsKey("class");
            if (isEnvelope && !atRoot) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("__ref__", map.get("object_id"));
                return ref;
            }
            // Root envelope OR plain (non-envelope) map. Walk all fields,
            // dropping the envelope identity keys at the root. Also drop
            // user fields literally named "__meta__" — they are reserved
            // for the hasher's output and excluded from the hash input by
            // walkMap; mirror the same rule here so own_hash is invariant
            // under their presence.
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (isEnvelope && (key.equals("object_id") || key.equals("class"))) continue;
                if (key.equals("__meta__")) continue;
                out.put(key, ownHashInput(entry.getValue(), false));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(ownHashInput(item, false));
            return out;
        }
        return node;
    }

    private static Walked walkList(List<?> list) throws IOException {
        List<Object> transformed = new ArrayList<>(list.size());
        List<Object> hashInput = new ArrayList<>(list.size());
        for (Object item : list) {
            Walked child = walk(item);
            transformed.add(child.transformed);
            hashInput.add(child.hashInput);
        }
        return new Walked(transformed, hashInput);
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    private record Walked(Object transformed, Object hashInput) {}
}
