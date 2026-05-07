<script setup lang="ts">
import { computed, inject, ref } from 'vue';
import PayloadViewer from './PayloadViewer.vue';
import FrameChildrenGroup from './FrameChildrenGroup.vue';
import { fmtTime, shortSig } from '../util/format';
import {
  PAYLOADS_BY_CALL_ID, CHILDREN_BY_PARENT,
  EXPANSION_DEFAULT, EXPANSION_OVERRIDES,
  MUTATED_OBJECTS_BY_CALL_ID, ADDED_OBJECTS_BY_CALL_ID
} from '../keys';
import type { CallRow, OriginTarget, PayloadRow, Watch } from '../types';

const props = defineProps<{ call: CallRow }>();

const emit = defineEmits<{
  (e: 'pin', payload: Watch): void;
  (e: 'origin', target: OriginTarget): void;
}>();

const payloadsByCallId   = inject(PAYLOADS_BY_CALL_ID, computed(() => new Map<string, PayloadRow[]>()));
const childrenByParent   = inject(CHILDREN_BY_PARENT, computed(() => new Map<string | null, CallRow[]>()));
const expansionDefault   = inject(EXPANSION_DEFAULT, ref(false));
const expansionOverrides = inject(EXPANSION_OVERRIDES, ref(new Map<string, boolean>()));
const mutatedObjectsByCallId = inject(MUTATED_OBJECTS_BY_CALL_ID, computed(() => new Map<string, Set<number>>()));
const addedObjectsByCallId   = inject(ADDED_OBJECTS_BY_CALL_ID,   computed(() => new Map<string, Set<number>>()));

const mutatedCount = computed(() => {
  const ids = mutatedObjectsByCallId.value.get(props.call.call_id);
  return ids ? ids.size : 0;
});
const addedCount = computed(() => {
  const ids = addedObjectsByCallId.value.get(props.call.call_id);
  return ids ? ids.size : 0;
});

const expanded = computed<boolean>({
  get() {
    const o = expansionOverrides.value.get(props.call.call_id);
    return o === undefined ? expansionDefault.value : o;
  },
  set(v: boolean) {
    const next = new Map(expansionOverrides.value);
    next.set(props.call.call_id, v);
    expansionOverrides.value = next;
  }
});

function toggle(): void { expanded.value = !expanded.value; }

const payloads = computed<PayloadRow[]>(() => payloadsByCallId.value.get(props.call.call_id) || []);
const children = computed<CallRow[]>(() => childrenByParent.value.get(props.call.call_id) || []);
const hasChildren = computed(() => children.value.length > 0);

type Layer = 'controller' | 'service' | 'repository' | 'mapper' | 'other';

const layer = computed<Layer>(() => layerOf(props.call.signature));
function layerOf(signature: string | null | undefined): Layer {
  if (!signature) return 'other';
  if (/\.controller\./i.test(signature)) return 'controller';
  if (/\.service\./i.test(signature))    return 'service';
  if (/\.repository\./i.test(signature)) return 'repository';
  if (/\.mapper\./i.test(signature))     return 'mapper';
  return 'other';
}

const entryPayloads = computed(() => payloads.value.filter(p => p.kind === 'AR' || p.kind === 'TI'));

// AX is rendered as its own block right after the entry block — i.e.
// AR and AX sit next to each other so the before/after pair is
// visually adjacent without children pushing them apart. Only
// rendered when this call has a mutation or an added envelope; the
// no-mutation case would just duplicate AR. (Search-result rows
// targeting AX of a non-mutated call are filtered out at the
// search layer instead — see useValueSearch.)
const axPayload = computed<PayloadRow | null>(() => {
  if (mutatedCount.value === 0 && addedCount.value === 0) return null;
  return payloads.value.find(p => p.kind === 'AX') || null;
});

// Exit block now carries only RE — AX moved up to sit beside AR.
const exitPayloads = computed(() => payloads.value.filter(p => p.kind === 'RE'));
</script>

