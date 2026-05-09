<script setup lang="ts">
// Left pane of the workspace: fixed header (exception nav + optional
// instance-trace bar) plus the FrameCard call tree below.
//
// The header is sticky-pinned to the top of the scrollable pane so the
// nav controls stay reachable as the developer scrolls deep into a
// long trace. Both bars share one opaque chrome so rows underneath
// can't bleed through the way they did when each bar was its own
// translucent strip.
//
// =====================================================================
// Public API (defineExpose'd to the parent ref)
// =====================================================================
//
// highlightCall(callId): single primitive for "navigate to this call
// in the tree". Three guarantees, all from one call:
//
//   1. BOX     — draw a yellow highlight outline around the row.
//                Persistent until the next highlightCall / clearHighlight
//                / request change.
//   2. EXPAND  — every collapsed ancestor of the target is forced
//                expanded so the target row is actually rendered.
//                The user's prior collapse choices on those ancestors
//                are overridden (necessary — you can't see a row
//                hidden inside a collapsed parent).
//   3. SCROLL  — the row is scrolled into the viewport if it isn't
//                already fully visible (block: 'center'). No-op when
//                the row is on screen.
//
// Intended as the one primitive any cross-pane "go to this call"
// feature reaches for: exception ↑/↓, instance-trace ↑/↓, future
// search jumps, etc. Cycling features just call this; box + expand +
// scroll all happen in lockstep.
//
// Implementation notes (so future edits don't accidentally split it):
//   * The box state and expansion writes happen here in the panel.
//   * The actual scroll lives on the FrameCard via
//     useScrollIntoViewOnHighlight (it owns the row's element ref).
//     The composable uses runOnMount: true to cover the case where
//     the target row mounts due to step (2) with isHighlighted
//     already true.
//   * clearHighlight() drops the BOX only — it does not collapse
//     ancestors. The user's reading position stays sticky.
//
// `selected` state on FrameCard is something else: persistent blue
// tint that tracks which inspection card on the right is focused.
// Highlight is the search-cursor pointer; selection is the opened
// document. Don't conflate them.

import { computed, inject, provide, ref } from 'vue';
import ProgressSpinner from 'primevue/progressspinner';
import FrameCard from './FrameCard.vue';
import NavOverlay from './NavOverlay.vue';
import { CALL_HIGHLIGHT, EXPANSION_OVERRIDES } from '../keys';
import type { CallRow, OriginTarget, TraceTarget, Watch } from '../types';

const props = defineProps<{
  rootCalls: CallRow[];
  callsLoading: boolean;
  hasNoCalls: boolean;
  // Child → parent map for the loaded request, used by highlightCall
  // to walk ancestors and auto-expand them when the cycle target sits
  // inside collapsed frames.
  parentByCallId: Map<string, string>;
  // Exception-nav state (always rendered). count=0 → green "no
  // exceptions in trace"; count>0 → red "<N> exceptions in trace"
  // with ↑/↓ enabled.
  exceptionCount: number;
  exceptionCursor: number;
  // Instance-trace banner state — second header row, only when an
  // instance is being traced.
  inspectedInstance: TraceTarget | null;
  inspectedShortClass: string;
  inspectedCount: number;
  traceCursor: number;
}>();

const emit = defineEmits<{
  (e: 'pin', payload: Watch): void;
  (e: 'origin', target: OriginTarget): void;
  (e: 'goto-prev-exception'): void;
  (e: 'goto-next-exception'): void;
  (e: 'goto-prev-appearance'): void;
  (e: 'goto-next-appearance'): void;
  (e: 'clear-instance'): void;
}>();

// Highlight state lives here. Cleared automatically when the trace
// target changes (the parent triggers it externally by calling
// highlightCall(null) or clearHighlight()), and on request change
// (the parent unmounts the FrameCards anyway via v-if path).
const highlightedCallId = ref<string | null>(null);
const highlightTick = ref(0);

provide(CALL_HIGHLIGHT, {
  callId: highlightedCallId,
  tick: highlightTick
});

// Per-row expansion overrides — used by highlightCall() to expand
// every ancestor of the highlight target. Same Ref the FrameCards
// read from via inject(EXPANSION_OVERRIDES); writing to it from here
// flips collapsed parents open in one reactive batch.
const expansionOverrides = inject(EXPANSION_OVERRIDES, ref(new Map<string, boolean>()));

