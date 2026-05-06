import { ref } from 'vue';

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
export function useNavigator(parentByCallId) {
  const highlight = ref(null);
  const navTick = ref(0);

  const expansionDefault = ref(false);
  const expansionOverrides = ref(new Map());

  function goto({ callId, kind, path }) {
    // 1. Expand every ancestor of the target so its FrameCard is mounted
    //    by the time Vue settles. (Collapsed frames unmount their bodies,
    //    so without this the target's PayloadViewer wouldn't exist.)
    const next = new Map(expansionOverrides.value);
    let cur = callId;
    while (cur != null) {
      next.set(cur, true);
      cur = parentByCallId.value.get(cur);
    }
    expansionOverrides.value = next;

    // 2. Set the address. Vue's reactivity does the rest:
    //    - The target PayloadViewer (matching callId + kind) sees its
    //      local highlight flip non-null and expands the path's prefixes.
    //    - The matching JsonTree node sees its own pathKey === highlight
    //      and renders the flash class; its post-flush watcher scrolls
    //      it into view.
    //    - Any previously-matched node sees its match flip false and
    //      drops the flash class automatically.
    highlight.value = {
      callId,
      kind,
      pathKey: JSON.stringify(path || [])
    };
    navTick.value++;
  }

  function collapseAll() {
    expansionDefault.value = false;
    expansionOverrides.value = new Map();
  }

  function expandAll() {
    expansionDefault.value = true;
    expansionOverrides.value = new Map();
  }

  // Reset to a known state when the active request changes — caller
  // typically calls this from the watcher that loads new calls.
  function reset() {
    highlight.value = null;
    expansionOverrides.value = new Map();
    expansionDefault.value = false;
  }

  return {
    highlight,
    navTick,
    expansionDefault,
    expansionOverrides,
    goto,
    expandAll,
    collapseAll,
    reset
  };
}
