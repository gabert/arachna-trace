import { computed, ref, watch } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import { api } from '../api/client';
import type { EnclosingEnvelope } from '../util/envelope';
import { enclosingEnvelopeFromPath, findByObjectId, findPathToObjectId, resolvePath } from '../util/envelope';
import { chronoIndex, eventComparator, isExitKind } from '../util/chrono';
import { tryParse } from '../util/format';
import type {
  CallMeta,
  Confidence,
  OriginAppearance,
  OriginChain,
  OriginMutation,
  OriginTarget,
  Path,
  PayloadKind,
  PayloadNode,
  PayloadRow,
  ValueSearchHit
} from '../types';

// Phase-2 provenance: the value-appearance index now lives server-side
// (bloom-filter `has(payload_tokens, V)` over the payloads table). The
// UI just fetches when the user clicks ↤ origin, then assembles the
// chain from the server response plus already-loaded request payloads.
//
// What the agent / processor / server know vs what we still derive
// client-side:
//   server-side: the *appearance set* — which (call, kind, path) tuples
//                contain V. Comes from the indexed lookup.
//   client-side: the *narrative shape* — chronological sort, source
//                classification (produced vs entered), AR/RE-only
//                propagation filter, next-mutation lookup. All pure
//                derivations over the already-loaded request data.
//
// Honesty constraint unchanged from Phase 1: this is heuristic. A
// match means "same canonicalized scalar observed earlier", not
// "produced from this earlier observation". The agent doesn't see
// method bodies. The UI surfaces source rows as "produced here" only
// when the source is AX/RE (no AR/TI of same call carried it) — that
// part is structural, not heuristic.

export interface UseProvenance {
  target: Ref<OriginTarget | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  chain: Ref<OriginChain | null>;
  isTooCommon: ComputedRef<boolean>;
  setTarget: (t: OriginTarget) => void;
  clear: () => void;
}

const MED_HIGH_OCCURRENCE_LIMIT = 5;
const MED_LOW_OCCURRENCE_LIMIT = 15;

export function useProvenance(
  parsedPayloads: ComputedRef<PayloadRow[]>,
  callMeta: ComputedRef<Map<string, CallMeta>>,
  sessionId: Ref<string>
): UseProvenance {
  const target = ref<OriginTarget | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const chain = ref<OriginChain | null>(null);

  const isTooCommon: ComputedRef<boolean> = computed(() => {
    if (!target.value) return false;
    return intrinsicUniqueness(target.value.value) === 'low';
  });

  // Pre-sort all parsed payloads by chronological position once per
  // request load. findNextMutation's linear scan reads this directly
  // instead of resorting on every click.
  const payloadsInChronoOrder: ComputedRef<PayloadRow[]> = computed(() => {
    const arr = parsedPayloads.value.slice();
    arr.sort((a, b) =>
      chronoIndex(a.call_id, a.kind, callMeta.value)
      - chronoIndex(b.call_id, b.kind, callMeta.value));
    return arr;
  });

  watch(target, async (t) => {
    if (!t) {
      chain.value = null;
      error.value = null;
      return;
    }
    // Refuse trivial values without a fetch — server would happily
    // return matches but the resulting "chain" is meaningless and
    // wastes a round-trip.
    if (intrinsicUniqueness(t.value) === 'low') {
      chain.value = null;
      error.value = null;
      return;
    }
    const canonical = canonicalize(t.value);
    if (canonical == null) {
      chain.value = null;
      return;
    }
    loading.value = true;
    error.value = null;
    try {
      // Always session-wide: origin should follow a value across
      // requests (the whole point of the cross-request forensic mode).
      const hits = await api.valueSearch(sessionId.value, null, canonical);
      // Steps 1-5 of the chain (source / propagation / current) are
      // built purely from hits + callMeta — no local payloads needed.
      // Step 6 (next mutation) needs envelope-aware walking of payloads
      // that mention the enclosing envelope's object_id; we fetch
      // those bloom-filtered, on demand. The CURRENT click site's
      // payload comes from the local cache (the inspection card the
      // user clicked is open and has acquired).
      const partial = buildChainCore(t, hits, callMeta.value);
      let nextMutation: OriginMutation | null = null;
      if (partial.current) {
        const env = locateEnclosingEnvelopeFromCache(t, partial.current, payloadsInChronoOrder.value);
        if (env) {
          const objectPayloads = await api.objectPayloads(sessionId.value, env.envelopeId);
          if (target.value !== t) return; // stale fetch
          nextMutation = findNextMutationOver(t, partial.current, env, objectPayloads, callMeta.value);
        }
      }
      chain.value = { ...partial, nextMutation };
    } catch (e) {
      error.value = (e as Error).message;
      chain.value = null;
    } finally {
      loading.value = false;
    }
  });

  function setTarget(t: OriginTarget): void {
    target.value = t;
  }

  function clear(): void {
    target.value = null;
    chain.value = null;
    error.value = null;
  }

  return { target, loading, error, chain, isTooCommon, setTarget, clear };
}

