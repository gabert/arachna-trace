<script setup lang="ts">
// Reusable "this is an exception" chip — the rounded red pill with a
// dominant border. Same visual signal everywhere it appears so the
// developer reads it as one concept across the UI:
//
//   - call-tree request header (when the request contains exceptions)
//   - inspection card header (when call.return_type === 'EXCEPTION')
//   - inspection card RE section header (the RE payload is the throwable)
//   - cycle-nav floating overlay (NavOverlay variant=exception)
//
// Two variants — the chip optionally carries a numeric count (e.g.
// "⚠ 3 exceptions") or just shows the warning glyph + a label.

withDefaults(defineProps<{
  // Optional numeric count for "N exception(s)" framing. Omit for the
  // single-call usages where the chip is purely categorical.
  count?: number;
  label?: string;
}>(), { label: 'exception' });
</script>

<template>
  <span class="exc-chip" :title="count != null
        ? `${count} exception${count === 1 ? '' : 's'}`
        : 'Exception'">
    <span class="exc-icon" aria-hidden="true">⚠</span>
    <template v-if="count != null">
      {{ count }} {{ label }}{{ count === 1 ? '' : 's' }}
    </template>
    <template v-else>{{ label }}</template>
  </span>
</template>

<style scoped>
.exc-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  background: rgba(248, 113, 113, 0.16);
  border: 2px solid var(--accent-red);
  color: #fca5a5;
  font-size: 0.7rem;
  font-weight: 600;
  padding: 0.05rem 0.5rem;
  border-radius: 8px;
  line-height: 1.3;
  font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
}
.exc-icon { font-weight: 600; }
</style>
