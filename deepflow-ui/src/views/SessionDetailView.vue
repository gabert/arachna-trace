<script setup>
import { computed, provide, ref, toRef, watch } from 'vue';
import Select from 'primevue/select';
import Message from 'primevue/message';
import ProgressSpinner from 'primevue/progressspinner';
import Splitter from 'primevue/splitter';
import SplitterPanel from 'primevue/splitterpanel';
import FrameCard from '../components/FrameCard.vue';
import WatchPanel from '../components/WatchPanel.vue';
import MutationsPanel from '../components/MutationsPanel.vue';
import { useRequestData } from '../composables/useRequestData.js';
import { useNavigator } from '../composables/useNavigator.js';
import { useArAxAnalysis } from '../composables/useArAxAnalysis.js';
import {
  PAYLOADS_BY_CALL_ID, CHILDREN_BY_PARENT,
  EXPANSION_DEFAULT, EXPANSION_OVERRIDES,
  MUTATED_OBJECTS_BY_CALL_ID, ADDED_OBJECTS_BY_CALL_ID,
  HIGHLIGHT, NAV_TICK
} from '../keys.js';

const props = defineProps({ sessionId: { type: String, required: true } });

// Right-pane tab. Mutations is the discovery surface, default. Watch
// is the follow-up tool — keeps its pinned items across tab switches.
const rightTab = ref('mutations');

const selectedRequestId = ref(null);

const sessionIdRef = toRef(props, 'sessionId');
const {
  requests, calls, requestPayloads,
  loadingRequests, loadingCalls, error,
  payloadsByCallId, childrenByParent, rootCalls, parentByCallId, callOrder
} = useRequestData(sessionIdRef, selectedRequestId);

const {
  highlight, navTick, expansionDefault, expansionOverrides,
  goto, expandAll, collapseAll, reset: resetNavigator
} = useNavigator(parentByCallId);

const { mutatedObjectsByCallId, addedObjectsByCallId } = useArAxAnalysis(payloadsByCallId);

provide(PAYLOADS_BY_CALL_ID, payloadsByCallId);
provide(CHILDREN_BY_PARENT, childrenByParent);
provide(EXPANSION_DEFAULT, expansionDefault);
provide(EXPANSION_OVERRIDES, expansionOverrides);
provide(MUTATED_OBJECTS_BY_CALL_ID, mutatedObjectsByCallId);
provide(ADDED_OBJECTS_BY_CALL_ID, addedObjectsByCallId);
provide(HIGHLIGHT, highlight);
provide(NAV_TICK, navTick);

// Watch model. Local to this view because it scopes to one
// (session, request) — moving requests should clear watches.
const watches = ref([]);

function pinWatch(w) {
  if (!w || w.objectId == null) return;
  const key = watchKey(w);
  if (watches.value.some(x => watchKey(x) === key)) return;
  watches.value = [...watches.value, w];
}

function watchKey(w) {
  if (w.kind === 'field') return `field:${w.objectId}:${(w.fieldPath || []).join('.')}`;
  return `instance:${w.objectId}`;
}

function removeWatch(idx) {
  watches.value = watches.value.filter((_, i) => i !== idx);
}

// Reset navigator + watches whenever the active request changes.
watch(selectedRequestId, () => {
  watches.value = [];
  resetNavigator();
});
</script>

<template>
  <section class="session-view">
    <header class="sv-head">
      <h1 class="session-title">{{ sessionId }}</h1>
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
                     @pin="pinWatch" />
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
          </nav>
          <div class="right-tab-body">
            <!-- Both panels stay mounted (v-show, not v-if) so per-row /
                 per-group expansion state survives tab switches. The
                 outer v-if guards on having a request selected to avoid
                 a flash of empty content during initial load. -->
            <template v-if="selectedRequestId != null">
              <MutationsPanel v-show="rightTab === 'mutations'"
                              :sessionId="sessionId"
                              :requestId="selectedRequestId"
                              @jump="goto" />
              <WatchPanel v-show="rightTab === 'watch'"
                          :watches="watches"
                          :payloads="requestPayloads"
                          :callOrder="callOrder"
                          @remove="removeWatch"
                          @jump="goto" />
            </template>
          </div>
        </div>
      </SplitterPanel>
    </Splitter>
  </section>
</template>

<style scoped>
.session-view { display: flex; flex-direction: column; height: calc(100vh - 60px); background: var(--bg-base); }

.sv-head {
  display: flex; align-items: center; gap: 1.5rem;
  padding: 0.5rem 1rem; border-bottom: 1px solid var(--border); flex-shrink: 0;
  background: var(--bg-surface);
}
.session-title { margin: 0; font-family: ui-monospace, monospace; font-size: 0.9rem; color: var(--text-secondary); }
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
