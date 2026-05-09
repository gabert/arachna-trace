<script setup lang="ts">
import { computed, provide, ref, toRef, watch } from 'vue';
import Message from 'primevue/message';
import Splitter from 'primevue/splitter';
import SplitterPanel from 'primevue/splitterpanel';
import CallTreePanel from '../components/CallTreePanel.vue';
import CallInspectionCard from '../components/CallInspectionCard.vue';
import WatchPanel from '../components/WatchPanel.vue';
import MutationsPanel from '../components/MutationsPanel.vue';
import OriginPanel from '../components/OriginPanel.vue';
import SearchPanel from '../components/SearchPanel.vue';
import { useSessionData } from '../composables/useSessionData';
import { useNavigator } from '../composables/useNavigator';
import { useObjectChanges } from '../composables/useObjectChanges';
import { useProvenance } from '../composables/useProvenance';
import { useValueSearch } from '../composables/useValueSearch';
import { useInspectionStack } from '../composables/useInspectionStack';
import { useInstanceTrace } from '../composables/useInstanceTrace';
import { useExceptionNav } from '../composables/useExceptionNav';
import { useToolStrip, type ToolId } from '../composables/useToolStrip';
import {
  PAYLOADS_BY_CALL_ID, SESSION_PAYLOADS, SESSION_TREE_LOADER,
  CHILDREN_BY_PARENT,
  EXPANSION_DEFAULT, EXPANSION_OVERRIDES,
  MUTATED_OBJECTS_BY_CALL_ID, ADDED_OBJECTS_BY_CALL_ID,
  HIGHLIGHT, NAV_TICK,
  CALL_SELECTION, INSTANCE_TRACE, EXCEPTION_NAV
} from '../keys';
import type { CallRow, JumpAddress, OriginTarget, Watch } from '../types';

const props = defineProps<{ sessionId: string }>();

const sessionIdRef = toRef(props, 'sessionId');

const {
  requests, calls,
  loadingRequests, error,
  payloadsByCallId, loadingCallIds,
  acquireCallPayloads, releaseCallPayloads,
  loadingRequestIds, isRequestLoaded, loadRequestCalls, ensureRequestsLoaded,
  childrenByParent, rootCallsByRequestId, parentByCallId, requestIdByCallId, callMeta
} = useSessionData(sessionIdRef);

// Flat list of currently-loaded payloads. Only contains entries for
// call_ids whose payloads are in cache (driven by mounted cards), not
// session-wide. Watch / origin panels degrade accordingly — the right
// long-term fix is server-side endpoints for both.
const parsedPayloads = computed(() => {
  const out = [];
  for (const arr of payloadsByCallId.value.values()) {
    for (const p of arr) out.push(p);
  }
  return out;
});

// Derived "currently active request" — used by tools that scope per
// request (mutations, origin, search). Follows the selected
// inspection card; clicking a call from a different request changes
// the scope. Null until the user opens a card.
const selectedRequestId = ref<number | null>(null);

const {
  highlight, navTick, expansionDefault, expansionOverrides,
  goto, expandAll, collapseAll, reset: resetNavigator
} = useNavigator(parentByCallId);

const {
  loading: loadingMutations,
  error: mutationsError,
  groups: mutationGroups,
  summary: mutationsSummary,
  mutatedObjectsByCallId,
  addedObjectsByCallId
} = useObjectChanges(sessionIdRef);

const provenance = useProvenance(parsedPayloads, callMeta, sessionIdRef, ensureRequestsLoaded);
const valueSearch = useValueSearch(sessionIdRef, selectedRequestId);

// Right-pane card stack. Owns inspectionCallIds/collapsedCards/drag
// state and the selectedCallId that pairs with it (the "selected"
// affordance on FrameCard rows mirrors which card is focused).
const callsById = computed<Map<string, CallRow>>(() => {
  const m = new Map<string, CallRow>();
  for (const c of calls.value) m.set(c.call_id, c);
  return m;
});
const stack = useInspectionStack({ callsById });

