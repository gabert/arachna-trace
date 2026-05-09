import { computed, ref } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import { findPathToObjectId } from '../util/envelope';
import type {
  AppearanceKind, CallMeta, JumpAddress, Path, PayloadKind, PayloadRow, TraceTarget
} from '../types';

export interface UseInstanceTraceArgs {
  // Inputs
  callIdsByObjectId: ComputedRef<Map<number, Set<string>>>;
  mutatedObjectsByCallId: ComputedRef<Map<string, Set<number>>>;
  payloadsByCallId: ComputedRef<Map<string, PayloadRow[]>>;
  callMeta: ComputedRef<Map<string, CallMeta>>;
  // Live read of the currently-open inspection card; drives traceCursor.
  selectedCallId: Ref<string | null>;
  // Side-effect callbacks. Both fire on a goto: the JSON-tree highlight
  // (gotoAndSelect, opens / focuses the inspection card) and the call-
  // tree row flash (highlightCall on the CallTreePanel ref).
  gotoAndSelect: (addr: JumpAddress) => void;
  highlightCallRow: (callId: string) => void;
}

export interface UseInstanceTrace {
  inspectedInstance: Ref<TraceTarget | null>;
  setInspectedInstance: (t: TraceTarget) => void;
  clearInspectedInstance: () => void;
  // Per-call appearance kind for the inspected instance — direct only
  // (no subtree rollup; the trace banner's ↑/↓ navigation handles
  // "find next call that touches this").
  instanceAppearancesByCallId: ComputedRef<Map<string, AppearanceKind>>;
  // Chronologically-ordered call ids (DFS pre-order), drives ↑/↓.
  orderedAppearanceCallIds: ComputedRef<string[]>;
  inspectedCount: ComputedRef<number>;
  // Cursor derived from selectedCallId — no separate state to keep in
  // sync. Manual row clicks update the counter naturally.
  traceCursor: ComputedRef<number>;
  // Short class name (last segment) for the trace banner label.
  inspectedShortClass: ComputedRef<string>;
  // Random-access nav: jumps to a specific appearance, used by both
  // ↑/↓ stepping and the bubble click on a FrameCard row.
  gotoAppearanceForCall: (callId: string) => void;
  gotoNextAppearance: () => void;
  gotoPrevAppearance: () => void;
  // Find where the instance lives in a given call's payloads. Exposed
  // because the row-click handler in SessionDetailView needs it for
  // the auto-navigate-on-inspect path.
  findInstanceLocation: (callId: string, objectId: number) => { kind: PayloadKind; path: Path } | null;
}

const PAYLOAD_KIND_ORDER: PayloadKind[] = ['TI', 'AR', 'AX', 'RE'];

export function useInstanceTrace(args: UseInstanceTraceArgs): UseInstanceTrace {
  const {
    callIdsByObjectId, mutatedObjectsByCallId, payloadsByCallId,
    callMeta, selectedCallId, gotoAndSelect, highlightCallRow
  } = args;

  const inspectedInstance = ref<TraceTarget | null>(null);

  function setInspectedInstance(t: TraceTarget): void {
    if (inspectedInstance.value?.objectId === t.objectId) {
      inspectedInstance.value = null;
    } else {
      inspectedInstance.value = t;
    }
  }
  function clearInspectedInstance(): void { inspectedInstance.value = null; }

  // Look up the pre-built inverted index for this object_id, then
  // overlay 'mutated' on top where applicable (mutation set is
  // per-call, small).
  const instanceAppearancesByCallId = computed<Map<string, AppearanceKind>>(() => {
    const out = new Map<string, AppearanceKind>();
    const inst = inspectedInstance.value;
    if (!inst) return out;
    const callIds = callIdsByObjectId.value.get(inst.objectId);
    if (callIds) {
      for (const callId of callIds) out.set(callId, 'appears');
    }
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

  function gotoAppearanceForCall(callId: string): void {
    const inst = inspectedInstance.value;
    if (!inst) return;
    const loc = findInstanceLocation(callId, inst.objectId);
    gotoAndSelect({
      callId,
      kind: loc?.kind || 'AR',
      path: loc?.path || []
    });
    highlightCallRow(callId);
  }

  function gotoAppearanceAt(index: number): void {
    const ids = orderedAppearanceCallIds.value;
    if (!ids.length) return;
    const wrapped = ((index % ids.length) + ids.length) % ids.length;
    gotoAppearanceForCall(ids[wrapped]);
  }

  function gotoNextAppearance(): void { gotoAppearanceAt(traceCursor.value + 1); }
  function gotoPrevAppearance(): void { gotoAppearanceAt(traceCursor.value - 1); }

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
