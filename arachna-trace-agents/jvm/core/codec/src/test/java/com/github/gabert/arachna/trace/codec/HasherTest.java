package com.github.gabert.arachna.trace.codec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HasherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void simpleEnvelopeProducesMetaWithIdClassAndHash() throws IOException {
        String json = """
                {"object_id": 1, "class": "com.example.User", "name": "Alice", "age": 30}
                """;

        Map<String, Object> result = parseJson(Hasher.hash(json));

        assertTrue(result.containsKey("__meta__"));
        Map<?, ?> meta = (Map<?, ?>) result.get("__meta__");
        assertEquals(1, meta.get("id"));
        assertEquals("com.example.User", meta.get("class"));
        assertNotNull(meta.get("hash"));
        assertTrue(meta.get("hash").toString().matches("[0-9a-f]{32}"),
                "hash must be 32-char hex MD5");

        assertFalse(result.containsKey("object_id"));
        assertFalse(result.containsKey("class"));
        assertEquals("Alice", result.get("name"));
        assertEquals(30, result.get("age"));
    }

    @Test
    void hashIsIndependentOfInputKeyOrder() throws IOException {
        String a = """
                {"object_id": 1, "class": "C", "x": 1, "y": 2, "z": 3}
                """;
        String b = """
                {"z": 3, "object_id": 1, "y": 2, "class": "C", "x": 1}
                """;
        assertEquals(rootHash(a), rootHash(b));
    }

    @Test
    void hashIsStableAcrossInvocations() throws IOException {
        String json = """
                {"object_id": 1, "class": "P", "child": {"object_id": 2, "class": "C", "v": 1}}
                """;
        assertEquals(rootHash(json), rootHash(json));
    }

    @Test
    void deeplyNestedChangePropagatesToRootHash() throws IOException {
        String before = """
                {"object_id": 1, "class": "P",
                 "child": {"object_id": 2, "class": "C",
                           "grandchild": {"object_id": 3, "class": "GC", "v": "original"}}}
                """;
        String after = """
                {"object_id": 1, "class": "P",
                 "child": {"object_id": 2, "class": "C",
                           "grandchild": {"object_id": 3, "class": "GC", "v": "modified"}}}
                """;
        assertNotEquals(rootHash(before), rootHash(after));
    }

    @Test
    void siblingHashesAreIndependent() throws IOException {
        String json1 = """
                {"object_id": 1, "class": "P",
                 "a": {"object_id": 2, "class": "C", "v": "x"},
                 "b": {"object_id": 3, "class": "C", "v": "y"}}
                """;
        String json2 = """
                {"object_id": 1, "class": "P",
                 "a": {"object_id": 2, "class": "C", "v": "x"},
                 "b": {"object_id": 3, "class": "C", "v": "DIFFERENT"}}
                """;

        Map<String, Object> r1 = parseJson(Hasher.hash(json1));
        Map<String, Object> r2 = parseJson(Hasher.hash(json2));

        // Sibling 'a' is unchanged in both — its hash should be identical.
        assertEquals(childHash(r1, "a"), childHash(r2, "a"));
        // Root must differ because sibling 'b' changed.
        assertNotEquals(metaHash(r1), metaHash(r2));
    }

    @Test
    void identityChangeAloneDoesNotChangeContentHash() throws IOException {
        // Same data, different object_ids → CH must match (CH is data-only, identity is in __meta__.id).
        String j1 = """
                {"object_id": 1, "class": "C", "v": "x"}
                """;
        String j2 = """
                {"object_id": 999, "class": "C", "v": "x"}
                """;
        assertEquals(rootHash(j1), rootHash(j2));
    }

    @Test
    void listOrderAffectsHash() throws IOException {
        String original = """
                {"object_id": 1, "class": "P",
                 "items": [{"object_id": 2, "class": "I", "v": "a"},
                           {"object_id": 3, "class": "I", "v": "b"}]}
                """;
        String reversed = """
                {"object_id": 1, "class": "P",
                 "items": [{"object_id": 3, "class": "I", "v": "b"},
                           {"object_id": 2, "class": "I", "v": "a"}]}
                """;
        assertNotEquals(rootHash(original), rootHash(reversed));
    }

    @Test
    void envelopeWithItemsListAddsMetaToEachChild() throws IOException {
        String json = """
                {"object_id": 1, "class": "java.util.ArrayList",
                 "items": [{"object_id": 2, "class": "I", "v": "a"},
                           {"object_id": 3, "class": "I", "v": "b"}]}
                """;

        Map<String, Object> result = parseJson(Hasher.hash(json));

        assertTrue(result.containsKey("__meta__"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertEquals(2, items.size());
        assertEquals(2, ((Map<?, ?>) items.get(0).get("__meta__")).get("id"));
        assertEquals(3, ((Map<?, ?>) items.get(1).get("__meta__")).get("id"));
    }

    @Test
    void envelopeWithScalarValueIsHashed() throws IOException {
        String json = """
                {"object_id": 1, "class": "Proxy", "value": "<proxy>"}
                """;

        Map<String, Object> result = parseJson(Hasher.hash(json));

        assertTrue(result.containsKey("__meta__"));
        assertEquals("<proxy>", result.get("value"));
    }

    @Test
    void plainMapHasNoMeta() throws IOException {
        // 'config' is a Map<String, Object> field — humanized without envelope wrap.
        String json = """
                {"object_id": 1, "class": "Container",
                 "config": {"timeout": 30, "retries": 3}}
                """;

        Map<String, Object> result = parseJson(Hasher.hash(json));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) result.get("config");

        assertFalse(config.containsKey("__meta__"));
        assertEquals(30, config.get("timeout"));
        assertEquals(3, config.get("retries"));
    }

    @Test
    void cycleRefPassesThroughUnchanged() throws IOException {
        String json = """
                {"object_id": 1, "class": "P",
                 "self": {"ref_id": 1, "cycle_ref": true}}
                """;

        Map<String, Object> result = parseJson(Hasher.hash(json));
        @SuppressWarnings("unchecked")
        Map<String, Object> self = (Map<String, Object>) result.get("self");

        assertEquals(1, self.get("ref_id"));
        assertEquals(true, self.get("cycle_ref"));
        assertFalse(self.containsKey("__meta__"));
    }

    @Test
    void scalarTopLevelPassesThrough() throws IOException {
        assertEquals("\"hello\"", Hasher.hash("\"hello\""));
        assertEquals("42", Hasher.hash("42"));
        assertEquals("null", Hasher.hash("null"));
    }

    @Test
    void hashWithRootForEnvelopeReturnsTheEnvelopesHash() throws IOException {
        String json = """
                {"object_id": 1, "class": "User", "name": "Alice"}
                """;
        Hasher.HashResult r = Hasher.hashWithRoot(json);
        Map<String, Object> hashed = parseJson(r.hashedJson());
        // rootHash equals the root envelope's __meta__.hash
        assertEquals(metaHash(hashed), r.rootHash());
    }

    @Test
    void hashWithRootForArraySynthesizesContainerHash() throws IOException {
        // Top-level array of envelopes (typical AR shape, e.g. method arguments
        // serialized as an envelope-per-arg list). For non-envelope roots there
        // is no __meta__.hash on the root, so hashWithRoot synthesizes a root
        // hash over the canonical form of the array where each element is its
        // own deep hash. Shape check only here — equality against the
        // extract-side computation is covered by
        // extractRootHashFromHashedMatchesHashWithRoot below.
        String json = """
                [{"object_id": 1, "class": "X", "v": "a"},
                 {"object_id": 2, "class": "X", "v": "b"}]
                """;
        Hasher.HashResult r = Hasher.hashWithRoot(json);
        assertNotNull(r.rootHash());
        assertTrue(r.rootHash().matches("[0-9a-f]{32}"));
    }

    @Test
    void rootHashOfArrayChangesIfAnyElementChanges() throws IOException {
        String j1 = """
                [{"object_id": 1, "class": "X", "v": "a"},
                 {"object_id": 2, "class": "X", "v": "b"}]
                """;
        String j2 = """
                [{"object_id": 1, "class": "X", "v": "a"},
                 {"object_id": 2, "class": "X", "v": "DIFFERENT"}]
                """;
        assertNotEquals(
                Hasher.hashWithRoot(j1).rootHash(),
                Hasher.hashWithRoot(j2).rootHash());
    }

    @Test
    void rootHashOfArrayUnchangedIfElementsContentSameButIdsDiffer() throws IOException {
        // Same content, different object_ids → content hash matches.
        String j1 = """
                [{"object_id": 1, "class": "X", "v": "a"},
                 {"object_id": 2, "class": "X", "v": "b"}]
                """;
        String j2 = """
                [{"object_id": 99, "class": "X", "v": "a"},
                 {"object_id": 100, "class": "X", "v": "b"}]
                """;
        assertEquals(
                Hasher.hashWithRoot(j1).rootHash(),
                Hasher.hashWithRoot(j2).rootHash());
    }

    @Test
    void rootHashChangesIfArrayOrderChanges() throws IOException {
        String original = """
                [{"object_id": 1, "class": "X", "v": "a"},
                 {"object_id": 2, "class": "X", "v": "b"}]
                """;
        String reversed = """
                [{"object_id": 1, "class": "X", "v": "b"},
                 {"object_id": 2, "class": "X", "v": "a"}]
                """;
        assertNotEquals(
                Hasher.hashWithRoot(original).rootHash(),
                Hasher.hashWithRoot(reversed).rootHash());
    }

    @Test
    void extractRootHashFromHashedMatchesHashWithRoot() throws IOException {
        // The two paths must agree: "hash and read root in one go"
        // (agent side, single call) and "hash, then extract root from the
        // hashed JSON" (sink side, when the root hash needs to be derived
        // again from stored JSON). Disagreement here would break
        // root_hash-based queries in ClickHouse because the value the
        // processor inserts would not match the value the agent would
        // have computed.
        String[] inputs = {
                "{\"object_id\": 1, \"class\": \"X\", \"v\": 1}",
                "[{\"object_id\": 1, \"class\": \"X\"}, {\"object_id\": 2, \"class\": \"X\"}]",
                "[\"a\", \"b\", \"c\"]",
                "{\"plain\": \"map\", \"no\": \"envelope\"}",
                "\"scalar\"",
                "42"
        };
        for (String in : inputs) {
            Hasher.HashResult r = Hasher.hashWithRoot(in);
            assertEquals(r.rootHash(), Hasher.extractRootHashFromHashed(r.hashedJson()),
                    "mismatch for: " + in);
        }
    }

    @Test
    void emptyEnvelopeStillGetsMeta() throws IOException {
        String json = """
                {"object_id": 7, "class": "Empty"}
                """;

        Map<String, Object> result = parseJson(Hasher.hash(json));

        assertTrue(result.containsKey("__meta__"));
        Map<?, ?> meta = (Map<?, ?>) result.get("__meta__");
        assertEquals(7, meta.get("id"));
        assertEquals("Empty", meta.get("class"));
        assertNotNull(meta.get("hash"));
        // Only __meta__, no other fields.
        assertEquals(1, result.size());
    }

    // --- helpers ---

    private static Map<String, Object> parseJson(String json) throws IOException {
        return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    private static String rootHash(String inputJson) throws IOException {
        return metaHash(parseJson(Hasher.hash(inputJson)));
    }

    private static String metaHash(Map<String, Object> hashed) {
        return (String) ((Map<?, ?>) hashed.get("__meta__")).get("hash");
    }

    private static String childHash(Map<String, Object> hashed, String childKey) {
        @SuppressWarnings("unchecked")
        Map<String, Object> child = (Map<String, Object>) hashed.get(childKey);
        return metaHash(child);
    }

    private static String metaOwnHash(Map<String, Object> hashed) {
        return (String) ((Map<?, ?>) hashed.get("__meta__")).get("own_hash");
    }

    private static String childOwnHash(Map<String, Object> hashed, String childKey) {
        @SuppressWarnings("unchecked")
        Map<String, Object> child = (Map<String, Object>) hashed.get(childKey);
        return metaOwnHash(child);
    }

    private static String rootOwnHash(String inputJson) throws IOException {
        return metaOwnHash(parseJson(Hasher.hash(inputJson)));
    }

    // --- own_hash tests ---------------------------------------------------

    @Test
    void everyEnvelopeGetsAnOwnHashAlongsideHash() throws IOException {
        String json = """
                {"object_id": 1, "class": "U", "name": "Alice"}
                """;
        Map<String, Object> result = parseJson(Hasher.hash(json));
        Map<?, ?> meta = (Map<?, ?>) result.get("__meta__");
        assertNotNull(meta.get("own_hash"));
        assertTrue(meta.get("own_hash").toString().matches("[0-9a-f]{32}"),
                "own_hash must be 32-char hex MD5");
        // own_hash and hash are independent values for the same envelope.
        // For a leaf they happen to be derivable from one another, but the
        // raw bytes need not match — assert they're both populated, not
        // equality.
        assertNotNull(meta.get("hash"));
    }

    @Test
    void ownHashChangesWhenScalarFieldMutates() throws IOException {
        String before = """
                {"object_id": 1, "class": "Author", "name": "Tolkien"}
                """;
        String after = """
                {"object_id": 1, "class": "Author", "name": "Lewis"}
                """;
        assertNotEquals(rootOwnHash(before), rootOwnHash(after));
    }

    @Test
    void parentOwnHashUnchangedWhenChildContentMutates() throws IOException {
        // The headline invariant for own_hash: parent's own scalars and child
        // id-refs are unchanged, so parent's own_hash must NOT move — even
        // though the child's own state did move (and its own_hash does).
        // The deep-hash propagation that runs in parallel is documented by
        // deeplyNestedChangePropagatesToRootHash; this test focuses on the
        // own/deep decoupling.
        String before = """
                {"object_id": 1, "class": "Author", "name": "Tolkien",
                 "books": [{"object_id": 2, "class": "Book", "isbn": "abc"}]}
                """;
        String after = """
                {"object_id": 1, "class": "Author", "name": "Tolkien",
                 "books": [{"object_id": 2, "class": "Book", "isbn": "DIFFERENT"}]}
                """;

        Map<String, Object> r1 = parseJson(Hasher.hash(before));
        Map<String, Object> r2 = parseJson(Hasher.hash(after));

        // Own hash on the parent (Author) MUST NOT move — its own scalars
        // and child id-refs are identical.
        assertEquals(metaOwnHash(r1), metaOwnHash(r2));

        // The child's (Book) own hash MUST move.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> books1 = (List<Map<String, Object>>) r1.get("books");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> books2 = (List<Map<String, Object>>) r2.get("books");
        assertNotEquals(metaOwnHash(books1.get(0)), metaOwnHash(books2.get(0)));
    }

    @Test
    void parentOwnHashChangesWhenChildReferenceChanges() throws IOException {
        // Same parent scalars, but the referenced child id changes →
        // parent own_hash should move (the reference graph changed).
        String before = """
                {"object_id": 1, "class": "Author", "name": "Tolkien",
                 "books": [{"object_id": 2, "class": "Book", "isbn": "abc"}]}
                """;
        String after = """
                {"object_id": 1, "class": "Author", "name": "Tolkien",
                 "books": [{"object_id": 7, "class": "Book", "isbn": "abc"}]}
                """;
        assertNotEquals(rootOwnHash(before), rootOwnHash(after));
    }

    @Test
    void parentOwnHashChangesWhenChildListReorders() throws IOException {
        String original = """
                {"object_id": 1, "class": "P",
                 "books": [{"object_id": 2, "class": "B", "v": "x"},
                           {"object_id": 3, "class": "B", "v": "y"}]}
                """;
        String reversed = """
                {"object_id": 1, "class": "P",
                 "books": [{"object_id": 3, "class": "B", "v": "y"},
                           {"object_id": 2, "class": "B", "v": "x"}]}
                """;
        assertNotEquals(rootOwnHash(original), rootOwnHash(reversed));
    }

    @Test
    void ownHashIgnoresClassLabel() throws IOException {
        // Two different classes, same scalars and child shape — own_hash
        // matches. Documents the deliberate "class is not in own-hash input"
        // decision (mirrors the UI's WatchPanel collapse rule).
        String a = """
                {"object_id": 1, "class": "ClassA", "v": 1}
                """;
        String b = """
                {"object_id": 1, "class": "ClassB", "v": 1}
                """;
        assertEquals(rootOwnHash(a), rootOwnHash(b));
    }

    @Test
    void ownHashCollapsesCycleRefToChildIdRef() throws IOException {
        // {ref_id: 1, cycle_ref: true} should collapse to {__ref__: 1}.
        // Outer scalars unchanged → outer own_hash same as if a real
        // envelope #1 were referenced there.
        String cyclic = """
                {"object_id": 1, "class": "Node", "v": "x",
                 "self": {"ref_id": 1, "cycle_ref": true}}
                """;
        String idRef = """
                {"object_id": 1, "class": "Node", "v": "x",
                 "self": {"object_id": 1, "class": "Node", "v": "x"}}
                """;
        // The non-cyclic version embeds the same id but as a non-root
        // envelope; ownHashInput collapses it to {__ref__: 1}, matching
        // the cycle-ref shape.
        assertEquals(rootOwnHash(cyclic), rootOwnHash(idRef));
    }

    @Test
    void ownHashIsStableAcrossInvocations() throws IOException {
        String json = """
                {"object_id": 1, "class": "P", "name": "x",
                 "child": {"object_id": 2, "class": "C", "v": 1}}
                """;
        assertEquals(rootOwnHash(json), rootOwnHash(json));
    }

    @Test
    void ownHashIndependentOfInputKeyOrder() throws IOException {
        String a = """
                {"object_id": 1, "class": "P", "x": 1, "y": 2, "z": 3}
                """;
        String b = """
                {"z": 3, "object_id": 1, "y": 2, "class": "P", "x": 1}
                """;
        assertEquals(rootOwnHash(a), rootOwnHash(b));
    }

    // --- defensive / edge-case tests ----------------------------------------

    @Test
    void userFieldNamedMetaIsDroppedAndDoesNotShadowHasherMeta() throws IOException {
        // __meta__ is reserved by the hasher. If the input envelope happens to
        // carry a field literally named __meta__ (e.g. legacy/already-hashed
        // payload accidentally re-fed through the hasher), the hasher's own
        // meta block must win and the hash must be invariant under the
        // presence of the user field.
        String withUserMeta = """
                {"object_id": 1, "class": "X",
                 "__meta__": {"hash": "deadbeef", "id": 999, "class": "FAKE"},
                 "v": 1}
                """;
        String withoutUserMeta = """
                {"object_id": 1, "class": "X", "v": 1}
                """;

        Map<String, Object> r1 = parseJson(Hasher.hash(withUserMeta));
        Map<String, Object> r2 = parseJson(Hasher.hash(withoutUserMeta));

        // Hasher's meta dominates: __meta__ in the output is NOT the user's.
        assertNotEquals("deadbeef", metaHash(r1));
        // The user __meta__ is silently dropped — hash matches the clean input.
        assertEquals(metaHash(r1), metaHash(r2));
        assertEquals(metaOwnHash(r1), metaOwnHash(r2));
    }

    @Test
    void extractRootHashRejectsUserMetaWithoutOwnHash() throws IOException {
        // Defensive guard for extractRootHashFromHashed: hasher's __meta__
        // always carries BOTH "hash" and "own_hash". A plain (non-hasher)
        // input that happens to contain {"__meta__": {"hash": "deadbeef"}}
        // must not be mis-read as a pre-computed root hash — the function
        // must fall back to recomputing.
        String plainWithFakeMeta = """
                {"__meta__": {"hash": "deadbeef"}, "v": 1}
                """;
        String extracted = Hasher.extractRootHashFromHashed(plainWithFakeMeta);
        assertNotEquals("deadbeef", extracted,
                "user-supplied __meta__.hash without own_hash must not be trusted");
        assertTrue(extracted.matches("[0-9a-f]{32}"),
                "fallback must still produce a valid MD5 over the canonical form");
    }

    @Test
    void hashObjectOverloadAppliesMetaToEnvelopeMap() throws IOException {
        // The Object-input overload is used by RecordHashEnricher to add
        // __meta__ blocks in-process without a JSON round-trip. Smoke test
        // that it follows the same envelope-recognition rules as the
        // String-input overload.
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("object_id", 1);
        input.put("class", "X");
        input.put("v", 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) Hasher.hash(input);

        assertTrue(result.containsKey("__meta__"));
        Map<?, ?> meta = (Map<?, ?>) result.get("__meta__");
        assertEquals(1, meta.get("id"));
        assertEquals("X", meta.get("class"));
        assertNotNull(meta.get("hash"));
        assertNotNull(meta.get("own_hash"));
        assertEquals(1, result.get("v"));
    }

    @Test
    void emptyArrayAtRootGetsSynthesizedHash() throws IOException {
        // Edge case: top-level empty array. Documented contract is "rootHash
        // is always non-null and stable". md5 of "[]" is fine; the value
        // itself doesn't matter — stability does.
        Hasher.HashResult r1 = Hasher.hashWithRoot("[]");
        Hasher.HashResult r2 = Hasher.hashWithRoot("[]");
        assertNotNull(r1.rootHash());
        assertEquals(r1.rootHash(), r2.rootHash());
    }

    @Test
    void emptyPlainMapAtRootGetsSynthesizedHash() throws IOException {
        // Edge case: top-level empty plain map (not an envelope — no
        // object_id/class). Must still synthesize a stable root hash.
        Hasher.HashResult r1 = Hasher.hashWithRoot("{}");
        Hasher.HashResult r2 = Hasher.hashWithRoot("{}");
        assertNotNull(r1.rootHash());
        assertEquals(r1.rootHash(), r2.rootHash());
    }

    @Test
    void nullFieldInEnvelopeIsPreservedAndHashable() throws IOException {
        // A null-valued user field inside an envelope must round-trip and
        // must produce a stable hash across invocations.
        String json = """
                {"object_id": 1, "class": "X", "v": null}
                """;
        Map<String, Object> r1 = parseJson(Hasher.hash(json));
        Map<String, Object> r2 = parseJson(Hasher.hash(json));

        assertTrue(r1.containsKey("v"), "null field must be preserved as a key");
        assertEquals(null, r1.get("v"));
        assertEquals(metaHash(r1), metaHash(r2));
    }

    @Test
    void rootLevelCycleRefPassesThroughUnchanged() throws IOException {
        // ref_id maps at the root are not envelopes — they pass through as-is
        // and the root hash is synthesized over the map's canonical form.
        // This is the same shape that EnvelopeSerializer would emit for a
        // top-level self-cycle (rare but possible).
        String json = """
                {"ref_id": 1, "cycle_ref": true}
                """;
        Hasher.HashResult r = Hasher.hashWithRoot(json);
        assertNotNull(r.rootHash());
        assertTrue(r.rootHash().matches("[0-9a-f]{32}"));

        Map<String, Object> hashed = parseJson(r.hashedJson());
        assertEquals(1, hashed.get("ref_id"));
        assertEquals(true, hashed.get("cycle_ref"));
        assertFalse(hashed.containsKey("__meta__"));
    }
}
