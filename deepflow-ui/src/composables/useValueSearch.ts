import { ref, watch } from 'vue';
import type { Ref } from 'vue';
import { api } from '../api/client';
import type { ValueSearchHit } from '../types';

// Drives the right-pane Search tab. The endpoint
// (/api/analysis/value-search) is the same one the Origin panel uses
// internally for chain construction; here we expose it directly to
// the user as a "find every place this value appears" affordance,
// without the source/propagation/mutation framing.
//
// State model:
//   query  — the literal value the user is searching for. Empty
//            means "no search yet"; results are cleared.
//   scope  — 'request' (only the currently-loaded request) or
//            'session' (everything in this session that's still
//            within ClickHouse TTL).
//   hits   — server-returned occurrences, sorted by ts. Cleared
//            on session change.
//   loading / error — usual fetch state.
//
// Fetches only on explicit submit() (Enter or button). No debounced
// fetch-as-you-type — the bloom-filter probe is fast but partial
// strings produce confusing intermediate results.

export type SearchScope = 'request' | 'session';
export type SearchMode = 'exact' | 'substring';

export interface UseValueSearch {
  query: Ref<string>;
  scope: Ref<SearchScope>;
  hits: Ref<ValueSearchHit[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  submitted: Ref<string | null>;
  // Mode of the most recently completed search. Lets the panel
  // distinguish "no exact match — try substring?" from "no
  // substring match either, give up" without a separate flag.
  submittedMode: Ref<SearchMode | null>;
  submit: (mode?: SearchMode) => void;
  clear: () => void;
}

export function useValueSearch(
  sessionId: Ref<string>,
  requestId: Ref<number | null>
): UseValueSearch {
  const query = ref('');
  const scope = ref<SearchScope>('request');
  const hits = ref<ValueSearchHit[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);
  // Records the value of `query` at the moment the user last
  // submitted. Lets the panel say "showing results for X" even if
  // the user keeps typing.
  const submitted = ref<string | null>(null);
  const submittedMode = ref<SearchMode | null>(null);

  async function submit(mode: SearchMode = 'exact'): Promise<void> {
    const q = query.value.trim();
    if (!q) {
      hits.value = [];
      submitted.value = null;
      submittedMode.value = null;
      error.value = null;
      return;
    }
    loading.value = true;
    error.value = null;
    submitted.value = q;
    submittedMode.value = mode;
    try {
      const reqId = scope.value === 'request' ? requestId.value : null;
      const raw = await api.valueSearch(sessionId.value, reqId, q, mode);
      hits.value = dropAxIfArHasIt(raw);
    } catch (e) {
      error.value = (e as Error).message;
      hits.value = [];
    } finally {
      loading.value = false;
    }
  }

  function clear(): void {
    query.value = '';
    submitted.value = null;
    submittedMode.value = null;
    hits.value = [];
    error.value = null;
  }

  // Reset when the session changes. The request changing within one
  // session doesn't auto-clear results — a user who searched session-
  // wide expects results to persist across request swaps.
  watch(sessionId, () => { clear(); });

  return { query, scope, hits, loading, error, submitted, submittedMode, submit, clear };
}

// Drop AX hits whose (call_id, path) also has an AR hit. When AR and
// AX of the same call carry the value at the same path, the call
// did not mutate that field — AX is semantically identical to AR
// there, so its hit is noise. The kept AR hit lands the user on the
// always-rendered AR block, avoiding the case where clicking an AX
// hit in a non-mutated call goes nowhere (FrameCard hides AX unless
// a mutation exists). Mutation sites are unaffected: when AR has the
// OLD value and AX has the NEW one, only one of them matches the
// query and there's no duplicate to drop.
function dropAxIfArHasIt(hits: ValueSearchHit[]): ValueSearchHit[] {
  const arKeys = new Set<string>();
  for (const h of hits) {
    if (h.kind === 'AR') arKeys.add(`${h.call_id}|${JSON.stringify(h.path)}`);
  }
  return hits.filter(h => {
    if (h.kind !== 'AX') return true;
    return !arKeys.has(`${h.call_id}|${JSON.stringify(h.path)}`);
  });
}
