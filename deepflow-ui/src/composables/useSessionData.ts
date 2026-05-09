import { computed, ref, watch } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import { api } from '../api/client';
import { tryParse } from '../util/format';
import type { CallMeta, CallRow, PayloadNode, PayloadRow, RequestRow } from '../types';

// Session-scoped data layer.
//
// Loads the session's REQUEST inventory eagerly (cheap, from the
// requests rollup). Loads each REQUEST's call tree LAZILY on demand —
// triggered when the user expands a RequestNode, navigates into a
// call from a different request, etc. Payloads are loaded per-call
// with a refcount-managed cache (see acquire/release).
//
// Why lazy per-request: a session with thousands of requests times
// thousands of calls is megabytes of call-row JSON. Eager loading
// stalls the UI and pins the data in memory even when the user only
// looks at one request. Lazy load scales the on-screen footprint to
// what the developer is actually inspecting.
//
// What lives session-wide vs per-request:
//   - requests inventory (rollup): session-wide
//   - calls tree:                  loaded per-request, cached
//   - payloads:                    loaded per-call, refcount-cached
//   - exception nav, instance trace, value search, mutations:
//     server-side queries (not derived from local calls)

export interface UseSessionData {
  // Server data
  requests: Ref<RequestRow[]>;
  loadingRequests: Ref<boolean>;
  error: Ref<string | null>;

  // Lazy per-request call cache.
  loadedRequestIds: ComputedRef<Set<number>>;
  loadingRequestIds: Ref<Set<number>>;
  isRequestLoaded: (requestId: number) => boolean;
  // Promise-returning loader. Concurrent calls dedupe via in-flight
  // map. Idempotent: once loaded, future calls resolve immediately.
  loadRequestCalls: (requestId: number) => Promise<CallRow[]>;
  // Bulk loader — useful when a panel needs multiple requests' call
  // trees (e.g. Origin building a chain across requests). Returns
  // when all requested ids are loaded.
  ensureRequestsLoaded: (requestIds: Iterable<number>) => Promise<void>;

  // Flat union of every loaded request's calls. Recomputes when the
  // cache changes; cheap O(N) where N is loaded calls.
  calls: ComputedRef<CallRow[]>;

  // Tree shape — derived from the loaded calls union.
  childrenByParent: ComputedRef<Map<string | null, CallRow[]>>;
  rootCallsByRequestId: ComputedRef<Map<number, CallRow[]>>;
  parentByCallId: ComputedRef<Map<string, string>>;
  requestIdByCallId: ComputedRef<Map<string, number>>;
  // pre/post counters assigned in chronological-by-request order so
  // cross-request chrono is monotonic. Only contains entries for
  // currently-loaded requests; the chronoIndex helper falls back to
  // MAX_SAFE_INTEGER for absent entries (sort-last behavior).
  callMeta: ComputedRef<Map<string, CallMeta>>;

  // Lazy payload cache — see acquire/release for ownership rules.
  payloadsByCallId: ComputedRef<Map<string, PayloadRow[]>>;
  loadingCallIds: ComputedRef<Set<string>>;
  acquireCallPayloads: (callId: string) => Promise<PayloadRow[]>;
  releaseCallPayloads: (callId: string) => void;
}

