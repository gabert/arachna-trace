<script setup lang="ts">
// Top-level watch list. Pure orchestration — header, empty state, list
// of WatchItem children. Per-watch collapse / per-row expansion / row
// transition logic all live in WatchItem.
//
// `payloads` arrives already parsed from useRequestData.parsedPayloads;
// no JSON.parse happens here.

import WatchItem from './WatchItem.vue';
import type { CallMeta, JumpAddress, PayloadRow, Watch } from '../types';

const props = withDefaults(defineProps<{
  watches: Watch[];
  payloads: PayloadRow[];
  callMeta?: Map<string, CallMeta>;
}>(), {
  callMeta: () => new Map<string, CallMeta>()
});

const emit = defineEmits<{
  (e: 'remove', idx: number): void;
  (e: 'jump', addr: JumpAddress): void;
}>();
</script>

<template>
  <aside class="watch-panel">
    <header class="wp-head">
      <h3>Watches</h3>
      <small v-if="payloads.length">{{ payloads.length }} payloads in request</small>
    </header>

    <p v-if="!watches.length" class="wp-empty">
      Hover any value in the recording on the left and click <code>⊕ watch</code>.
      Hovering an object pins the whole instance — tracked by its own-state
      hash (moves only when this object's own scalars or child references
      change; mutations of nested children don't count). Hovering a field
      pins that field on the enclosing instance — tracked by value (literal
      for primitives, <code>#id</code> for object references).
    </p>

    <WatchItem v-for="(w, i) in watches"
               :key="i"
               :watch="w"
               :parsedPayloads="payloads"
               :callMeta="callMeta"
               @remove="emit('remove', i)"
               @jump="(addr) => emit('jump', addr)" />
  </aside>
</template>

<style scoped>
.watch-panel {
  background: var(--bg-surface);
  border-left: 1px solid var(--border);
  padding: 0.75rem;
  overflow-y: auto;
  height: 100%;
  color: var(--text-primary);
}
.wp-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 0.5rem; }
.wp-head h3 { margin: 0; font-size: 0.95rem; color: var(--text-primary); }
.wp-head small { color: var(--text-muted); font-size: 0.75rem; }
.wp-empty {
  color: var(--text-secondary);
  font-size: 0.85rem;
  background: var(--bg-elevated);
  border: 1px dashed var(--border-strong);
  border-radius: 4px;
  padding: 0.75rem;
  line-height: 1.55;
}
.wp-empty code { background: rgba(96, 165, 250, 0.15); padding: 0 0.3rem; border-radius: 3px; color: #93c5fd; font-size: 0.78rem; }
</style>
