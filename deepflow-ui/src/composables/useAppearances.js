import { diffOwnState } from '../util/envelopeDiff.js';
import { isCycleRef, isEnvelope, walkEnvelopes } from '../util/envelope.js';
import { formatValue, shortHash } from '../util/format.js';

// Pure function: given a single watch and a request's parsed payloads,
// return the row list for the watch's appearances table.
//
// Each row carries:
//   call_id, kind, ts_in, signature, snapshot, envelopePath
//   fieldValue?  — for field watches: resolved value at fieldPath
//   repr         — what the row's "value" cell renders
//   compareKey   — what we band/transition on
//   band         — 0/1, flips on transition
//   changed      — true on rows where the primary signal moved
//   diffs?       — for instance watches: per-field diff vs previous snapshot
//
// `callOrder` orders rows by the server's stable seq, then by AR/TI/AX/RE.
// Pure: takes inputs, returns rows. No reactivity — caller wraps in
// computed() at the call site.

const KIND_ORDER = { AR: 0, TI: 1, AX: 2, RE: 3 };

export function appearancesFor(watch, parsedPayloads, callOrder) {
  const out = [];
  for (const p of parsedPayloads) {
    walkEnvelopes(p.parsed, (env, path) => {
      if (env.__meta__.id !== watch.objectId) return;
      out.push({
        call_id: p.call_id,
        kind: p.kind,
        ts_in: p.ts_in,
        signature: p.signature,
        hash: env.__meta__.hash,
        ownHash: env.__meta__.own_hash || '',
        snapshot: env,
        envelopePath: path
      });
    });
  }

  out.sort((a, b) => {
    const ai = callOrder.get(a.call_id);
    const bi = callOrder.get(b.call_id);
    const ao = ai == null ? Number.MAX_SAFE_INTEGER : ai;
    const bo = bi == null ? Number.MAX_SAFE_INTEGER : bi;
    if (ao !== bo) return ao - bo;
    return (KIND_ORDER[a.kind] ?? 99) - (KIND_ORDER[b.kind] ?? 99);
  });

  if (watch.kind === 'field') {
    for (const r of out) {
      const v = resolvePath(r.snapshot, watch.fieldPath);
      r.fieldValue = v;
      r.repr = formatValue(v);
      r.compareKey = normalizeFieldKey(v);
    }
  } else {
    // Instance watch: own-state hash only. Deep / Merkle hash is no
    // longer surfaced here — in a flat row context it propagates child
    // changes upward and forces the reader to mentally disambiguate
    // "did this object change or did something below it." Drill via
    // the call tree and JsonTree if you need the deep view.
    for (const r of out) {
      r.repr = shortHash(r.ownHash);
      r.compareKey = r.ownHash;
    }
  }

  // Bands flip on the row's primary signal:
  //   - instance watch → own_hash (this object's own-state)
  //   - field watch    → resolved field value
  // A row marked `changed` is one where the primary signal moved
  // between this row and the previous one. For instance watches we
  // also pre-compute `diffs` against the previous snapshot so the
  // row can render an inline field-level diff when expanded.
  let band = 0;
  let lastKey = null;
  let lastSnapshot = null;
  for (const r of out) {
    const moved = lastKey !== null && r.compareKey !== lastKey;
    if (moved) band ^= 1;
    r.band = band;
    r.changed = moved;
    if (watch.kind === 'instance' && moved && lastSnapshot) {
      r.diffs = diffOwnState(lastSnapshot, r.snapshot);
    }
    lastKey = r.compareKey;
    lastSnapshot = r.snapshot;
  }
  return out;
}

function resolvePath(envelope, path) {
  let v = envelope;
  for (const seg of path) {
    if (v == null || typeof v !== 'object') return undefined;
    v = v[seg];
  }
  return v;
}

// Stable key for "did the field's value change?" comparison. Refs
// compare by id (so the SAME object re-pointed counts as same), arrays
// and plain objects compare structurally. Primitives JSON-stringify.
function normalizeFieldKey(v) {
  if (v === undefined) return '__undef__';
  if (v === null) return 'null';
  if (typeof v !== 'object') return JSON.stringify(v);
  if (isCycleRef(v)) return `ref:${v.ref_id}`;
  if (isEnvelope(v)) return `ref:${v.__meta__.id}`;
  if (Array.isArray(v)) return JSON.stringify(v.map(normalizeFieldKey));
  const keys = Object.keys(v).filter(k => k !== '__meta__').sort();
  return JSON.stringify(keys.map(k => [k, normalizeFieldKey(v[k])]));
}
