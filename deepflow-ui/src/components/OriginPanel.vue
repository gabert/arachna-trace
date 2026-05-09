<script setup lang="ts">
// Right-pane peer to MutationsPanel and WatchPanel. Renders the
// structured chain returned by useProvenance.findChain — source row
// (with classification badge), collapsible propagation summary, and
// the user-clicked current row. Dropping future appearances and
// collapsing pass-through rows turns a 20-row noise list into a
// 2-3 row narrative: "produced HERE, propagated through N hops, you
// clicked HERE."
//
// Honest by construction: classification labels say "produced" or
// "entered from outside instrumentation", never "originated" — the
// agent doesn't trace method bodies, so a true causal origin is
// only knowable when the value's *first appearance* is an output of
// a call whose inputs didn't carry it (the 'produced' case).

import { computed, ref, watch } from 'vue';
import CollapsiblePanel from './CollapsiblePanel.vue';
import { fmtTime, formatValue, shortSig } from '../util/format';
import { formatPath } from '../util/envelopeDiff';
import type { JumpAddress, OriginAppearance, OriginMutation } from '../types';
import type { UseProvenance } from '../composables/useProvenance';

const props = defineProps<{
  provenance: UseProvenance;
}>();

const emit = defineEmits<{
  (e: 'jump', addr: JumpAddress): void;
}>();

// All reactive state lives inside the composable now: the user's
// click sets the target there, an async fetch populates `chain`, and
// `loading` / `error` reflect the in-flight state. The panel just
// reads.
const target = computed(() => props.provenance.target.value);
const chain = computed(() => props.provenance.chain.value);
const loading = computed(() => props.provenance.loading.value);
const fetchError = computed(() => props.provenance.error.value);
const tooCommon = computed(() => props.provenance.isTooCommon.value);

const onlyOneRow = computed(() => {
  const c = chain.value;
  return c?.source && c.current && c.source === c.current && c.propagation.length === 0;
});

// Propagation rows are collapsed by default — that's the whole
// point of the source/current framing. Reset whenever the target
// changes so a fresh click doesn't carry old expand state.
// CollapsiblePanel uses `collapsed` semantics, so this flips the
// polarity of the previous `propagationExpanded`.
const propagationCollapsed = ref(true);
watch(target, () => { propagationCollapsed.value = true; });

function jumpRow(a: OriginAppearance): void {
  emit('jump', { callId: a.callId, kind: a.kind, path: a.path });
}

function jumpMutation(m: OriginMutation): void {
  emit('jump', { callId: m.callId, kind: m.kind, path: m.path });
}

const sourceLabel = computed(() => {
  const c = chain.value;
  if (!c?.source) return '';
  if (c.sourceKind === 'produced') {
    return `Produced in ${shortSig(c.source.signature)}`;
  }
  return `Entered as input in ${shortSig(c.source.signature)}`;
});

const sourceExplanation = computed(() => {
  const c = chain.value;
  if (!c?.sourceKind) return '';
  if (c.sourceKind === 'produced') {
    return 'First appears as an OUTPUT of this call. The call\'s inputs did not contain this value, so it was generated inside the method body.';
  }
  return 'First appears as an INPUT to this call. The value entered the trace at this point — could be HTTP body, DB read, request param, configuration, or a constant inside the calling code that the agent does not see.';
});
</script>

