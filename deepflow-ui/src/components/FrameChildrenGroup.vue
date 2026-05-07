<script setup lang="ts">
// Collapsible nested-calls section inside a FrameCard. Owns its
// per-callId fold state via the CHILDREN_EXPANDED_OVERRIDES injection
// key (default mirrors the global EXPANSION_DEFAULT, so expandAll
// opens the folds and collapseAll closes them).
//
// Recursive child rendering still routes through FrameCard — this
// component only owns the "▾ N calls" toggle and the bordered
// container; it does not know about payloads, mutations, or the
// origin/pin event payloads (it just relays them).

import { computed, inject, ref } from 'vue';
import FrameCard from './FrameCard.vue';
import { CHILDREN_EXPANDED_OVERRIDES, EXPANSION_DEFAULT } from '../keys';
import type { CallRow, OriginTarget, Watch } from '../types';

const props = defineProps<{
  callId: string;
  children: CallRow[];
}>();

const emit = defineEmits<{
  (e: 'pin', payload: Watch): void;
  (e: 'origin', target: OriginTarget): void;
}>();

const expansionDefault = inject(EXPANSION_DEFAULT, ref(false));
const overrides = inject(CHILDREN_EXPANDED_OVERRIDES, ref(new Map<string, boolean>()));

const expanded = computed<boolean>({
  get() {
    const o = overrides.value.get(props.callId);
    return o === undefined ? expansionDefault.value : o;
  },
  set(v: boolean) {
    const next = new Map(overrides.value);
    next.set(props.callId, v);
    overrides.value = next;
  }
});

function toggle(): void { expanded.value = !expanded.value; }
</script>

<template>
  <div class="rec-children-group">
    <button class="rec-children-toggle"
            @click="toggle"
            :title="expanded ? 'Collapse nested calls' : 'Expand nested calls'">
      <span class="rec-children-disclosure">{{ expanded ? '▾' : '▸' }}</span>
      <span class="rec-children-label">{{ children.length }} call{{ children.length === 1 ? '' : 's' }}</span>
    </button>
    <ol v-if="expanded" class="rec-children">
      <FrameCard v-for="child in children"
                 :key="child.call_id"
                 :call="child"
                 @pin="(p) => emit('pin', p)"
                 @origin="(t) => emit('origin', t)" />
    </ol>
  </div>
</template>

<style scoped>
.rec-children-group {
  margin: 0.5rem 0;
  border-left: 1px solid rgba(251, 191, 36, 0.35);  /* same hue family as mutation accents, softened — line is a hint, not a billboard */
  padding-left: 0.6rem;
}
.rec-children-toggle {
  display: flex; align-items: baseline; gap: 0.4rem;
  width: 100%;
  background: rgba(251, 191, 36, 0.08);    /* faint yellow background — matches the band-1 watch row tint */
  border: 0; padding: 0.25rem 0.45rem;
  font: inherit; cursor: pointer; text-align: left;
  color: #fcd34d;                          /* readable yellow (matches diff-toggle hover, mutation badge text) */
  border-radius: 3px;
}
.rec-children-toggle:hover {
  background: rgba(251, 191, 36, 0.18);
  color: #fbbf24;
}
.rec-children-disclosure { width: 1ch; user-select: none; color: inherit; }
.rec-children-label {
  font-family: ui-monospace, monospace;
  font-size: 0.78rem;
  font-weight: 600;
  color: inherit;                          /* take the yellow from .rec-children-toggle */
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.rec-children { list-style: none; padding: 0; margin: 0.25rem 0; }
</style>
