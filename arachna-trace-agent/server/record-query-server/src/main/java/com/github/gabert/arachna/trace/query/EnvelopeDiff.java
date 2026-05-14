package com.github.gabert.arachna.trace.query;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Server-side path-set fingerprinter for two hashed envelope snapshots
 * of the same object id. Returns the sorted set of (path, kind) pairs
 * that differ between AR and AX — used as the grouping key for the
 * mutations endpoint so "5000 books with the same isbn rewrite" folds
 * to one row, not 5000.
 *
 * <p>Mirrors the rules of {@code arachna-trace-ui/src/util/envelopeDiff.js}
 * but emits only the *shape* of the diff (path, kind), not the values.
 * Values stay on the client where they're rendered. Same semantics
 * either way: walks what {@code own_hash} captures (scalars, child
 * id-refs, list shape, added/removed keys); does not descend into
 * nested envelope contents.</p>
 */
public final class EnvelopeDiff {

    private EnvelopeDiff() {}

    /** A single path that differs, plus the kind of difference. */
    public record DiffPath(String path, String kind) {}

    private static final Set<String> ENVELOPE_NOISE = Set.of(
            "__meta__", "object_id", "class", "ref_id", "cycle_ref"
    );

    private static final Comparator<DiffPath> PATH_KIND_ORDER =
            Comparator.comparing(DiffPath::path).thenComparing(DiffPath::kind);

    public static SortedSet<DiffPath> changedPaths(JsonNode a, JsonNode b) {
        SortedSet<DiffPath> out = new TreeSet<>(PATH_KIND_ORDER);
        if (a == null || b == null) return out;
        walkObject(a, b, "", out);
        return out;
    }

    private static void walkObject(JsonNode a, JsonNode b, String path, Set<DiffPath> out) {
        Set<String> aKeys = ownKeys(a);
        Set<String> bKeys = ownKeys(b);
        Set<String> all = new LinkedHashSet<>(aKeys);
        all.addAll(bKeys);
        for (String k : all) {
            String p = path.isEmpty() ? k : path + "." + k;
            if (!aKeys.contains(k)) { out.add(new DiffPath(p, "added")); continue; }
            if (!bKeys.contains(k)) { out.add(new DiffPath(p, "removed")); continue; }
            diffValue(a.get(k), b.get(k), p, out);
        }
    }

    private static void diffValue(JsonNode a, JsonNode b, String path, Set<DiffPath> out) {
        Long aRef = refIdOf(a);
        Long bRef = refIdOf(b);
        if (aRef != null && bRef != null) {
            if (!aRef.equals(bRef)) out.add(new DiffPath(path, "idSwap"));
            return; // same id → child's content drift belongs to its own diff
        }
        if (aRef != null || bRef != null) {
            out.add(new DiffPath(path, "scalar"));
            return;
        }
        if (a.isArray() && b.isArray()) {
            int max = Math.max(a.size(), b.size());
            for (int i = 0; i < max; i++) {
                String p = path + "[" + i + "]";
                if (i >= a.size()) { out.add(new DiffPath(p, "added")); continue; }
                if (i >= b.size()) { out.add(new DiffPath(p, "removed")); continue; }
                diffValue(a.get(i), b.get(i), p, out);
            }
            return;
        }
        if (a.isObject() && b.isObject()) {
            walkObject(a, b, path, out);
            return;
        }
        if (!a.equals(b)) out.add(new DiffPath(path, "scalar"));
    }

    private static Set<String> ownKeys(JsonNode obj) {
        if (!obj.isObject()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        Iterator<String> it = obj.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            if (!ENVELOPE_NOISE.contains(k)) out.add(k);
        }
        return out;
    }

    private static Long refIdOf(JsonNode v) {
        if (v == null || !v.isObject()) return null;
        JsonNode meta = v.get("__meta__");
        if (meta != null) {
            JsonNode id = meta.get("id");
            if (id != null && id.isNumber()) return id.asLong();
        }
        JsonNode cycle = v.get("cycle_ref");
        JsonNode refId = v.get("ref_id");
        if (cycle != null && cycle.asBoolean(false) && refId != null && refId.isNumber()) {
            return refId.asLong();
        }
        return null;
    }
}
