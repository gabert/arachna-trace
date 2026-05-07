<script setup lang="ts">
import { provide, ref, toRef, watch } from 'vue';
import Select from 'primevue/select';
import Message from 'primevue/message';
import ProgressSpinner from 'primevue/progressspinner';
import Splitter from 'primevue/splitter';
import SplitterPanel from 'primevue/splitterpanel';
import FrameCard from '../components/FrameCard.vue';
import WatchPanel from '../components/WatchPanel.vue';
import MutationsPanel from '../components/MutationsPanel.vue';
import OriginPanel from '../components/OriginPanel.vue';
import SearchPanel from '../components/SearchPanel.vue';
import { useRequestData } from '../composables/useRequestData';
import { useNavigator } from '../composables/useNavigator';
import { useObjectChanges } from '../composables/useObjectChanges';
import { useProvenance } from '../composables/useProvenance';
import { useValueSearch } from '../composables/useValueSearch';
import {
  PAYLOADS_BY_CALL_ID, CHILDREN_BY_PARENT,
  EXPANSION_DEFAULT, EXPANSION_OVERRIDES, CHILDREN_EXPANDED_OVERRIDES,
  MUTATED_OBJECTS_BY_CALL_ID, ADDED_OBJECTS_BY_CALL_ID,
  HIGHLIGHT, NAV_TICK
} from '../keys';
import type { OriginTarget, Watch } from '../types';


const props = defineProps<{ sessionId: string }>();

// Right-pane tab. Mutations is the discovery surface, default. Watch
// is the follow-up tool — keeps its pinned items across tab switches.
// Origin is the trace-where-it-came-from view — auto-activated when
// the user clicks ↤ origin on a value in the call tree.
type RightTab = 'mutations' | 'watch' | 'origin' | 'search';
const rightTab = ref<RightTab>('mutations');

const selectedRequestId = ref<number | null>(null);

const sessionIdRef = toRef(props, 'sessionId');
const {
  requests, calls, parsedPayloads,
  loadingRequests, loadingCalls, error,
  payloadsByCallId, childrenByParent, rootCalls, parentByCallId, callMeta
} = useRequestData(sessionIdRef, selectedRequestId);

const {
  highlight, navTick, expansionDefault, expansionOverrides, childrenExpandedOverrides,
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

provide(PAYLOADS_BY_CALL_ID, payloadsByCallId);
provide(CHILDREN_BY_PARENT, childrenByParent);
provide(EXPANSION_DEFAULT, expansionDefault);
provide(EXPANSION_OVERRIDES, expansionOverrides);
provide(CHILDREN_EXPANDED_OVERRIDES, childrenExpandedOverrides);
provide(MUTATED_OBJECTS_BY_CALL_ID, mutatedObjectsByCallId);
provide(ADDED_OBJECTS_BY_CALL_ID, addedObjectsByCallId);
provide(HIGHLIGHT, highlight);
provide(NAV_TICK, navTick);

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
  // gained content. Same tab switch even when the watch already
  // existed, so a duplicate click still feels responsive (the user
  // gets focus on the panel rather than a silent no-op).
  rightTab.value = 'watch';
}

function watchKey(w: Watch): string {
  if (w.kind === 'field') return `field:${w.objectId}:${(w.fieldPath || []).join('.')}`;
  return `instance:${w.objectId}`;
}

function removeWatch(idx: number): void {
  watches.value = watches.value.filter((_, i) => i !== idx);
}

// Origin target lives inside the provenance composable; the view just
// wires the user click → tab switch + composable update. Subsequent
// clicks just replace the target without further nav.
function setOrigin(t: OriginTarget): void {
  provenance.setTarget(t);
  rightTab.value = 'origin';
}

function clearOrigin(): void {
  provenance.clear();
}

// Reset navigator + watches + origin whenever the active request changes.
watch(selectedRequestId, () => {
  watches.value = [];
  provenance.clear();
  resetNavigator();
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

    <Splitter class="workspace" stateKey="deepflow-session-splitter" stateStorage="local">
      <SplitterPanel :size="65" :minSize="25" class="left-pane">
        <div v-if="loadingCalls" class="centered"><ProgressSpinner style="width:2rem;height:2rem" /></div>

        <ol v-else class="recording" :start="1">
          <FrameCard v-for="call in rootCalls"
                     :key="call.call_id"
                     :call="call"
                     @pin="pinWatch"
                     @origin="setOrigin" />
        </ol>

        <p v-if="!loadingCalls && !calls.length && selectedRequestId != null" class="muted centered">
          no calls in this request
        </p>
      </SplitterPanel>

      <SplitterPanel :size="35" :minSize="20" class="right-pane">
        <div class="right-pane-shell">
          <nav class="right-tabs">
            <button :class="{ active: rightTab === 'mutations' }"
                    @click="rightTab = 'mutations'">Mutations</button>
            <button :class="{ active: rightTab === 'watch' }"
                    @click="rightTab = 'watch'">Watches <span v-if="watches.length" class="tab-count">{{ watches.length }}</span></button>
            <button :class="{ active: rightTab === 'origin' }"
                    @click="rightTab = 'origin'">Origin <span v-if="provenance.target.value" class="tab-count">1</span></button>
            <button :class="{ active: rightTab === 'search' }"
                    @click="rightTab = 'search'">Search <span v-if="valueSearch.hits.value.length" class="tab-count">{{ valueSearch.hits.value.length }}</span></button>
          </nav>
          <div class="right-tab-body">
            <!-- Panels stay mounted (v-show) so per-row / per-group
                 expansion state survives tab switches. Outer v-if
                 guards on having a request selected to avoid a flash
                 of empty content during initial load. -->
            <template v-if="selectedRequestId != null">
              <MutationsPanel v-show="rightTab === 'mutations'"
                              :groups="mutationGroups"
                              :summary="mutationsSummary"
                              :loading="loadingMutations"
                              :error="mutationsError"
                              @jump="goto" />
              <WatchPanel v-show="rightTab === 'watch'"
                          :watches="watches"
                          :payloads="parsedPayloads"
                          :callMeta="callMeta"
                          @remove="removeWatch"
                          @jump="goto" />
              <OriginPanel v-show="rightTab === 'origin'"
                           :provenance="provenance"
                           @jump="goto" />
              <SearchPanel v-show="rightTab === 'search'"
                           :search="valueSearch"
                           :active="rightTab === 'search'"
                           @jump="goto" />
            </template>
          </div>
        </div>
      </SplitterPanel>
    </Splitter>
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

.recording { list-style: none; padding: 0; margin: 0; }

/* Right-pane tab shell — Mutations | Watches. */
.right-pane-shell { display: flex; flex-direction: column; height: 100%; }
.right-tabs {
  display: flex;
  border-bottom: 1px solid var(--border);
  background: var(--bg-surface);
  flex-shrink: 0;
}
.right-tabs button {
  flex: 1;
  background: transparent;
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--text-secondary);
  padding: 0.5rem 0.6rem;
  font-size: 0.85rem;
  cursor: pointer;
}
.right-tabs button:hover { color: var(--text-primary); background: var(--bg-hover); }
.right-tabs button.active {
  color: var(--text-primary);
  border-bottom-color: var(--accent-blue);
  background: var(--bg-base);
}
.tab-count {
  background: var(--bg-elevated);
  color: var(--text-secondary);
  border-radius: 8px;
  padding: 0 0.4rem;
  margin-left: 0.3rem;
  font-size: 0.7rem;
}
.right-tab-body { flex: 1; overflow: hidden; }
</style>
