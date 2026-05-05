<script setup>
import { computed, nextTick, ref } from 'vue';
import PayloadViewer from './PayloadViewer.vue';
import { api } from '../api/client.js';

const props = defineProps({
  call: { type: Object, required: true }
});

const emit = defineEmits(['pin']);

const expanded = ref(false);
const payloads = ref(null);     // [{ kind, parsed, payload_size, root_hash }]
const loading  = ref(false);
const error    = ref(null);

const viewerRefs = ref({});       // kind → PayloadViewer instance

const rootEl = ref(null);

function tryParse(s) {
  if (s == null) return null;
  try { return JSON.parse(s); } catch (_) { return s; }
}

async function ensurePayloads() {
  if (payloads.value) return;
  loading.value = true;
  error.value = null;
  try {
    const rows = await api.callPayloads(props.call.call_id);
    rows.forEach(r => { r.parsed = tryParse(r.payload_json); });
    payloads.value = rows;
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}

async function toggle() {
  expanded.value = !expanded.value;
  if (expanded.value) await ensurePayloads();
}

/**
 * Imperative entry point used by the navigator. Open the body, fetch
 * payloads, then ask the matching {@code PayloadViewer} to reveal the
 * path. The frame's own DOM is also scrolled into view.
 */
async function revealAt(kind, path) {
  if (!expanded.value) {
    expanded.value = true;
  }
  await ensurePayloads();
  await nextTick();
  if (rootEl.value) {
    rootEl.value.scrollIntoView({ block: 'start' });
  }
  if (!path || !path.length) return;
  const viewer = viewerRefs.value[kind];
  if (viewer) viewer.revealPath(path);
}

defineExpose({ revealAt });

function captureViewer(kind, instance) {
  if (instance) viewerRefs.value[kind] = instance;
  else delete viewerRefs.value[kind];
}

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

const depth = computed(() => props.call.depth ?? 0);
</script>

<template>
  <li ref="rootEl"
      class="rec-row"
      :class="['layer-' + layer, { open: expanded, exception: call.is_exception }]">
    <button class="rec-head" @click="toggle">
      <span class="rec-time">{{ fmtTime(call.ts_in) }}</span>
      <span class="rec-dur">{{ call.duration_ms }} ms</span>
      <span class="layer-stripe" :class="'layer-' + layer"></span>
      <span class="rec-indent" :style="{ width: (depth * 1.4) + 'rem' }"></span>
      <span class="rec-sig" :title="call.signature">{{ shortSig(call.signature) }}</span>
      <span class="rec-ret" :class="(call.return_type || 'VOID').toLowerCase()">{{ call.return_type }}</span>
    </button>

    <div v-if="expanded" class="rec-body"
         :style="{ paddingLeft: `calc(25ch + ${depth * 1.4}rem)` }">
      <div v-if="loading" class="muted">loading…</div>
      <div v-else-if="error" class="error">{{ error }}</div>
      <template v-else-if="payloads">
        <div v-for="p in payloads" :key="p.kind" class="payload">
          <div class="payload-head">
            <span class="kind" :class="p.kind">{{ p.kind }}</span>
            <span class="muted">{{ p.payload_size }} B · hash {{ String(p.root_hash).slice(0, 8) }}…</span>
          </div>
          <PayloadViewer
              :ref="(el) => captureViewer(p.kind, el)"
              :data="p.parsed"
              @pin="(payload) => emit('pin', payload)" />
        </div>
        <div v-if="!payloads.length" class="muted">no payloads</div>
      </template>
    </div>
  </li>
</template>

<style scoped>
.rec-row { border-top: 1px solid var(--border); list-style: none; }
.rec-row.exception { background: rgba(248, 113, 113, 0.07); }
.rec-row.open { background: var(--bg-surface); }

.rec-head {
  width: 100%; display: flex; align-items: center; gap: 0.5rem;
  padding: 0.3rem 0.5rem; background: none; border: 0; text-align: left;
  font: inherit; cursor: pointer; position: relative; color: var(--text-primary);
}
.rec-head:hover { background: var(--bg-hover); }

.rec-indent { flex-shrink: 0; }

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

.rec-body { padding: 0.5rem 1rem 1rem; }
.payload { margin: 0.6rem 0; }
.payload-head { display: flex; gap: 0.6rem; align-items: baseline; margin-bottom: 0.2rem; }
.kind { font-size: 0.7rem; padding: 0.05rem 0.4rem; border-radius: 3px; font-weight: 600; }
.kind.AR { background: rgba(96, 165, 250, 0.18);  color: #93c5fd; }
.kind.AX { background: rgba(251, 191, 36, 0.18);  color: #fcd34d; }
.kind.RE { background: rgba(110, 231, 183, 0.18); color: #6ee7b7; }
.kind.TI { background: rgba(196, 181, 253, 0.18); color: #c4b5fd; }

.muted { color: var(--text-muted); font-size: 0.85rem; }
.error { color: var(--accent-red); }
</style>
