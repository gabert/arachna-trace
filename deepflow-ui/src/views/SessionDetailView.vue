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
import { walkEnvelopes } from '../util/envelope';
import {
  PAYLOADS_BY_CALL_ID, CHILDREN_BY_PARENT,
  EXPANSION_DEFAULT, EXPANSION_OVERRIDES,
  MUTATED_OBJECTS_BY_CALL_ID, ADDED_OBJECTS_BY_CALL_ID,
  HIGHLIGHT, NAV_TICK,
  SELECT_CALL, SELECTED_CALL_ID,
  INSPECTED_INSTANCE, INSTANCE_APPEARANCES_BY_CALL_ID,
  NAVIGATE_TO_APPEARANCE
} from '../keys';
import { findPathToObjectId } from '../util/envelope';
import type { AppearanceKind, CallRow, JumpAddress, OriginTarget, Path, PayloadKind, TraceTarget, Watch } from '../types';


const props = defineProps<{ sessionId: string }>();

// Right-edge tool strip. Always-visible icon column on the far right;
// clicking an icon expands an overlay showing that tool's panel. null
// = collapsed (icons only). Tools are demoted from a tabbed top-level
// surface to call-out tools alongside the inspection area, which is
// the new dominant content. Mutations / Watches / Origin / Search all
// keep their full state via v-show even when collapsed.
type ToolId = 'mutations' | 'watch' | 'origin' | 'search';
const activeTool = ref<ToolId | null>(null);
function toggleTool(id: ToolId): void {
  activeTool.value = activeTool.value === id ? null : id;
}

// Tool-content panel width is user-resizable via a drag handle on its
// left edge. Persisted to localStorage so the user's preferred width
// survives reload (mirrors the Splitter's stateStorage="local" pattern
// for the tree-vs-inspection divider).
const TOOL_WIDTH_KEY = 'deepflow-tool-content-width';
const TOOL_WIDTH_DEFAULT = 360;
const TOOL_WIDTH_MIN = 220;
const TOOL_WIDTH_MAX = 1100;
function readStoredToolWidth(): number {
  try {
    const v = localStorage.getItem(TOOL_WIDTH_KEY);
    if (!v) return TOOL_WIDTH_DEFAULT;
    const n = parseInt(v, 10);
    if (!Number.isFinite(n)) return TOOL_WIDTH_DEFAULT;
    return Math.min(TOOL_WIDTH_MAX, Math.max(TOOL_WIDTH_MIN, n));
  } catch { return TOOL_WIDTH_DEFAULT; }
}
const toolWidth = ref<number>(readStoredToolWidth());

let dragStartX = 0;
let dragStartWidth = 0;
function onResizeMove(e: MouseEvent): void {
  // Panel sits on the right; dragging the handle leftward should widen
  // the panel, so subtract the cursor delta.
  const delta = dragStartX - e.clientX;
  const next = dragStartWidth + delta;
  toolWidth.value = Math.min(TOOL_WIDTH_MAX, Math.max(TOOL_WIDTH_MIN, next));
}
function onResizeEnd(): void {
  document.removeEventListener('mousemove', onResizeMove);
  document.removeEventListener('mouseup', onResizeEnd);
  document.body.style.userSelect = '';
  document.body.style.cursor = '';
  try { localStorage.setItem(TOOL_WIDTH_KEY, String(toolWidth.value)); } catch { /* quota / private mode */ }
}
function onResizeStart(e: MouseEvent): void {
  dragStartX = e.clientX;
  dragStartWidth = toolWidth.value;
  document.addEventListener('mousemove', onResizeMove);
  document.addEventListener('mouseup', onResizeEnd);
  // Suppress text selection and lock the cursor so the drag feels like
  // a UI gesture rather than a stray drag-select on the page content.
  document.body.style.userSelect = 'none';
  document.body.style.cursor = 'col-resize';
}

const selectedRequestId = ref<number | null>(null);

