<script setup lang="ts">
// Reusable click-header-to-toggle pattern. Used wherever the UI needs a
// titled card whose body folds away on click — inspection-card
// sections (TI / AR / AX / RE), watch items, and (future) any other
// per-instance / per-group expander.
//
// Body uses v-show so child component state (PayloadViewer's tree
// expansion, table scroll position, etc.) survives a fold cycle.
//
// Two modes:
//   - Uncontrolled: omit both `collapsed` and `@update:collapsed`.
//     Component owns its own ref, defaults to expanded (or
//     `defaultCollapsed`).
//   - Controlled: bind `v-model:collapsed`. Useful when collapse
//     state needs to live in a parent (e.g. so a navigator's jump
//     can auto-uncollapse a target).
//
// Header click target is a div with role="button" (not a <button>)
// so consumers can place real buttons inside the slot — nesting
// interactive controls inside a real <button> is invalid HTML.
// Child clickables should `@click.stop` themselves; the chevron
// affordance handles toggle.

import { computed, ref } from 'vue';

const props = withDefaults(defineProps<{
  collapsed?: boolean;
  defaultCollapsed?: boolean;
}>(), {
  defaultCollapsed: false
});

const emit = defineEmits<{
  (e: 'update:collapsed', v: boolean): void;
}>();

const isControlled = computed(() => props.collapsed !== undefined);
const localCollapsed = ref(props.defaultCollapsed);
const isCollapsed = computed(() =>
  isControlled.value ? !!props.collapsed : localCollapsed.value);

function toggle(): void {
  const next = !isCollapsed.value;
  if (isControlled.value) emit('update:collapsed', next);
  else localCollapsed.value = next;
}
</script>

<template>
  <section class="cp" :class="{ collapsed: isCollapsed }">
    <div class="cp-head"
         role="button"
         tabindex="0"
         :aria-expanded="!isCollapsed"
         :title="isCollapsed ? 'Expand' : 'Collapse'"
         @click="toggle"
         @keydown.enter.prevent="toggle"
         @keydown.space.prevent="toggle">
      <span class="cp-chevron" aria-hidden="true">{{ isCollapsed ? '▸' : '▾' }}</span>
      <span class="cp-head-content">
        <!-- Slot prop `collapsed` lets consumers render context-aware
             header text (e.g. "show 3 more" ↔ "collapse") without
             needing controlled mode just to read state. -->
        <slot name="header" :collapsed="isCollapsed" />
      </span>
    </div>
    <div v-show="!isCollapsed" class="cp-body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.cp { /* outer container — consumer styles spacing/borders if needed */ }

.cp-head {
  display: flex; align-items: baseline; gap: 0.5rem;
  padding: 0.2rem 0.35rem;
  cursor: pointer;
  user-select: none;
  border-radius: 3px;
  color: inherit;
}
.cp-head:hover { background: var(--bg-hover); }
.cp-head:focus-visible {
  outline: 2px solid var(--accent-blue);
  outline-offset: -2px;
}
.cp-chevron {
  width: 1ch; color: var(--text-muted);
  font-size: 0.75rem; line-height: 1;
  flex-shrink: 0;
}
.cp-head:hover .cp-chevron { color: var(--text-primary); }

.cp-head-content {
  flex: 1; min-width: 0;
  display: flex; align-items: baseline; gap: 0.5rem;
}
</style>