// Template-side helpers — splitting transientCallId / pinnedCallIds
// into resolved CallRow objects keeps the template free of repeated
// callsById lookups and lets the v-for over pinned use idx as a
// pinned-relative index for the drag handlers.
const transientCard = computed<CallRow | null>(() => {
  const id = stack.transientCallId.value;
  if (!id) return null;
  return callsById.value.get(id) || null;
});
const pinnedCalls = computed<CallRow[]>(() => {
  const map = callsById.value;
  const out: CallRow[] = [];
  for (const id of stack.pinnedCallIds.value) {
    const c = map.get(id);
    if (c) out.push(c);
  }
  return out;
});

// Transient-card event helpers. The inline arrow form
//   `(v) => stack.setCardCollapsed(transientCard.call_id, v)`
// loses vue-tsc's v-if narrowing inside the closure, so use stable
// wrappers that re-read transientCallId.
function onTransientSetCollapsed(v: boolean): void {
  const id = stack.transientCallId.value;
  if (id) stack.setCardCollapsed(id, v);
}
function onTransientClose(): void {
  const id = stack.transientCallId.value;
  if (id) stack.closeInspection(id);
}

// Ref to the call-tree panel so trace nav can flash + scroll a row in
// addition to opening its inspection card. Created up front because
// useInstanceTrace closes over highlightCallRow below.
const callTreeRef = ref<InstanceType<typeof CallTreePanel> | null>(null);

// Cross-pane jump used by trace nav AND the tool panels. Composables
// (instance trace below) only need the gotoAndSelect handle, but they
// need it to exist before they're constructed — so define it first.
//
// Goes through showTransient — navigations replace the browsing slot
// rather than piling cards onto the right pane. The user pins
// (📌 button on the transient card's header) to promote a card out of
// the slot when they want to keep it.
// Cross-pane jump. Async because the target call may live in a
// not-yet-loaded request — we ensure-load before opening the card so
// callsById can resolve the row. JumpAddress optionally carries the
// requestId; we fall back to requestIdByCallId for already-loaded
// calls (no load needed).
async function gotoAndSelect(addr: JumpAddress): Promise<void> {
  const rid = addr.requestId ?? requestIdByCallId.value.get(addr.callId);
  if (rid !== undefined && !isRequestLoaded(rid)) {
    await loadRequestCalls(rid);
  }
  stack.showTransient(addr.callId);
  goto(addr);
}

const trace = useInstanceTrace({
  sessionId: sessionIdRef,
  payloadsByCallId,
  mutatedObjectsByCallId,
  callMeta,
  selectedCallId: stack.selectedCallId,
  gotoAndSelect,
  highlightCallRow: (callId) => callTreeRef.value?.highlightCall(callId),
  acquireCallPayloads,
  releaseCallPayloads,
  ensureRequestLoaded: loadRequestCalls
});

// Manual row-click handler (FrameCard's ↗ inspect chip). Shows the
// call in the transient browsing slot and — when an instance is being
// traced AND this call has the instance — auto-navigates the card's
// PayloadViewer to the instance's path so the developer doesn't have
// to hunt for it among TI / AR / AX / RE. With lazy payload loading
// the path lookup has to wait for the call's payloads to land in the
// cache. The card itself acquires on mount, so our extra acquire
// here only deduplicates the fetch and keeps the cache populated
// across the await; we release once the card has had a chance to
// take its own ref.
async function selectCall(id: string): Promise<void> {
  stack.showTransient(id);
  const inst = trace.inspectedInstance.value;
  if (!inst) return;
  if (!trace.instanceAppearancesByCallId.value.has(id)) return;
  await acquireCallPayloads(id);
  try {
    const loc = trace.findInstanceLocation(id, inst.objectId);
    if (loc) goto({ callId: id, kind: loc.kind, path: loc.path });
  } finally {
    releaseCallPayloads(id);
  }
}