<template>
  <aside class="origin-panel">
    <header class="op-head">
      <h3>Origin</h3>
      <small v-if="target">
        tracing
        <code>{{ formatValue(target.value) }}</code>
        <button class="op-clear" @click="provenance.clear()" title="Clear origin">×</button>
      </small>
    </header>

    <p v-if="!target" class="op-empty">
      Hover any scalar value in the recording on the left and click
      <code>↤ origin</code> to trace where it came from.
      <br><br>
      <strong>Heuristic, not authoritative.</strong> The agent doesn't see
      method bodies, so a match means "same value, observed earlier" — not
      "computed from this." Trivial values
      (<code>0</code>, <code>1</code>, <code>true</code>, short strings)
      cannot be chained.
    </p>

    <p v-else-if="tooCommon" class="op-warn">
      <code>{{ formatValue(target.value) }}</code> is intrinsically too
      common to chain meaningfully — short strings, booleans, small
      integers like <code>0</code> or <code>1</code> can't be traced to a
      specific source.
    </p>

    <p v-else-if="loading" class="op-loading">
      Searching for occurrences of <code>{{ formatValue(target.value) }}</code>…
    </p>

    <p v-else-if="fetchError" class="op-warn">
      Lookup failed: {{ fetchError }}
    </p>

    <p v-else-if="!chain || !chain.source" class="op-end">
      No occurrences of <code>{{ formatValue(target.value) }}</code> were found
      in this request. (If you're sure the value is in the trace, the
      processor may not have indexed it yet — payloads need the
      <code>payload_tokens</code> column populated at enrich time.)
    </p>

    <div v-else class="op-chain">
      <!-- Source row, classified. Click to navigate the call tree to
           the producing/entry point. -->
      <div class="op-block source"
           :class="['kind-' + chain.sourceKind]"
           @click="chain.source && jumpRow(chain.source)">
        <div class="op-block-head">
          <span class="op-icon">{{ chain.sourceKind === 'produced' ? '⚙' : '↦' }}</span>
          <span class="op-block-label">{{ sourceLabel }}</span>
        </div>
        <div class="op-block-body">
          <span class="op-time">{{ fmtTime(chain.source.ts) }}</span>
          <span class="op-kind kind" :class="chain.source.kind">{{ chain.source.kind }}</span>
          <code class="op-path">{{ formatPath(chain.source.path) || '·' }}</code>
        </div>
        <p class="op-explain">{{ sourceExplanation }}</p>
      </div>

      <!-- Propagation collapse. One row when collapsed, full list
           when expanded. Hidden entirely when source and current
           are adjacent (no intermediate rows). -->
      <CollapsiblePanel v-if="chain.propagation.length > 0"
                        class="op-prop"
                        v-model:collapsed="propagationCollapsed">
        <template #header>
          propagated through {{ chain.propagation.length }} method
          {{ chain.propagation.length === 1 ? 'boundary' : 'boundaries' }}
        </template>
        <ol class="op-prop-list">
          <li v-for="(a, i) in chain.propagation"
              :key="i"
              class="op-row"
              @click="jumpRow(a)">
            <span class="op-time">{{ fmtTime(a.ts) }}</span>
            <span class="op-kind kind" :class="a.kind">{{ a.kind }}</span>
            <span class="op-sig">{{ shortSig(a.signature) }}</span>
            <code class="op-path">{{ formatPath(a.path) || '·' }}</code>
          </li>
        </ol>
      </CollapsiblePanel>

      <!-- Current row — where the user clicked. Suppressed when
           source === current (single-row case). -->
      <div v-if="chain.current && !onlyOneRow" class="op-block current"
           @click="chain.current && jumpRow(chain.current)">
        <div class="op-block-head">
          <span class="op-icon">●</span>
          <span class="op-block-label">Current — {{ shortSig(chain.current.signature) }}</span>
        </div>
        <div class="op-block-body">
          <span class="op-time">{{ fmtTime(chain.current.ts) }}</span>
          <span class="op-kind kind" :class="chain.current.kind">{{ chain.current.kind }}</span>
          <code class="op-path">{{ formatPath(chain.current.path) || '·' }}</code>
        </div>
      </div>

      <p v-if="onlyOneRow" class="op-end">
        You clicked the source itself — the value first appears at this row.
      </p>

      <!-- Third card: the same field on the same envelope took on a
           different value later. Closes the loop on "what happened
           after the value's last appearance". Only shown when target
           sits inside an envelope and a later observation differs. -->
      <div v-if="chain.nextMutation" class="op-block mutation"
           @click="jumpMutation(chain.nextMutation)">
        <div class="op-block-head">
          <span class="op-icon">⚠</span>
          <span class="op-block-label">Then mutated to {{ formatValue(chain.nextMutation.newValue) }}</span>
        </div>
        <div class="op-block-body">
          <span class="op-time">{{ fmtTime(chain.nextMutation.ts) }}</span>
          <span class="op-kind kind" :class="chain.nextMutation.kind">{{ chain.nextMutation.kind }}</span>
          <span class="op-sig">{{ shortSig(chain.nextMutation.signature) }}</span>
          <code class="op-path">{{ formatPath(chain.nextMutation.fieldPath) || '·' }}</code>
        </div>
        <p class="op-explain">
          Same field on the same instance was observed with a different value here. The traced value did not "die out" — it was overwritten.
        </p>
      </div>

      <div class="op-conf-row">
        <span class="op-conf" :class="'conf-' + chain.source.confidence.toLowerCase()">{{ chain.source.confidence }}</span>
        <span class="op-conf-explain">
          confidence reflects how unique the value looks. HIGH — long structured strings, fractional / large numbers. MED — short strings, small ints. LOW — common literals.
        </span>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.origin-panel {
  background: var(--bg-surface);
  border-left: 1px solid var(--border);
  padding: 0.75rem;
  overflow-y: auto;
  height: 100%;
  color: var(--text-primary);
}
.op-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 0.5rem; gap: 0.5rem; }
.op-head h3 { margin: 0; font-size: 0.95rem; color: var(--text-primary); }
.op-head small { color: var(--text-muted); font-size: 0.75rem; display: inline-flex; align-items: center; gap: 0.4rem; }
.op-head small code {
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
.op-clear {
  background: transparent; border: 0; padding: 0;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 1rem;
  line-height: 1;
}
.op-clear:hover { color: var(--accent-red); }

.op-empty, .op-warn, .op-end {
  color: var(--text-secondary);
  font-size: 0.85rem;
  background: var(--bg-elevated);
  border: 1px dashed var(--border-strong);
  border-radius: 4px;
  padding: 0.75rem;
  line-height: 1.55;
}
.op-empty code, .op-warn code, .op-end code {
  background: rgba(96, 165, 250, 0.15);
  padding: 0 0.3rem;
  border-radius: 3px;
  color: #93c5fd;
  font-family: ui-monospace, monospace;
  font-size: 0.78rem;
}
.op-warn { border-color: rgba(251, 191, 36, 0.4); color: #fcd34d; }
.op-end  { border-color: rgba(110, 231, 183, 0.4); margin-top: 0.5rem; }
.op-loading {
  color: var(--text-secondary);
  font-size: 0.85rem;
  background: var(--bg-elevated);
  border: 1px dashed var(--border-strong);
  border-radius: 4px;
  padding: 0.75rem;
  font-style: italic;
}
.op-loading code {
  font-family: ui-monospace, monospace;
  background: rgba(96, 165, 250, 0.15);
  padding: 0 0.3rem;
  border-radius: 3px;
  color: #93c5fd;
  font-size: 0.78rem;
  font-style: normal;
}

/* Source / current "block" — boxed framing makes them visually
   distinct from the propagation list which is just a one-line
   collapsible. */
.op-block {
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  border-radius: 4px;
  padding: 0.55rem 0.7rem;
  margin-bottom: 0.5rem;
  cursor: pointer;
  transition: background 0.1s;
}
.op-block:hover { background: var(--bg-hover); }

.op-block.source.kind-produced { border-left: 3px solid #6ee7b7; }
.op-block.source.kind-entered  { border-left: 3px solid var(--accent-blue); }
.op-block.current              { border-left: 3px solid #fbbf24; }
.op-block.mutation             { border-left: 3px solid var(--accent-red); margin-top: 0.6rem; }

.op-block-head {
  display: flex; align-items: baseline; gap: 0.4rem;
  margin-bottom: 0.25rem;
}
.op-icon {
  font-size: 0.95rem;
  width: 1.2em;
  text-align: center;
  color: var(--text-secondary);
}
.op-block.source.kind-produced .op-icon { color: #6ee7b7; }
.op-block.source.kind-entered  .op-icon { color: var(--accent-blue); }
.op-block.current              .op-icon { color: #fbbf24; }
.op-block.mutation             .op-icon { color: var(--accent-red); }

.op-block-label {
  font-weight: 600;
  font-size: 0.85rem;
  color: var(--text-primary);
  font-family: ui-monospace, monospace;
}

.op-block-body {
  display: flex; align-items: baseline; gap: 0.5rem;
  font-family: ui-monospace, monospace;
  font-size: var(--mono-size);
  margin-bottom: 0.25rem;
}
.op-time { color: var(--text-muted); }
.op-sig  { color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.op-path {
  color: var(--text-secondary);
  background: rgba(255, 255, 255, 0.04);
  padding: 0 0.35rem;
  border-radius: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.op-explain {
  margin: 0.3rem 0 0;
  font-size: 0.78rem;
  color: var(--text-muted);
  line-height: 1.45;
}

/* Propagation collapse — one toggle line, expands into a
   monospace row list of pass-through observations. */
.op-prop {
  margin: 0.4rem 0;
  border-left: 2px dashed var(--border-strong);
  padding-left: 0.7rem;
}
/* CollapsiblePanel renders the chevron + click affordance. Style the
   header text inline with the previous "propagation" tone (uppercase,
   muted, monospace) and indent the body. */
.op-prop :deep(.cp-head) {
  font-family: ui-monospace, monospace;
  font-size: 0.78rem;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.op-prop :deep(.cp-body) { padding-top: 0.25rem; }
.op-prop-list { list-style: none; padding: 0; margin: 0; }
.op-prop-list .op-row {
  display: flex;
  gap: 0.5rem;
  align-items: baseline;
  padding: 0.25rem 0.4rem;
  font-family: ui-monospace, monospace;
  font-size: var(--mono-size);
  cursor: pointer;
  border-top: 1px solid rgba(255, 255, 255, 0.04);
}
.op-prop-list .op-row:first-child { border-top: 0; }
.op-prop-list .op-row:hover { background: var(--bg-hover); border-radius: 3px; }
.op-prop-list .op-row .op-sig { flex: 1; }

.op-conf-row {
  display: flex; align-items: baseline; gap: 0.5rem;
  margin-top: 0.6rem;
  padding-top: 0.5rem;
  border-top: 1px dashed var(--border);
}
.op-conf {
  font-size: 0.65rem;
  font-weight: 700;
  letter-spacing: 0.04em;
  padding: 0.05rem 0.3rem;
  border-radius: 3px;
}
.op-conf.conf-high { background: rgba(110, 231, 183, 0.18); color: #6ee7b7; }
.op-conf.conf-med  { background: rgba(251, 191, 36, 0.18); color: #fcd34d; }
.op-conf.conf-low  { background: rgba(248, 113, 113, 0.18); color: #fca5a5; }
.op-conf-explain {
  font-size: 0.72rem;
  color: var(--text-muted);
  line-height: 1.4;
}
</style>
