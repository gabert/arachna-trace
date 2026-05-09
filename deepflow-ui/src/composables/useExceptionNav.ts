import { computed } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import type { CallMeta, CallRow } from '../types';

// Exception navigator for the call-tree fixed header. Mirrors
// useInstanceTrace's shape so the two header bars can share the same
// label/count/↑↓ template pattern.
//
// "Exception" = any call whose RT is EXCEPTION (i.e. is_exception is
// truthy on the row). The nav doesn't differentiate throw site from
// propagation frame — every exception frame is a stop on the walk.
// Order is DFS pre-order via callMeta.pre, matching how the call tree
// is rendered.

export interface UseExceptionNavArgs {
  calls: Ref<CallRow[]>;
  callMeta: ComputedRef<Map<string, CallMeta>>;
  // Live read of the selected inspection card; drives the cursor so
  // clicking an exception row directly keeps the counter in sync, and
  // clicking a non-exception row resets it (cursor = -1 → next ↓
  // restarts at the first exception).
  selectedCallId: Ref<string | null>;
  // Side-effects for ↑/↓ stepping. Same pair the instance-trace uses:
  // open the call in the inspection pane and flash its row in the tree.
  selectCall: (callId: string) => void;
  highlightCallRow: (callId: string) => void;
}

export interface UseExceptionNav {
  exceptionCallIds: ComputedRef<string[]>;
  exceptionCount: ComputedRef<number>;
  exceptionCursor: ComputedRef<number>;
  gotoNextException: () => void;
  gotoPrevException: () => void;
}

export function useExceptionNav(args: UseExceptionNavArgs): UseExceptionNav {
  const { calls, callMeta, selectedCallId, selectCall, highlightCallRow } = args;

  const exceptionCallIds = computed<string[]>(() => {
    const meta = callMeta.value;
    return calls.value
      .filter(c => Boolean(c.is_exception))
      .map(c => c.call_id)
      .sort((a, b) => (meta.get(a)?.pre ?? 0) - (meta.get(b)?.pre ?? 0));
  });

  const exceptionCount = computed(() => exceptionCallIds.value.length);

  const exceptionCursor = computed<number>(() => {
    const id = selectedCallId.value;
    if (!id) return -1;
    return exceptionCallIds.value.indexOf(id);
  });

  function gotoExceptionAt(index: number): void {
    const ids = exceptionCallIds.value;
    if (!ids.length) return;
    const wrapped = ((index % ids.length) + ids.length) % ids.length;
    const callId = ids[wrapped];
    selectCall(callId);
    highlightCallRow(callId);
  }

  function gotoNextException(): void { gotoExceptionAt(exceptionCursor.value + 1); }
  function gotoPrevException(): void { gotoExceptionAt(exceptionCursor.value - 1); }

  return {
    exceptionCallIds,
    exceptionCount,
    exceptionCursor,
    gotoNextException,
    gotoPrevException,
  };
}
