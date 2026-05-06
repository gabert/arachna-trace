import { computed, ref, watch } from 'vue';
import { api } from '../api/client.js';
import { tryParse } from '../util/format.js';

// Loads requests / calls / payloads for a session and exposes the
// derived maps the rest of the view needs:
//
//   requests          — list of requests in the session
//   calls             — flat list of calls in the selected request
//   requestPayloads   — flat list of payload rows
//   payloadsByCallId  — calls grouped, with payload_json lazily parsed
//   childrenByParent  — parent → ordered children
//   parentByCallId    — child → parent (for the navigator's ancestor walk)
//   rootCalls         — calls with no known parent (rendered as roots)
//   callOrder         — callId → server-returned index (for stable sort)
//
// `selectedRequestId` is owned by the caller (the view binds the Select
// to it). Calls/payloads reload whenever it changes.
export function useRequestData(sessionId, selectedRequestId) {
  const requests = ref([]);
  const calls = ref([]);
  const requestPayloads = ref([]);
  const loadingRequests = ref(false);
  const loadingCalls = ref(false);
  const loadingRequestPayloads = ref(false);
  const error = ref(null);

  async function loadRequests() {
    loadingRequests.value = true;
    error.value = null;
    try {
      requests.value = await api.listRequests(sessionId.value);
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
    try {
      calls.value = await api.callTree(sessionId.value, { requestId: selectedRequestId.value });
    } catch (e) {
      error.value = e.message;
    } finally {
      loadingCalls.value = false;
    }
    loadingRequestPayloads.value = true;
    try {
      requestPayloads.value = await api.requestPayloads(sessionId.value, selectedRequestId.value);
    } catch (e) {
      error.value = e.message;
    } finally {
      loadingRequestPayloads.value = false;
    }
  }

  // Reload requests when the session itself changes, and reset the
  // selected request — its id space differs across sessions.
  watch(sessionId, () => {
    selectedRequestId.value = null;
    loadRequests();
  }, { immediate: true });

  watch(selectedRequestId, loadCalls);

  // Pre-parse payloads once and group by call_id. Distributing this
  // down to FrameCards via provide eliminates per-frame JSON.parse on
  // every render.
  const payloadsByCallId = computed(() => {
    const m = new Map();
    for (const p of requestPayloads.value) {
      const arr = m.get(p.call_id) || [];
      arr.push({ ...p, parsed: p.parsed !== undefined ? p.parsed : tryParse(p.payload_json) });
      m.set(p.call_id, arr);
    }
    return m;
  });

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

  const rootCalls = computed(() => childrenByParent.value.get(null) || []);

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

  const callOrder = computed(() => {
    const m = new Map();
    calls.value.forEach((c, i) => m.set(c.call_id, i));
    return m;
  });

  return {
    requests,
    calls,
    requestPayloads,
    loadingRequests,
    loadingCalls,
    loadingRequestPayloads,
    error,
    payloadsByCallId,
    childrenByParent,
    rootCalls,
    parentByCallId,
    callOrder
  };
}
