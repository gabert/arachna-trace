<script setup>
// Right-pane peer to WatchPanel. Shows every (call, class, changed-
// fields) PATTERN where one or more objects were silently mutated
// between AR and AX in the loaded request — i.e. the method changed
// an argument's state. Bulk transforms (5000 books with the same
// isbn rewrite) collapse to one group, not 5000 rows.
//
// Detection is server-side (indexed own_hashes scan + grouping by
// changed-path-set). Sample diff is rendered from snapshots the
// server returns. Per-instance diffs (on expand) are computed
// client-side from already-loaded request payloads — no extra
// round-trip.
import { computed, inject, ref, watch } from 'vue';
import ProgressSpinner from 'primevue/progressspinner';
import Message from 'primevue/message';
import { api } from '../api/client.js';
import { diffOwnState } from '../util/envelopeDiff.js';
import { findByObjectId, findPathToObjectId } from '../util/envelope.js';
import { shortClass, shortSig } from '../util/format.js';
import { PAYLOADS_BY_CALL_ID } from '../keys.js';
import DiffEntries from './DiffEntries.vue';

const props = defineProps({
  sessionId: { type: String, required: true },
  requestId: { type: [Number, String], default: null }
});

const emit = defineEmits(['jump']);

// SessionDetailView's already-loaded request payloads, indexed by
// call_id. Used for per-instance diff lookups when a group is expanded.
const payloadsByCallId = inject(PAYLOADS_BY_CALL_ID, ref(new Map()));

const loading = ref(false);
const error = ref(null);
const rawGroups = ref([]);
const summary = ref({ total_mutations: 0, total_groups: 0 });

const expanded = ref(new Set());
function groupKey(g) {
  return `${g.call_id}|${g.class}|${g.field_paths.map(p => p.path + ':' + p.kind).join(',')}`;
}
function isExpanded(g) { return expanded.value.has(groupKey(g)); }
function toggleExpand(g) {
  const k = groupKey(g);
  const next = new Set(expanded.value);
  if (next.has(k)) next.delete(k); else next.add(k);
  expanded.value = next;
}

const groups = computed(() =>
  rawGroups.value.map(g => ({
    ...g,
    sampleDiff: diffOwnState(g.sample.ar_snapshot, g.sample.ax_snapshot)
  }))
);

async function load() {
  if (props.sessionId == null || props.requestId == null) {
    rawGroups.value = [];
    summary.value = { total_mutations: 0, total_groups: 0 };
    return;
  }
  loading.value = true;
  error.value = null;
  try {
    const result = await api.analysisMutations(props.sessionId, props.requestId);
    rawGroups.value = result.groups || [];
    summary.value = result.summary || {};
  } catch (e) {
    error.value = e.message;
    rawGroups.value = [];
  } finally {
    loading.value = false;
  }
}

watch(() => [props.sessionId, props.requestId], load, { immediate: true });

// Per-instance diff for expanded groups. Looks up AR/AX envelopes by
// id from the request's loaded payloads (browser already has them).
function memberDiff(callId, objectId) {
  const calls = payloadsByCallId.value.get(callId) || [];
  const ar = calls.find(p => p.kind === 'AR');
  const ax = calls.find(p => p.kind === 'AX');
  if (!ar?.parsed || !ax?.parsed) return null;
  const arEnv = findByObjectId(ar.parsed, objectId);
  const axEnv = findByObjectId(ax.parsed, objectId);
  if (!arEnv || !axEnv) return null;
  return diffOwnState(arEnv, axEnv);
}

function jumpTo(callId, objectId) {
  // Land on the actual mutated envelope inside AX (e.g. the BookEntity),
  // not the AX args-array root. Walk the loaded AX payload to find the
  // path; PayloadViewer's existing highlight machinery does the rest
  // (expand prefixes, scroll into view, flash).
  const calls = payloadsByCallId.value.get(callId) || [];
  const ax = calls.find(p => p.kind === 'AX');
  const path = ax?.parsed ? findPathToObjectId(ax.parsed, objectId) : null;
  emit('jump', { callId, kind: 'AX', objectId, path: path || [] });
}
</script>