// highlightCall — see file header for the full API contract.
// Steps applied here, in order:
//   (a) walk parentByCallId upward, force every ancestor expanded so
//       the target row will be rendered;
//   (b) set the highlight state, which drives the row's yellow box
//       (FrameCard's `.highlighted` class) and triggers
//       useScrollIntoViewOnHighlight to scroll the row into view.
// Tick bump covers re-highlight of the same call (the watcher needs a
// transition signal even when callId hasn't changed).
function highlightCall(callId: string | null): void {
  if (callId) {
    const next = new Map(expansionOverrides.value);
    let cursor: string | undefined = props.parentByCallId.get(callId);
    while (cursor) {
      next.set(cursor, true);
      cursor = props.parentByCallId.get(cursor);
    }
    expansionOverrides.value = next;
  }
  highlightedCallId.value = callId;
  highlightTick.value++;
}
// clearHighlight — drops the BOX only. Does not collapse ancestors.
// The user's reading position stays sticky.
function clearHighlight(): void {
  highlightedCallId.value = null;
}

defineExpose({ highlightCall, clearHighlight });

// Trace banner shows the inspected instance's class name + object id;
// keep the short-class / id formatting where it's used (template).
const traceObjectId = computed(() => props.inspectedInstance?.objectId ?? null);
</script>

<template>
  <div class="ctp">
    <!-- Header for the call-tree panel. Structurally always present
         (semantic <header>), but visually invisible: no bg, no border,
         no padding of its own. Hosts the two cycle-nav overlay chips
         (exception + instance trace) which stack vertically and
         render as floating bars over the call tree. The wrapper
         disappears entirely (v-if) when both chips are hidden, so the
         tree starts flush with the top of the panel. -->
    <header v-if="exceptionCount > 0 || inspectedInstance" class="ctp-header">
      <NavOverlay v-if="exceptionCount > 0"
                  variant="exception"
                  :count="exceptionCount"
                  :cursor="exceptionCursor"
                  itemSingular="exception"
                  prevTitle="Previous exception"
                  nextTitle="Next exception"
                  @prev="emit('goto-prev-exception')"
                  @next="emit('goto-next-exception')">
        <span class="ov-icon">⚠</span>
        <span>exceptions</span>
      </NavOverlay>

      <NavOverlay v-if="inspectedInstance"
                  variant="trace"
                  :count="inspectedCount"
                  :cursor="traceCursor"
                  itemSingular="appearance"
                  showClear
                  prevTitle="Previous occurrence"
                  nextTitle="Next occurrence"
                  clearTitle="Clear trace"
                  @prev="emit('goto-prev-appearance')"
                  @next="emit('goto-next-appearance')"
                  @clear="emit('clear-instance')">
        <span class="ov-icon">🔎</span>
        <code class="ov-target">{{ inspectedShortClass }} #{{ traceObjectId }}</code>
      </NavOverlay>
    </header>

    <div v-if="callsLoading" class="centered">
      <ProgressSpinner style="width:2rem;height:2rem" />
    </div>

    <ol v-else class="recording" :start="1">
      <FrameCard v-for="call in rootCalls"
                 :key="call.call_id"
                 :call="call"
                 @pin="(p) => emit('pin', p)"
                 @origin="(t) => emit('origin', t)" />
    </ol>

    <p v-if="hasNoCalls && !callsLoading" class="muted centered">
      no calls in this request
    </p>
  </div>
</template>

<style scoped>
.ctp { height: 100%; }
.recording { list-style: none; padding: 0; margin: 0; }
.muted { color: var(--text-muted); font-size: 0.85rem; }
.centered { display: flex; justify-content: center; padding: 2rem; }

/* Call-tree header — semantic structural container for the cycle-nav
   overlay chips. Visually invisible: no background, no border, no
   padding of its own; the chips inside carry all the visual
   treatment. Sticky at the top of the scrollable pane so the chips
   stay reachable while scrolling deep into a long trace, and the
   tree below scrolls cleanly underneath them. Takes its natural
   vertical layout space at the top of the panel — does NOT overlay
   the first rows. */
.ctp-header {
  position: sticky;
  top: 0;
  z-index: 5;
  padding: 0.5rem 0.75rem;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 0.5rem;
}

/* Inline content slotted into the overlay's label slot. */
.ov-icon { font-size: 0.9rem; }
.ov-target {
  font-family: ui-monospace, monospace;
  color: var(--text-primary);
  background: rgba(0, 0, 0, 0.25);
  padding: 0.05rem 0.4rem;
  border-radius: 3px;
  font-size: 0.78rem;
}
</style>
