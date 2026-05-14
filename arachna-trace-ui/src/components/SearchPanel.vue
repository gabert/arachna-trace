<script setup lang="ts">
// Right-pane Search tab. Find every place a value appears in the
// loaded request (or the whole session). Powered by useValueSearch
// → /api/analysis/value-search → bloom-filter probe over
// payloads.payload_tokens.
//
// Distinct from the Origin panel: Origin walks a chain (source →
// propagation → current → next-mutation) starting from a click;
// Search is just "show me everywhere this value lives" — a flat
// list with click-to-jump.

import { computed, nextTick, ref, watch } from 'vue';
import { fmtTime, shortSig } from '../util/format';
import { formatPath } from '../util/envelopeDiff';
import type { JumpAddress, ValueSearchHit } from '../types';
import type { UseValueSearch } from '../composables/useValueSearch';

const props = defineProps<{
  search: UseValueSearch;
  // SessionDetailView passes its own activation flag so the input
  // can autofocus the moment the tab becomes visible.
  active: boolean;
}>();

const emit = defineEmits<{
  (e: 'jump', addr: JumpAddress): void;
}>();

const inputRef = ref<HTMLInputElement | null>(null);

watch(() => props.active, async (visible) => {
  if (!visible) return;
  await nextTick();
  inputRef.value?.focus();
});

// Local "last jumped from" highlight. Intentionally NOT tied to the
// global CALL_HIGHLIGHT ref — the call-tree/inspection-card pair
// represents "where the user is now" and moves with every click;
// this panel represents "what I jumped from in search" and stays put
// until the user picks another hit, even when the user goes
// investigating elsewhere in the tree. Same yellow as the unified
// focus, but a different meaning — and that's what makes the panes
// feel connected instead of erasing themselves on every unrelated
// tree click.
const lastJumpedCallId = ref<string | null>(null);

// Re-running a search throws away the prior result set — drop the
// breadcrumb so a stale highlight doesn't survive into the new hits.
watch(() => props.search.submitted.value, () => { lastJumpedCallId.value = null; });

function jumpHit(h: ValueSearchHit): void {
  lastJumpedCallId.value = h.call_id;
  emit('jump', {
    callId: h.call_id,
    kind: h.kind,
    path: h.path,
    requestId: h.request_id != null ? Number(h.request_id) : undefined
  });
}

const resultsLabel = computed(() => {
  const n = props.search.hits.value.length;
  if (n === 0) return 'No occurrences';
  if (n === 1) return '1 occurrence';
  return `${n} occurrences`;
});
</script>

<template>
  <aside class="search-panel">
    <header class="sp-head">
      <h3>Search</h3>
      <small v-if="search.submitted.value">
        results for <code>{{ search.submitted.value }}</code>
      </small>
    </header>

    <form class="sp-form" @submit.prevent="search.submit()">
      <input ref="inputRef"
             type="text"
             class="sp-input"
             placeholder="exact value to find — string, number, or boolean"
             v-model="search.query.value"
             :disabled="search.loading.value" />

      <div class="sp-controls">
        <label class="sp-scope">
          <input type="radio" :value="'request'" v-model="search.scope.value" />
          this request
        </label>
        <label class="sp-scope">
          <input type="radio" :value="'session'" v-model="search.scope.value" />
          whole session
        </label>
        <button type="submit" class="sp-submit" :disabled="!search.query.value.trim() || search.loading.value">
          {{ search.loading.value ? 'searching…' : 'search' }}
        </button>
        <button v-if="search.submitted.value" type="button" class="sp-clear"
                @click="search.clear()" title="Clear search">×</button>
      </div>
    </form>

    <p v-if="!search.submitted.value && !search.loading.value" class="sp-empty">
      Enter an exact scalar value (e.g. <code>"9780618002213"</code>,
      <code>0.15</code>, <code>true</code>) and search to find every
      payload that contains it. Matches are by exact value equality —
      no substring or prefix matching. Powered by an indexed bloom-
      filter probe; works at session scale.
    </p>

    <p v-else-if="search.error.value" class="sp-warn">
      Search failed: {{ search.error.value }}
    </p>

    <div v-else-if="search.submitted.value && !search.loading.value" class="sp-results">
      <header class="sp-results-head">
        {{ resultsLabel }}
        <span v-if="search.scope.value === 'session'" class="sp-scope-badge">session-wide</span>
        <span v-else class="sp-scope-badge">this request</span>
        <span v-if="search.submittedMode.value === 'substring'" class="sp-mode-badge">substring</span>
      </header>
      <ol v-if="search.hits.value.length" class="sp-list">
        <li v-for="(h, i) in search.hits.value"
            :key="i"
            class="sp-row"
            :class="{ highlighted: lastJumpedCallId === h.call_id }"
            @click="jumpHit(h)">
          <span class="sp-time">{{ fmtTime(h.ts_in) }}</span>
          <span class="sp-kind kind" :class="h.kind">{{ h.kind }}</span>
          <span class="sp-sig">{{ shortSig(h.signature) }}</span>
          <code class="sp-path">{{ formatPath(h.path) || '·' }}</code>
        </li>
      </ol>
      <div v-else>
        <p class="sp-empty">
          No payloads in scope contain that
          {{ search.submittedMode.value === 'substring' ? 'substring' : 'exact value' }}.
        </p>
        <button v-if="search.submittedMode.value !== 'substring'"
                class="sp-fallback"
                @click="search.submit('substring')"
                title="Bypasses the bloom-filter index and scans the JSON of every payload in scope. Slower; correct.">
          ↪ try substring search (slower, scans payload bodies)
        </button>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.search-panel {
  background: var(--bg-surface);
  border-left: 1px solid var(--border);
  padding: 0.75rem;
  overflow-y: auto;
  height: 100%;
  color: var(--text-primary);
  display: flex;
  flex-direction: column;
}
.sp-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 0.5rem; gap: 0.5rem; }
.sp-head h3 { margin: 0; font-size: 0.95rem; color: var(--text-primary); }
.sp-head small { color: var(--text-muted); font-size: 0.75rem; }
.sp-head small code {
  font-family: ui-monospace, monospace;
  background: rgba(96, 165, 250, 0.15);
  color: #93c5fd;
  padding: 0 0.4rem;
  border-radius: 3px;
  max-width: 22ch;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
}