<template>
  <li class="rec-row"
      :class="['layer-' + layer, { open: expanded, exception: call.is_exception, leaf: !hasChildren }]">
    <button class="rec-head" @click="toggle"
            :title="call.signature">
      <span class="rec-disclosure">{{ expanded ? '▾' : '▸' }}</span>
      <span class="rec-time">{{ fmtTime(call.ts_in) }}</span>
      <span class="rec-dur">{{ call.duration_ms }} ms</span>
      <span class="layer-stripe" :class="'layer-' + layer"></span>
      <span class="rec-sig">{{ shortSig(call.signature) }}</span>
      <span class="rec-ret" :class="(call.return_type || 'VOID').toLowerCase()">{{ call.return_type }}</span>
    </button>

    <div v-if="expanded" class="rec-body">
      <div v-if="entryPayloads.length" class="payload-block payload-block-entry">
        <div v-for="p in entryPayloads" :key="p.kind" class="payload">
          <div class="payload-head">
            <span class="kind" :class="p.kind">{{ p.kind }}</span>
            <span v-if="p.kind === 'AR' && mutatedCount > 0" class="mutation-badge"
                  :title="mutatedCount + ' envelope(s) here are mutated by this call (own_hash differs between AR and AX). Look for the highlighted envelope below.'">
              ⚠ {{ mutatedCount }} mutation site{{ mutatedCount === 1 ? '' : 's' }}
            </span>
            <span v-if="p.kind === 'AR' && addedCount > 0" class="added-badge"
                  :title="addedCount + ' envelope(s) appear in AX but not in AR — this call introduces them. Expand AX below to inspect.'">
              + {{ addedCount }} new in AX
            </span>
            <span class="muted" title="Merkle root hash of the rendered payload tree. Differs from the previous payload whenever ANY envelope's content changed anywhere in the subtree — useful as a cheap 'did anything change at all' signal. Per-envelope precision is shown by the in-tree mutation mark, not by this.">{{ p.payload_size }} B · deep {{ String(p.root_hash).slice(0, 8) }}…</span>
          </div>
          <PayloadViewer :data="p.parsed"
                         :callId="call.call_id"
                         :kind="p.kind"
                         @pin="(payload) => emit('pin', payload)"
                         @origin="(t) => emit('origin', t)" />
        </div>
      </div>

      <div v-if="axPayload" class="payload-block payload-block-ax">
        <div class="payload">
          <div class="payload-head">
            <span class="kind" :class="axPayload.kind">{{ axPayload.kind }}</span>
            <span v-if="mutatedCount > 0" class="mutation-badge"
                  :title="mutatedCount + ' envelope(s) own_hash changed between AR and AX. Look for the highlighted envelope inside.'">
              ⚠ {{ mutatedCount }} mutation{{ mutatedCount === 1 ? '' : 's' }}
            </span>
            <span v-if="addedCount > 0" class="added-badge"
                  :title="addedCount + ' envelope(s) appear in AX but not in AR — newly introduced during the call.'">
              + {{ addedCount }} new
            </span>
            <span class="muted" title="Merkle root hash of the rendered payload tree. Differs from AR whenever ANY envelope's content changed anywhere in the subtree.">{{ axPayload.payload_size }} B · deep {{ String(axPayload.root_hash).slice(0, 8) }}…</span>
          </div>
          <PayloadViewer :data="axPayload.parsed"
                         :callId="call.call_id"
                         :kind="axPayload.kind"
                         @pin="(payload) => emit('pin', payload)"
                         @origin="(t) => emit('origin', t)" />
        </div>
      </div>

      <FrameChildrenGroup v-if="hasChildren"
                          :callId="call.call_id"
                          :children="children"
                          @pin="(p) => emit('pin', p)"
                          @origin="(t) => emit('origin', t)" />

      <div v-if="exitPayloads.length" class="payload-block payload-block-exit">
        <div v-for="p in exitPayloads" :key="p.kind" class="payload">
          <div class="payload-head">
            <span class="kind" :class="p.kind">{{ p.kind }}</span>
            <span class="muted" title="Merkle root hash of the rendered payload tree. Differs from AR whenever ANY envelope's content changed anywhere in the subtree.">{{ p.payload_size }} B · deep {{ String(p.root_hash).slice(0, 8) }}…</span>
          </div>
          <PayloadViewer :data="p.parsed"
                         :callId="call.call_id"
                         :kind="p.kind"
                         @pin="(payload) => emit('pin', payload)"
                         @origin="(t) => emit('origin', t)" />
        </div>
      </div>
    </div>
  </li>