// --- pure builders over server hits + client-side data ----------

// Pure chain assembly — works from server hits + callMeta, no local
// payloads needed. The next-mutation step is split out into the
// async path because it needs envelope-aware payload walking.
function buildChainCore(
  target: OriginTarget,
  hits: ValueSearchHit[],
  callMeta: Map<string, CallMeta>
): OriginChain {
  if (!hits.length) {
    return { source: null, sourceKind: null, propagation: [], current: null, nextMutation: null };
  }
  const conf = confidenceOf(target.value, hits.length);
  const rows = appearancesFromHits(hits, target, callMeta, conf);
  const source = rows[0];
  const sourceKind = classifySource(source);
  const currentIndex = rows.findIndex(r => r.isCurrent);
  const current = currentIndex >= 0 ? rows[currentIndex] : null;
  const propagation = extractPropagation(rows, currentIndex);
  return { source, sourceKind, propagation, current, nextMutation: null };
}

// Maps the server's flat hit list into chronologically-sorted
// OriginAppearance rows. Each hit becomes one row; the row marked
// isCurrent is the one whose (callId, kind, path) matches the click.
function appearancesFromHits(
  hits: ValueSearchHit[],
  target: OriginTarget,
  callMeta: Map<string, CallMeta>,
  confidence: Confidence
): OriginAppearance[] {
  const targetPathKey = JSON.stringify(target.path);
  const rows: OriginAppearance[] = hits.map(h => {
    const meta = callMeta.get(h.call_id);
    const ts = isExitKind(h.kind) ? (meta?.ts_out ?? h.ts_in) : (meta?.ts_in ?? h.ts_in);
    return {
      callId: h.call_id,
      kind: h.kind,
      path: h.path,
      ts,
      signature: h.signature,
      confidence,
      isCurrent: h.call_id === target.callId
              && h.kind === target.kind
              && JSON.stringify(h.path) === targetPathKey
    };
  });
  rows.sort(eventComparator<OriginAppearance>(callMeta));
  return rows;
}

function classifySource(source: OriginAppearance): 'produced' | 'entered' {
  return isExitKind(source.kind) ? 'produced' : 'entered';
}

function extractPropagation(rows: OriginAppearance[], currentIndex: number): OriginAppearance[] {
  if (currentIndex <= 0) return [];
  const out: OriginAppearance[] = [];
  for (let i = 1; i < currentIndex; i++) {
    const r = rows[i];
    if (r.kind === 'AR' || r.kind === 'RE') out.push(r);
  }
  return out;
}

// Locate the enclosing envelope from the user-clicked path. Reads the
// CURRENT click site's payload from the local cache (the inspection
// card the user clicked is open and has acquired its payloads).
function locateEnclosingEnvelopeFromCache(
  target: OriginTarget,
  current: OriginAppearance,
  payloadsInChronoOrder: PayloadRow[]
): EnclosingEnvelope | null {
  const currentPayload = payloadsInChronoOrder.find(p =>
    p.call_id === current.callId && p.kind === current.kind);
  if (!currentPayload?.parsed) return null;
  return enclosingEnvelopeFromPath(currentPayload.parsed, target.path);
}

