import { computed, ref } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import type { CallRow } from '../types';

export interface UseInspectionStackArgs {
  // Live read of the call set for the active request — used to map
  // ids back to CallRow for rendering. If a card's id falls out of
  // this map (e.g. request changed), it silently doesn't render;
  // the request-change watcher in the view should reset() the stack.
  callsById: ComputedRef<Map<string, CallRow>>;
}

// One inspection entry as the template iterates them. `transient` is
// true for the (at most one) browsing slot; false for explicitly
// pinned cards. The view uses this flag to swap header affordances
// (📌 pin button vs ⋮⋮ drag handle) and to enable/disable drag drop.
export interface InspectionEntry {
  call: CallRow;
  transient: boolean;
}

export interface UseInspectionStack {
  // Browsing slot — at most one card. Replaced by trace ↑/↓, the
  // FrameCard ↗ inspect chip, and tool-panel jumps. Pinning promotes
  // the transient into the pinned list (and clears the slot).
  transientCallId: Ref<string | null>;
  // Cards the user explicitly pinned. Order is insertion (newest at
  // top); only this list is drag-reorderable.
  pinnedCallIds: Ref<string[]>;
  // What the template iterates: transient first (if any), then pinned.
  // If a navigation targets a call that's already pinned, the
  // transient is suppressed so the same call doesn't render twice.
  inspectionEntries: ComputedRef<InspectionEntry[]>;
  collapsedCards: Ref<Set<string>>;

  // Currently-focused call. Drives CallInspectionCard focus and the
  // "selected" affordance on FrameCard rows. Independent of the
  // navigator highlight — focusing a call only sets which card is
  // visually active; jumping to a JSON path is a separate concern.
  selectedCallId: Ref<string | null>;
  setSelectedCallId: (id: string | null) => void;

  // Show a call in the transient slot. If the id is already pinned,
  // the transient stays cleared and the pinned card is just focused
  // (no duplicate render). Always uncollapses the target so a
  // navigation never lands on a hidden card body.
  showTransient: (id: string) => void;
  // Promote whatever's in the transient slot to the pinned list.
  // No-op when transient is null.
  pinCurrent: () => void;
  // Pin a specific id. If it's the transient, promotes it (and clears
  // the slot). If it's not in pinned yet, prepends. If it's already
  // pinned, no-op.
  pinCall: (id: string) => void;
  // Close either the transient or a pinned card by id.
  closeInspection: (id: string) => void;
  setCardCollapsed: (id: string, collapsed: boolean) => void;
  uncollapse: (id: string) => void;

  // Reset to a clean state — used by the view's request-change watcher.
  reset: () => void;

  // Drag-to-reorder state. Operates on pinnedCallIds only — indexes
  // are pinned-relative (the template iterates pinned in its own
  // v-for so `idx` matches pinnedCallIds directly). Native HTML5
  // dnd: handle on each card's header is draggable, every card body
  // is a drop target. dragOverIdx + dragOverPos drive the CSS class
  // on the target card for the drop indicator (line above or below).
  dragSourceIdx: Ref<number | null>;
  dragOverIdx: Ref<number | null>;
  dragOverPos: Ref<'before' | 'after'>;
  onCardDragStart: (idx: number, e: DragEvent) => void;
  onCardDragOver: (idx: number, e: DragEvent) => void;
  onCardDragLeave: (idx: number) => void;
  onCardDrop: (idx: number, e: DragEvent) => void;
  onCardDragEnd: () => void;
}