<template>
  <aside class="mutations-panel">
    <header class="mp-head">
      <h3>Mutations</h3>
      <small v-if="!loading && !error">
        {{ summary.total_mutations || 0 }} mutations ·
        {{ summary.total_groups || 0 }}
        {{ summary.total_groups === 1 ? 'pattern' : 'patterns' }}
      </small>
    </header>

    <div v-if="loading" class="centered">
      <ProgressSpinner style="width:1.5rem;height:1.5rem" />
    </div>

    <Message v-else-if="error" severity="error" :closable="false">{{ error }}</Message>

    <p v-else-if="!groups.length" class="mp-empty">
      No within-call mutations in this request. Mutations are detected by
      comparing each method's args at entry (AR) vs at exit (AX) — if the
      agent isn't emitting AX (default config), this panel will always be
      empty.
    </p>

    <ul v-else class="mp-list">
      <li v-for="(g, i) in groups" :key="i" class="mp-group">
        <header class="mp-row-head" @click="jumpTo(g.call_id, g.sample.object_id)">
          <code class="mp-sig">{{ shortSig(g.signature) }}</code>
          <span class="mp-arrow-sep">⏵</span>
          <strong class="mp-class">{{ shortClass(g.class) }}</strong>
          <code class="mp-id">#{{ g.sample.object_id }}</code>
          <span v-if="g.occurrences > 1" class="mp-plus">+{{ g.occurrences - 1 }} more</span>
        </header>

        <DiffEntries :diffs="g.sampleDiff" />

        <button v-if="g.occurrences > 1" class="mp-expand" @click.stop="toggleExpand(g)">
          {{ isExpanded(g)
              ? '▼ collapse'
              : '▶ show ' + (g.occurrences - 1) + ' more occurrence' + (g.occurrences > 2 ? 's' : '') }}
        </button>

        <ul v-if="isExpanded(g) && g.occurrences > 1" class="mp-members">
          <template v-for="oid in g.object_ids" :key="oid">
            <li v-if="oid !== g.sample.object_id"
                class="mp-member"
                @click="jumpTo(g.call_id, oid)">
              <header class="mp-member-head">
                <strong class="mp-class">{{ shortClass(g.class) }}</strong>
                <code class="mp-id">#{{ oid }}</code>
              </header>
              <DiffEntries :diffs="memberDiff(g.call_id, oid) || []" />
            </li>
          </template>
        </ul>
      </li>
    </ul>
  </aside>
</template>

<style scoped>
.mutations-panel {
  background: var(--bg-surface);
  border-left: 1px solid var(--border);
  padding: 0.75rem;
  overflow-y: auto;
  height: 100%;
  color: var(--text-primary);
}
.mp-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 0.5rem; }
.mp-head h3 { margin: 0; font-size: 0.95rem; color: var(--text-primary); }
.mp-head small { color: var(--text-muted); font-size: 0.75rem; }

.mp-empty {
  color: var(--text-secondary);
  font-size: 0.85rem;
  background: var(--bg-elevated);
  border: 1px dashed var(--border-strong);
  border-radius: 4px;
  padding: 0.75rem;
  line-height: 1.55;
}
.centered { display: flex; justify-content: center; padding: 1.5rem; }

.mp-list { list-style: none; padding: 0; margin: 0; }

.mp-group {
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  border-radius: 4px;
  padding: 0.55rem 0.7rem;
  margin-bottom: 0.5rem;
}
.mp-row-head {
  display: flex; gap: 0.35rem; align-items: baseline;
  margin-bottom: 0.35rem;
  font-family: ui-monospace, monospace;
  font-size: var(--mono-size);
  flex-wrap: wrap;
  cursor: pointer;
}
.mp-row-head:hover { outline: 1px solid var(--accent-blue); outline-offset: 2px; border-radius: 2px; }
.mp-sig { color: var(--text-secondary); }
.mp-arrow-sep { color: var(--text-muted); }
.mp-class { color: var(--text-primary); font-weight: 600; }
.mp-id { color: #c4b5fd; }
.mp-plus {
  color: #fbbf24; background: rgba(251, 191, 36, 0.12);
  padding: 0 0.4rem; border-radius: 3px; font-size: 0.75rem;
}

.mp-expand {
  background: transparent; border: 0; padding: 0.3rem 0 0;
  color: var(--accent-blue); font-family: ui-monospace, monospace;
  font-size: var(--mono-size); cursor: pointer;
}
.mp-expand:hover { color: #93c5fd; }

.mp-members {
  list-style: none; padding: 0.4rem 0 0 0.7rem; margin: 0.4rem 0 0;
  border-top: 1px dashed var(--border);
}
.mp-member {
  padding: 0.4rem 0.5rem;
  border-radius: 3px;
  cursor: pointer;
  margin-bottom: 0.25rem;
}
.mp-member:hover { outline: 1px solid var(--accent-blue); outline-offset: -1px; }
.mp-member-head {
  display: flex; gap: 0.35rem; align-items: baseline;
  font-family: ui-monospace, monospace;
  font-size: var(--mono-size);
  margin-bottom: 0.2rem;
}
</style>
