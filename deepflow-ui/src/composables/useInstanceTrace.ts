import { computed, nextTick, ref, watch } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import { findPathToObjectId } from '../util/envelope';
import { api } from '../api/client';
import type {
  AppearanceKind, CallMeta, JumpAddress, Path, PayloadKind, PayloadRow, TraceTarget
} from '../types';

export interface UseInstanceTraceArgs {
  // Session id used by the object-trace endpoint to scope the search.
  sessionId: Ref<string>;
  // Read-only views of the lazy payload cache. findInstanceLocation
  // walks payloads of a single call, so the call's payloads need to
  // be in cache by the time the lookup runs (the navigator routes
  // ensure that via acquireCallPayloads + nextTick).
  payloadsByCallId: ComputedRef<Map<string, PayloadRow[]>>;
  mutatedObjectsByCallId: ComputedRef<Map<string, Set<number>>>;
  callMeta: ComputedRef<Map<string, CallMeta>>;
  // Live read of the currently-open inspection card; drives traceCursor.
  selectedCallId: Ref<string | null>;
  // Navigator side-effects.
  gotoAndSelect: (addr: JumpAddress) => void;
  highlightCallRow: (callId: string) => void;
  // Payload cache acquire/release, used by gotoAppearanceForCall to
  // ensure the target call's payloads are loaded before reading the
  // path. The navigator holds a ref across the navigation; the card
  // that mounts as a result of gotoAndSelect acquires its own ref,
  // so the cache entry survives the trailing release.
  acquireCallPayloads: (callId: string) => Promise<PayloadRow[]>;
  releaseCallPayloads: (callId: string) => void;
}

export interface UseInstanceTrace {
  inspectedInstance: Ref<TraceTarget | null>;
  setInspectedInstance: (t: TraceTarget) => void;
  clearInspectedInstance: () => void;
  instanceAppearancesByCallId: ComputedRef<Map<string, AppearanceKind>>;
  orderedAppearanceCallIds: ComputedRef<string[]>;
  inspectedCount: ComputedRef<number>;
  traceCursor: ComputedRef<number>;
  inspectedShortClass: ComputedRef<string>;
  gotoAppearanceForCall: (callId: string) => Promise<void>;
  gotoNextAppearance: () => Promise<void>;
  gotoPrevAppearance: () => Promise<void>;
  // Synchronous best-effort lookup — returns null if the call's
  // payloads aren't currently in cache. Caller is responsible for
  // ensuring load before relying on the result (or accepting null
  // as "navigate without a specific path").
  findInstanceLocation: (callId: string, objectId: number) => { kind: PayloadKind; path: Path } | null;
}

const PAYLOAD_KIND_ORDER: PayloadKind[] = ['TI', 'AR', 'AX', 'RE'];