export function useInspectionStack(args: UseInspectionStackArgs): UseInspectionStack {
  const { callsById } = args;

  const transientCallId = ref<string | null>(null);
  const pinnedCallIds = ref<string[]>([]);
  const collapsedCards = ref<Set<string>>(new Set());
  const selectedCallId = ref<string | null>(null);

  function setSelectedCallId(id: string | null): void {
    selectedCallId.value = id;
  }

  function uncollapse(id: string): void {
    if (!collapsedCards.value.has(id)) return;
    const next = new Set(collapsedCards.value);
    next.delete(id);
    collapsedCards.value = next;
  }

  function showTransient(id: string): void {
    setSelectedCallId(id);
    uncollapse(id);
    if (pinnedCallIds.value.includes(id)) {
      // Already pinned — focus it, don't duplicate by also putting it
      // in the transient slot. Clears any previous transient.
      transientCallId.value = null;
      return;
    }
    transientCallId.value = id;
  }

  function pinCurrent(): void {
    const id = transientCallId.value;
    if (!id) return;
    if (!pinnedCallIds.value.includes(id)) {
      pinnedCallIds.value = [id, ...pinnedCallIds.value];
    }
    transientCallId.value = null;
  }

  function pinCall(id: string): void {
    if (transientCallId.value === id) {
      pinCurrent();
      return;
    }
    if (!pinnedCallIds.value.includes(id)) {
      pinnedCallIds.value = [id, ...pinnedCallIds.value];
    }
  }

  function closeInspection(id: string): void {
    if (transientCallId.value === id) {
      transientCallId.value = null;
    } else {
      pinnedCallIds.value = pinnedCallIds.value.filter(x => x !== id);
    }
    if (collapsedCards.value.has(id)) {
      const next = new Set(collapsedCards.value);
      next.delete(id);
      collapsedCards.value = next;
    }
    if (selectedCallId.value === id) setSelectedCallId(null);
  }

  function setCardCollapsed(id: string, collapsed: boolean): void {
    const next = new Set(collapsedCards.value);
    if (collapsed) next.add(id); else next.delete(id);
    collapsedCards.value = next;
  }

  function reset(): void {
    transientCallId.value = null;
    pinnedCallIds.value = [];
    collapsedCards.value = new Set();
    selectedCallId.value = null;
  }

  const inspectionEntries = computed<InspectionEntry[]>(() => {
    const map = callsById.value;
    const out: InspectionEntry[] = [];
    if (transientCallId.value) {
      const c = map.get(transientCallId.value);
      if (c) out.push({ call: c, transient: true });
    }
    for (const id of pinnedCallIds.value) {
      const c = map.get(id);
      if (c) out.push({ call: c, transient: false });
    }
    return out;
  });

  // Drag state — pinned-only. The template iterates pinned in its
  // own v-for so the idx passed in matches pinnedCallIds directly.
  const dragSourceIdx = ref<number | null>(null);
  const dragOverIdx = ref<number | null>(null);
  const dragOverPos = ref<'before' | 'after'>('before');

  function onCardDragStart(idx: number, e: DragEvent): void {
    dragSourceIdx.value = idx;
    if (e.dataTransfer) {
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', String(idx));
    }
  }
  function onCardDragOver(idx: number, e: DragEvent): void {
    if (dragSourceIdx.value === null) return;
    e.preventDefault();
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
    const target = e.currentTarget as HTMLElement | null;
    if (!target) return;
    const rect = target.getBoundingClientRect();
    const midY = rect.top + rect.height / 2;
    dragOverIdx.value = idx;
    dragOverPos.value = e.clientY < midY ? 'before' : 'after';
  }
  function onCardDragLeave(idx: number): void {
    if (dragOverIdx.value === idx) dragOverIdx.value = null;
  }
  function onCardDrop(idx: number, e: DragEvent): void {
    e.preventDefault();
    const from = dragSourceIdx.value;
    dragSourceIdx.value = null;
    dragOverIdx.value = null;
    if (from === null) return;
    let to = idx + (dragOverPos.value === 'after' ? 1 : 0);
    if (from < to) to -= 1;
    if (from === to) return;
    const next = [...pinnedCallIds.value];
    const [moved] = next.splice(from, 1);
    next.splice(to, 0, moved);
    pinnedCallIds.value = next;
  }
  function onCardDragEnd(): void {
    dragSourceIdx.value = null;
    dragOverIdx.value = null;
  }

  return {
    transientCallId,
    pinnedCallIds,
    inspectionEntries,
    collapsedCards,
    selectedCallId,
    setSelectedCallId,
    showTransient,
    pinCurrent,
    pinCall,
    closeInspection,
    setCardCollapsed,
    uncollapse,
    reset,
    dragSourceIdx,
    dragOverIdx,
    dragOverPos,
    onCardDragStart,
    onCardDragOver,
    onCardDragLeave,
    onCardDrop,
    onCardDragEnd
  };
}
