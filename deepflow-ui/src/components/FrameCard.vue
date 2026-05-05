<script setup>
import { computed, inject, ref } from 'vue';
import PayloadViewer from './PayloadViewer.vue';

const props = defineProps({
  call: { type: Object, required: true }
});

const emit = defineEmits(['pin']);

const payloadsByCallId   = inject('payloadsByCallId');
const childrenByParent   = inject('childrenByParent');
const expansionDefault   = inject('expansionDefault');
const expansionOverrides = inject('expansionOverrides');

const expanded = computed({
  get() {
    const o = expansionOverrides.value.get(props.call.call_id);
    return o === undefined ? expansionDefault.value : o;
  },
  set(v) {
    const next = new Map(expansionOverrides.value);
    next.set(props.call.call_id, v);
    expansionOverrides.value = next;
  }
});

function toggle() { expanded.value = !expanded.value; }

const payloads = computed(() => payloadsByCallId.value.get(props.call.call_id) || []);
const children = computed(() => childrenByParent.value.get(props.call.call_id) || []);
const hasChildren = computed(() => children.value.length > 0);

function shortSig(s) {
  if (!s) return '';
  return s.replace(/\(.*$/, '').split('.').slice(-2).join('.');
}

function fmtTime(ts) {
  if (!ts) return '';
  return String(ts).replace(/^\d{4}-\d\d-\d\d /, '').slice(0, 12);
}

const layer = computed(() => layerOf(props.call.signature));
function layerOf(signature) {
  if (!signature) return 'other';
  if (/\.controller\./i.test(signature)) return 'controller';
  if (/\.service\./i.test(signature))    return 'service';
  if (/\.repository\./i.test(signature)) return 'repository';
  if (/\.mapper\./i.test(signature))     return 'mapper';
  return 'other';
}

const entryPayloads = computed(() => payloads.value.filter(p => p.kind === 'AR' || p.kind === 'TI'));
const exitPayloads  = computed(() => payloads.value.filter(p => p.kind === 'AX' || p.kind === 'RE'));
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
            <span class="muted">{{ p.payload_size }} B · hash {{ String(p.root_hash).slice(0, 8) }}…</span>
          </div>
          <PayloadViewer :data="p.parsed"
                         :callId="call.call_id"
                         :kind="p.kind"
                         @pin="(payload) => emit('pin', payload)" />
        </div>
      </div>

      <ol v-if="hasChildren" class="rec-children">
        <FrameCard v-for="child in children"
                   :key="child.call_id"
                   :call="child"
                   @pin="(payload) => emit('pin', payload)" />
      </ol>

      <div v-if="exitPayloads.length" class="payload-block payload-block-exit">
        <div v-for="p in exitPayloads" :key="p.kind" class="payload">
          <div class="payload-head">
            <span class="kind" :class="p.kind">{{ p.kind }}</span>
            <span class="muted">{{ p.payload_size }} B · hash {{ String(p.root_hash).slice(0, 8) }}…</span>
          </div>
          <PayloadViewer :data="p.parsed"
                         :callId="call.call_id"
                         :kind="p.kind"
                         @pin="(payload) => emit('pin', payload)" />
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
.rec-row.leaf .rec-disclosure { visibility: hidden; }

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
.kind { font-size: 0.7rem; padding: 0.05rem 0.4rem; border-radius: 3px; font-weight: 600; }
.kind.AR { background: rgba(96, 165, 250, 0.18);  color: #93c5fd; }
.kind.AX { background: rgba(251, 191, 36, 0.18);  color: #fcd34d; }
.kind.RE { background: rgba(110, 231, 183, 0.18); color: #6ee7b7; }
.kind.TI { background: rgba(196, 181, 253, 0.18); color: #c4b5fd; }

.rec-children { list-style: none; padding: 0; margin: 0.25rem 0; }

.muted { color: var(--text-muted); font-size: 0.85rem; }
</style>
