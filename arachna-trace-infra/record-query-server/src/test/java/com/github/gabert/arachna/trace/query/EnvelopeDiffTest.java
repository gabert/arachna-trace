package com.github.gabert.arachna.trace.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.SortedSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeDiffTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void identicalEnvelopesYieldEmptyDiff() {
        JsonNode env = env(1L, "Book", "{\"isbn\":\"978-x\",\"pages\":300}");
        assertTrue(EnvelopeDiff.changedPaths(env, env).isEmpty());
    }

    @Test
    void addedKeyShowsAsAdded() {
        JsonNode a = env(1L, "Book", "{\"isbn\":\"978-x\"}");
        JsonNode b = env(1L, "Book", "{\"isbn\":\"978-x\",\"pages\":300}");
        SortedSet<EnvelopeDiff.DiffPath> diff = EnvelopeDiff.changedPaths(a, b);
        assertEquals(1, diff.size());
        assertEquals(new EnvelopeDiff.DiffPath("pages", "added"), diff.first());
    }

    @Test
    void removedKeyShowsAsRemoved() {
        JsonNode a = env(1L, "Book", "{\"isbn\":\"978-x\",\"pages\":300}");
        JsonNode b = env(1L, "Book", "{\"isbn\":\"978-x\"}");
        SortedSet<EnvelopeDiff.DiffPath> diff = EnvelopeDiff.changedPaths(a, b);
        assertEquals(1, diff.size());
        assertEquals(new EnvelopeDiff.DiffPath("pages", "removed"), diff.first());
    }

    @Test
    void scalarLeafChangeShowsAsScalar() {
        JsonNode a = env(1L, "Book", "{\"isbn\":\"978-x\"}");
        JsonNode b = env(1L, "Book", "{\"isbn\":\"978-y\"}");
        SortedSet<EnvelopeDiff.DiffPath> diff = EnvelopeDiff.changedPaths(a, b);
        assertEquals(1, diff.size());
        assertEquals(new EnvelopeDiff.DiffPath("isbn", "scalar"), diff.first());
    }

    @Test
    void sameChildIdYieldsNoEntryBecauseChildContentDriftIsItsOwnDiff() {
        // Parent points to the same child envelope (same id) but the child's
        // content differs. The diff is the child's own concern — parent diff
        // is empty. This is the load-bearing rule that prevents "5000 books
        // with the same author edit" exploding into 5000 parent diffs.
        JsonNode a = env(1L, "Book", "{\"author\":" + env(2L, "Author", "{\"name\":\"X\"}").toString() + "}");
        JsonNode b = env(1L, "Book", "{\"author\":" + env(2L, "Author", "{\"name\":\"Y\"}").toString() + "}");
        assertTrue(EnvelopeDiff.changedPaths(a, b).isEmpty());
    }

    @Test
    void differentChildIdYieldsIdSwap() {
        JsonNode a = env(1L, "Book", "{\"author\":" + env(2L, "Author", "{}").toString() + "}");
        JsonNode b = env(1L, "Book", "{\"author\":" + env(3L, "Author", "{}").toString() + "}");
        SortedSet<EnvelopeDiff.DiffPath> diff = EnvelopeDiff.changedPaths(a, b);
        assertEquals(1, diff.size());
        assertEquals(new EnvelopeDiff.DiffPath("author", "idSwap"), diff.first());
    }

    @Test
    void envelopeVsPlainScalarYieldsScalar() {
        JsonNode a = env(1L, "Book", "{\"author\":" + env(2L, "Author", "{}").toString() + "}");
        JsonNode b = env(1L, "Book", "{\"author\":\"plain string\"}");
        SortedSet<EnvelopeDiff.DiffPath> diff = EnvelopeDiff.changedPaths(a, b);
        assertEquals(1, diff.size());
        assertEquals(new EnvelopeDiff.DiffPath("author", "scalar"), diff.first());
    }

    @Test
    void arrayGrowthShowsAddedAtIndex() {
        JsonNode a = env(1L, "List", "{\"items\":[10,20]}");
        JsonNode b = env(1L, "List", "{\"items\":[10,20,30]}");
        SortedSet<EnvelopeDiff.DiffPath> diff = EnvelopeDiff.changedPaths(a, b);
        assertEquals(1, diff.size());
        assertEquals(new EnvelopeDiff.DiffPath("items[2]", "added"), diff.first());
    }

    @Test
    void arrayShrinkShowsRemovedAtIndex() {
        JsonNode a = env(1L, "List", "{\"items\":[10,20,30]}");
        JsonNode b = env(1L, "List", "{\"items\":[10,20]}");
        SortedSet<EnvelopeDiff.DiffPath> diff = EnvelopeDiff.changedPaths(a, b);
        assertEquals(1, diff.size());
        assertEquals(new EnvelopeDiff.DiffPath("items[2]", "removed"), diff.first());
    }

    @Test
    void arrayElementScalarChangeShowsScalarAtIndex() {
        JsonNode a = env(1L, "List", "{\"items\":[10,20,30]}");
        JsonNode b = env(1L, "List", "{\"items\":[10,99,30]}");
        SortedSet<EnvelopeDiff.DiffPath> diff = EnvelopeDiff.changedPaths(a, b);
        assertEquals(1, diff.size());
        assertEquals(new EnvelopeDiff.DiffPath("items[1]", "scalar"), diff.first());
    }

    @Test
    void cycleRefEqualityYieldsNoEntry() {
        // cycle_ref with same ref_id on both sides is a graph back-edge to the
        // same node — no diff. refIdOf must treat cycle_ref the same as a
        // full envelope's __meta__.id for swap detection.
        JsonNode a = parse("{\"self\":{\"cycle_ref\":true,\"ref_id\":7}}");
        JsonNode b = parse("{\"self\":{\"cycle_ref\":true,\"ref_id\":7}}");
        assertTrue(EnvelopeDiff.changedPaths(a, b).isEmpty());
    }

    @Test
    void cycleRefIdSwapYieldsIdSwap() {
        JsonNode a = parse("{\"self\":{\"cycle_ref\":true,\"ref_id\":7}}");
        JsonNode b = parse("{\"self\":{\"cycle_ref\":true,\"ref_id\":9}}");
        SortedSet<EnvelopeDiff.DiffPath> diff = EnvelopeDiff.changedPaths(a, b);
        assertEquals(1, diff.size());
        assertEquals(new EnvelopeDiff.DiffPath("self", "idSwap"), diff.first());
    }

    @Test
    void envelopeNoiseFieldsAreNotSurfacedAsDiff() {
        // Changing __meta__/object_id/class/ref_id/cycle_ref between A and B
        // must NOT generate a diff path — they're envelope identity, not
        // user data. ownKeys filters them out.
        JsonNode a = parse("{\"__meta__\":{\"id\":1,\"class\":\"A\",\"hash\":\"h1\"},\"k\":1}");
        JsonNode b = parse("{\"__meta__\":{\"id\":1,\"class\":\"A\",\"hash\":\"h2\"},\"k\":1}");
        assertTrue(EnvelopeDiff.changedPaths(a, b).isEmpty());
    }

    private static JsonNode env(long id, String cls, String fieldsJsonObject) {
        String inner = fieldsJsonObject.substring(1, fieldsJsonObject.length() - 1);
        String meta = "\"__meta__\":{\"id\":" + id + ",\"class\":\"" + cls + "\",\"hash\":\"h\"}";
        String json = inner.isEmpty()
                ? "{" + meta + "}"
                : "{" + meta + "," + inner + "}";
        return parse(json);
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
