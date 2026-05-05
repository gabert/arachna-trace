<script setup>
import { computed } from 'vue';

const props = defineProps({
  data: { required: true },
  name: { type: [String, Number], default: '' },
  path: { type: Array, default: () => [] },
  depth: { type: Number, default: 0 },
  // Owner of expansion state — typically the enclosing PayloadViewer.
  isExpanded: { type: Function, required: true },
  // Enclosing envelope info for field pinning. Plain object, not reactive
  // chain. Set by the parent JsonTree when it is itself an envelope.
  envContext: { type: Object, default: null }
});

const emit = defineEmits(['toggle', 'pin', 'follow-cycle']);

const kind = computed(() => {
  if (props.data === null) return 'null';
  if (Array.isArray(props.data)) return 'array';
  if (typeof props.data === 'object') return 'object';
  return typeof props.data;
});
const isContainer = computed(() => kind.value === 'object' || kind.value === 'array');

const envelope = computed(() => {
  if (kind.value !== 'object') return null;
  const m = props.data.__meta__;
  if (!m || m.id == null) return null;
  return { objectId: m.id, className: m.class || 'Object', hash: m.hash };
});

const cycleRef = computed(() => {
  if (kind.value !== 'object') return null;
  const d = props.data;
  if (d && d.ref_id != null && d.cycle_ref === true) {
    return { objectId: d.ref_id };
  }
  return null;
});

const ownPath = computed(() =>
  props.name === '' ? props.path : [...props.path, props.name]);

// Path key uses JSON.stringify so every path is globally unique
// (object keys can contain any character, so no in-band delimiter is safe).
const pathKey = computed(() => JSON.stringify(ownPath.value));
const expanded = computed(() => props.isExpanded(pathKey.value));

// Context to forward to children: own envelope wins, else passthrough.
const childEnvContext = computed(() => {
  if (envelope.value) {
    return {
      objectId: envelope.value.objectId,
      className: envelope.value.className,
      basePath: ownPath.value
    };
  }
  return props.envContext;
});

const entries = computed(() => {
  if (kind.value === 'object') {
    const keys = Object.keys(props.data).filter(k => k !== '__meta__');
    return keys.map(k => [k, props.data[k]]);
  }
  if (kind.value === 'array') {
    return props.data.map((v, i) => [i, v]);
  }
  return [];
});

const summary = computed(() => {
  if (kind.value === 'array') return `[${props.data.length}]`;
  if (kind.value === 'object') {
    const meta = props.data.__meta__;
    const keys = Object.keys(props.data).filter(k => k !== '__meta__');
    if (meta && meta.class) {
      const cls = String(meta.class).split('.').pop();
      return `${cls} #${meta.id ?? '?'}  · ${keys.length} fields`;
    }
    return `{ ${keys.length} }`;
  }
  return '';
});

function toggle() {
  if (!isContainer.value || cycleRef.value) return;
  emit('toggle', pathKey.value);
}

function pinSelf() {
  if (cycleRef.value) return;
  if (envelope.value) {
    emit('pin', { kind: 'instance', ...envelope.value });
    return;
  }
  if (props.envContext) {
    const fieldPath = ownPath.value.slice(props.envContext.basePath.length);
    if (!fieldPath.length) return;
    emit('pin', {
      kind: 'field',
      objectId: props.envContext.objectId,
      className: props.envContext.className,
      fieldPath
    });
  }
}

function followCycle() {
  if (cycleRef.value) emit('follow-cycle', cycleRef.value.objectId);
}

const canPin = computed(() => !cycleRef.value && (!!envelope.value || !!props.envContext));
const pinTitle = computed(() => {
  if (envelope.value) {
    const cls = String(envelope.value.className).split('.').pop();
    return `Watch ${cls} #${envelope.value.objectId}`;
  }
  if (props.envContext) {
    const ctx = props.envContext;
    const cls = String(ctx.className).split('.').pop();
    const path = ownPath.value.slice(ctx.basePath.length).join('.');
    return `Watch ${cls} #${ctx.objectId}.${path}`;
  }
  return 'Watch';
});

function relayToggle(p) { emit('toggle', p); }
function relayPin(p)    { emit('pin', p); }
function relayFollow(id){ emit('follow-cycle', id); }