export function useSessionData(sessionId: Ref<string>): UseSessionData {
  const requests = ref<RequestRow[]>([]);
  const loadingRequests = ref(false);
  const error = ref<string | null>(null);

  // Per-request call-tree cache. Reactive Map so derived state
  // recomputes when a request loads.
  const requestCallsCache = ref<Map<number, CallRow[]>>(new Map());
  // Reactive set so RequestNode can render a spinner while its calls
  // are in flight.
  const loadingRequestIds = ref<Set<number>>(new Set());
  // In-flight promises (non-reactive bookkeeping).
  const inFlightRequests = new Map<number, Promise<CallRow[]>>();

  // Payload cache — same model as before the lazy refactor.
  const payloadCache = ref<Map<string, PayloadRow[]>>(new Map());
  const refCounts = new Map<string, number>();
  const inFlight = new Map<string, Promise<PayloadRow[]>>();
  const inFlightIds = ref<Set<string>>(new Set());

  async function loadRequestsList(): Promise<void> {
    loadingRequests.value = true;
    error.value = null;
    try {
      requests.value = await api.listRequests(sessionId.value);
    } catch (e) {
      error.value = (e as Error).message;
    } finally {
      loadingRequests.value = false;
    }
  }

  function loadRequestCalls(requestId: number): Promise<CallRow[]> {
    const cached = requestCallsCache.value.get(requestId);
    if (cached) return Promise.resolve(cached);
    const inFlightP = inFlightRequests.get(requestId);
    if (inFlightP) return inFlightP;

    const next = new Set(loadingRequestIds.value);
    next.add(requestId);
    loadingRequestIds.value = next;

    const sid = sessionId.value;
    const p = api.callTree(sid, { requestId })
      .then(rows => {
        // Stale-fetch guard: the user may have switched sessions
        // while we were awaiting the response.
        if (sid !== sessionId.value) return [];
        const cache = new Map(requestCallsCache.value);
        cache.set(requestId, rows);
        requestCallsCache.value = cache;
        return rows;
      })
      .finally(() => {
        inFlightRequests.delete(requestId);
        const next2 = new Set(loadingRequestIds.value);
        next2.delete(requestId);
        loadingRequestIds.value = next2;
      });
    inFlightRequests.set(requestId, p);
    return p;
  }

  function ensureRequestsLoaded(requestIds: Iterable<number>): Promise<void> {
    const promises: Promise<unknown>[] = [];
    for (const rid of requestIds) {
      if (!requestCallsCache.value.has(rid)) {
        promises.push(loadRequestCalls(rid));
      }
    }
    return Promise.all(promises).then(() => undefined);
  }

  function isRequestLoaded(requestId: number): boolean {
    return requestCallsCache.value.has(requestId);
  }

  // ---- payload cache ----

  function startPayloadFetch(callId: string): Promise<PayloadRow[]> {
    if (inFlight.has(callId)) return inFlight.get(callId)!;
    const next = new Set(inFlightIds.value);
    next.add(callId);
    inFlightIds.value = next;
    const p = api.callPayloads(callId)
      .then(rows => {
        const parsed: PayloadRow[] = rows.map(r => ({
          ...r,
          // The /api/calls/{id}/payloads endpoint doesn't echo call_id
          // back in each row; stamp it in so flat consumers can rely
          // on row.call_id being populated, just like rows from
          // requestPayloads / objectPayloads.
          call_id: callId,
          parsed: r.parsed !== undefined
            ? r.parsed
            : (tryParse<PayloadNode>(r.payload_json) ?? undefined)
        }));
        if ((refCounts.get(callId) || 0) > 0) {
          const cache = new Map(payloadCache.value);
          cache.set(callId, parsed);
          payloadCache.value = cache;
        }
        return parsed;
      })
      .finally(() => {
        inFlight.delete(callId);
        const next2 = new Set(inFlightIds.value);
        next2.delete(callId);
        inFlightIds.value = next2;
      });
    inFlight.set(callId, p);
    return p;
  }

  function acquireCallPayloads(callId: string): Promise<PayloadRow[]> {
    refCounts.set(callId, (refCounts.get(callId) || 0) + 1);
    const cached = payloadCache.value.get(callId);
    if (cached) return Promise.resolve(cached);
    return startPayloadFetch(callId);
  }

  function releaseCallPayloads(callId: string): void {
    const cur = refCounts.get(callId) || 0;
    if (cur <= 1) {
      refCounts.delete(callId);
      if (payloadCache.value.has(callId)) {
        const cache = new Map(payloadCache.value);
        cache.delete(callId);
        payloadCache.value = cache;
      }
    } else {
      refCounts.set(callId, cur - 1);
    }
  }

  // ---- session-change reset ----

  watch(sessionId, () => {
    requestCallsCache.value = new Map();
    loadingRequestIds.value = new Set();
    inFlightRequests.clear();
    payloadCache.value = new Map();
    refCounts.clear();
    inFlight.clear();
    inFlightIds.value = new Set();
    loadRequestsList();
  }, { immediate: true });

  // ---- derived state ----

  const loadedRequestIds: ComputedRef<Set<number>> = computed(() =>
    new Set(requestCallsCache.value.keys()));

  const calls: ComputedRef<CallRow[]> = computed(() => {
    const out: CallRow[] = [];
    for (const arr of requestCallsCache.value.values()) {
      for (const c of arr) out.push(c);
    }
    return out;
  });

  const payloadsByCallId: ComputedRef<Map<string, PayloadRow[]>> = computed(() =>
    payloadCache.value);

  const loadingCallIds: ComputedRef<Set<string>> = computed(() =>
    inFlightIds.value);

  const childrenByParent: ComputedRef<Map<string | null, CallRow[]>> = computed(() => {
    // Build per-request-aware children: a parent_call_id from a
    // not-yet-loaded request would be unknown to us, so treat as root.
    // Parent-child links are always within the same request, so we
    // never miss real parents this way.
    const known = new Set(calls.value.map(c => c.call_id));
    const m = new Map<string | null, CallRow[]>();
    for (const c of calls.value) {
      const parent = c.parent_call_id && known.has(c.parent_call_id) ? c.parent_call_id : null;
      if (!m.has(parent)) m.set(parent, []);
      m.get(parent)!.push(c);
    }
    return m;
  });

  const rootCallsByRequestId: ComputedRef<Map<number, CallRow[]>> = computed(() => {
    const roots = childrenByParent.value.get(null) || [];
    const m = new Map<number, CallRow[]>();
    for (const c of roots) {
      const rid = Number(c.request_id);
      if (!m.has(rid)) m.set(rid, []);
      m.get(rid)!.push(c);
    }
    return m;
  });

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

  const requestIdByCallId: ComputedRef<Map<string, number>> = computed(() => {
    const m = new Map<string, number>();
    for (const c of calls.value) m.set(c.call_id, Number(c.request_id));
    return m;
  });

  // pre/post counters with global monotonic order across loaded
  // requests. Iterate `requests` (server-ordered ASC by first_call)
  // so cross-request chrono follows wall-clock; per-request DFS
  // gives correct entry/exit ordering within the tree.
  const callMeta: ComputedRef<Map<string, CallMeta>> = computed(() => {
    const m = new Map<string, CallMeta>();
    let counter = 0;
    for (const r of requests.value) {
      const rid = Number(r.request_id);
      const requestCalls = requestCallsCache.value.get(rid);
      if (!requestCalls || requestCalls.length === 0) continue;
      // per-request children index
      const known = new Set(requestCalls.map(c => c.call_id));
      const cbp = new Map<string | null, CallRow[]>();
      for (const c of requestCalls) {
        const parent = c.parent_call_id && known.has(c.parent_call_id) ? c.parent_call_id : null;
        if (!cbp.has(parent)) cbp.set(parent, []);
        cbp.get(parent)!.push(c);
      }
      const dfs = (callId: string, ts_in: string, ts_out: string): void => {
        const pre = counter++;
        const children = cbp.get(callId) || [];
        for (const child of children) {
          dfs(child.call_id, child.ts_in, child.ts_out);
        }
        const post = counter++;
        m.set(callId, { ts_in, ts_out, pre, post });
      };
      const roots = cbp.get(null) || [];
      for (const root of roots) dfs(root.call_id, root.ts_in, root.ts_out);
    }
    return m;
  });

  return {
    requests,
    loadingRequests,
    error,
    loadedRequestIds,
    loadingRequestIds,
    isRequestLoaded,
    loadRequestCalls,
    ensureRequestsLoaded,
    calls,
    childrenByParent,
    rootCallsByRequestId,
    parentByCallId,
    requestIdByCallId,
    callMeta,
    payloadsByCallId,
    loadingCallIds,
    acquireCallPayloads,
    releaseCallPayloads
  };
}
