import { diffOwnState } from '../util/envelopeDiff';
import { isCycleRef, isEnvelope, resolvePath, walkEnvelopes } from '../util/envelope';
import { formatValue, shortHash } from '../util/format';
import { eventComparator, isExitKind } from '../util/chrono';
import type {
  CallMeta,
  DiffEntry,
  Envelope,
  Path,
  PayloadKind,
  PayloadRow,
  Watch
} from '../types';

export interface AppearanceRow {
  callId: string;
  kind: PayloadKind;
  // Event time of the row — ts_in for AR/TI (entry), ts_out for
  // AX/RE (exit). Used only for display; the row's chronological
  // position is determined by util/chrono.eventComparator.
  ts: string;
  signature: string;
  hash: string | undefined;
  ownHash: string;
  snapshot: Envelope;
  envelopePath: Path;
  fieldValue?: unknown;
  repr: string;
  compareKey: string | null;
  band: 0 | 1;
  changed: boolean;
  diffs?: DiffEntry[];
}

// Display helper: which call-clock timestamp does this kind of event
// observe? Mirrors isExitKind: AR/TI at entry, AX/RE at exit.
function eventTime(kind: PayloadKind, meta: CallMeta | undefined): string {
  if (!meta) return '';
  return isExitKind(kind) ? meta.ts_out : meta.ts_in;
}

// Pure function: given a single watch and a request's parsed payloads,
// return the row list for the watch's appearances table. Sorted via
// the shared chronological comparator (util/chrono).
//
// Pure: takes inputs, returns rows. No reactivity — caller wraps in
// computed() at the call site.
export function appearancesFor(
  watch: Watch,
  parsedPayloads: PayloadRow[],
  callMeta: Map<string, CallMeta>
): AppearanceRow[] {
  const out: AppearanceRow[] = [];
  for (const p of parsedPayloads) {
    walkEnvelopes(p.parsed, (env, path) => {
      if (env.__meta__.id !== watch.objectId) return;
      const meta = callMeta.get(p.call_id);
      out.push({
        callId: p.call_id,
        kind: p.kind,
        ts: eventTime(p.kind, meta) || p.ts_in,
        signature: p.signature,
        hash: env.__meta__.hash,
        ownHash: env.__meta__.own_hash || '',
        snapshot: env,
        envelopePath: path,
        repr: '',
        compareKey: null,
        band: 0,
        changed: false
      });
    });
  }

  out.sort(eventComparator<AppearanceRow>(callMeta));

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

  // Drop AX rows whose primary signal matches the AR row's signal for
  // the same call. AR↔AX is the agent's mutation-detection pair, but if
  // the call doesn't actually change the watched signal, the AX row is
  // a duplicate of AR and just doubles the table. Kept when AX differs
  // (real mutation) and when there's no AR for the call (object newly
  // added by the call). RE/TI rows are unaffected.
  const arKeyByCall = new Map<string, string | null>();
  for (const r of out) {
    if (r.kind === 'AR') arKeyByCall.set(r.callId, r.compareKey);
  }
  const filtered = out.filter(r => {
    if (r.kind !== 'AX') return true;
    if (!arKeyByCall.has(r.callId)) return true;
    return arKeyByCall.get(r.callId) !== r.compareKey;
  });

  // Bands flip on the row's primary signal:
  //   - instance watch → own_hash (this object's own-state)
  //   - field watch    → resolved field value
  // A row marked `changed` is one where the primary signal moved
  // between this row and the previous one. For instance watches we
  // also pre-compute `diffs` against the previous snapshot so the
  // row can render an inline field-level diff when expanded.
  let band: 0 | 1 = 0;
  let lastKey: string | null = null;
  let lastSnapshot: Envelope | null = null;
  for (const r of filtered) {
    const moved = lastKey !== null && r.compareKey !== lastKey;
    if (moved) band = (band ^ 1) as 0 | 1;
    r.band = band;
    r.changed = moved;
    if (watch.kind === 'instance' && moved && lastSnapshot) {
      r.diffs = diffOwnState(lastSnapshot, r.snapshot);
    }
    lastKey = r.compareKey;
    lastSnapshot = r.snapshot;
  }
  return filtered;
}

// Stable key for "did the field's value change?" comparison. Refs
// compare by id (so the SAME object re-pointed counts as same), arrays
// and plain objects compare structurally. Primitives JSON-stringify.
function normalizeFieldKey(v: unknown): string {
  if (v === undefined) return '__undef__';
  if (v === null) return 'null';
  if (typeof v !== 'object') return JSON.stringify(v);
  if (isCycleRef(v)) return `ref:${v.ref_id}`;
  if (isEnvelope(v)) return `ref:${v.__meta__.id}`;
  if (Array.isArray(v)) return JSON.stringify(v.map(normalizeFieldKey));
  const obj = v as Record<string, unknown>;
  const keys = Object.keys(obj).filter(k => k !== '__meta__').sort();
  return JSON.stringify(keys.map(k => [k, normalizeFieldKey(obj[k])]));
}
