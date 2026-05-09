<script setup lang="ts">
import { computed, provide, ref, toRef, watch } from 'vue';
import Select from 'primevue/select';
import Message from 'primevue/message';
import Splitter from 'primevue/splitter';
import SplitterPanel from 'primevue/splitterpanel';
import CallTreePanel from '../components/CallTreePanel.vue';
import CallInspectionCard from '../components/CallInspectionCard.vue';
import WatchPanel from '../components/WatchPanel.vue';
import MutationsPanel from '../components/MutationsPanel.vue';
import OriginPanel from '../components/OriginPanel.vue';
import SearchPanel from '../components/SearchPanel.vue';
import { useRequestData } from '../composables/useRequestData';
import { useNavigator } from '../composables/useNavigator';
import { useObjectChanges } from '../composables/useObjectChanges';
import { useProvenance } from '../composables/useProvenance';
import { useValueSearch } from '../composables/useValueSearch';
import { useInspectionStack } from '../composables/useInspectionStack';
import { useInstanceTrace } from '../composables/useInstanceTrace';
import { useToolStrip, type ToolId } from '../composables/useToolStrip';
import {
  PAYLOADS_BY_CALL_ID, CHILDREN_BY_PARENT,
  EXPANSION_DEFAULT, EXPANSION_OVERRIDES,
  MUTATED_OBJECTS_BY_CALL_ID, ADDED_OBJECTS_BY_CALL_ID,
  HIGHLIGHT, NAV_TICK,
  CALL_SELECTION, INSTANCE_TRACE
} from '../keys';
import type { CallRow, JumpAddress, OriginTarget, Watch } from '../types';

const props = defineProps<{ sessionId: string }>();

const selectedRequestId = ref<number | null>(null);
const sessionIdRef = toRef(props, 'sessionId');

const {
  requests, calls, parsedPayloads,
  loadingRequests, loadingCalls, error,
  payloadsByCallId, childrenByParent, rootCalls, parentByCallId, callMeta,
  callIdsByObjectId
} = useRequestData(sessionIdRef, selectedRequestId);

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
} = useObjectChanges(sessionIdRef, selectedRequestId);

const provenance = useProvenance(parsedPayloads, callMeta, sessionIdRef, selectedRequestId);
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
function gotoAndSelect(addr: JumpAddress): void {
  stack.showTransient(addr.callId);
  goto(addr);
}

const trace = useInstanceTrace({
  callIdsByObjectId,
  mutatedObjectsByCallId,
  payloadsByCallId,
  callMeta,
  selectedCallId: stack.selectedCallId,
  gotoAndSelect,
  highlightCallRow: (callId) => callTreeRef.value?.highlightCall(callId)
});

// Manual row-click handler (FrameCard's ↗ inspect chip). Shows the
// call in the transient browsing slot and — when an instance is being
// traced AND this call has the instance — auto-navigates the card's
// PayloadViewer to the instance's path so the developer doesn't have
// to hunt for it among TI / AR / AX / RE. The user pins (📌) when
// they want the card kept across the next navigation.
function selectCall(id: string): void {
  stack.showTransient(id);
  const inst = trace.inspectedInstance.value;
  if (!inst) return;
  if (!trace.instanceAppearancesByCallId.value.has(id)) return;
  const loc = trace.findInstanceLocation(id, inst.objectId);
  if (loc) goto({ callId: id, kind: loc.kind, path: loc.path });
}

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