export function useInstanceTrace(args: UseInstanceTraceArgs): UseInstanceTrace {
  const {
    sessionId, payloadsByCallId, mutatedObjectsByCallId,
    callMeta, selectedCallId,
    gotoAndSelect, highlightCallRow,
    acquireCallPayloads, releaseCallPayloads
  } = args;

  const inspectedInstance = ref<TraceTarget | null>(null);
  const appearedCallIds = ref<Set<string>>(new Set());

  function setInspectedInstance(t: TraceTarget): void {
    if (inspectedInstance.value?.objectId === t.objectId) {
      inspectedInstance.value = null;
    } else {
      inspectedInstance.value = t;
    }
  }
  function clearInspectedInstance(): void { inspectedInstance.value = null; }

  // Server-side object trace — replaces the local payload-walk index.
  // Fires once per (session, object_id) change. The bloom-filter probe
  // on payloads.object_ids makes the query indexed; we never need the
  // payloads themselves for the appearance map.
  watch([sessionId, inspectedInstance], async ([sid, inst]) => {
    appearedCallIds.value = new Set();
    if (!inst || !sid) return;
    try {
      const rows = await api.objectTrace(sid, inst.objectId);
      // Guard against a stale response landing after the user changed
      // the trace target.
      if (inspectedInstance.value?.objectId !== inst.objectId) return;
      appearedCallIds.value = new Set(rows.map(r => r.call_id));
    } catch {
      // Leave the set empty; the trace banner will read 0 appearances
      // and the bubbles won't render. The call tree still works.
    }
  }, { immediate: true });

  const instanceAppearancesByCallId = computed<Map<string, AppearanceKind>>(() => {
    const out = new Map<string, AppearanceKind>();
    const inst = inspectedInstance.value;
    if (!inst) return out;
    for (const callId of appearedCallIds.value) out.set(callId, 'appears');
    for (const [callId, ids] of mutatedObjectsByCallId.value) {
      if (ids.has(inst.objectId)) out.set(callId, 'mutated');
    }
    return out;
  });

  const orderedAppearanceCallIds = computed<string[]>(() => {
    const ids = Array.from(instanceAppearancesByCallId.value.keys());
    const meta = callMeta.value;
    ids.sort((a, b) => (meta.get(a)?.pre ?? 0) - (meta.get(b)?.pre ?? 0));
    return ids;
  });

  const inspectedCount = computed(() => orderedAppearanceCallIds.value.length);

  const traceCursor = computed<number>(() => {
    const id = selectedCallId.value;
    if (!id) return -1;
    return orderedAppearanceCallIds.value.indexOf(id);
  });

  const inspectedShortClass = computed(() => {
    const c = inspectedInstance.value?.className;
    if (!c) return '';
    return String(c).split('.').pop() || c;
  });

  function findInstanceLocation(callId: string, objectId: number): { kind: PayloadKind; path: Path } | null {
    const ps = payloadsByCallId.value.get(callId) || [];
    for (const k of PAYLOAD_KIND_ORDER) {
      const p = ps.find(x => x.kind === k);
      if (!p?.parsed) continue;
      const path = findPathToObjectId(p.parsed, objectId);
      if (path) return { kind: k, path };
    }
    return null;
  }

  // Async navigation. Acquires the target call's payloads BEFORE
  // resolving the path (so findInstanceLocation can read them), then
  // opens the card and waits for it to mount + acquire its own ref.
  // Final release here keeps refcount > 0 because the card is now
  // holding the entry — no spurious eviction → refetch.
  async function gotoAppearanceForCall(callId: string): Promise<void> {
    const inst = inspectedInstance.value;
    if (!inst) return;
    await acquireCallPayloads(callId);
    try {
      const loc = findInstanceLocation(callId, inst.objectId);
      gotoAndSelect({
        callId,
        kind: loc?.kind || 'AR',
        path: loc?.path || []
      });
      highlightCallRow(callId);
      // Yield a frame so the inspection card mounts and acquires its
      // own ref before we drop ours.
      await nextTick();
    } finally {
      releaseCallPayloads(callId);
    }
  }

  async function gotoAppearanceAt(index: number): Promise<void> {
    const ids = orderedAppearanceCallIds.value;
    if (!ids.length) return;
    const wrapped = ((index % ids.length) + ids.length) % ids.length;
    await gotoAppearanceForCall(ids[wrapped]);
  }

  async function gotoNextAppearance(): Promise<void> { await gotoAppearanceAt(traceCursor.value + 1); }
  async function gotoPrevAppearance(): Promise<void> { await gotoAppearanceAt(traceCursor.value - 1); }

  return {
    inspectedInstance,
    setInspectedInstance,
    clearInspectedInstance,
    instanceAppearancesByCallId,
    orderedAppearanceCallIds,
    inspectedCount,
    traceCursor,
    inspectedShortClass,
    gotoAppearanceForCall,
    gotoNextAppearance,
    gotoPrevAppearance,
    findInstanceLocation
  };
}
