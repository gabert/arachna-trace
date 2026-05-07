import { computed, ref, watch } from 'vue';
import type { ComputedRef, Ref } from 'vue';
import { api } from '../api/client';
import type {
  MutationGroup,
  MutationsSummary
} from '../types';

// Replaces the older client-side useArAxAnalysis. The server's
// /api/analysis/mutations endpoint already computes per-call mutated
// and added object-id sets in the same SQL pass it uses for the
// grouped panel view. Walking parsed payloads in the browser to
// derive the same data was duplicated work.
//
// One fetch per (sessionId, requestId), exposed as both:
//   - groups / summary  (consumed by MutationsPanel render)
//   - mutatedObjectsByCallId / addedObjectsByCallId  (consumed by
//     PayloadViewer to scope JsonTree's in-tree marks down to AX)
//
// Loading / error are exposed so MutationsPanel can render its own
// spinner / error state without a second fetch.

export interface UseObjectChanges {
  loading: Ref<boolean>;
  error: Ref<string | null>;
  groups: Ref<MutationGroup[]>;
  summary: Ref<MutationsSummary>;
  mutatedObjectsByCallId: ComputedRef<Map<string, Set<number>>>;
  addedObjectsByCallId: ComputedRef<Map<string, Set<number>>>;
}

const EMPTY_SUMMARY: MutationsSummary = { total_mutations: 0, total_groups: 0 };

export function useObjectChanges(
  sessionId: Ref<string>,
  requestId: Ref<number | null>
): UseObjectChanges {
  const loading = ref(false);
  const error = ref<string | null>(null);
  const groups = ref<MutationGroup[]>([]);
  const summary = ref<MutationsSummary>({ ...EMPTY_SUMMARY });
  // perCall stays as raw rows; consumers access it via the Map
  // computeds below.
  const perCall = ref<Array<{ call_id: string; mutated: number[]; added: number[] }>>([]);

  async function load(): Promise<void> {
    if (sessionId.value == null || requestId.value == null) {
      groups.value = [];
      summary.value = { ...EMPTY_SUMMARY };
      perCall.value = [];
      return;
    }
    loading.value = true;
    error.value = null;
    try {
      const result = await api.analysisMutations(sessionId.value, requestId.value);
      groups.value = result.groups || [];
      summary.value = result.summary || { ...EMPTY_SUMMARY };
      perCall.value = result.perCall || [];
    } catch (e) {
      error.value = (e as Error).message;
      groups.value = [];
      summary.value = { ...EMPTY_SUMMARY };
      perCall.value = [];
    } finally {
      loading.value = false;
    }
  }

  watch(() => [sessionId.value, requestId.value], load, { immediate: true });

  const mutatedObjectsByCallId: ComputedRef<Map<string, Set<number>>> = computed(() => {
    const m = new Map<string, Set<number>>();
    for (const r of perCall.value) {
      if (r.mutated && r.mutated.length > 0) {
        m.set(r.call_id, new Set(r.mutated));
      }
    }
    return m;
  });

  const addedObjectsByCallId: ComputedRef<Map<string, Set<number>>> = computed(() => {
    const m = new Map<string, Set<number>>();
    for (const r of perCall.value) {
      if (r.added && r.added.length > 0) {
        m.set(r.call_id, new Set(r.added));
      }
    }
    return m;
  });

  return {
    loading,
    error,
    groups,
    summary,
    mutatedObjectsByCallId,
    addedObjectsByCallId
  };
}
