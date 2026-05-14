package com.github.gabert.arachna.trace.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParamsTest {

    @Test
    void requiredReturnsValueWhenPresent() {
        Map<String, List<String>> params = Map.of("session_id", List.of("s-1"));
        assertEquals("s-1", Params.required(params, "session_id"));
    }

    @Test
    void requiredThrowsWhenKeyMissingWithParamNameInMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Params.required(Map.of(), "session_id"));
        // QueryHandler.dispatch surfaces this message verbatim as the 400 body;
        // the param name must appear so the UI can show actionable errors.
        assertEquals("missing required parameter: session_id", ex.getMessage());
    }

    @Test
    void requiredThrowsWhenListIsEmpty() {
        Map<String, List<String>> params = Map.of("session_id", List.of());
        assertThrows(IllegalArgumentException.class,
                () -> Params.required(params, "session_id"));
    }

    @Test
    void requiredThrowsWhenValueIsEmptyString() {
        // `?session_id=` parses to a list with a single empty string; treat
        // it the same as missing rather than letting "" through to a SQL bind.
        Map<String, List<String>> params = Map.of("session_id", List.of(""));
        assertThrows(IllegalArgumentException.class,
                () -> Params.required(params, "session_id"));
    }

    @Test
    void singleParamReturnsNullWhenAbsentOrEmptyList() {
        assertNull(Params.singleParam(Map.of(), "request_id"));
        assertNull(Params.singleParam(Map.of("request_id", List.of()), "request_id"));
    }
}