function renderScalar(v) {
  if (v === null) return 'null';
  if (typeof v === 'string') return JSON.stringify(v);
  return String(v);
}
</script>

<template>
  <div class="jt-node"
       :class="{ container: isContainer && !cycleRef }"
       :data-path="pathKey">
    <div class="jt-row">
      <span v-if="!cycleRef" class="jt-toggle" @click="toggle">
        <template v-if="isContainer">{{ expanded ? '▾' : '▸' }}</template>
        <template v-else>·</template>
      </span>
      <span v-else class="jt-toggle">·</span>

      <span v-if="name !== ''" class="jt-key" @click="toggle">{{ name }}:</span>

      <template v-if="cycleRef">
        <button class="jt-cycle"
                @click.stop="followCycle"
                :title="`Cycle reference — same instance as object #${cycleRef.objectId} above. Click to reveal.`">
          ↺ #{{ cycleRef.objectId }}
        </button>
      </template>
      <template v-else>
        <span v-if="isContainer" class="jt-summary" @click="toggle">{{ summary }}</span>
        <span v-else class="jt-value" :class="kind">{{ renderScalar(data) }}</span>
        <button v-if="canPin" class="jt-pin"
                @click.stop="pinSelf"
                :title="pinTitle">⊕ watch</button>
      </template>
    </div>
    <div v-if="isContainer && !cycleRef && expanded" class="jt-children">
      <JsonTree v-for="[k, v] in entries"
                :key="String(k)"
                :name="k"
                :data="v"
                :path="ownPath"
                :depth="depth + 1"
                :isExpanded="isExpanded"
                :envContext="childEnvContext"
                @toggle="relayToggle"
                @pin="relayPin"
                @follow-cycle="relayFollow" />
    </div>
  </div>
</template>

<style scoped>
.jt-node { font-family: ui-monospace, "Cascadia Code", Consolas, monospace; font-size: var(--mono-size); line-height: 1.5; color: var(--text-primary); }
.jt-row { display: flex; gap: 0.4rem; align-items: baseline; }
.jt-toggle { width: 1ch; color: var(--text-muted); user-select: none; cursor: pointer; }
.container > .jt-row .jt-toggle { color: var(--text-secondary); }
.jt-key { color: #c4b5fd; cursor: pointer; }
.jt-key:hover { background: rgba(196, 181, 253, 0.12); }
.jt-summary { color: var(--text-muted); font-style: italic; cursor: pointer; }
.jt-value { cursor: pointer; }
.jt-value:hover { background: rgba(251, 191, 36, 0.12); }
.jt-value.string  { color: #6ee7b7; }
.jt-value.number  { color: #fca5a5; }
.jt-value.boolean { color: #60a5fa; }
.jt-value.null    { color: var(--text-muted); }
.jt-pin {
  opacity: 0;
  pointer-events: none;
  background: rgba(96, 165, 250, 0.18);
  border: 1px solid rgba(96, 165, 250, 0.4);
  color: #93c5fd;
  font-size: 0.7rem;
  cursor: pointer;
  padding: 0.05rem 0.4rem;
  border-radius: 3px;
  transition: opacity 0.1s;
  font-family: inherit;
}
.jt-row:hover .jt-pin { opacity: 1; pointer-events: auto; }
.jt-pin:hover { background: rgba(96, 165, 250, 0.3); }
.jt-children { padding-left: 1.4ch; border-left: 1px dashed var(--border); margin-left: 0.4ch; }

.jt-cycle {
  background: rgba(196, 181, 253, 0.15);
  border: 1px solid rgba(196, 181, 253, 0.35);
  color: #c4b5fd;
  font-family: inherit;
  font-size: 0.78rem;
  padding: 0.05rem 0.45rem;
  border-radius: 999px;
  cursor: pointer;
}
.jt-cycle:hover { background: rgba(196, 181, 253, 0.25); }

/* Imperatively-applied flash for navigation. The navigator adds and
   removes this class via DOM, no reactive prop. */
.jt-node.flashed > .jt-row {
  background: rgba(251, 191, 36, 0.22);
  outline: 2px solid #fbbf24;
  outline-offset: -2px;
  border-radius: 3px;
  transition: background 0.4s, outline-color 0.4s;
}
</style>
