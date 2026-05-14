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
//                Persistent until the next highlightCall / request change.
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
//   * The box state lives in SessionDetailView (CALL_HIGHLIGHT provide).
//     The expansion writes happen here in the panel.
//   * The actual scroll lives on the FrameCard via
//     useScrollIntoViewOnHighlight (it owns the row's element ref).
//     The composable uses runOnMount: true to cover the case where
//     the target row mounts due to step (2) with isHighlighted
//     already true.
//
// Highlight is the unified "focus marker" — one yellow box at a
// time, representing whichever row is currently focused. Every focus
// path routes through highlightCall: manual row clicks (via
// CALL_SELECTION.select on SessionDetailView), trace ↑/↓, exception
// ↑/↓, "reveal in tree" from cards, watch goto. There is no separate
// "selected blue" tint anymore — the previous two-visual model was
// confusing in practice (hover blue / select darker blue / nav
// yellow read as three different concepts when really only two
// matter: hover and focus).

import { computed, inject, ref, watch } from 'vue';
import ProgressSpinner from 'primevue/progressspinner';
import RequestNode from './RequestNode.vue';
import NavOverlay from './NavOverlay.vue';
import { CALL_HIGHLIGHT, EXPANSION_OVERRIDES } from '../keys';
import type { CallRow, OriginTarget, RequestRow, TraceTarget, Watch } from '../types';

const props = defineProps<{
  // Session-wide tree shape
  requests: RequestRow[];
  rootCallsByRequestId: Map<number, CallRow[]>;
  // True while the request inventory is loading; per-request call
  // trees load lazily and surface their own spinners inside each
  // RequestNode.
  requestsLoading: boolean;
  hasNoRequests: boolean;
  // Child → parent map and per-call request id, used by highlightCall
  // to walk ancestors + auto-expand the containing request node.
  parentByCallId: Map<string, string>;
  requestIdByCallId: Map<string, number>;
  // Instance-trace banner state — only when an instance is being
  // traced. Lives here because it's a "currently active tool" signal
  // tied to the call tree. Exception nav was moved up to the
  // workspace header (it's session-scoped, the panel was overloaded
  // when both showed at once).
  inspectedInstance: TraceTarget | null;
  inspectedShortClass: string;
  inspectedCount: number;
  traceCursor: number;
}>();

const emit = defineEmits<{
  (e: 'pin', payload: Watch): void;
  (e: 'origin', target: OriginTarget): void;
  (e: 'goto-prev-appearance'): void;
  (e: 'goto-next-appearance'): void;
  (e: 'clear-instance'): void;
  (e: 'expand-all'): void;
  (e: 'collapse-all'): void;
}>();

// Highlight state lives one level up in SessionDetailView so both
// this pane (the call tree) and the inspection-card pane on the right
// inject from the same source — that's what visually pairs the
// focused row with its matching CallInspectionCard. We inject the
// refs here and write through them; consumers (FrameCard,
// CallInspectionCard) read them via inject(CALL_HIGHLIGHT).
const callHighlight = inject(CALL_HIGHLIGHT, {
  callId: ref<string | null>(null),
  tick: ref(0)
});

// Per-row expansion overrides — used by highlightCall() to expand
// every ancestor of the highlight target. Same Ref the FrameCards
// read from via inject(EXPANSION_OVERRIDES); writing to it from here
// flips collapsed parents open in one reactive batch.
const expansionOverrides = inject(EXPANSION_OVERRIDES, ref(new Map<string, boolean>()));

// Request-level expansion state. Default: only the latest request
// (last in the list — server orders ASC by first_call) is expanded;
// older requests render as collapsed headers in the tree. Lazy-mount
// keeps the cost of "show me the whole session" bounded by what the
// developer has actually opened.
const expandedRequests = ref<Set<number>>(new Set());
watch(() => props.requests, (reqs) => {
  if (!reqs.length) { expandedRequests.value = new Set(); return; }
  const latest = Number(reqs[reqs.length - 1].request_id);
  expandedRequests.value = new Set([latest]);
}, { immediate: true });