.sp-form {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  margin-bottom: 0.6rem;
}
.sp-input {
  background: var(--bg-elevated);
  color: var(--text-primary);
  border: 1px solid var(--border-strong);
  border-radius: 3px;
  padding: 0.4rem 0.55rem;
  font-family: ui-monospace, monospace;
  font-size: var(--mono-size);
  outline: none;
}
.sp-input:focus { border-color: var(--accent-blue); }
.sp-input::placeholder { color: var(--text-muted); }

.sp-controls {
  display: flex; align-items: center; gap: 0.7rem;
  font-size: 0.78rem;
}
.sp-scope { display: inline-flex; align-items: center; gap: 0.25rem; cursor: pointer; color: var(--text-secondary); }
.sp-scope input[type="radio"] { accent-color: var(--accent-blue); }
.sp-submit {
  background: rgba(96, 165, 250, 0.18);
  color: #93c5fd;
  border: 1px solid rgba(96, 165, 250, 0.4);
  font: inherit;
  padding: 0.25rem 0.7rem;
  border-radius: 3px;
  cursor: pointer;
  margin-left: auto;
}
.sp-submit:hover:not(:disabled) { background: rgba(96, 165, 250, 0.3); }
.sp-submit:disabled { opacity: 0.5; cursor: not-allowed; }
.sp-clear {
  background: transparent;
  border: 0;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 1rem;
  padding: 0 0.3rem;
  line-height: 1;
}
.sp-clear:hover { color: var(--accent-red); }

.sp-empty, .sp-warn {
  color: var(--text-secondary);
  font-size: 0.85rem;
  background: var(--bg-elevated);
  border: 1px dashed var(--border-strong);
  border-radius: 4px;
  padding: 0.75rem;
  line-height: 1.55;
}
.sp-empty code {
  background: rgba(96, 165, 250, 0.15);
  padding: 0 0.3rem;
  border-radius: 3px;
  color: #93c5fd;
  font-family: ui-monospace, monospace;
  font-size: 0.78rem;
}
.sp-warn { border-color: rgba(248, 113, 113, 0.4); color: #fca5a5; }

.sp-results { display: flex; flex-direction: column; min-height: 0; flex: 1; }
.sp-results-head {
  display: flex; align-items: baseline; gap: 0.5rem;
  font-size: 0.78rem;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 0.4rem;
}
.sp-scope-badge {
  font-size: 0.65rem;
  text-transform: none;
  letter-spacing: 0;
  background: var(--bg-elevated);
  color: var(--text-secondary);
  padding: 0.05rem 0.4rem;
  border-radius: 3px;
}
.sp-mode-badge {
  font-size: 0.65rem;
  text-transform: none;
  letter-spacing: 0;
  background: rgba(251, 191, 36, 0.12);
  color: #fcd34d;
  padding: 0.05rem 0.4rem;
  border-radius: 3px;
}
.sp-fallback {
  margin-top: 0.5rem;
  background: rgba(251, 191, 36, 0.12);
  border: 1px dashed rgba(251, 191, 36, 0.4);
  color: #fcd34d;
  font: inherit;
  font-size: 0.78rem;
  padding: 0.4rem 0.6rem;
  border-radius: 3px;
  cursor: pointer;
  display: block;
  width: 100%;
  text-align: left;
}
.sp-fallback:hover { background: rgba(251, 191, 36, 0.22); color: #fbbf24; }

.sp-list { list-style: none; padding: 0; margin: 0; }
.sp-row {
  display: grid;
  grid-template-columns: 11ch 3ch auto 1fr;
  gap: 0.4rem;
  align-items: baseline;
  padding: 0.35rem 0.4rem;
  font-family: ui-monospace, monospace;
  font-size: var(--mono-size);
  cursor: pointer;
  border-top: 1px solid rgba(255, 255, 255, 0.04);
}
.sp-row:first-child { border-top: 0; }
.sp-row:hover { background: var(--bg-hover); border-radius: 3px; }
/* "Last jumped from" outline. Painted on the hit the user most
   recently clicked here. Same yellow as the call-tree row and
   inspection card, but a different meaning — the global focus
   moves with every click in the tree, this stays put until the
   user picks another hit. Cleared when a new search submits, so
   a stale breadcrumb doesn't survive into a fresh result set. */
.sp-row.highlighted {
  outline: 2px solid #fbbf24;
  outline-offset: -1px;
  border-radius: 3px;
}
.sp-time { color: var(--text-muted); }
.sp-sig  { color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sp-path {
  color: var(--text-secondary);
  background: rgba(255, 255, 255, 0.04);
  padding: 0 0.35rem;
  border-radius: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