// Exception navigator. Backed by the server's exception-calls
// endpoint (the call list isn't a session-wide local array anymore;
// calls load lazily per request). Each exception entry carries its
// request_id so cycle nav loads the target's request before
// highlighting.
const exceptionNav = useExceptionNav({
  sessionId: sessionIdRef,
  selectedCallId: stack.selectedCallId,
  selectCall,
  highlightCallRow: (callId) => callTreeRef.value?.highlightCall(callId),
  ensureRequestLoaded: loadRequestCalls
});

// Watch model. Local to this view because it scopes to one
// (session, request) — moving requests should clear watches.
const watches = ref<Watch[]>([]);

function watchKey(w: Watch): string {
  if (w.kind === 'field') return `field:${w.objectId}:${(w.fieldPath || []).join('.')}`;
  return `instance:${w.objectId}`;
}

function pinWatch(w: Watch | null | undefined): void {
  if (!w || w.objectId == null) return;
  const key = watchKey(w);
  if (!watches.value.some(x => watchKey(x) === key)) {
    watches.value = [...watches.value, w];
  }
  // Surface the panel that just gained content. Even on duplicate
  // pins, so the click never feels like a silent no-op.
  toolStrip.setActiveTool('watch');
}

function removeWatch(idx: number): void {
  watches.value = watches.value.filter((_, i) => i !== idx);
}

function setOrigin(t: OriginTarget): void {
  provenance.setTarget(t);
  toolStrip.setActiveTool('origin');
}

const toolBadges = computed<Record<ToolId, number>>(() => ({
  mutations: mutationGroups.value.length,
  watch:     watches.value.length,
  origin:    provenance.target.value ? 1 : 0,
  search:    valueSearch.hits.value.length
}));
const toolStrip = useToolStrip({ badges: toolBadges });

provide(PAYLOADS_BY_CALL_ID, payloadsByCallId);
provide(SESSION_PAYLOADS, {
  loadingCallIds,
  acquire: acquireCallPayloads,
  release: releaseCallPayloads
});
provide(SESSION_TREE_LOADER, {
  loadingRequestIds,
  isLoaded: isRequestLoaded,
  load: loadRequestCalls,
  loadAll: ensureRequestsLoaded
});
provide(CHILDREN_BY_PARENT, childrenByParent);
provide(EXPANSION_DEFAULT, expansionDefault);
provide(EXPANSION_OVERRIDES, expansionOverrides);
provide(MUTATED_OBJECTS_BY_CALL_ID, mutatedObjectsByCallId);
provide(ADDED_OBJECTS_BY_CALL_ID, addedObjectsByCallId);
provide(HIGHLIGHT, highlight);
provide(NAV_TICK, navTick);
provide(CALL_SELECTION, {
  selectedId: stack.selectedCallId,
  select: selectCall
});
provide(INSTANCE_TRACE, {
  instance: trace.inspectedInstance,
  appearances: trace.instanceAppearancesByCallId,
  navigateTo: trace.gotoAppearanceForCall
});
provide(EXCEPTION_NAV, {
  active: computed(() => exceptionNav.exceptionCount.value > 0),
  navigateTo: exceptionNav.gotoException
});

// Update the derived selectedRequestId whenever the focused
// inspection card changes. Drives the per-request tools.
watch(stack.selectedCallId, (id) => {
  if (!id) { selectedRequestId.value = null; return; }
  selectedRequestId.value = requestIdByCallId.value.get(id) ?? null;
});

// Header buttons fan out to BOTH layers — call-level expansion
// (via the navigator) AND request-level expansion (via the panel).
// Without the request-level half, a "collapse all" only affects
// FrameCards inside the one request that happened to be open.
function expandEverything(): void {
  expandAll();
  callTreeRef.value?.expandAllRequests();
}
function collapseEverything(): void {
  collapseAll();
  callTreeRef.value?.collapseAllRequests();
}

// Reset navigator + watches + origin + inspection whenever the
// session itself changes — old state belongs to the old data and
// the call_ids don't survive the session swap. (Per-request resets
// are gone: with the session-wide tree, the user navigates freely
// across requests and state should follow them, not get clobbered.)
watch(sessionIdRef, () => {
  watches.value = [];
  stack.reset();
  trace.clearInspectedInstance();
  provenance.clear();
  resetNavigator();
});

