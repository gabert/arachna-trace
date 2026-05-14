<script setup lang="ts">
// Top-level node in the call tree representing one request. Sits
// between CallTreePanel and the FrameCards: each request renders as
// its own collapsible block with a header (id · thread · call/exc
// counts · ms) and a body that holds the request's root FrameCards.
//
// Lazy mount is intentional: the FrameCards (and their entire nested
// subtree) only mount when this request node is expanded, so a session
// with N requests of M calls each costs O(M) renders for whichever
// requests are currently open, not O(N×M). Collapsing a request
// unmounts its FrameCards; the cards' own onBeforeUnmount releases
// any payload cache they were holding.

import { computed, inject, ref, watch } from 'vue';
import FrameCard from './FrameCard.vue';
import ExceptionChip from './ExceptionChip.vue';
import ProgressSpinner from 'primevue/progressspinner';
import { fmtTime } from '../util/format';
import { SESSION_TREE_LOADER } from '../keys';
import type { CallRow, OriginTarget, RequestRow, Watch } from '../types';

const props = defineProps<{
  request: RequestRow;
  rootCalls: CallRow[];
  expanded: boolean;
}>();

// Lazy-load this request's calls when the node is first expanded.
// Re-collapsing keeps the cached calls (so re-expanding doesn't refetch).
// Eviction strategy for unloaded requests is the data layer's concern.
const treeLoader = inject(SESSION_TREE_LOADER, undefined);
const isLoading = computed(() => {
  const rid = Number(props.request.request_id);
  return treeLoader?.loadingRequestIds.value.has(rid) ?? false;
});
const isLoaded = computed(() => {
  const rid = Number(props.request.request_id);
  return treeLoader?.isLoaded(rid) ?? false;
});
watch(() => props.expanded, (now) => {
  if (!now) return;
  const rid = Number(props.request.request_id);
  if (treeLoader && !treeLoader.isLoaded(rid)) {
    treeLoader.load(rid).catch(() => { /* surfaced via isLoading false + empty rootCalls */ });
  }
}, { immediate: true });

const emit = defineEmits<{
  (e: 'toggle'): void;
  (e: 'pin', payload: Watch): void;
  (e: 'origin', target: OriginTarget): void;
}>();

const exceptionCount = computed(() => Number(props.request.exception_count) || 0);
const callCount = computed(() => Number(props.request.call_count) || 0);
const spanMs = computed(() => Number(props.request.span_ms) || 0);
</script>

<template>
  <li class="rn-row" :class="{ open: expanded, exception: exceptionCount > 0 }">
    <button class="rn-head" @click="emit('toggle')" :title="`Request #${request.request_id}`">
      <span class="rn-disclosure">{{ expanded ? '▾' : '▸' }}</span>
      <span class="rn-label">request</span>
      <strong class="rn-id">#{{ request.request_id }}</strong>
      <span class="rn-thread">{{ request.thread_name }}</span>
      <span class="rn-time">{{ fmtTime(request.first_call) }}</span>
      <ExceptionChip v-if="exceptionCount > 0" :count="exceptionCount" />
      <span class="rn-meta">
        <span class="rn-stat">{{ callCount }} {{ callCount === 1 ? 'call' : 'calls' }}</span>
        <span class="rn-stat">{{ spanMs }} ms</span>
      </span>
    </button>

    <!-- Lazy: FrameCards mount only while this node is open AND its
         calls have arrived from the server. Collapsing unmounts the
         entire request subtree (FrameCards then release their payload
         refs). -->
    <div v-if="expanded && isLoading && !isLoaded" class="rn-loading">
      <ProgressSpinner style="width:1.25rem;height:1.25rem" />
      <span>Loading call tree…</span>
    </div>
    <ol v-else-if="expanded" class="rn-body">
      <FrameCard v-for="call in rootCalls"
                 :key="call.call_id"
                 :call="call"
                 @pin="(p) => emit('pin', p)"
                 @origin="(t) => emit('origin', t)" />
    </ol>
  </li>
</template>

<style scoped>
.rn-row {
  list-style: none;
  border-top: 1px solid var(--border);
}
.rn-row:first-child { border-top: 0; }

.rn-head {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  width: 100%;
  background: var(--bg-elevated);
  color: var(--text-primary);
  border: 0;
  border-bottom: 1px solid var(--border);
  padding: 0.5rem 0.75rem;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.rn-head:hover { background: var(--bg-hover); }

.rn-disclosure {
  width: 1ch;
  color: var(--text-muted);
  user-select: none;
  font-size: 0.95rem;
}
.rn-label {
  text-transform: uppercase;
  letter-spacing: 0.04em;
  font-size: 0.65rem;
  color: var(--text-muted);
  font-family: system-ui, sans-serif;
}
.rn-id {
  font-family: ui-monospace, monospace;
  font-size: var(--mono-size);
  color: var(--text-primary);
}
.rn-thread {
  font-family: ui-monospace, monospace;
  font-size: 0.78rem;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 0 1 auto;
}
.rn-time {
  font-family: ui-monospace, monospace;
  font-size: 0.78rem;
  color: var(--text-muted);
}
.rn-meta {
  margin-left: auto;
  display: flex;
  gap: 0.5rem;
  flex-shrink: 0;
}
.rn-stat {
  font-family: ui-monospace, monospace;
  font-size: 0.78rem;
  color: var(--text-secondary);
}

/* Request rows whose calls contain at least one exception get the
   same light-red row tint as exception FrameCards — bounded to the
   header, no bleed into the expanded body / call-tree gutter. */
.rn-row.exception > .rn-head {
  background: rgba(248, 113, 113, 0.10);
}
.rn-row.exception > .rn-head:hover { background: rgba(248, 113, 113, 0.18); }

.rn-body {
  list-style: none;
  padding: 0;
  margin: 0;
}

.rn-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.6rem;
  padding: 1rem;
  color: var(--text-muted);
  font-size: 0.85rem;
}
</style>
