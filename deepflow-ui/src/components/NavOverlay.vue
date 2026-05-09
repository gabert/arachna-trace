<script setup lang="ts">
// Floating "cycle through hits" overlay chip for the call tree.
//
// Two flavours so far:
//   * exception — light-red bg, dominant red border
//   * trace     — muted-yellow bg, dominant yellow border
//
// Both share the same shape: an icon + label slot, a "<cursor> of
// <total>" counter (or "<n> items" when nothing is focused), prev /
// next ↑↓ buttons, and an optional × clear. New variants slot in by
// adding a CSS rule for `.nav-overlay.variant-X` — no template
// changes needed.
//
// Positioning is the caller's job: this component renders an
// inline-flex chip with rounded corners and shadow; the parent
// chooses sticky / absolute / wherever.

defineProps<{
  variant: 'exception' | 'trace';
  count: number;
  // -1 when no item is currently focused. The label flips between
  // "<cursor+1> of <count>" (focused) and "<count> <itemSingular>(s)"
  // (unfocused / overall).
  cursor: number;
  itemSingular: string;
  showClear?: boolean;
  prevTitle?: string;
  nextTitle?: string;
  clearTitle?: string;
}>();

const emit = defineEmits<{
  (e: 'prev'): void;
  (e: 'next'): void;
  (e: 'clear'): void;
}>();
</script>

<template>
  <div class="nav-overlay" :class="'variant-' + variant">
    <span class="nav-label"><slot /></span>
    <span class="nav-count">
      <template v-if="cursor >= 0 && count > 0">{{ cursor + 1 }} of {{ count }}</template>
      <template v-else>{{ count }} {{ itemSingular }}{{ count === 1 ? '' : 's' }}</template>
    </span>
    <button class="nav-btn"
            :disabled="!count"
            :title="prevTitle"
            @click="emit('prev')">↑</button>
    <button class="nav-btn"
            :disabled="!count"
            :title="nextTitle"
            @click="emit('next')">↓</button>
    <button v-if="showClear"
            class="nav-clear"
            :title="clearTitle"
            @click="emit('clear')">×</button>
  </div>
</template>

<style scoped>
.nav-overlay {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.4rem 0.75rem;
  border-radius: 8px;
  border: 2px solid;
  font-size: 0.8rem;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(2px);
}

.nav-overlay.variant-exception {
  background: rgba(248, 113, 113, 0.16);
  border-color: var(--accent-red);
  color: #fca5a5;
}
.nav-overlay.variant-trace {
  background: rgba(251, 191, 36, 0.12);
  border-color: #fbbf24;
  color: #fcd34d;
}

.nav-label { font-weight: 600; display: inline-flex; align-items: center; gap: 0.4rem; }
.nav-count {
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
  margin-left: auto;
}

.nav-btn {
  background: transparent;
  border: 0;
  color: inherit;
  font-size: 0.95rem;
  line-height: 1;
  padding: 0.1rem 0.4rem;
  cursor: pointer;
  border-radius: 3px;
}
.nav-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.08);
}
.nav-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.nav-clear {
  background: transparent;
  border: 0;
  color: var(--text-muted);
  font-size: 1.1rem;
  line-height: 1;
  padding: 0 0.3rem;
  cursor: pointer;
  border-radius: 3px;
}
.nav-clear:hover { color: var(--text-primary); background: rgba(255, 255, 255, 0.08); }
</style>