// Clearing the trace target also clears the call-tree highlight so a
// stale "you are here" outline doesn't linger on a row that no longer
// has any reason to be highlighted.
watch(trace.inspectedInstance, (v) => {
  if (v == null) callTreeRef.value?.clearHighlight();
});
</script>

<template>
  <section class="session-view">
    <header class="sv-head">
      <h1 class="session-title">
        <span class="session-label">session</span>
        <code>{{ sessionId }}</code>
      </h1>
      <div class="req-pick">
        <button class="tree-btn" @click="expandEverything" title="Expand every request and frame">expand all</button>
        <button class="tree-btn" @click="collapseEverything" title="Collapse every request and frame">collapse all</button>
      </div>
    </header>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <div class="workspace-row" :class="{ 'has-tool-divider': toolStrip.activeTool.value != null }">
      <Splitter class="workspace" stateKey="deepflow-session-splitter-v2" stateStorage="local">
        <SplitterPanel :size="50" :minSize="25" class="left-pane">
          <div class="panel-window">
            <CallTreePanel ref="callTreeRef"
                           :requests="requests"
                           :rootCallsByRequestId="rootCallsByRequestId"
                           :requestsLoading="loadingRequests"
                           :hasNoRequests="!requests.length && !loadingRequests"
                           :parentByCallId="parentByCallId"
                           :requestIdByCallId="requestIdByCallId"
                           :exceptionCount="exceptionNav.exceptionCount.value"
                           :exceptionCursor="exceptionNav.exceptionCursor.value"
                           :inspectedInstance="trace.inspectedInstance.value"
                           :inspectedShortClass="trace.inspectedShortClass.value"
                           :inspectedCount="trace.inspectedCount.value"
                           :traceCursor="trace.traceCursor.value"
                           @pin="pinWatch"
                           @origin="setOrigin"
                           @goto-prev-exception="exceptionNav.gotoPrevException"
                           @goto-next-exception="exceptionNav.gotoNextException"
                           @goto-prev-appearance="trace.gotoPrevAppearance"
                           @goto-next-appearance="trace.gotoNextAppearance"
                           @clear-instance="trace.clearInspectedInstance" />
          </div>
        </SplitterPanel>

        <SplitterPanel :size="50" :minSize="20" class="inspection-pane">
          <div class="panel-window inspection-window">
            <div class="inspection-area">
            <!-- Transient slot — at most one card. No drag handle, no
                 drag-drop class flags; lives above the pinned list and
                 gets replaced by the next nav. The 📌 button on its
                 header promotes it into the pinned list below. -->
            <CallInspectionCard v-if="transientCard"
                                :key="transientCard.call_id"
                                :call="transientCard"
                                :transient="true"
                                :collapsed="stack.collapsedCards.value.has(transientCard.call_id)"
                                @pin="pinWatch"
                                @origin="setOrigin"
                                @trace="trace.setInspectedInstance"
                                @close="onTransientClose"
                                @set-collapsed="onTransientSetCollapsed"
                                @pin-card="stack.pinCurrent" />

            <!-- Pinned cards — drag-reorderable. idx is pinned-relative
                 so the drag handlers see their own array indexes. -->
            <CallInspectionCard v-for="(c, idx) in pinnedCalls"
                                :key="c.call_id"
                                :call="c"
                                :transient="false"
                                :collapsed="stack.collapsedCards.value.has(c.call_id)"
                                :class="{
                                  'drop-before': stack.dragOverIdx.value === idx && stack.dragOverPos.value === 'before',
                                  'drop-after':  stack.dragOverIdx.value === idx && stack.dragOverPos.value === 'after',
                                  'dragging':    stack.dragSourceIdx.value === idx
                                }"
                                @pin="pinWatch"
                                @origin="setOrigin"
                                @trace="trace.setInspectedInstance"
                                @close="stack.closeInspection(c.call_id)"
                                @set-collapsed="(v) => stack.setCardCollapsed(c.call_id, v)"
                                @handle-drag-start="stack.onCardDragStart(idx, $event)"
                                @dragover="stack.onCardDragOver(idx, $event)"
                                @dragleave="stack.onCardDragLeave(idx)"
                                @drop="stack.onCardDrop(idx, $event)"
                                @dragend="stack.onCardDragEnd" />

            <div v-if="!transientCard && !pinnedCalls.length" class="inspection-placeholder">
              <p>Click <span class="inline-inspect-chip" aria-hidden="true">↗</span> on a call in the tree to open its TI / AR / AX / RE.</p>
              <p class="hint">A new card replaces the previous one as you browse. Click <code>📌</code> on its header to keep it; pinned cards stack below and are drag-reorderable.</p>
            </div>
            </div>
          </div>
        </SplitterPanel>
      </Splitter>

      <!-- Resize divider between inspection and tool windows. Same
           visual + dash as the splitter gutter between tree and
           inspection. Only rendered when a tool is active — when
           tool window is collapsed (icons-only), the workspace-row's
           default gap provides the visual separator instead. -->
      <div v-if="toolStrip.activeTool.value != null"
           class="tool-divider"
           title="Drag to resize"
           @mousedown.prevent="toolStrip.onResizeStart"></div>

      <!-- Tool strip — third "window" in the workspace row. Always-
           visible icon column on the right edge plus a content panel
           that opens when a tool is active. Shares the .panel-window
           rounded chrome with the call-tree and inspection panels so
           the three read as a single layout. -->
      <aside class="tool-strip panel-window" :class="{ expanded: toolStrip.activeTool.value != null }">
        <section v-if="toolStrip.activeTool.value != null"
                 class="tool-content"
                 :style="{ width: toolStrip.toolWidth.value + 'px' }">
          <header class="tool-header">
            <span class="tool-title">{{ toolStrip.activeToolLabel.value }}</span>
            <button class="tool-close" @click="toolStrip.setActiveTool(null)" title="Collapse">×</button>
          </header>
          <div class="tool-body">
            <!-- Tools all operate session-wide now: mutations and search
                 hit indexed CH endpoints with no request_id; origin
                 follows values across requests. They render meaningful
                 content from the moment the session loads, not just
                 once a card is opened. -->
            <MutationsPanel v-show="toolStrip.activeTool.value === 'mutations'"
                            :groups="mutationGroups"
                            :summary="mutationsSummary"
                            :loading="loadingMutations"
                            :error="mutationsError"
                            @jump="gotoAndSelect" />
            <WatchPanel v-show="toolStrip.activeTool.value === 'watch'"
                        :watches="watches"
                        :sessionId="sessionId"
                        :callMeta="callMeta"
                        @remove="removeWatch"
                        @jump="gotoAndSelect" />
            <OriginPanel v-show="toolStrip.activeTool.value === 'origin'"
                         :provenance="provenance"
                         @jump="gotoAndSelect" />
            <SearchPanel v-show="toolStrip.activeTool.value === 'search'"
                         :search="valueSearch"
                         :active="toolStrip.activeTool.value === 'search'"
                         @jump="gotoAndSelect" />
          </div>
        </section>

        <nav class="tool-icons">
          <button v-for="id in toolStrip.toolIds"
                  :key="id"
                  class="tool-icon"
                  :class="{ active: toolStrip.activeTool.value === id }"
                  :title="toolStrip.toolMeta[id].label"
                  @click="toolStrip.toggleTool(id)">
            <span class="tool-glyph">{{ toolStrip.toolMeta[id].icon }}</span>
            <span class="tool-label">{{ toolStrip.toolMeta[id].label }}</span>
            <span v-if="toolStrip.toolBadge.value[id]" class="tool-badge">{{ toolStrip.toolBadge.value[id] }}</span>
          </button>
        </nav>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.session-view { display: flex; flex-direction: column; height: 100%; min-height: 0; background: var(--bg-base); }

