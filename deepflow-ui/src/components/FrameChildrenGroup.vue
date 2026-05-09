<script setup lang="ts">
// Indented wrapper around a FrameCard's nested calls. Pure styling
// container — no fold of its own. The parent FrameCard's disclosure
// (▸/▾) is the single source of truth for "show nested calls", and
// the parent only renders this group when expanded, so the inner
// fold that used to live here was redundant once payload bodies
// moved out of the call tree.

import FrameCard from './FrameCard.vue';
import type { CallRow, OriginTarget, Watch } from '../types';

defineProps<{
  callId: string;
  children: CallRow[];
}>();

const emit = defineEmits<{
  (e: 'pin', payload: Watch): void;
  (e: 'origin', target: OriginTarget): void;
}>();
</script>

<template>
  <ol class="rec-children">
    <FrameCard v-for="child in children"
               :key="child.call_id"
               :call="child"
               @pin="(p) => emit('pin', p)"
               @origin="(t) => emit('origin', t)" />
  </ol>
</template>

<style scoped>
.rec-children {
  list-style: none; padding: 0; margin: 0;
  border-left: 1px solid rgba(251, 191, 36, 0.35);
  padding-left: 0.6rem;
}
</style>
