<script setup lang="ts">
// Left pane of the workspace: trace banner (with ↑/↓ navigation
// across the inspected instance's appearances) plus the FrameCard
// call tree.
//
// Owns the "you are here" highlight state that flashes a row when a
// programmatic navigation lands on it. Distinct from FrameCard's
// `selected` state (which tracks the open inspection card on the
// right). Selection is persistent; highlight is the search-cursor
// pointer — set by parents via the exposed highlightCall(callId)
// method, cleared on request change or trace clear.

import { provide, ref } from 'vue';
import ProgressSpinner from 'primevue/progressspinner';
import FrameCard from './FrameCard.vue';
import {
  HIGHLIGHTED_CALL_ID, HIGHLIGHT_CALL_TICK
} from '../keys';
import type { CallRow, OriginTarget, TraceTarget, Watch } from '../types';

const props = defineProps<{
  rootCalls: CallRow[];
  callsLoading: boolean;
  hasNoCalls: boolean;
  // Trace-banner state (lifted from SessionDetailView so the panel
  // can render its own header bar without duplicating the cross-
  // cutting state owners).
  inspectedInstance: TraceTarget | null;
  inspectedShortClass: string;
  inspectedCount: number;
  traceCursor: number;
}>();

const emit = defineEmits<{
  (e: 'pin', payload: Watch): void;
  (e: 'origin', target: OriginTarget): void;
  (e: 'goto-prev-appearance'): void;
  (e: 'goto-next-appearance'): void;
  (e: 'clear-instance'): void;
}>();

// Highlight state lives here. Cleared automatically when the trace
// target changes (the parent triggers it externally by calling
// highlightCall(null) or clearHighlight()), and on request change
// (the parent unmounts the FrameCards anyway via v-if path).
const highlightedCallId = ref<string | null>(null);
const highlightTick = ref(0);

provide(HIGHLIGHTED_CALL_ID, highlightedCallId);
provide(HIGHLIGHT_CALL_TICK, highlightTick);

// Public API surface. Parent grabs a ref to this component and calls
// highlightCall(callId) after a programmatic navigation (trace ↑/↓,
// future "find next exception" etc.). Bumps tick so re-highlighting
// the same call still fires FrameCard's scroll-into-view watcher.
function highlightCall(callId: string | null): void {
  highlightedCallId.value = callId;
  highlightTick.value++;
}
function clearHighlight(): void {
  highlightedCallId.value = null;
}

defineExpose({ highlightCall, clearHighlight });
</script>

<template>
  <div class="ctp">
    <div v-if="inspectedInstance" class="trace-banner">
      <span class="trace-label">🔎 Tracing</span>
      <code class="trace-target">{{ inspectedShortClass }} #{{ inspectedInstance.objectId }}</code>
      <span class="trace-count">
        <template v-if="traceCursor >= 0 && inspectedCount > 0">{{ traceCursor + 1 }} of {{ inspectedCount }}</template>
        <template v-else>{{ inspectedCount }} appearance{{ inspectedCount === 1 ? '' : 's' }}</template>
      </span>
      <button class="trace-nav"
              :disabled="!inspectedCount"
              @click="emit('goto-prev-appearance')"
              title="Previous occurrence">↑</button>
      <button class="trace-nav"
              :disabled="!inspectedCount"
              @click="emit('goto-next-appearance')"
              title="Next occurrence">↓</button>
      <button class="trace-clear" @click="emit('clear-instance')" title="Clear trace">×</button>
    </div>

    <div v-if="callsLoading" class="centered">
      <ProgressSpinner style="width:2rem;height:2rem" />
    </div>

    <ol v-else class="recording" :start="1">
      <FrameCard v-for="call in rootCalls"
                 :key="call.call_id"
                 :call="call"
                 @pin="(p) => emit('pin', p)"
                 @origin="(t) => emit('origin', t)" />
    </ol>

    <p v-if="hasNoCalls && !callsLoading" class="muted centered">
      no calls in this request
    </p>
  </div>
</template>

<style scoped>
.ctp { height: 100%; }
.recording { list-style: none; padding: 0; margin: 0; }
.muted { color: var(--text-muted); font-size: 0.85rem; }
.centered { display: flex; justify-content: center; padding: 2rem; }

/* Trace banner — sticks to the top of the scrollable left-pane
   container so the trace controls + nav arrows stay reachable when
   the user has scrolled deep into a long call tree. Layered
   background (opaque base + amber tint) so scrolled rows underneath
   don't bleed through; soft shadow signals it's floating above. */
.trace-banner {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.4rem 0.75rem;
  background:
    linear-gradient(rgba(251, 191, 36, 0.10), rgba(251, 191, 36, 0.10))
    var(--bg-base);
  border-bottom: 1px solid rgba(251, 191, 36, 0.45);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.25);
  font-size: 0.8rem;
}
.trace-label { color: #fcd34d; font-weight: 600; }
.trace-target {
  font-family: ui-monospace, monospace;
  color: var(--text-primary);
  background: var(--bg-elevated);
  padding: 0.05rem 0.4rem;
  border-radius: 3px;
}
.trace-count {
  color: var(--text-secondary);
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}
.trace-nav {
  background: transparent;
  border: 0;
  color: var(--text-secondary);
  font-size: 0.95rem;
  line-height: 1;
  padding: 0.15rem 0.45rem;
  cursor: pointer;
  border-radius: 3px;
}
.trace-nav:hover:not(:disabled) { color: var(--text-primary); background: var(--bg-hover); }
.trace-nav:disabled { color: var(--text-muted); cursor: not-allowed; opacity: 0.5; }
.trace-clear {
  background: transparent;
  border: 0;
  color: var(--text-muted);
  font-size: 1.1rem;
  line-height: 1;
  padding: 0 0.3rem;
  cursor: pointer;
  border-radius: 3px;
}
.trace-clear:hover { color: var(--text-primary); background: var(--bg-hover); }
</style>
