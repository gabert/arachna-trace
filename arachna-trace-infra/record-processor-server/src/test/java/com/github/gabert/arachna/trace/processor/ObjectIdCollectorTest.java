package com.github.gabert.arachna.trace.processor;

import com.github.gabert.arachna.trace.codec.Hasher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectIdCollectorTest {

    @Test
    void singleEnvelopeYieldsOneId() throws IOException {
        String json = Hasher.hash("""
                {"object_id": 42, "class": "C", "v": 1}
                """);
        assertEquals(Set.of(42L), ObjectIdCollector.collect(json));
    }

    @Test
    void nestedEnvelopesAreAllCollected() throws IOException {
        String json = Hasher.hash("""
                {"object_id": 1, "class": "P",
                 "child": {"object_id": 2, "class": "C",
                           "grandchild": {"object_id": 3, "class": "GC", "v": "x"}}}
                """);
        assertEquals(Set.of(1L, 2L, 3L), ObjectIdCollector.collect(json));
    }

    @Test
    void listOfEnvelopesIsCollected() throws IOException {
        String json = Hasher.hash("""
                [{"object_id": 10, "class": "I"},
                 {"object_id": 11, "class": "I"},
                 {"object_id": 12, "class": "I"}]
                """);
        assertEquals(Set.of(10L, 11L, 12L), ObjectIdCollector.collect(json));
    }

    @Test
    void plainMapHasNoIds() throws IOException {
        String json = Hasher.hash("""
                {"object_id": 1, "class": "P",
                 "config": {"timeout": 30, "retries": 3}}
                """);
        // Only the root envelope has an id; "config" is a plain Map.
        assertEquals(Set.of(1L), ObjectIdCollector.collect(json));
    }

    @Test
    void duplicateIdsAreDeduplicated() throws IOException {
        // Same logical id appearing in two positions of the tree.
        String json = Hasher.hash("""
                {"object_id": 1, "class": "P",
                 "a": {"object_id": 7, "class": "X"},
                 "b": {"object_id": 7, "class": "X"}}
                """);
        Set<Long> ids = ObjectIdCollector.collect(json);
        assertEquals(2, ids.size(), "expected dedup of 7");
        assertTrue(ids.contains(1L));
        assertTrue(ids.contains(7L));
    }

    @Test
    void scalarTopLevelHasNoIds() throws IOException {
        assertEquals(Set.of(), ObjectIdCollector.collect("\"hello\""));
        assertEquals(Set.of(), ObjectIdCollector.collect("42"));
        assertEquals(Set.of(), ObjectIdCollector.collect("null"));
    }

    // ============================================================
    //  collectBoth — the path actually used by ClickHouseSink to
    //  populate payloads.object_ids and payloads.own_hashes. The
    //  index-by-index alignment is load-bearing for per-object
    //  mutation queries that read own_hashes[i] for object_ids[i].
    // ============================================================

    @Test
    void collectBothCarriesOwnHashAlignedToIdOrder() throws IOException {
        // Hasher.hash always emits own_hash. Walk a tree with three distinct
        // envelopes and check that for each id, the paired own_hash slot is
        // the value Hasher actually wrote into that envelope's __meta__.
        String json = Hasher.hash("""
                {"object_id": 1, "class": "P",
                 "child": {"object_id": 2, "class": "C",
                           "grandchild": {"object_id": 3, "class": "GC", "v": "x"}}}
                """);
        ObjectIdCollector.Result r = ObjectIdCollector.collectBoth(json);
        assertEquals(List.of(1L, 2L, 3L), r.ids(),
                "ids in first-occurrence order (root → child → grandchild)");
        assertEquals(r.ids().size(), r.ownHashes().size(),
                "own_hashes length must match ids length");
        for (int i = 0; i < r.ids().size(); i++) {
            assertFalse(r.ownHashes().get(i).isEmpty(),
                    "Hasher.hash always populates own_hash for envelope id " + r.ids().get(i));
        }
    }

    @Test
    void collectBothFillsEmptyStringWhenOwnHashAbsent() throws IOException {
        // Stored payloads that predate the own_hash enrichment have __meta__
        // without own_hash. The collector must degrade gracefully — empty
        // string in the slot, not null, so JSONEachRow doesn't blow up.
        String legacyJson = """
                {"__meta__": {"id": 99, "class": "L", "hash": "deadbeef"},
                 "v": 1}
                """;
        ObjectIdCollector.Result r = ObjectIdCollector.collectBoth(legacyJson);
        assertEquals(List.of(99L), r.ids());
        assertEquals(List.of(""), r.ownHashes(),
                "missing own_hash must surface as \"\", aligned by index");
    }

    @Test
    void collectBothDedupsIdsAndKeepsFirstOwnHashSlot() throws IOException {
        // Same id in two positions of the tree — only the first occurrence
        // contributes to the lists. The paired own_hash slot is the one from
        // that first occurrence (both Hasher-emitted hashes will be equal for
        // structurally-equal envelopes, but the contract is "first wins").
        String json = Hasher.hash("""
                {"object_id": 1, "class": "P",
                 "a": {"object_id": 7, "class": "X"},
                 "b": {"object_id": 7, "class": "X"}}
                """);
        ObjectIdCollector.Result r = ObjectIdCollector.collectBoth(json);
        assertEquals(List.of(1L, 7L), r.ids(), "id 7 deduplicated");
        assertEquals(2, r.ownHashes().size(),
                "own_hashes stays aligned with deduplicated ids");
    }
}
