import { computed, ref, watch } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import { api } from '../api/client';
import { tryParse } from '../util/format';
import type { CallMeta, CallRow, PayloadNode, PayloadRow, RequestRow } from '../types';

export interface UseRequestData {
  requests: Ref<RequestRow[]>;
  calls: Ref<CallRow[]>;
  // Flat list of payloads with `parsed` populated. Shared between the
  // call-tree path (via payloadsByCallId) and the watch panel — same
  // parse, no duplication.
  parsedPayloads: ComputedRef<PayloadRow[]>;
  loadingRequests: Ref<boolean>;
  loadingCalls: Ref<boolean>;
  loadingRequestPayloads: Ref<boolean>;
  error: Ref<string | null>;
  payloadsByCallId: ComputedRef<Map<string, PayloadRow[]>>;
  childrenByParent: ComputedRef<Map<string | null, CallRow[]>>;
  rootCalls: ComputedRef<CallRow[]>;
  parentByCallId: ComputedRef<Map<string, string>>;
  callMeta: ComputedRef<Map<string, CallMeta>>;
}

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
//   callMeta          — callId → { ts_in, ts_out, pre, post } for event-time row sort
//
// `selectedRequestId` is owned by the caller (the view binds the Select
// to it). Calls/payloads reload whenever it changes.
export function useRequestData(
  sessionId: Ref<string>,
  selectedRequestId: Ref<number | null>
): UseRequestData {
  const requests = ref<RequestRow[]>([]);
  const calls = ref<CallRow[]>([]);
  const requestPayloadsRaw = ref<PayloadRow[]>([]);
  const loadingRequests = ref(false);
  const loadingCalls = ref(false);
  const loadingRequestPayloads = ref(false);
  const error = ref<string | null>(null);

  async function loadRequests(): Promise<void> {
    loadingRequests.value = true;
    error.value = null;
    try {
      requests.value = await api.listRequests(sessionId.value);
      if (requests.value.length && selectedRequestId.value == null) {
        selectedRequestId.value = requests.value[0].request_id;
      }
    } catch (e) {
      error.value = (e as Error).message;
    } finally {
      loadingRequests.value = false;
    }
  }

  async function loadCalls(): Promise<void> {
    if (selectedRequestId.value == null) {
      calls.value = [];
      requestPayloadsRaw.value = [];
      return;
    }
    loadingCalls.value = true;
    try {
      calls.value = await api.callTree(sessionId.value, { requestId: selectedRequestId.value });
    } catch (e) {
      error.value = (e as Error).message;
    } finally {
      loadingCalls.value = false;
    }
    loadingRequestPayloads.value = true;
    try {
      requestPayloadsRaw.value = await api.requestPayloads(sessionId.value, selectedRequestId.value);
    } catch (e) {
      error.value = (e as Error).message;
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

  // Single canonical parse pass. Both downstream consumers — the
  // call-tree view (via payloadsByCallId) and the watch panel (via
  // parsedPayloads) — read these rows and never re-parse, so a 10k-
  // payload request triggers exactly one JSON.parse per row.
  const parsedPayloads: ComputedRef<PayloadRow[]> = computed(() =>
    requestPayloadsRaw.value.map(p => ({
      ...p,
      parsed: p.parsed !== undefined ? p.parsed : (tryParse<PayloadNode>(p.payload_json) ?? undefined)
    }))
  );

  const payloadsByCallId: ComputedRef<Map<string, PayloadRow[]>> = computed(() => {
    const m = new Map<string, PayloadRow[]>();
    for (const p of parsedPayloads.value) {
      const arr = m.get(p.call_id) || [];
      arr.push(p);
      m.set(p.call_id, arr);
    }
    return m;
  });

  // Build the parent → ordered children map. Children inherit the
  // server's seq order so iterating them in array order = time order.
  const childrenByParent: ComputedRef<Map<string | null, CallRow[]>> = computed(() => {
    const known = new Set(calls.value.map(c => c.call_id));
    const m = new Map<string | null, CallRow[]>();
    for (const c of calls.value) {
      const parent = c.parent_call_id && known.has(c.parent_call_id) ? c.parent_call_id : null;
      if (!m.has(parent)) m.set(parent, []);
      m.get(parent)!.push(c);
    }
    return m;
  });

  const rootCalls: ComputedRef<CallRow[]> = computed(() =>
    childrenByParent.value.get(null) || []);

  const parentByCallId: ComputedRef<Map<string, string>> = computed(() => {
    const known = new Set(calls.value.map(c => c.call_id));
    const m = new Map<string, string>();
    for (const c of calls.value) {
      if (c.parent_call_id && known.has(c.parent_call_id)) {
        m.set(c.call_id, c.parent_call_id);
      }
    }
    return m;
  });

  // DFS over childrenByParent (which already orders children by seq)
  // gives every call a (pre, post) pair. Together they form a single
  // chronological clock for every entry and exit event in the trace,
  // which is what tied-ms tiebreakers need to be correct in all
  // nested-vs-sibling configurations.
  const callMeta: ComputedRef<Map<string, CallMeta>> = computed(() => {
    const m = new Map<string, CallMeta>();
    let counter = 0;
    const dfs = (callId: string, ts_in: string, ts_out: string): void => {
      const pre = counter++;
      const children = childrenByParent.value.get(callId) || [];
      for (const child of children) {
        dfs(child.call_id, child.ts_in, child.ts_out);
      }
      const post = counter++;
      m.set(callId, { ts_in, ts_out, pre, post });
    };
    const roots = childrenByParent.value.get(null) || [];
    for (const root of roots) dfs(root.call_id, root.ts_in, root.ts_out);
    return m;
  });

  return {
    requests,
    calls,
    parsedPayloads,
    loadingRequests,
    loadingCalls,
    loadingRequestPayloads,
    error,
    payloadsByCallId,
    childrenByParent,
    rootCalls,
    parentByCallId,
    callMeta
  };
}
