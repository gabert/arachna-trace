<script setup lang="ts">
// Right-pane peer to WatchPanel. Shows every (call, class, changed-
// fields) PATTERN where one or more objects were silently mutated
// between AR and AX in the loaded request — i.e. the method changed
// an argument's state. Bulk transforms (5000 books with the same
// isbn rewrite) collapse to one group, not 5000 rows.
//
// Detection is server-side (indexed own_hashes scan + grouping by
// changed-path-set in QueryHandler.analysisMutations). Sample diff
// is rendered from snapshots the server returns. Per-instance diffs
// (on expand) are computed client-side from already-loaded request
// payloads — no extra round-trip.
//
// The fetch itself is owned by SessionDetailView via useObjectChanges
// so the same call powers JsonTree's in-tree marks and this panel
// from a single network request.
import { computed, inject, ref } from 'vue';
import ProgressSpinner from 'primevue/progressspinner';
import Message from 'primevue/message';
import { diffOwnState } from '../util/envelopeDiff';
import { findByObjectId, findPathToObjectId } from '../util/envelope';
import { shortClass, shortSig } from '../util/format';
import { PAYLOADS_BY_CALL_ID } from '../keys';
import CollapsiblePanel from './CollapsiblePanel.vue';
import DiffEntries from './DiffEntries.vue';
import type {
  DiffEntry,
  JumpAddress,
  MutationGroup,
  MutationsSummary,
  PayloadRow
} from '../types';

const props = defineProps<{
  groups: MutationGroup[];
  summary: MutationsSummary;
  loading: boolean;
  error: string | null;
}>();

const emit = defineEmits<{
  (e: 'jump', addr: JumpAddress): void;
}>();

// SessionDetailView's already-loaded request payloads, indexed by
// call_id. Used for per-instance diff lookups when a group is expanded.
const payloadsByCallId = inject(PAYLOADS_BY_CALL_ID, computed(() => new Map<string, PayloadRow[]>()));

interface MutationGroupView extends MutationGroup {
  sampleDiff: DiffEntry[];
}

const expanded = ref<Set<string>>(new Set());
function groupKey(g: MutationGroup): string {
  return `${g.call_id}|${g.class}|${g.field_paths.map(p => p.path + ':' + p.kind).join(',')}`;
}
function isCollapsed(g: MutationGroup): boolean { return !expanded.value.has(groupKey(g)); }
function setCollapsed(g: MutationGroup, v: boolean): void {
  const k = groupKey(g);
  const next = new Set(expanded.value);
  if (v) next.delete(k); else next.add(k);
  expanded.value = next;
}

const groupsView = computed<MutationGroupView[]>(() =>
  props.groups.map(g => ({
    ...g,
    sampleDiff: diffOwnState(g.sample.ar_snapshot, g.sample.ax_snapshot)
  }))
);

// Per-instance diff for expanded groups. Looks up AR/AX envelopes by
// id from the request's loaded payloads (browser already has them).
function memberDiff(callId: string, objectId: number): DiffEntry[] | null {
  const calls = payloadsByCallId.value.get(callId) || [];
  const ar = calls.find(p => p.kind === 'AR');
  const ax = calls.find(p => p.kind === 'AX');
  if (!ar?.parsed || !ax?.parsed) return null;
  const arEnv = findByObjectId(ar.parsed, objectId);
  const axEnv = findByObjectId(ax.parsed, objectId);
  if (!arEnv || !axEnv) return null;
  return diffOwnState(arEnv, axEnv);
}

// Local "last jumped from" highlight. Intentionally NOT tied to the
// global CALL_HIGHLIGHT ref — the call-tree/inspection-card pair
// represents "where the user is now" and moves with every click;
// the tool panel represents "what I jumped from in this tool" and
// stays put until the user picks another row here, even when the
// user goes investigating elsewhere in the tree. Same yellow as the
// unified focus, but a different meaning — and that's what makes
// the panes feel connected instead of erasing themselves on every
// unrelated tree click.
const lastJumpedCallId = ref<string | null>(null);

