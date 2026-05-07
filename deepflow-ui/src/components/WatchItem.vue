<script setup lang="ts">
// One watch's appearance table. Owns its own collapse state + per-row
// diff expansion state so the parent panel doesn't have to key Sets
// by (watchIdx, rowIdx). Dropping the watch removes this entire item,
// and with it all its local state — exactly what we want.

import { computed, inject, ref } from 'vue';
import type { AppearanceRow } from '../util/appearances';
import { appearancesFor } from '../util/appearances';
import { fmtTime, shortClass, shortSig } from '../util/format';
import { HIGHLIGHT } from '../keys';
import DiffEntries from './DiffEntries.vue';
import type { CallMeta, JumpAddress, Path, PayloadRow, Watch } from '../types';

const props = withDefaults(defineProps<{
  watch: Watch;
  parsedPayloads: PayloadRow[];
  callMeta?: Map<string, CallMeta>;
}>(), {
  callMeta: () => new Map<string, CallMeta>()
});

const emit = defineEmits<{
  (e: 'remove'): void;
  (e: 'jump', addr: JumpAddress): void;
}>();

const highlight = inject(HIGHLIGHT, ref(null));

const collapsed = ref(false);
function toggleCollapse(): void { collapsed.value = !collapsed.value; }

const expandedRows = ref<Set<number>>(new Set());
function isRowExpanded(j: number): boolean { return expandedRows.value.has(j); }
function toggleRowExpand(j: number): void {
  const next = new Set(expandedRows.value);
  if (next.has(j)) next.delete(j); else next.add(j);
  expandedRows.value = next;
}

const rows = computed<AppearanceRow[]>(() =>
  appearancesFor(props.watch, props.parsedPayloads, props.callMeta));

const changedCount = computed(() => rows.value.filter(r => r.changed).length);

function pathFor(r: AppearanceRow): Path {
  return props.watch.kind === 'field'
    ? [...(r.envelopePath || []), ...(props.watch.fieldPath || [])]
    : (r.envelopePath || []);
}

function rowAddress(r: AppearanceRow): { callId: string; kind: string; pathKey: string } {
  return { callId: r.callId, kind: r.kind, pathKey: JSON.stringify(pathFor(r)) };
}

function isCurrent(r: AppearanceRow): boolean {
  const h = highlight.value;
  if (!h) return false;
  const a = rowAddress(r);
  return h.callId === a.callId && h.kind === a.kind && h.pathKey === a.pathKey;
}

function onRowClick(r: AppearanceRow): void {
  emit('jump', {
    callId: r.callId,
    kind: r.kind,
    objectId: props.watch.objectId,
    path: pathFor(r)
  });
}
</script>

