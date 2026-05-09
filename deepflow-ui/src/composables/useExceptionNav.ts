import { computed, ref, watch } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import { api } from '../api/client';

// Exception navigator for the call-tree fixed header.
//
// Fetches the session's exception list from the server (one row per
// call where return_type=EXCEPTION, server-ordered by request +
// chrono). Local computation across calls.value would miss exceptions
// in unloaded requests now that the call tree loads lazily per
// request — the server is the only source of truth.
//
// Random-access nav: clicking ↑/↓ or the per-row exception bubble
// triggers the navigator-passed selectCall + highlightCallRow. The
// caller is responsible for ensuring the target's request is loaded
// before the highlight resolves; for cycle nav we await
// ensureRequestLoaded ourselves so a call in a not-yet-loaded request
// still lands cleanly.

export interface ExceptionEntry {
  call_id: string;
  request_id: number;
  signature: string;
  ts_in: string;
  thread_name: string;
}

export interface UseExceptionNavArgs {
  sessionId: Ref<string>;
  selectedCallId: Ref<string | null>;
  // Async wrappers — selectCall opens the inspection card,
  // highlightCallRow flashes the row in the tree. Both are
  // navigator-side concerns, lifted in.
  selectCall: (callId: string) => void | Promise<void>;
  highlightCallRow: (callId: string) => void;
  // Loader so we can guarantee the target's request is in the cache
  // before highlighting (otherwise the row isn't rendered yet).
  ensureRequestLoaded: (requestId: number) => Promise<unknown>;
}

export interface UseExceptionNav {
  exceptions: Ref<ExceptionEntry[]>;
  exceptionCount: ComputedRef<number>;
  exceptionCursor: ComputedRef<number>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  gotoNextException: () => Promise<void>;
  gotoPrevException: () => Promise<void>;
  gotoException: (callId: string) => Promise<void>;
}

export function useExceptionNav(args: UseExceptionNavArgs): UseExceptionNav {
  const { sessionId, selectedCallId, selectCall, highlightCallRow, ensureRequestLoaded } = args;

  const exceptions = ref<ExceptionEntry[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  watch(sessionId, async (sid) => {
    exceptions.value = [];
    error.value = null;
    if (!sid) return;
    loading.value = true;
    try {
      const rows = await api.exceptionCalls(sid);
      // Stale-fetch guard.
      if (sessionId.value !== sid) return;
      exceptions.value = rows.map(r => ({
        call_id: r.call_id,
        request_id: Number(r.request_id),
        signature: r.signature,
        ts_in: r.ts_in,
        thread_name: r.thread_name
      }));
    } catch (e) {
      error.value = (e as Error).message;
    } finally {
      loading.value = false;
    }
  }, { immediate: true });

  const exceptionCount = computed(() => exceptions.value.length);

  const exceptionCursor = computed<number>(() => {
    const id = selectedCallId.value;
    if (!id) return -1;
    return exceptions.value.findIndex(e => e.call_id === id);
  });

  async function gotoException(callId: string): Promise<void> {
    const entry = exceptions.value.find(e => e.call_id === callId);
    if (!entry) return;
    // Ensure the row is rendered before highlighting; the highlight
    // walks parentByCallId to expand ancestors and that walk needs
    // the call to exist in the loaded set.
    await ensureRequestLoaded(entry.request_id);
    await selectCall(callId);
    highlightCallRow(callId);
  }

  async function gotoExceptionAt(index: number): Promise<void> {
    const list = exceptions.value;
    if (!list.length) return;
    const wrapped = ((index % list.length) + list.length) % list.length;
    await gotoException(list[wrapped].call_id);
  }

  async function gotoNextException(): Promise<void> { await gotoExceptionAt(exceptionCursor.value + 1); }
  async function gotoPrevException(): Promise<void> { await gotoExceptionAt(exceptionCursor.value - 1); }

  return {
    exceptions,
    exceptionCount,
    exceptionCursor,
    loading,
    error,
    gotoNextException,
    gotoPrevException,
    gotoException
  };
}