</template>

<style scoped>
.rec-row { border-top: 1px solid var(--border); list-style: none; }
.rec-row.exception { background: rgba(248, 113, 113, 0.04); }
.rec-row.open      { background: var(--bg-base); }

.rec-head {
  width: 100%; display: flex; align-items: center; gap: 0.5rem;
  padding: 0.3rem 0.5rem; background: none; border: 0; text-align: left;
  font: inherit; cursor: pointer; position: relative; color: var(--text-primary);
}
.rec-head:hover { background: var(--bg-hover); }

.rec-disclosure { width: 1ch; color: var(--text-muted); user-select: none; }
/* Arrow is always shown — it's the "this row is expandable" signal,
   not a "has nested calls" badge. Calls-within count is rendered
   separately by FrameChildrenGroup's yellow toggle. */

.layer-stripe { width: 3px; height: 1.1rem; border-radius: 2px; flex-shrink: 0; }
.layer-stripe.layer-controller { background: #60a5fa; }
.layer-stripe.layer-service    { background: #34d399; }
.layer-stripe.layer-repository { background: #fbbf24; }
.layer-stripe.layer-mapper     { background: #c4b5fd; }
.layer-stripe.layer-other      { background: #6b7280; }

.rec-time { font-family: ui-monospace, monospace; color: var(--text-muted); width: 13ch; font-size: var(--mono-size); flex-shrink: 0; }
.rec-dur  { font-family: ui-monospace, monospace; color: var(--text-secondary); width: 8ch; text-align: right; font-size: var(--mono-size); flex-shrink: 0; }
.rec-sig  { flex: 1; font-family: ui-monospace, monospace; font-size: var(--mono-size); color: var(--text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rec-ret  { font-size: 0.68rem; padding: 0.05rem 0.4rem; border-radius: 3px; background: var(--bg-elevated); color: var(--text-secondary); }
.rec-ret.value     { background: rgba(110, 231, 183, 0.15); color: #6ee7b7; }
.rec-ret.exception { background: rgba(248, 113, 113, 0.18); color: #fca5a5; }
.rec-ret.void      { background: var(--bg-elevated); color: var(--text-muted); }

.rec-body {
  padding: 0.25rem 0 0.25rem 1.4rem;
  border-left: 1px dashed var(--border-strong);
  margin-left: 1.4rem;
}
.payload { margin: 0.4rem 0; }
.payload-head { display: flex; gap: 0.6rem; align-items: baseline; margin-bottom: 0.2rem; }

/* AR / AX block visually paired — small gap, AX gets a subtle dividing
   border so the eye registers them as before/after of the same call. */
.payload-block-ax { margin-top: 0.15rem; padding-top: 0.15rem; border-top: 1px dashed var(--border); }

/* Mutation badge in the AX header. Block-level highlight removed —
   it implied "the whole AR scope changed", which is the deep / Merkle
   framing we don't want. The actual mutated envelope inside JsonTree
   carries its own per-row highlight. */
.mutation-badge {
  background: rgba(251, 191, 36, 0.18);
  color: #fcd34d;
  font-size: 0.7rem;
  padding: 0.05rem 0.4rem;
  border-radius: 3px;
  font-weight: 600;
}
.added-badge {
  background: rgba(110, 231, 183, 0.18);
  color: #6ee7b7;
  font-size: 0.7rem;
  padding: 0.05rem 0.4rem;
  border-radius: 3px;
  font-weight: 600;
}

.muted { color: var(--text-muted); font-size: 0.85rem; }
</style>