function toggleRequest(rid: number): void {
  const next = new Set(expandedRequests.value);
  if (next.has(rid)) next.delete(rid); else next.add(rid);
  expandedRequests.value = next;
}

// Bulk request-level expansion. Wired to the session view's
// "expand all" / "collapse all" buttons so a single click affects
// both the call-level FrameCards (via the navigator) AND every
// request node in the tree, not just whichever request happened to
// be open at the time.
function expandAllRequests(): void {
  expandedRequests.value = new Set(props.requests.map(r => Number(r.request_id)));
}
function collapseAllRequests(): void {
  expandedRequests.value = new Set();
}

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
    // Also expand the request node that contains this call. With the
    // session-wide tree, the target may live inside a collapsed
    // request — walk up + expand the request makes the row reachable.
    const reqId = props.requestIdByCallId.get(callId);
    if (reqId !== undefined && !expandedRequests.value.has(reqId)) {
      const reqs = new Set(expandedRequests.value);
      reqs.add(reqId);
      expandedRequests.value = reqs;
    }
  }
  callHighlight.callId.value = callId;
  callHighlight.tick.value++;
}

defineExpose({ highlightCall, expandAllRequests, collapseAllRequests });

// Trace banner shows the inspected instance's class name + object id;
// keep the short-class / id formatting where it's used (template).
const traceObjectId = computed(() => props.inspectedInstance?.objectId ?? null);
</script>

<template>
  <div class="ctp">
    <!-- Sticky panel header. Tree toolbar on the left (always — the
         expand/collapse-all buttons live with the tree they control),
         trace banner on the right (when an instance is being traced
         it grows to fill the remaining width). When no trace is
         active, only the toolbar shows. -->
    <header class="ctp-header">
      <div class="ctp-toolbar">
        <button class="tree-btn" @click="emit('expand-all')"
                title="Expand every request and frame">expand all</button>
        <button class="tree-btn" @click="emit('collapse-all')"
                title="Collapse every request and frame">collapse all</button>
      </div>
      <NavOverlay v-if="inspectedInstance"
                  class="ctp-trace-overlay"
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

    <div v-if="requestsLoading" class="centered">
      <ProgressSpinner style="width:2rem;height:2rem" />
    </div>

    <ol v-else class="recording">
      <RequestNode v-for="req in requests"
                   :key="req.request_id"
                   :request="req"
                   :rootCalls="rootCallsByRequestId.get(Number(req.request_id)) || []"
                   :expanded="expandedRequests.has(Number(req.request_id))"
                   @toggle="toggleRequest(Number(req.request_id))"
                   @pin="(p) => emit('pin', p)"
                   @origin="(t) => emit('origin', t)" />
    </ol>

    <p v-if="hasNoRequests && !requestsLoading" class="muted centered">
      no requests in this session
    </p>
  </div>
</template>

<style scoped>
.ctp { height: 100%; overflow-y: auto; }
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
  flex-direction: row;
  align-items: center;
  gap: 0.5rem;
  background: var(--bg-surface);
}

/* Tree toolbar — small horizontal strip with the expand/collapse-all
   controls. Always-on, on the left. Lives next to the tree it
   operates on; the workspace header is reserved for session-level
   state (title, exception nav). */
.ctp-toolbar {
  display: flex;
  gap: 0.4rem;
  flex-shrink: 0;
}

/* Trace overlay — grows into the remaining row width so its label /
   count / buttons get all the room they can. Only renders when an
   instance is being traced. */
.ctp-trace-overlay { flex: 1; min-width: 0; }
.tree-btn {
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  color: var(--text-secondary);
  font-size: 0.75rem;
  padding: 0.3rem 0.55rem;
  border-radius: 4px;
  cursor: pointer;
  font-family: inherit;
}
.tree-btn:hover { background: var(--bg-hover); color: var(--text-primary); }

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
