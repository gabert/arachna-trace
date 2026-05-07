import { ref } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import type { Highlight, JumpAddress } from '../types';

export interface UseNavigator {
  highlight: Ref<Highlight | null>;
  navTick: Ref<number>;
  expansionDefault: Ref<boolean>;
  expansionOverrides: Ref<Map<string, boolean>>;
  childrenExpandedOverrides: Ref<Map<string, boolean>>;
  goto: (addr: JumpAddress) => void;
  expandAll: () => void;
  collapseAll: () => void;
  reset: () => void;
}

// Owns the cross-pane navigator state.
//
// Design — read this before "improving" it:
//   One reactive `highlight` ref drives every navigation. Its shape is
//   { callId, kind, pathKey } (pathKey = JSON-stringified path array).
//   Each PayloadViewer compares its own (callId, kind) to the global
//   highlight; only the matching one forwards pathKey down to its
//   JsonTree subtree. The matching JsonTree node sees its own
//   pathKey === highlight.pathKey, flips its `isMatch`, renders the
//   flash class, and scrolls itself into view via a single watcher.
//
// `navTick` is bumped on every goto. JsonTree watches it so re-clicking
// the already-current row (or clicking when the target was mounted but
// pushed off-screen by later expansions) still re-scrolls. Without it,
// a same-address click is a no-op because isMatch never transitions.
//
// `expansionDefault` + `expansionOverrides` together give the call tree
// a tri-state expansion model:
//   - per-frame override wins if set (`overrides.get(callId)`);
//   - otherwise the default applies. expandAll/collapseAll flip the
//     default and clear overrides — newly-mounted children inherit the
//     last user intent, instead of always defaulting to "expanded".
export function useNavigator(
  parentByCallId: ComputedRef<Map<string, string>>
): UseNavigator {
  const highlight = ref<Highlight | null>(null);
  const navTick = ref(0);

  const expansionDefault = ref(false);
  const expansionOverrides = ref<Map<string, boolean>>(new Map());

  // Children-fold overrides default to "expanded" (true) when missing —
  // only explicit collapses are stored. See CHILDREN_EXPANDED_OVERRIDES.
  const childrenExpandedOverrides = ref<Map<string, boolean>>(new Map());

  function goto(addr: JumpAddress): void {
    // Expand every ancestor of the target so its FrameCard is mounted
    // by the time Vue settles, AND force-open every ancestor's
    // children-fold so the nested FrameCards render. (Collapsed frames
    // unmount their bodies; collapsed children-folds unmount the child
    // list — either path leaves the target unmounted otherwise.)
    const nextExp = new Map(expansionOverrides.value);
    const nextChildren = new Map(childrenExpandedOverrides.value);
    let cur: string | undefined = addr.callId;
    while (cur != null) {
      nextExp.set(cur, true);
      nextChildren.set(cur, true);
      cur = parentByCallId.value.get(cur);
    }
    expansionOverrides.value = nextExp;
    childrenExpandedOverrides.value = nextChildren;

    // Set the address. Vue's reactivity does the rest.
    highlight.value = {
      callId: addr.callId,
      kind: addr.kind,
      pathKey: JSON.stringify(addr.path || [])
    };
    navTick.value++;
  }

  function collapseAll(): void {
    expansionDefault.value = false;
    expansionOverrides.value = new Map();
    childrenExpandedOverrides.value = new Map();
  }

  function expandAll(): void {
    expansionDefault.value = true;
    expansionOverrides.value = new Map();
    childrenExpandedOverrides.value = new Map();
  }

  // Reset to a known state when the active request changes — caller
  // typically calls this from the watcher that loads new calls.
  function reset(): void {
    highlight.value = null;
    expansionOverrides.value = new Map();
    childrenExpandedOverrides.value = new Map();
    expansionDefault.value = false;
  }

  return {
    highlight,
    navTick,
    expansionDefault,
    expansionOverrides,
    childrenExpandedOverrides,
    goto,
    expandAll,
    collapseAll,
    reset
  };
}