// Walk SERVER-RETURNED object payloads (already filtered to ones that
// mention the envelope's object_id) chronologically AFTER the current
// click and find the first observation where the field value diverges
// from target.value. The server returns payload_json as a string; we
// parse on the fly here, no client cache pollution.
function findNextMutationOver(
  target: OriginTarget,
  current: OriginAppearance,
  env: EnclosingEnvelope,
  objectPayloads: PayloadRow[],
  callMeta: Map<string, CallMeta>
): OriginMutation | null {
  const sorted = objectPayloads.slice().sort((a, b) =>
    chronoIndex(a.call_id, a.kind, callMeta) - chronoIndex(b.call_id, b.kind, callMeta));
  const currentChrono = chronoIndex(current.callId, current.kind, callMeta);
  for (const p of sorted) {
    const c = chronoIndex(p.call_id, p.kind, callMeta);
    if (c <= currentChrono) continue;
    const parsed = p.parsed !== undefined ? p.parsed : (tryParse<PayloadNode>(p.payload_json) ?? undefined);
    if (parsed === undefined) continue;
    const envInPayload = findByObjectId(parsed, env.envelopeId);
    if (!envInPayload) continue;
    const observation = resolvePath(envInPayload, env.fieldPath);
    if (observation === undefined) continue;
    if (observation === target.value) continue;
    const enriched: PayloadRow = { ...p, parsed };
    return buildMutation(enriched, env, observation, callMeta);
  }
  return null;
}

function buildMutation(
  p: PayloadRow,
  env: EnclosingEnvelope,
  newValue: unknown,
  callMeta: Map<string, CallMeta>
): OriginMutation {
  const pathToEnv = findPathToObjectId(p.parsed, env.envelopeId) ?? [];
  const fullPath = [...pathToEnv, ...env.fieldPath];
  const meta = callMeta.get(p.call_id);
  const ts = isExitKind(p.kind) ? (meta?.ts_out ?? '') : (meta?.ts_in ?? '');
  return {
    callId: p.call_id,
    kind: p.kind,
    path: fullPath,
    ts,
    signature: p.signature,
    newValue,
    envelopeId: env.envelopeId,
    fieldPath: env.fieldPath
  };
}

// --- value classification (mirrors server-side ScalarTokenCollector) -

// Stable string key for value-equality matching. Returns null when
// the value isn't a chain candidate (containers, identity refs).
// Uses raw string form for primitives — matches the server's token
// canonicalization, so what we ask for is what the server has indexed.
function canonicalize(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string') return value;
  if (typeof value === 'number') return String(value);
  if (typeof value === 'boolean') return String(value);
  return null;
}

type Uniqueness = 'high' | 'med' | 'low';

function intrinsicUniqueness(v: unknown): Uniqueness {
  if (v === null || v === undefined) return 'low';
  if (typeof v === 'boolean') return 'low';
  if (typeof v === 'number') {
    if (v === 0 || v === 1 || v === -1) return 'low';
    if (Number.isInteger(v) && Math.abs(v) < 100) return 'med';
    return 'high';
  }
  if (typeof v === 'string') {
    if (v.length < 3) return 'low';
    if (v.length >= 8) return 'high';
    return 'med';
  }
  return 'low';
}

function confidenceOf(value: unknown, occurrenceCount: number): Confidence {
  const u = intrinsicUniqueness(value);
  if (u === 'high') return 'HIGH';
  if (u === 'med') {
    if (occurrenceCount <= MED_HIGH_OCCURRENCE_LIMIT) return 'HIGH';
    if (occurrenceCount <= MED_LOW_OCCURRENCE_LIMIT) return 'MED';
    return 'LOW';
  }
  return 'LOW';
}