const sessionIdRef = toRef(props, 'sessionId');
const {
  requests, calls, parsedPayloads,
  loadingRequests, loadingCalls, error,
  payloadsByCallId, childrenByParent, rootCalls, parentByCallId, callMeta
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

// Currently-inspected call. Drives CallInspectionCard in the center
// pane and the "selected" affordance on FrameCard rows.
const selectedCallId = ref<string | null>(null);

// Bare setter — used by gotoAndSelect (which already carries its own
// kind/path target) and by the request-change reset. Manual row
// clicks go through selectCall() below, which adds auto-navigation
// to the inspected instance when tracing is active.
function setSelectedCallId(id: string | null): void {
  selectedCallId.value = id;
}

// SELECT_CALL provider — called by FrameCard on manual row click.
// When an instance is being traced AND this call has the instance,
// auto-navigates the right-pane PayloadViewer to where the instance
// lives so the developer doesn't have to hunt for it among TI / AR /
// AX / RE. Defined later (after gotoAndSelect / instanceAppearances /
// findInstanceLocation are in scope) — see selectCall below.
const callsById = computed<Map<string, CallRow>>(() => {
  const m = new Map<string, CallRow>();
  for (const c of calls.value) m.set(c.call_id, c);
  return m;
});
const selectedCall = computed<CallRow | null>(() => {
  const id = selectedCallId.value;
  return id ? (callsById.value.get(id) || null) : null;
});

// Pinned inspection cards. Survive new selections; user manages with
// the 📌 / ✕ buttons in each card's header. Pin order preserves the
// order they were pinned, oldest first (top of stack), so the user
// can predict where each parked card sits.
const pinnedCallIds = ref<string[]>([]);
const collapsedCards = ref<Set<string>>(new Set());

function togglePinCard(id: string): void {
  if (pinnedCallIds.value.includes(id)) {
    pinnedCallIds.value = pinnedCallIds.value.filter(x => x !== id);
    const next = new Set(collapsedCards.value);
    next.delete(id);
    collapsedCards.value = next;
  } else {
    pinnedCallIds.value = [...pinnedCallIds.value, id];
  }
}

function setCardCollapsed(id: string, collapsed: boolean): void {
  const next = new Set(collapsedCards.value);
  if (collapsed) next.add(id); else next.delete(id);
  collapsedCards.value = next;
}

const pinnedCalls = computed<CallRow[]>(() => {
  const map = callsById.value;
  const out: CallRow[] = [];
  for (const id of pinnedCallIds.value) {
    const c = map.get(id);
    if (c) out.push(c);
  }
  return out;
});

// Hide the "current" card if its call is already pinned — no point
// rendering the same inspection twice.
const currentCard = computed<CallRow | null>(() => {
  const c = selectedCall.value;
  if (!c) return null;
  if (pinnedCallIds.value.includes(c.call_id)) return null;
  return c;
});

// Instance the user picked to trace on the tree. Click an envelope's
// "🔎 trace" button in any inspection card to set; click the same
// instance again to clear (toggle).
const inspectedInstance = ref<TraceTarget | null>(null);
function setInspectedInstance(t: TraceTarget): void {
  if (inspectedInstance.value?.objectId === t.objectId) {
    inspectedInstance.value = null;
  } else {
    inspectedInstance.value = t;
  }
}
function clearInspectedInstance(): void { inspectedInstance.value = null; }

// Per-call appearance kind for the inspected instance: 'mutated' when
// this call's own_hash for the instance changed (AR vs AX), 'appears'
// when the instance is just present in any of the call's payloads.
// Direct only — no subtree rollup. The trace banner's ↑/↓ buttons
// handle navigation across the chronological appearance list, which
// is the right primitive for "where else does this instance live".
//
// Walks parsed payload JSON for envelopes whose __meta__.id matches.
// Doesn't rely on PayloadRow.object_ids — that column is sometimes
// absent from the request-payloads endpoint, and walking the parsed
// tree we already have is cheap (one pass per trace change, runs
// only when inspectedInstance is non-null).
const instanceAppearancesByCallId = computed<Map<string, AppearanceKind>>(() => {
  const out = new Map<string, AppearanceKind>();
  const inst = inspectedInstance.value;
  if (!inst) return out;
  const id = inst.objectId;
  for (const p of parsedPayloads.value) {
    if (out.get(p.call_id) === 'appears') continue;
    let found = false;
    walkEnvelopes(p.parsed, (env) => {
      if (env.__meta__.id === id) found = true;
    });
    if (found && !out.has(p.call_id)) out.set(p.call_id, 'appears');
  }
  for (const [callId, ids] of mutatedObjectsByCallId.value) {
    if (ids.has(id)) out.set(callId, 'mutated');
  }
  return out;
});

// Chronologically-ordered list of call ids where the instance appears.
// Sorted by callMeta.pre (DFS pre-order) — same total ordering the
// rest of the UI uses for "what happened next?". Drives ↑/↓ navigation
// in the trace banner.
const orderedAppearanceCallIds = computed<string[]>(() => {
  const ids = Array.from(instanceAppearancesByCallId.value.keys());
  const meta = callMeta.value;
  ids.sort((a, b) => (meta.get(a)?.pre ?? 0) - (meta.get(b)?.pre ?? 0));
  return ids;
});

const inspectedCount = computed(() => orderedAppearanceCallIds.value.length);

// Cursor is derived from the current selection: if the selected call
// is in the appearance list, that's our position; otherwise -1 (no
// position, ↓ goes to first / ↑ goes to last). Manual clicks update
// the counter naturally without a separate state ref to keep in sync.
const traceCursor = computed<number>(() => {
  const id = selectedCallId.value;
  if (!id) return -1;
  return orderedAppearanceCallIds.value.indexOf(id);
});

// Walk the call's payloads to find where the instance lives, so the
// JsonTree highlight can land on the actual envelope. Prefers TI →
// AR → AX → RE (the order they were observed in this call).
const PAYLOAD_KIND_ORDER: PayloadKind[] = ['TI', 'AR', 'AX', 'RE'];
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

// Ref to the call-tree panel so we can call its public highlightCall
// method after a programmatic navigation. The panel owns the highlight
// state internally — this view doesn't poke at FrameCard rows directly.
const callTreeRef = ref<InstanceType<typeof CallTreePanel> | null>(null);

// Random-access nav: jump to a specific appearance call by id. Used
// by both the trace ↑/↓ buttons (via index → callId) and the bubble
// click affordance on each FrameCard row. Both flash the row and
// open the inspection card pointed at the instance's path.
function gotoAppearanceForCall(callId: string): void {
  const inst = inspectedInstance.value;
  if (!inst) return;
  const loc = findInstanceLocation(callId, inst.objectId);
  gotoAndSelect({
    callId,
    kind: loc?.kind || 'AR',
    path: loc?.path || []
  });
  // Flash + scroll the call row in the tree, separate from the JSON-
  // node highlight that gotoAndSelect drives inside the inspection
  // card. Both fire together so the user sees "you are here" on both
  // sides of the workspace.
  callTreeRef.value?.highlightCall(callId);
}
function gotoAppearanceAt(index: number): void {
  const ids = orderedAppearanceCallIds.value;
  if (!ids.length) return;
  const wrapped = ((index % ids.length) + ids.length) % ids.length;
  gotoAppearanceForCall(ids[wrapped]);
}
function gotoNextAppearance(): void { gotoAppearanceAt(traceCursor.value + 1); }
function gotoPrevAppearance(): void { gotoAppearanceAt(traceCursor.value - 1); }

const inspectedShortClass = computed(() => {
  const c = inspectedInstance.value?.className;
  if (!c) return '';
  return String(c).split('.').pop() || c;
});

// Manual row-click handler. Sets the selection AND, when an instance
// is being traced, auto-navigates the inspection card's PayloadViewer
// to where the instance lives so the developer doesn't have to hunt
// for it among TI / AR / AX / RE. gotoAndSelect (used for programmatic
// jumps from tool panels) bypasses this — those carry their own
// kind/path target.
function selectCall(id: string): void {
  setSelectedCallId(id);
  const inst = inspectedInstance.value;
  if (!inst) return;
  if (!instanceAppearancesByCallId.value.has(id)) return;
  const loc = findInstanceLocation(id, inst.objectId);
  if (loc) goto({ callId: id, kind: loc.kind, path: loc.path });
}

provide(PAYLOADS_BY_CALL_ID, payloadsByCallId);
provide(CHILDREN_BY_PARENT, childrenByParent);
provide(EXPANSION_DEFAULT, expansionDefault);
provide(EXPANSION_OVERRIDES, expansionOverrides);
provide(MUTATED_OBJECTS_BY_CALL_ID, mutatedObjectsByCallId);
provide(ADDED_OBJECTS_BY_CALL_ID, addedObjectsByCallId);
provide(HIGHLIGHT, highlight);
provide(NAV_TICK, navTick);
provide(SELECT_CALL, selectCall);
provide(SELECTED_CALL_ID, selectedCallId);
provide(INSPECTED_INSTANCE, inspectedInstance);
provide(INSTANCE_APPEARANCES_BY_CALL_ID, instanceAppearancesByCallId);
provide(NAVIGATE_TO_APPEARANCE, gotoAppearanceForCall);

// Jumps from tool panels (mutations, watches, origin, search) need to
// land in a mounted PayloadViewer to drive the highlight scroll. The
// only PayloadViewers now live inside CallInspectionCard, so a jump
// must also select the target call (and uncollapse it if it was a
// collapsed pinned card). Uses setSelectedCallId (bare) so the
// caller's own kind/path isn't overwritten by selectCall's auto-nav.
function gotoAndSelect(addr: JumpAddress): void {
  setSelectedCallId(addr.callId);
  if (collapsedCards.value.has(addr.callId)) {
    const next = new Set(collapsedCards.value);
    next.delete(addr.callId);
    collapsedCards.value = next;
  }
  goto(addr);
}

// Watch model. Local to this view because it scopes to one
// (session, request) — moving requests should clear watches.
const watches = ref<Watch[]>([]);

function pinWatch(w: Watch | null | undefined): void {
  if (!w || w.objectId == null) return;
  const key = watchKey(w);
  if (!watches.value.some(x => watchKey(x) === key)) {
    watches.value = [...watches.value, w];
  }
  // Mirror the origin click affordance: surface the panel that just
  // gained content. Same expand even when the watch already existed,
  // so a duplicate click still feels responsive (the user gets focus
  // on the panel rather than a silent no-op).
  activeTool.value = 'watch';
}

function watchKey(w: Watch): string {
  if (w.kind === 'field') return `field:${w.objectId}:${(w.fieldPath || []).join('.')}`;
  return `instance:${w.objectId}`;
}

function removeWatch(idx: number): void {
  watches.value = watches.value.filter((_, i) => i !== idx);
}

// Origin target lives inside the provenance composable; the view just
// wires the user click → tool expand + composable update. Subsequent
// clicks just replace the target without further nav.
function setOrigin(t: OriginTarget): void {
  provenance.setTarget(t);
  activeTool.value = 'origin';
}

const toolMeta: Record<ToolId, { label: string; icon: string }> = {
  mutations: { label: 'Mutations', icon: '⟳' },
  watch:     { label: 'Watches',   icon: '⊙' },
  origin:    { label: 'Origin',    icon: '↤' },
  search:    { label: 'Search',    icon: '⌕' }
};
const toolIds: ToolId[] = ['mutations', 'watch', 'origin', 'search'];
const toolBadge = computed<Record<ToolId, number>>(() => ({
  mutations: mutationGroups.value.length,
  watch:     watches.value.length,
  origin:    provenance.target.value ? 1 : 0,
  search:    valueSearch.hits.value.length
}));
const activeToolLabel = computed(() => activeTool.value ? toolMeta[activeTool.value].label : '');

function clearOrigin(): void {
  provenance.clear();
}

// Reset navigator + watches + origin + inspection whenever the active
// request changes.
watch(selectedRequestId, () => {
  watches.value = [];
  selectedCallId.value = null;
  pinnedCallIds.value = [];
  collapsedCards.value = new Set();
  inspectedInstance.value = null;
  provenance.clear();
  resetNavigator();
});

// Clearing the trace target also clears the call-tree highlight so a
// stale "you are here" outline doesn't linger on a row that no longer
// has any reason to be highlighted.
watch(inspectedInstance, (v) => {
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
                         :inspectedInstance="inspectedInstance"
                         :inspectedShortClass="inspectedShortClass"
                         :inspectedCount="inspectedCount"
                         :traceCursor="traceCursor"
                         @pin="pinWatch"
                         @origin="setOrigin"
                         @goto-prev-appearance="gotoPrevAppearance"
                         @goto-next-appearance="gotoNextAppearance"
                         @clear-instance="clearInspectedInstance" />
        </SplitterPanel>

        <SplitterPanel :size="50" :minSize="20" class="inspection-pane">
          <div class="inspection-area">
            <CallInspectionCard v-if="currentCard"
                                :call="currentCard"
                                :pinned="false"
                                @pin="pinWatch"
                                @origin="setOrigin"
                                @trace="setInspectedInstance"
                                @pin-card="togglePinCard(currentCard.call_id)" />
            <CallInspectionCard v-for="c in pinnedCalls"
                                :key="c.call_id"
                                :call="c"
                                :pinned="true"
                                :collapsed="collapsedCards.has(c.call_id)"
                                @pin="pinWatch"
                                @origin="setOrigin"
                                @trace="setInspectedInstance"
                                @pin-card="togglePinCard(c.call_id)"
                                @set-collapsed="(v) => setCardCollapsed(c.call_id, v)" />
            <div v-if="!currentCard && !pinnedCalls.length" class="inspection-placeholder">
              <p>Click a call on the left to inspect its TI / AR / AX / RE.</p>
              <p class="hint">Pin (📌) a card to keep it side-by-side with later selections.</p>
            </div>
          </div>
        </SplitterPanel>
      </Splitter>

      <!-- Tool strip on the far right edge: always-visible icon column,
           plus a content overlay (only when a tool is active). The
           panels remain mounted via v-show so per-group / per-row
           expansion state survives expand/collapse. -->
      <aside class="tool-strip" :class="{ expanded: activeTool != null }">
        <section v-if="activeTool != null"
                 class="tool-content"
                 :style="{ width: toolWidth + 'px' }">
          <div class="tool-resize-handle"
               @mousedown.prevent="onResizeStart"
               title="Drag to resize"></div>
          <header class="tool-header">
            <span class="tool-title">{{ activeToolLabel }}</span>
            <button class="tool-close" @click="activeTool = null" title="Collapse">×</button>
          </header>
          <div class="tool-body">
            <template v-if="selectedRequestId != null">
              <MutationsPanel v-show="activeTool === 'mutations'"
                              :groups="mutationGroups"
                              :summary="mutationsSummary"
                              :loading="loadingMutations"
                              :error="mutationsError"
                              @jump="gotoAndSelect" />
              <WatchPanel v-show="activeTool === 'watch'"
                          :watches="watches"
                          :payloads="parsedPayloads"
                          :callMeta="callMeta"
                          @remove="removeWatch"
                          @jump="gotoAndSelect" />
              <OriginPanel v-show="activeTool === 'origin'"
                           :provenance="provenance"
                           @jump="gotoAndSelect" />
              <SearchPanel v-show="activeTool === 'search'"
                           :search="valueSearch"
                           :active="activeTool === 'search'"
                           @jump="gotoAndSelect" />
            </template>
            <p v-else class="tool-empty">Pick a request to start.</p>
          </div>
        </section>

        <nav class="tool-icons">
          <button v-for="id in toolIds"
                  :key="id"
                  class="tool-icon"
                  :class="{ active: activeTool === id }"
                  :title="toolMeta[id].label"
                  @click="toggleTool(id)">
            <span class="tool-glyph">{{ toolMeta[id].icon }}</span>
            <span class="tool-label">{{ toolMeta[id].label }}</span>
            <span v-if="toolBadge[id]" class="tool-badge">{{ toolBadge[id] }}</span>
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
