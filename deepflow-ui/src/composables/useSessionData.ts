import { computed, ref, watch } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import { api } from '../api/client';
import { tryParse } from '../util/format';
import type { CallMeta, CallRow, PayloadNode, PayloadRow, RequestRow } from '../types';

// Session-scoped data layer. Replaces the old request-scoped
// useRequestData — the call tree now spans the entire session and
// individual payloads are lazy-loaded on demand instead of fetched
// upfront per request.
//
// The payload cache is reference-counted: a consumer (typically a
// mounted CallInspectionCard) calls acquireCallPayloads(callId) on
// mount and releaseCallPayloads(callId) on unmount. When the refcount
// drops to zero the entry is evicted from the cache. Cache size is
// therefore bounded by what is currently rendered — closing a card
// drops its payloads.
//
// Cross-cutting features that need session-wide payload knowledge
// (instance trace, value search, provenance, mutations) go through
// dedicated server endpoints rather than walking the local cache,
// since the cache is a moving subset of the truth.

export interface UseSessionData {
  // Server data
  requests: Ref<RequestRow[]>;
  calls: Ref<CallRow[]>;
  loadingRequests: Ref<boolean>;
  loadingCalls: Ref<boolean>;
  error: Ref<string | null>;

  // Tree shape — derived from `calls`, session-wide
  childrenByParent: ComputedRef<Map<string | null, CallRow[]>>;
  rootCallsByRequestId: ComputedRef<Map<number, CallRow[]>>;
  parentByCallId: ComputedRef<Map<string, string>>;
  // Reverse lookup so highlightCall's auto-expand walk knows which
  // request node to expand for a target call.
  requestIdByCallId: ComputedRef<Map<string, number>>;
  callMeta: ComputedRef<Map<string, CallMeta>>;

  // Lazy payload cache — see acquire/release for ownership rules.
  // payloadsByCallId is the live view of what's currently cached; it
  // shrinks as cards close.
  payloadsByCallId: ComputedRef<Map<string, PayloadRow[]>>;
  // Set of call_ids whose payloads are currently being fetched. Used
  // by CallInspectionCard to render a loading state.
  loadingCallIds: ComputedRef<Set<string>>;

  // Refcount-managed acquire/release. Returns the loaded payloads
  // (resolves once the fetch completes; cached resolves immediately).
  // Concurrent acquires of the same callId share one in-flight fetch.
  acquireCallPayloads: (callId: string) => Promise<PayloadRow[]>;
  releaseCallPayloads: (callId: string) => void;
}

export function useSessionData(sessionId: Ref<string>): UseSessionData {
  const requests = ref<RequestRow[]>([]);
  const calls = ref<CallRow[]>([]);
  const loadingRequests = ref(false);
  const loadingCalls = ref(false);
  const error = ref<string | null>(null);

  // payload cache, keyed by call_id. Reactive so consumers see new
  // entries land and old entries vanish.
  const payloadCache = ref<Map<string, PayloadRow[]>>(new Map());

  // Refcounts and in-flight promises live outside the reactive layer —
  // they're internal bookkeeping, not state to render.
  const refCounts = new Map<string, number>();
  const inFlight = new Map<string, Promise<PayloadRow[]>>();
  // Reactive copy of in-flight ids so consumers can render a loading
  // state without subscribing to a Map outside the reactive layer.
  const inFlightIds = ref<Set<string>>(new Set());

  async function loadSession(): Promise<void> {
    loadingRequests.value = true;
    loadingCalls.value = true;
    error.value = null;
    try {
      const [reqs, allCalls] = await Promise.all([
        api.listRequests(sessionId.value),
        api.callTree(sessionId.value, {})
      ]);
      requests.value = reqs;
      calls.value = allCalls;
    } catch (e) {
      error.value = (e as Error).message;
    } finally {
      loadingRequests.value = false;
      loadingCalls.value = false;
    }
  }

  // On session change, drop the entire cache + bookkeeping. Old call_ids
  // don't exist in the new session anyway; releasing the cleanly is
  // simpler than tracking "is this id still in scope".
  watch(sessionId, () => {
    payloadCache.value = new Map();
    refCounts.clear();
    inFlight.clear();
    inFlightIds.value = new Set();
    loadSession();
  }, { immediate: true });

  function startFetch(callId: string): Promise<PayloadRow[]> {
    if (inFlight.has(callId)) return inFlight.get(callId)!;
    const next = new Set(inFlightIds.value);
    next.add(callId);
    inFlightIds.value = next;
    const p = api.callPayloads(callId)
      .then(rows => {
        const parsed: PayloadRow[] = rows.map(r => ({
          ...r,
          parsed: r.parsed !== undefined
            ? r.parsed
            : (tryParse<PayloadNode>(r.payload_json) ?? undefined)
        }));
        // Only commit to cache if there's still at least one consumer.
        // A release that races the fetch to zero would otherwise leak
        // a stale entry past its eviction point.
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
    return startFetch(callId);
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

  // ----- derived state -----

  const payloadsByCallId: ComputedRef<Map<string, PayloadRow[]>> = computed(() =>
    payloadCache.value);

  const loadingCallIds: ComputedRef<Set<string>> = computed(() =>
    inFlightIds.value);

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
    loadingRequests,
    loadingCalls,
    error,
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