.sv-head {
  display: flex; align-items: center; gap: 1.5rem;
  padding: 0.5rem 1rem; border-bottom: 1px solid var(--border); flex-shrink: 0;
  background: var(--bg-surface);
}
.session-title {
  margin: 0;
  display: inline-flex;
  align-items: baseline;
  gap: 0.5rem;
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-secondary);
}
.session-title code {
  font-family: ui-monospace, monospace;
  color: var(--text-primary);
  font-weight: 400;
}
.session-label {
  color: var(--text-muted);
  text-transform: uppercase;
  font-size: 0.7rem;
  letter-spacing: 0.04em;
  font-weight: 600;
  font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
}
.req-pick { display: flex; align-items: center; gap: 0.5rem; }
.req-pick label { font-size: 0.85rem; color: var(--text-secondary); }
.tree-btn {
  background: var(--bg-elevated); border: 1px solid var(--border-strong); color: var(--text-secondary);
  font-size: 0.75rem; padding: 0.3rem 0.55rem; border-radius: 4px; cursor: pointer;
}
.tree-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
.muted { color: var(--text-muted); font-size: 0.8rem; }
.centered { display: flex; justify-content: center; padding: 2rem; }

/* Workspace row: three rounded "window" panels — call tree,
   inspection, tools — separated by visible gaps. The Splitter handles
   resize for the first two; the tool strip uses its own drag handle.
   Padding around the row gives the windows a visible margin from the
   viewport edge; gap between Splitter and tool-strip matches the
   splitter gutter width so the spacing reads uniform. */