<template>
  <div class="watch" :class="['watch-' + watch.kind]">
    <header class="watch-head"
            role="button"
            :aria-expanded="!collapsed"
            :title="collapsed ? 'expand' : 'collapse'"
            @click="toggleCollapse">
      <span class="watch-collapse" aria-hidden="true">{{ collapsed ? '▶' : '▼' }}</span>
      <div class="watch-title">
        <strong>{{ shortClass(watch.className) }}</strong>
        <code>#{{ watch.objectId }}</code>
        <code v-if="watch.kind === 'field'" class="watch-field">.{{ (watch.fieldPath || []).join('.') }}</code>
      </div>
      <button class="watch-rm" @click.stop="emit('remove')" title="Remove watch">×</button>
    </header>

    <div class="watch-meta">
      {{ rows.length }} appearances
      · <span class="changes">{{ changedCount }}
        {{ watch.kind === 'instance' ? 'own-state transitions' : 'value transitions' }}
      </span>
    </div>

    <table v-if="!collapsed" class="watch-results">
      <colgroup>
        <col class="c-time"><col class="c-kind"><col class="c-sig">
        <col :class="watch.kind === 'instance' ? 'c-hash' : 'c-value'">
        <col class="c-marker">
      </colgroup>
      <tbody>
        <template v-for="(r, j) in rows" :key="r.callId + ':' + r.kind + ':' + j">
          <tr :class="['band-' + r.band, { changed: r.changed, current: isCurrent(r), expanded: isRowExpanded(j) }]"
              @click="onRowClick(r)">
            <td class="r-time">{{ fmtTime(r.ts) }}</td>
            <td class="r-kind"><span class="kind" :class="r.kind">{{ r.kind }}</span></td>
            <td class="r-sig">{{ shortSig(r.signature) }}</td>
            <td class="r-hash" :title="watch.kind === 'instance' ? 'own-state ' + r.repr : ''">{{ r.repr }}</td>
            <td class="r-marker">
              <button v-if="r.changed && watch.kind === 'instance'"
                      class="diff-toggle"
                      :title="isRowExpanded(j) ? 'collapse changed-fields' : 'show changed fields'"
                      @click.stop="toggleRowExpand(j)">{{ isRowExpanded(j) ? '▼' : '▶' }}</button>
              <span v-else-if="r.changed" class="m-own"
                    title="value differs from previous appearance">▲</span>
            </td>
          </tr>
          <tr v-if="r.changed && watch.kind === 'instance' && isRowExpanded(j)"
              class="diff-row" :class="['band-' + r.band]">
            <td colspan="5">
              <DiffEntries :diffs="r.diffs || []" />
            </td>
          </tr>
        </template>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.watch {
  background: var(--bg-elevated); border: 1px solid var(--border-strong); border-radius: 4px;
  padding: 0.5rem; margin-bottom: 0.75rem;
}
.watch-head {
  display: flex; justify-content: flex-start; align-items: center; gap: 0.4rem;
  margin-bottom: 0.15rem;
  padding: 0.1rem 0.2rem;
  border-radius: 3px;
  cursor: pointer;                     /* whole header toggles — same affordance as FrameCard's row click */
  user-select: none;
}
.watch-head:hover { background: var(--bg-hover); }
.watch-head:hover .watch-collapse { color: var(--text-primary); }
.watch-collapse {
  color: var(--text-muted);
  font-family: ui-monospace, monospace; font-size: var(--mono-size);
  line-height: 1; flex-shrink: 0;
}
.watch-title { display: flex; gap: 0.4rem; align-items: baseline; flex: 1; min-width: 0; overflow: hidden; }
.watch-title strong { font-size: 0.9rem; color: var(--text-primary); }
.watch-title code { font-family: ui-monospace, monospace; color: #c4b5fd; font-size: 0.8rem; }
.watch-title .watch-field { color: var(--text-secondary); font-size: 0.9rem; }
.watch-rm { border: 0; background: transparent; color: var(--text-muted); cursor: pointer; font-size: 1.05rem; line-height: 1; }
.watch-rm:hover { color: var(--accent-red); }
.watch-meta { color: var(--text-muted); font-size: 0.75rem; margin-bottom: 0.4rem; }
.watch-meta .changes { color: #fbbf24; }

.watch-results {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  font-family: ui-monospace, monospace;
  font-size: var(--mono-size);
}

.watch-results col.c-time   { width: 13ch; }
.watch-results col.c-kind   { width: 4ch;  }
.watch-results col.c-sig    { width: auto; }
.watch-results col.c-hash   { width: 17ch; }
.watch-results col.c-value  { width: auto; }
.watch-results col.c-marker { width: 5ch; }

.watch-results tbody tr {
  cursor: pointer;
  border-top: 1px solid rgba(255, 255, 255, 0.04);
}
.watch-results tbody tr:first-child { border-top: 0; }
.watch-results tbody tr.band-0 { background: rgba(110, 231, 183, 0.10); }
.watch-results tbody tr.band-1 { background: rgba(251, 191, 36, 0.10); }
.watch-results tbody tr:hover  { outline: 1px solid var(--accent-blue); outline-offset: -1px; }
.watch-results tbody tr.changed { box-shadow: inset 3px 0 0 #fbbf24; }

.watch-results tbody tr.current {
  background: rgba(251, 191, 36, 0.22) !important;
  outline: 2px solid #fbbf24;
  outline-offset: -2px;
}

.watch-results tbody td {
  padding: 0.3rem 0.4rem;
  vertical-align: baseline;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.watch-results td.r-time   { color: var(--text-muted); }
.watch-results td.r-kind   { padding: 0.3rem 0.2rem; }
.watch-results td.r-sig    { color: var(--text-secondary); }
.watch-results td.r-hash   { color: var(--text-secondary); }
.watch-results td.r-marker {
  padding: 0.3rem 0.2rem;
  white-space: nowrap;
  overflow: visible;
}
.m-own { color: #fbbf24; font-weight: 700; display: inline-block; }

.diff-toggle {
  background: transparent; border: 0; padding: 0;
  color: #fbbf24; font-weight: 700;
  font-family: ui-monospace, monospace; font-size: var(--mono-size);
  cursor: pointer; line-height: 1;
}
.diff-toggle:hover { color: #fcd34d; }

.watch-results tbody tr.diff-row { cursor: default; }
.watch-results tbody tr.diff-row:hover { outline: none; }
.watch-results tbody tr.diff-row > td {
  padding: 0.3rem 0.6rem 0.45rem 1rem;
  border-top: 1px dashed rgba(255, 255, 255, 0.06);
  white-space: normal;
}
</style>
