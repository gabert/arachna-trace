<script setup>
import { computed, onMounted, provide, ref, watch } from 'vue';
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

// Build the parent → ordered children map. Children inherit the
// server's seq order so iterating them in array order = time order.
const childrenByParent = computed(() => {
  const known = new Set(calls.value.map(c => c.call_id));
  const m = new Map();
  for (const c of calls.value) {
    const parent = c.parent_call_id && known.has(c.parent_call_id) ? c.parent_call_id : null;
    if (!m.has(parent)) m.set(parent, []);
    m.get(parent).push(c);
  }
  return m;
});

// Calls without a known parent in this request — the rendered roots.
const rootCalls = computed(() => childrenByParent.value.get(null) || []);

// Reverse lookup: callId → parent's callId. Used by `goto` to walk
// from a navigation target up to its root, expanding every link so
// the target is mounted by the time we try to reveal it.
const parentByCallId = computed(() => {
  const known = new Set(calls.value.map(c => c.call_id));
  const m = new Map();
  for (const c of calls.value) {
    if (c.parent_call_id && known.has(c.parent_call_id)) {
      m.set(c.call_id, c.parent_call_id);
    }
  }
  return m;
});

// Pre-parse payloads once and group by call_id. Distributing this
// down to FrameCards via provide eliminates per-frame fetching and
// repeated JSON.parse on every render.
function tryParse(s) {
  if (s == null) return null;
  try { return JSON.parse(s); } catch (_) { return s; }
}
const payloadsByCallId = computed(() => {
  const m = new Map();
  for (const p of requestPayloads.value) {
    const arr = m.get(p.call_id) || [];
    arr.push({ ...p, parsed: p.parsed !== undefined ? p.parsed : tryParse(p.payload_json) });
    m.set(p.call_id, arr);
  }
  return m;
});

// Position of each call in the server's returned order — used by
// WatchPanel to align its row order with the visual top-down order.
const callOrder = computed(() => {
  const m = new Map();
  calls.value.forEach((c, i) => m.set(c.call_id, i));
  return m;
});

provide('payloadsByCallId', payloadsByCallId);
provide('childrenByParent', childrenByParent);

// Shared expansion state for the call tree. A frame's expanded-ness
// is `overrides.get(callId) ?? defaultExpansion`. Per-frame toggles
// write into overrides; expand-all / collapse-all flip the default
// and clear the overrides — so newly-mounted children inherit
// whatever the user most recently asked for, instead of always
// defaulting to "expanded".
const expansionDefault = ref(false);
const expansionOverrides = ref(new Map());
provide('expansionDefault', expansionDefault);
provide('expansionOverrides', expansionOverrides);

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
  highlight.value = null;
  expansionOverrides.value = new Map();
  expansionDefault.value = false;
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

// --- navigator ----------------------------------------------------------
//
// One reactive ref drives every navigation: an "address" of the form
// {callId, kind, pathKey}. Each PayloadViewer compares its own
// (callId, kind) against this ref; only the matching one forwards
// pathKey to its JsonTree subtree. The matching JsonTree node — once
// the chain mounts — sees its own equality flip and renders the flash
// class plus scrolls itself into view via a single watcher. No ref
// maps, no querying, no polling.

const highlight = ref(null);
provide('highlight', highlight);

// Bumped on every goto. JsonTree watches this so re-clicking the
// already-current row (or clicking when the target was mounted but
// pushed off-screen by later expansions) still re-scrolls. Without it,
// a same-address click is a no-op because isMatch never transitions.
const navTick = ref(0);
provide('navTick', navTick);

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

// --- header collapse-all -------------------------------------------------

function collapseAll() {
  expansionDefault.value = false;
  expansionOverrides.value = new Map();
}
function expandAll() {
  expansionDefault.value = true;
  expansionOverrides.value = new Map();
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
.tree-btn {
  background: var(--bg-elevated); border: 1px solid var(--border-strong); color: var(--text-secondary);
  font-size: 0.75rem; padding: 0.3rem 0.55rem; border-radius: 4px; cursor: pointer;
}
.tree-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
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