function jumpTo(g: MutationGroup, objectId: number): void {
  // Land on the actual mutated envelope inside AX (e.g. the BookEntity),
  // not the AX args-array root. Walk the loaded AX payload to find the
  // path; PayloadViewer's existing highlight machinery does the rest
  // (expand prefixes, scroll into view, flash).
  // Mutations are session-wide — the target call's request may not
  // be loaded yet; carry request_id so the navigator can ensure-load
  // before opening the card.
  const callId = g.call_id;
  const calls = payloadsByCallId.value.get(callId) || [];
  const ax = calls.find(p => p.kind === 'AX');
  const path = ax?.parsed ? findPathToObjectId(ax.parsed, objectId) : null;
  lastJumpedCallId.value = callId;
  emit('jump', {
    callId,
    kind: 'AX',
    objectId,
    path: path || [],
    requestId: g.request_id != null ? Number(g.request_id) : undefined
  });
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

    <p v-else-if="!groupsView.length" class="mp-empty">
      No within-call mutations in this request. Mutations are detected by
      comparing each method's args at entry (AR) vs at exit (AX) — if the
      agent isn't emitting AX (default config), this panel will always be
      empty.
    </p>

    <ul v-else class="mp-list">
      <li v-for="(g, i) in groupsView" :key="i"
          class="mp-group"
          :class="{ highlighted: lastJumpedCallId === g.call_id }">
        <!-- The sample (first occurrence) header AND its diff entries
             share one click target — same affordance as the per-member
             rows below. Avoids "wide row clickable up top, narrow text
             clickable for the same hop". -->
        <div class="mp-sample" @click="jumpTo(g, g.sample.object_id)">
          <header class="mp-row-head">
            <code class="mp-sig">{{ shortSig(g.signature) }}</code>
            <span class="mp-arrow-sep">⏵</span>
            <strong class="mp-class">{{ shortClass(g.class) }}</strong>
            <code class="mp-id">#{{ g.sample.object_id }}</code>
            <span v-if="g.occurrences > 1" class="mp-plus">+{{ g.occurrences - 1 }} more</span>
          </header>

          <DiffEntries :diffs="g.sampleDiff" />
        </div>

        <CollapsiblePanel v-if="g.occurrences > 1"
                          class="mp-more"
                          :collapsed="isCollapsed(g)"
                          @update:collapsed="(v) => setCollapsed(g, v)">
          <template #header="{ collapsed }">
            <span class="mp-more-label">{{
              collapsed
                ? `show ${g.occurrences - 1} more occurrence${g.occurrences > 2 ? 's' : ''}`
                : 'collapse'
            }}</span>
          </template>
          <ul class="mp-members">
            <template v-for="oid in g.object_ids" :key="oid">
              <li v-if="oid !== g.sample.object_id"
                  class="mp-member"
                  @click="jumpTo(g, oid)">
                <header class="mp-member-head">
                  <strong class="mp-class">{{ shortClass(g.class) }}</strong>
                  <code class="mp-id">#{{ oid }}</code>
                </header>
                <DiffEntries :diffs="memberDiff(g.call_id, oid) || []" />
              </li>
            </template>
          </ul>
        </CollapsiblePanel>
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
/* "Last jumped from" outline. Painted on the group whose call the
   user most recently navigated from via this panel. Same yellow as
   the call-tree row and inspection card, but a different meaning —
   the global focus moves with every click in the tree, this stays
   put until the user picks another mutation here. Lets the panel
   read as a stable breadcrumb instead of erasing itself when the
   tree focus moves on. */
.mp-group.highlighted {
  outline: 2px solid #fbbf24;
  outline-offset: 1px;
}
.mp-sample {
  cursor: pointer;
  border-radius: 3px;
  padding: 0.1rem 0.2rem;
  margin: -0.1rem -0.2rem 0.1rem;       /* extend the click target a hair beyond text bounds without bloating layout */
}
.mp-sample:hover { outline: 1px solid var(--accent-blue); outline-offset: -1px; }
.mp-row-head {
  display: flex; gap: 0.35rem; align-items: baseline;
  margin-bottom: 0.35rem;
  font-family: ui-monospace, monospace;
  font-size: var(--mono-size);
  flex-wrap: wrap;
}
.mp-sig { color: var(--text-secondary); }
.mp-arrow-sep { color: var(--text-muted); }
.mp-class { color: var(--text-primary); font-weight: 600; }
.mp-id { color: #c4b5fd; }
.mp-plus {
  color: #fbbf24; background: rgba(251, 191, 36, 0.12);
  padding: 0 0.4rem; border-radius: 3px; font-size: 0.75rem;
}

/* CollapsiblePanel chrome — keep the previous accent-blue label tone
   for the more-occurrences toggle so the affordance still pops. */
.mp-more :deep(.cp-head) {
  color: var(--accent-blue);
  font-family: ui-monospace, monospace;
  font-size: var(--mono-size);
  padding-top: 0.3rem;
}
.mp-more :deep(.cp-head):hover .mp-more-label { color: #93c5fd; }

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
