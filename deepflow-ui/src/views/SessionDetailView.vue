<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import Select from 'primevue/select';
import Message from 'primevue/message';
import ProgressSpinner from 'primevue/progressspinner';
import Splitter from 'primevue/splitter';
import SplitterPanel from 'primevue/splitterpanel';
import { api } from '../api/client.js';
import FrameCard from '../components/FrameCard.vue';
import WatchPanel from '../components/WatchPanel.vue';

const props = defineProps({ sessionId: { type: String, required: true } });

// --- request / call state -----------------------------------------------

const requests = ref([]);
const selectedRequestId = ref(null);
const calls = ref([]);
const requestPayloads = ref([]);
const watches = ref([]);
const loadingRequests = ref(false);
const loadingCalls = ref(false);
const loadingRequestPayloads = ref(false);
const error = ref(null);

const callsWithDepth = computed(() => {
  const byId = new Map();
  for (const c of calls.value) byId.set(c.call_id, c);
  const depthCache = new Map();
  function depthOf(id) {
    if (depthCache.has(id)) return depthCache.get(id);
    const c = byId.get(id);
    if (!c || !c.parent_call_id || !byId.has(c.parent_call_id)) {
      depthCache.set(id, 0);
      return 0;
    }
    const d = depthOf(c.parent_call_id) + 1;
    depthCache.set(id, d);
    return d;
  }
  return calls.value.map(c => ({ ...c, depth: depthOf(c.call_id) }));
});

// Position of each call in the rendered list — used by WatchPanel to
// align its row order with the left pane.
const callOrder = computed(() => {
  const m = new Map();
  calls.value.forEach((c, i) => m.set(c.call_id, i));
  return m;
});

// --- loaders -------------------------------------------------------------

async function loadRequests() {
  loadingRequests.value = true;
  error.value = null;
  try {
    requests.value = await api.listRequests(props.sessionId);
    if (requests.value.length && selectedRequestId.value == null) {
      selectedRequestId.value = requests.value[0].request_id;
    }
  } catch (e) {
    error.value = e.message;
  } finally {
    loadingRequests.value = false;
  }
}

async function loadCalls() {
  if (selectedRequestId.value == null) {
    calls.value = [];
    requestPayloads.value = [];
    return;
  }
  loadingCalls.value = true;
  watches.value = [];
  frameRefs.value = {};
  try {
    calls.value = await api.callTree(props.sessionId, { requestId: selectedRequestId.value });
  } catch (e) {
    error.value = e.message;
  } finally {
    loadingCalls.value = false;
  }
  loadingRequestPayloads.value = true;
  try {
    requestPayloads.value = await api.requestPayloads(props.sessionId, selectedRequestId.value);
  } catch (e) {
    error.value = e.message;
  } finally {
    loadingRequestPayloads.value = false;
  }
}

// --- watch model ---------------------------------------------------------

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

// --- imperative navigator -----------------------------------------------
//
// The navigator owns the "go to (seq, kind, path)" entry point used by
// the watch panel. It looks up the matching FrameCard via a ref map
// keyed by call_id and delegates path-revealing to it. Nothing is
// broadcast; only the targeted card touches its own state.

const frameRefs = ref({});

function captureFrame(callId, instance) {
  if (instance) frameRefs.value[callId] = instance;
  else delete frameRefs.value[callId];
}

async function goto({ callId, kind, path }) {
  const frame = frameRefs.value[callId];
  if (!frame) return;
  await frame.revealAt(kind, path || []);
}

// --- header collapse-all -------------------------------------------------

function collapseAll() {
  // Imperative: ask each frame ref to close itself. Avoids a global
  // expanded-set in this view.
  for (const id of Object.keys(frameRefs.value)) {
    const f = frameRefs.value[id];
    if (f && f.collapse) f.collapse();
  }
}

// --- lifecycle -----------------------------------------------------------

onMounted(loadRequests);
watch(() => props.sessionId, () => { selectedRequestId.value = null; loadRequests(); });
watch(selectedRequestId, loadCalls);
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
      </div>
    </header>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <Splitter class="workspace" stateKey="deepflow-session-splitter" stateStorage="local">
      <SplitterPanel :size="65" :minSize="25" class="left-pane">
        <div v-if="loadingCalls" class="centered"><ProgressSpinner style="width:2rem;height:2rem" /></div>

        <ol v-else class="recording" :start="1">
          <FrameCard v-for="call in callsWithDepth"
                     :key="call.call_id"
                     :call="call"
                     :ref="(el) => captureFrame(call.call_id, el)"
                     @pin="pinWatch" />
        </ol>

        <p v-if="!loadingCalls && !calls.length && selectedRequestId != null" class="muted centered">
          no calls in this request
        </p>
      </SplitterPanel>

      <SplitterPanel :size="35" :minSize="20" class="right-pane">
        <WatchPanel :watches="watches"
                    :payloads="requestPayloads"
                    :callOrder="callOrder"
                    @remove="removeWatch"
                    @jump="goto" />
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
.req-option { display: inline-flex; gap: 0.6rem; align-items: baseline; }
.muted { color: var(--text-muted); font-size: 0.8rem; }
.centered { display: flex; justify-content: center; padding: 2rem; }

.workspace {
  flex: 1; overflow: hidden;
  border-radius: 0;
  border: 0;
  background: var(--bg-base);
}
:deep(.left-pane)  { overflow-y: auto !important; padding: 0.25rem 0; background: var(--bg-base); }
:deep(.right-pane) { overflow-y: auto !important; padding: 0; }
:deep(.p-splitter-gutter) { background: var(--border) !important; width: 6px !important; }
:deep(.p-splitter-gutter:hover),
:deep(.p-splitter-gutter.p-splitter-gutter-resizing) { background: var(--accent-blue) !important; }
:deep(.p-splitter-gutter-handle) { background: var(--text-muted) !important; }

.recording { list-style: none; padding: 0; margin: 0; }
</style>