// Reset navigator + watches + origin + inspection whenever the active
// request changes.
watch(selectedRequestId, () => {
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
        <label>Request</label>
        <Select v-model="selectedRequestId"
                :options="requests"
                optionLabel="request_id"
                optionValue="request_id"
                :placeholder="loadingRequests ? 'Loading...' : 'Pick a request'"
                :loading="loadingRequests">
          <template #option="{ option }">
            <span class="req-option">
              <strong>#{{ option.request_id }}</strong>
              <span class="muted">{{ option.thread_name }}</span>
              <span class="muted">{{ option.call_count }} calls</span>
              <span class="muted">{{ option.span_ms }} ms</span>
            </span>
          </template>
        </Select>
        <button class="tree-btn" @click="expandAll" title="Expand every frame">expand all</button>
        <button class="tree-btn" @click="collapseAll" title="Collapse every frame">collapse all</button>
      </div>
    </header>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <div class="workspace-row">
      <Splitter class="workspace" stateKey="deepflow-session-splitter-v2" stateStorage="local">
        <SplitterPanel :size="50" :minSize="25" class="left-pane">
          <CallTreePanel ref="callTreeRef"
                         :rootCalls="rootCalls"
                         :callsLoading="loadingCalls"
                         :hasNoCalls="!calls.length && selectedRequestId != null"
                         :inspectedInstance="trace.inspectedInstance.value"
                         :inspectedShortClass="trace.inspectedShortClass.value"
                         :inspectedCount="trace.inspectedCount.value"
                         :traceCursor="trace.traceCursor.value"
                         @pin="pinWatch"
                         @origin="setOrigin"
                         @goto-prev-appearance="trace.gotoPrevAppearance"
                         @goto-next-appearance="trace.gotoNextAppearance"
                         @clear-instance="trace.clearInspectedInstance" />
        </SplitterPanel>

        <SplitterPanel :size="50" :minSize="20" class="inspection-pane">
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
              <p>Click <code>↗</code> on a call in the tree to open its TI / AR / AX / RE.</p>
              <p class="hint">A new card replaces the previous one as you browse. Click <code>📌</code> on its header to keep it; pinned cards stack below and are drag-reorderable.</p>
            </div>
          </div>
        </SplitterPanel>
      </Splitter>

      <!-- Tool strip on the far right edge: always-visible icon column,
           plus a content overlay (only when a tool is active). The
           panels remain mounted via v-show so per-group / per-row
           expansion state survives expand/collapse. -->
      <aside class="tool-strip" :class="{ expanded: toolStrip.activeTool.value != null }">
        <section v-if="toolStrip.activeTool.value != null"
                 class="tool-content"
                 :style="{ width: toolStrip.toolWidth.value + 'px' }">
          <div class="tool-resize-handle"
               @mousedown.prevent="toolStrip.onResizeStart"
               title="Drag to resize"></div>
          <header class="tool-header">
            <span class="tool-title">{{ toolStrip.activeToolLabel.value }}</span>
            <button class="tool-close" @click="toolStrip.setActiveTool(null)" title="Collapse">×</button>
          </header>
          <div class="tool-body">
            <template v-if="selectedRequestId != null">
              <MutationsPanel v-show="toolStrip.activeTool.value === 'mutations'"
                              :groups="mutationGroups"
                              :summary="mutationsSummary"
                              :loading="loadingMutations"
                              :error="mutationsError"
                              @jump="gotoAndSelect" />
              <WatchPanel v-show="toolStrip.activeTool.value === 'watch'"
                          :watches="watches"
                          :payloads="parsedPayloads"
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
            </template>
            <p v-else class="tool-empty">Pick a request to start.</p>
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
.req-option { display: inline-flex; gap: 0.6rem; align-items: baseline; }
.muted { color: var(--text-muted); font-size: 0.8rem; }
.centered { display: flex; justify-content: center; padding: 2rem; }

/* SessionDetailView's left pane gets a hair of top padding so the
   first FrameCard isn't flush against the splitter gutter. SessionsView
   doesn't need it because the DataTable header already has padding. */
:deep(.workspace .left-pane) { padding: 0.25rem 0; }

/* .recording, trace-banner, and centered/.muted now live inside
   CallTreePanel (the entire left-pane chrome moved). */

/* Workspace row: Splitter (tree | inspection) on the left, fixed-width
   tool strip on the right edge. The Splitter fills the remaining width
   so tool strip expand/collapse never resizes the splitter ratio. */
.workspace-row {
  flex: 1;
  display: flex;
  min-height: 0;
  overflow: hidden;
}
.workspace-row .workspace { flex: 1; min-width: 0; }

/* Inspection pane — center column, currently a placeholder. Override
   the global .workspace .right-pane overflow rule because we want the
   inner area to scroll, not the SplitterPanel itself (Phase 2+ will
   stack cards inside it). */
:deep(.workspace .inspection-pane) {
  overflow: hidden !important;
  background: var(--bg-base);
}
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

/* Tool strip — always-visible icon column on the far right; expands
   leftward with a content panel when a tool is active. Width is the
   sum of icon column + (optional) content panel; flex doesn't grow
   the strip, the splitter eats the remainder. */
.tool-strip {
  display: flex;
  flex-direction: row;
  flex-shrink: 0;
  border-left: 1px solid var(--border);
  background: var(--bg-surface);
}
.tool-strip .tool-content {
  /* Width is user-controlled via the drag handle; inline :style sets
     it on the section element, persisted to localStorage. */
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--border);
  background: var(--bg-base);
  min-height: 0;
  position: relative;
}

/* Drag handle on the left edge of the tool-content panel. Wider hit
   area (6px) than visible area (1px when idle, 3px on hover/drag) so
   the user doesn't have to aim precisely. col-resize cursor signals
   the gesture; the script handler manages width + persistence. */
.tool-resize-handle {
  position: absolute;
  top: 0; left: 0; bottom: 0;
  width: 6px;
  margin-left: -3px;
  cursor: col-resize;
  background: transparent;
  z-index: 2;
}
.tool-resize-handle:hover,
.tool-resize-handle:active {
  background: var(--accent-blue);
}
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
  background: var(--bg-surface);
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
