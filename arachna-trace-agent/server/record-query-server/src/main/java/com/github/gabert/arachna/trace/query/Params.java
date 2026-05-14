package com.github.gabert.arachna.trace.query;

import java.util.List;
import java.util.Map;

/**
 * URL query-parameter extraction shared across the API classes. Throws
 * {@link IllegalArgumentException} on missing required values, which the
 * top-level handler maps to HTTP 400.
 */
final class Params {

    private Params() {}

    static String required(Map<String, List<String>> params, String name) {
        String v = singleParam(params, name);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing required parameter: " + name);
        }
        return v;
    }

    static String singleParam(Map<String, List<String>> params, String name) {
        List<String> vs = params.get(name);
        return (vs == null || vs.isEmpty()) ? null : vs.get(0);
    }
}