.workspace-row {
  flex: 1;
  display: flex;
  gap: 0.5rem;
  padding: 0.5rem;
  min-height: 0;
  overflow: hidden;
  background: var(--bg-base);
}
/* When the tool window is expanded the .tool-divider element provides
   the visual separator (with dash + resize), so the row's flex gap
   between Splitter and tool-strip would double the spacing — drop it. */
.workspace-row.has-tool-divider { gap: 0; }

/* Tool divider — sibling between Splitter and tool-strip; matches the
   PrimeVue splitter gutter visual (transparent column, short dash in
   the centre, accent on hover). Drives the same onResizeStart used by
   the previous in-panel handle. */
.tool-divider {
  width: 0.5rem;
  flex-shrink: 0;
  position: relative;
  cursor: col-resize;
}
.tool-divider::before {
  content: '';
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 4px;
  height: 24px;
  background: var(--text-muted);
  border-radius: 2px;
}
.tool-divider:hover::before,
.tool-divider:active::before { background: var(--accent-blue); }
.workspace-row .workspace {
  flex: 1;
  min-width: 0;
}
/* Splitter is just a flex slot for the two .panel-window children; it
   must not draw any chrome of its own (no bg, no border, no rounded
   corners) — otherwise the tree+inspection pair reads as a grouped
   box and the third "window" (tools) looks orphaned. Needs :deep
   because .workspace is the PrimeVue-rendered root, not in this
   component's scope. */
.workspace-row :deep(.workspace),
.workspace-row :deep(.p-splitter),
.workspace-row :deep(.p-splitter-panel) {
  background: transparent !important;
  border: 0 !important;
  border-radius: 0 !important;
  box-shadow: none !important;
}

/* Each panel — tree, inspection, tools — wraps its content in a
   .panel-window so the three areas read as separate rounded windows.
   Visual chrome only; flex layout inside is up to each consumer
   (tree/inspection use column, tool-strip overrides to row). */
.panel-window {
  height: 100%;
  background: var(--bg-surface);
  border: 1px solid var(--border-strong);
  border-radius: 8px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
}
.panel-window > * { min-height: 0; }

/* SplitterPanel padding/overflow overrides — the global app.css gave
   them their own bg + scroll; with .panel-window inside, the panel-
   window owns those concerns and the SplitterPanel becomes a
   transparent flex slot. */
:deep(.workspace .left-pane),
:deep(.workspace .inspection-pane) {
  overflow: hidden !important;
  padding: 0;
  background: transparent;
}

