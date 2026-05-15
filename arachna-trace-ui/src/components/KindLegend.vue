<script setup lang="ts">
// Inline TI / AR / AX / RE legend — small chip+? pairs, hovering
// either the chip or the ? surfaces a short explanation via the
// PrimeVue tooltip directive (registered globally in main.ts).
// Same chip colours as the section headers inside CallInspectionCard,
// so the pairing reads at a glance: the chip here looks like the
// chip there.
//
// Used in two spots: the inspection card header (so users learn the
// kinds in context) and the empty-state placeholder for the
// inspection pane (so users learn them before opening any card).

import type { PayloadKind } from '../types';

interface LegendEntry {
  kind: PayloadKind;
  text: string;
}

const LEGEND: LegendEntry[] = [
  { kind: 'TI', text: 'This Instance — the receiver object (this) at method entry.' },
  { kind: 'AR', text: 'Arguments — argument values at method entry.' },
  { kind: 'AX', text: 'Arguments at eXit — argument values at method exit. Differs from AR when the method mutated them.' },
  { kind: 'RE', text: 'Return — the return value, or the thrown exception if the method failed.' },
];
</script>

<template>
  <div class="kind-legend">
    <span v-for="entry in LEGEND" :key="entry.kind"
          v-tooltip.bottom="entry.text"
          class="kind-legend-pair"
          :aria-label="`${entry.kind} explanation`">
      <span class="kind" :class="entry.kind">{{ entry.kind }}</span>
      <span class="kind-legend-help" aria-hidden="true">?</span>
    </span>
  </div>
</template>

<style scoped>
.kind-legend {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  flex-shrink: 0;
}
/* Pair wrapper owns the tooltip directive (and the hover cursor) so
   the chip and the ? read as one hit target — hovering either
   surfaces the explanation. */
.kind-legend-pair {
  display: inline-flex;
  align-items: center;
  gap: 0.15rem;
  cursor: help;
}
.kind-legend-pair:hover .kind-legend-help {
  background: var(--bg-hover);
  color: var(--text-primary);
  border-color: var(--border-strong);
}
.kind-legend-help {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--text-muted);
  font-size: 0.65rem;
  font-weight: 600;
  line-height: 1;
  width: 14px;
  height: 14px;
  padding: 0;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
</style>
