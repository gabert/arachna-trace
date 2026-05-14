package com.github.gabert.arachna.trace.processor;

import com.github.gabert.arachna.trace.codec.Hasher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalarTokenCollectorTest {

    @Test
    void scalarLeavesBecomeTokens() throws IOException {
        String json = Hasher.hash("""
                {"object_id": 1, "class": "C",
                 "name": "Tolkien", "year": 1937, "active": true}
                """);
        List<String> tokens = ScalarTokenCollector.collect(json);
        assertTrue(tokens.contains("Tolkien"));
        assertTrue(tokens.contains("1937"));
        assertTrue(tokens.contains("true"));
    }

    @Test
    void metaBlockIsExcluded() throws IOException {
        String json = Hasher.hash("""
                {"object_id": 42, "class": "BookEntity",
                 "isbn": "9780618002213"}
                """);
        List<String> tokens = ScalarTokenCollector.collect(json);
        // The user's value is in.
        assertTrue(tokens.contains("9780618002213"));
        // Envelope identity / class / hashes are not.
        assertFalse(tokens.contains("42"),
                "envelope id leaked from __meta__ into tokens");
        assertFalse(tokens.contains("BookEntity"),
                "envelope class leaked from __meta__ into tokens");
    }

    @Test
    void nestedScalarsAreCollected() throws IOException {
        String json = Hasher.hash("""
                {"object_id": 1, "class": "P",
                 "child": {"object_id": 2, "class": "C",
                           "value": "deep"}}
                """);
        List<String> tokens = ScalarTokenCollector.collect(json);
        assertTrue(tokens.contains("deep"));
    }

    @Test
    void duplicatesAreDeduped() throws IOException {
        String json = Hasher.hash("""
                {"object_id": 1, "class": "C",
                 "a": "x", "b": "x", "c": "x"}
                """);
        List<String> tokens = ScalarTokenCollector.collect(json);
        assertEquals(1, tokens.stream().filter("x"::equals).count());
    }

    @Test
    void cycleRefIsSkipped() throws IOException {
        // Cycle refs carry a numeric ref_id we don't want indexed.
        String json = """
                {"__meta__": {"id": 1, "class": "P", "hash": "h"},
                 "self": {"cycle_ref": true, "ref_id": 1},
                 "other": "real"}
                """;
        List<String> tokens = ScalarTokenCollector.collect(json);
        assertTrue(tokens.contains("real"));
        assertFalse(tokens.stream().anyMatch(t -> t.equals("1") || t.equals("true")),
                "cycle_ref scalars (ref_id, the marker boolean) leaked into tokens");
    }

    @Test
    void emptyInputYieldsEmptyTokens() throws IOException {
        assertEquals(List.of(), ScalarTokenCollector.collect("{}"));
    }
}
