<script setup>
// Renders an array of diff entries from envelopeDiff.js#diffOwnState.
// Each entry has { path, kind, before?, after? } where kind is one of
// 'scalar' | 'idSwap' | 'added' | 'removed'. The before/after slots
// are formatted via util/format.js#formatValue.
//
// Used by both MutationsPanel (sample group diff + per-member diff)
// and WatchPanel (instance changed-row expansion). Single source of
// truth for diff render styles.

import { formatPath } from '../util/envelopeDiff.js';
import { formatValue } from '../util/format.js';

defineProps({
  diffs: { type: Array, required: true },
  // Optional message rendered when `diffs` is empty. Default fits the
  // "own_hash moved but no scalar/id-ref change at this level" case
  // (i.e. the change is in a nested envelope and surfaces on its own
  // row).
  emptyMessage: {
    type: String,
    default: 'own_hash moved but no scalar / id-ref change found at this level.'
  }
});
</script>

<template>
  <ul v-if="diffs.length" class="diff-list">
    <li v-for="(d, k) in diffs" :key="k" :class="['diff-entry', 'diff-' + d.kind]">
      <code class="diff-path">{{ formatPath(d.path) }}</code>
      <span class="diff-body">
        <template v-if="d.kind === 'scalar' || d.kind === 'idSwap'">
          <span class="diff-before">{{ formatValue(d.before) }}</span>
          <span class="diff-arrow">→</span>
          <span class="diff-after">{{ formatValue(d.after) }}</span>
        </template>
        <template v-else-if="d.kind === 'added'">
          <span class="diff-arrow added">+</span>
          <span class="diff-after">{{ formatValue(d.after) }}</span>
        </template>
        <template v-else-if="d.kind === 'removed'">
          <span class="diff-arrow removed">−</span>
          <span class="diff-before">{{ formatValue(d.before) }}</span>
        </template>
      </span>
    </li>
  </ul>
  <p v-else class="diff-empty">{{ emptyMessage }}</p>
</template>

<style scoped>
.diff-list { list-style: none; padding: 0; margin: 0; }
.diff-entry {
  padding: 0.1rem 0;
  font-family: ui-monospace, monospace;
  font-size: var(--mono-size);
  line-height: 1.4;
}
.diff-path {
  color: var(--text-secondary);
  margin-right: 0.7rem;
  background: rgba(255, 255, 255, 0.04);
  padding: 0 0.35rem;
  border-radius: 3px;
}
.diff-body { color: var(--text-primary); word-break: break-word; }
.diff-before { color: #fbbf24; }
.diff-after { color: #6ee7b7; }
.diff-arrow { color: var(--text-muted); margin: 0 0.4rem; }
.diff-arrow.added { color: #6ee7b7; }
.diff-arrow.removed { color: var(--accent-red); }
.diff-empty { color: var(--text-muted); margin: 0.25rem 0; font-size: var(--mono-size); }
</style>