/* Splitter gutter — narrower so the spacing between tree and
   inspection visually matches the workspace-row gap between Splitter
   and tool-strip. Hover/active still flips to accent-blue from the
   global rule. */
:deep(.workspace .p-splitter-gutter) {
  width: 0.5rem !important;
  background: transparent !important;
}

.inspection-window { background: var(--bg-base); }
.inspection-area {
  height: 100%;
  overflow-y: auto;
  padding: 0.75rem 1rem;
}
.inspection-placeholder {
  margin: 4rem auto;
  max-width: 26rem;
  text-align: center;
  color: var(--text-secondary);
}
.inspection-placeholder p { margin: 0.4rem 0; }
.inspection-placeholder .hint { color: var(--text-muted); font-size: 0.85rem; }

/* Inline ↗ chip in the placeholder copy — visually identical to
   FrameCard's .rec-inspect-btn so the placeholder reads "click the
   thing that looks exactly like THIS". Static span (not a button)
   because it's inert prose, but the rendering matches the real
   affordance. */
.inline-inspect-chip {
  display: inline-block;
  background: rgba(96, 165, 250, 0.18);
  border: 1px solid rgba(96, 165, 250, 0.4);
  color: #93c5fd;
  font-size: 0.9rem;
  font-weight: 600;
  line-height: 1;
  padding: 0.1rem 0.5rem;
  border-radius: 3px;
  vertical-align: middle;
}

/* Tool strip — third panel-window. Always-visible icon column on the
   right edge; expands leftward with a content panel when a tool is
   active. flex-shrink:0 + auto width — the strip's total width is
   icons + optional content; the Splitter eats the remainder of the
   row. .panel-window default flex-direction is column, override to
   row here so icons + content sit side by side. */
.tool-strip {
  flex-direction: row;
  flex-shrink: 0;
}
.tool-strip .tool-content {
  /* Width is user-controlled via the drag handle; inline :style sets
     it on the section element, persisted to localStorage. Border on
     the right separates content from the icon column inside the
     panel-window chrome. */
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--border);
  background: transparent;
  min-height: 0;
  position: relative;
}

/* Old in-panel .tool-resize-handle replaced by .tool-divider — a
   sibling element in the workspace row, between Splitter and tool-
   strip, so the resize affordance lives on the boundary instead of
   on the tool window's left edge. */
.tool-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.45rem 0.75rem;
  border-bottom: 1px solid var(--border);
  background: var(--bg-surface);
  flex-shrink: 0;
}
.tool-title {
  font-size: 0.8rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-secondary);
}
.tool-close {
  background: transparent;
  border: 0;
  color: var(--text-muted);
  font-size: 1.1rem;
  line-height: 1;
  cursor: pointer;
  padding: 0 0.3rem;
}
.tool-close:hover { color: var(--text-primary); }
.tool-body { flex: 1; overflow: auto; min-height: 0; }
.tool-empty { padding: 2rem 1rem; text-align: center; color: var(--text-muted); }

.tool-icons {
  display: flex;
  flex-direction: column;
  width: 3rem;
  flex-shrink: 0;
  padding-top: 0.4rem;
  gap: 0.15rem;
  background: transparent;
}
.tool-icon {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.15rem;
  padding: 0.45rem 0.2rem;
  background: transparent;
  border: 0;
  border-left: 2px solid transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font-family: inherit;
}
.tool-icon:hover { color: var(--text-primary); background: var(--bg-hover); }
.tool-icon.active {
  color: var(--text-primary);
  background: var(--bg-base);
  border-left-color: var(--accent-blue);
}
.tool-glyph { font-size: 1.05rem; line-height: 1; }
.tool-label { font-size: 0.6rem; letter-spacing: 0.03em; }
.tool-badge {
  position: absolute;
  top: 0.15rem;
  right: 0.15rem;
  background: var(--accent-blue);
  color: #0b1220;
  font-size: 0.6rem;
  font-weight: 700;
  border-radius: 8px;
  padding: 0 0.3rem;
  min-width: 1rem;
  text-align: center;
  line-height: 1.1;
}
</style>
