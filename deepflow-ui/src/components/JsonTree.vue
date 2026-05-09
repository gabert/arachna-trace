<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue';
import { isCycleRef, isEnvelope } from '../util/envelope';
import { useScrollIntoViewOnHighlight } from '../composables/useScrollIntoViewOnHighlight';
import { MUTATED_OBJECT_IDS, ADDED_OBJECT_IDS, NAV_TICK } from '../keys';
import type { Path, PathSegment, TraceTarget, Watch } from '../types';

interface EnvContext {
  objectId: number;
  className: string;
  basePath: Path;
}

const props = withDefaults(defineProps<{
  data: unknown;
  name?: PathSegment | '';
  path?: Path;
  depth?: number;
  isExpanded: (key: string) => boolean;
  envContext?: EnvContext | null;
  // Single source of truth for "is this the highlighted node".
  // Equality on pathKey decides; nothing recursive, nothing broadcast
  // beyond the per-node compare.
  highlightedPathKey?: string | null;
}>(), {
  name: '',
  path: () => [],
  depth: 0,
  envContext: null,
  highlightedPathKey: null
});

const emit = defineEmits<{
  (e: 'toggle', pathKey: string): void;
  (e: 'pin', payload: Watch): void;
  (e: 'follow-cycle', objectId: number): void;
  // Provenance — emitted from scalar leaves only. PayloadViewer
  // enriches with callId/kind before bubbling up. Path is relative
  // to the payload root.
  (e: 'origin', target: { path: Path; value: unknown }): void;
  // Trace this envelope on the call tree. Emitted from envelope
  // header rows only — payload viewers in the inspection cards
  // bubble it up to SessionDetailView, which paints subtree marks
  // on every FrameCard whose subtree contains this object id.
  (e: 'trace', target: TraceTarget): void;
}>();

type NodeKind = 'null' | 'array' | 'object' | 'string' | 'number' | 'boolean' | 'undefined' | 'function' | 'symbol' | 'bigint';

const kind = computed<NodeKind>(() => {
  if (props.data === null) return 'null';
  if (Array.isArray(props.data)) return 'array';
  if (typeof props.data === 'object') return 'object';
  return typeof props.data as NodeKind;
});
const isContainer = computed(() => kind.value === 'object' || kind.value === 'array');

interface EnvelopeRef {
  objectId: number;
  className: string;
  hash?: string;
}

const envelope = computed<EnvelopeRef | null>(() => {
  if (!isEnvelope(props.data)) return null;
  const m = props.data.__meta__;
  return { objectId: m.id, className: m.class || 'Object', hash: m.hash };
});

const cycleRef = computed<{ objectId: number } | null>(() => {
  if (!isCycleRef(props.data)) return null;
  return { objectId: props.data.ref_id };
});

const ownPath = computed<Path>(() =>
  props.name === '' ? props.path : [...props.path, props.name as PathSegment]);

const pathKey = computed(() => JSON.stringify(ownPath.value));
const expanded = computed(() => props.isExpanded(pathKey.value));

const isMatch = computed(() =>
  props.highlightedPathKey != null && pathKey.value === props.highlightedPathKey);

// Provided by PayloadViewer when it's rendering an AX payload of a
// call that had own_hash transitions. A non-null Set means "mark any
// envelope whose id is in here as mutated". Other payload kinds get
// null (no marks). Per-envelope precision — does not propagate to
// parents the way deep / Merkle hash would.
const mutatedObjectIds = inject(MUTATED_OBJECT_IDS, computed(() => null));
const isMutatedEnvelope = computed(() =>
  !!envelope.value
  && !!mutatedObjectIds.value
  && mutatedObjectIds.value.has(envelope.value.objectId)
);

// Same scoping for added envelopes — present in AX, absent from AR.
// Mutually exclusive with mutated (an added id has no AR own_hash to
// compare against, so it can't be in the mutated set).
const addedObjectIds = inject(ADDED_OBJECT_IDS, computed(() => null));
const isAddedEnvelope = computed(() =>
  !!envelope.value
  && !!addedObjectIds.value
  && addedObjectIds.value.has(envelope.value.objectId)
);

const nodeRef = ref<HTMLElement | null>(null);

// JsonTree wraps each row in a `.jt-node` whose bounding box includes
// the entire expanded subtree — scrollFirstChild=true makes the
// composable scroll the inner `.jt-row` instead. runOnMount=true
// covers the common case where ancestors expand as part of a search-
// hit / origin-jump and this node mounts with isMatch already true.
const navTick = inject(NAV_TICK, ref(0));
useScrollIntoViewOnHighlight(nodeRef, isMatch, navTick, {
  runOnMount: true,
  scrollFirstChild: true
});

const childEnvContext = computed<EnvContext | null>(() => {
  if (envelope.value) {
    return {
      objectId: envelope.value.objectId,
      className: envelope.value.className,
      basePath: ownPath.value
    };
  }
  return props.envContext;
});

// Clip very long arrays so a 5k-element list doesn't materialize 5k
// JsonTree component instances on a single expand. Object containers
// and small arrays render in full; large arrays render the first
// ARRAY_RENDER_LIMIT entries with an opt-in "show all" button. A
// navigation that targets an index past the limit auto-flips the
// switch (see watch on highlightedPathKey below) so links stay
// honored.
const ARRAY_RENDER_LIMIT = 200;
const showAllArray = ref(false);

const arrayLength = computed(() =>
  kind.value === 'array' ? (props.data as unknown[]).length : 0);

const arrayClipped = computed(() =>
  kind.value === 'array' && !showAllArray.value && arrayLength.value > ARRAY_RENDER_LIMIT);

const arrayHidden = computed(() =>
  arrayClipped.value ? arrayLength.value - ARRAY_RENDER_LIMIT : 0);

const entries = computed<[PathSegment, unknown][]>(() => {
  if (kind.value === 'object') {
    const obj = props.data as Record<string, unknown>;
    const keys = Object.keys(obj).filter(k => k !== '__meta__');
    return keys.map(k => [k, obj[k]] as [PathSegment, unknown]);
  }
  if (kind.value === 'array') {
    const arr = props.data as unknown[];
    const slice = arrayClipped.value ? arr.slice(0, ARRAY_RENDER_LIMIT) : arr;
    return slice.map((v, i) => [i, v] as [PathSegment, unknown]);
  }
  return [];
});

// If the global highlight lands on an index inside this array that's
// past the clip threshold, reveal the rest so the target node mounts
// and the existing scrollIntoView path can fire.
watch(() => props.highlightedPathKey, (key) => {
  if (!key || kind.value !== 'array' || showAllArray.value) return;
  if (!arrayClipped.value) return;
  let parsed: unknown;
  try { parsed = JSON.parse(key); } catch { return; }
  if (!Array.isArray(parsed)) return;
  const ours = ownPath.value;
  if (parsed.length <= ours.length) return;
  for (let i = 0; i < ours.length; i++) {
    if (parsed[i] !== ours[i]) return;
  }
  const childIdx = parsed[ours.length];
  if (typeof childIdx === 'number' && childIdx >= ARRAY_RENDER_LIMIT) {
    showAllArray.value = true;
  }
}, { immediate: true });

const summary = computed(() => {
  if (kind.value === 'array') return `[${(props.data as unknown[]).length}]`;
  if (kind.value === 'object') {
    const obj = props.data as Record<string, unknown>;
    const meta = (obj as { __meta__?: { class?: string; id?: number } }).__meta__;
    const keys = Object.keys(obj).filter(k => k !== '__meta__');
    if (meta && meta.class) {
      const cls = String(meta.class).split('.').pop();
      return `${cls} #${meta.id ?? '?'}  · ${keys.length} fields`;
    }
    return `{ ${keys.length} }`;
  }
  return '';
});

function toggle(): void {
  if (!isContainer.value || cycleRef.value) return;
  emit('toggle', pathKey.value);
}

function pinSelf(): void {
  if (cycleRef.value) return;
  if (envelope.value) {
    emit('pin', {
      kind: 'instance',
      objectId: envelope.value.objectId,
      className: envelope.value.className,
      hash: envelope.value.hash
    });
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

function followCycle(): void {
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

function relayToggle(p: string): void { emit('toggle', p); }
function relayPin(p: Watch): void    { emit('pin', p); }
function relayFollow(id: number): void { emit('follow-cycle', id); }
function relayOrigin(t: { path: Path; value: unknown }): void { emit('origin', t); }
function relayTrace(t: TraceTarget): void { emit('trace', t); }

// Trace is meaningful only on envelope rows — instances have stable
// object ids and visit specific frames; plain containers and scalars
// don't.
const canTrace = computed(() => !cycleRef.value && !!envelope.value);
function traceSelf(): void {
  if (!envelope.value) return;
  emit('trace', {
    objectId: envelope.value.objectId,
    className: envelope.value.className
  });
}

// Origin (provenance) is meaningful only for scalar leaves. Containers
// have no single "value" to chain on; envelopes are tracked by id
// linkage (handled by watch instead); cycle refs are graph pointers.
const canTraceOrigin = computed(() =>
  !cycleRef.value && !isContainer.value && !envelope.value);

function originSelf(): void {
  if (!canTraceOrigin.value) return;
  emit('origin', { path: ownPath.value, value: props.data });
}

function renderScalar(v: unknown): string {
  if (v === null) return 'null';
  if (typeof v === 'string') return JSON.stringify(v);
  return String(v);
}
</script>

<template>
  <div ref="nodeRef"
       class="jt-node"
       :class="{ container: isContainer && !cycleRef, flashed: isMatch, mutated: isMutatedEnvelope, added: isAddedEnvelope }">
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
        <button v-if="canTrace" class="jt-trace"
                @click.stop="traceSelf"
                title="Trace this instance on the call tree — every call where this object appears gets a bubble mark; mutating calls get a stronger mark.">🔎 trace</button>
        <button v-if="canTraceOrigin" class="jt-origin"
                @click.stop="originSelf"
                title="Trace where this value came from in this request (heuristic — see Origin panel)">↤ origin</button>
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
                :highlightedPathKey="highlightedPathKey"
                @toggle="relayToggle"
                @pin="relayPin"
                @follow-cycle="relayFollow"
                @origin="relayOrigin"
                @trace="relayTrace" />
      <button v-if="arrayClipped"
              class="jt-show-all"
              :title="`Rendering only the first ${ARRAY_RENDER_LIMIT} of ${arrayLength} elements; click to render the remaining ${arrayHidden} (may be slow on very large arrays).`"
              @click="showAllArray = true">
        + show {{ arrayHidden }} more
      </button>
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

.jt-origin {
  opacity: 0;
  pointer-events: none;
  background: rgba(196, 181, 253, 0.18);
  border: 1px solid rgba(196, 181, 253, 0.4);
  color: #c4b5fd;
  font-size: 0.7rem;
  cursor: pointer;
  padding: 0.05rem 0.4rem;
  border-radius: 3px;
  transition: opacity 0.1s;
  font-family: inherit;
}
.jt-row:hover .jt-origin { opacity: 1; pointer-events: auto; }
.jt-origin:hover { background: rgba(196, 181, 253, 0.3); }

/* Trace affordance — only on envelope rows. Amber palette so it
   visually relates to the bubble marks it paints on the call tree. */
.jt-trace {
  opacity: 0;
  pointer-events: none;
  background: rgba(251, 191, 36, 0.16);
  border: 1px solid rgba(251, 191, 36, 0.4);
  color: #fcd34d;
  font-size: 0.7rem;
  cursor: pointer;
  padding: 0.05rem 0.4rem;
  border-radius: 3px;
  transition: opacity 0.1s;
  font-family: inherit;
}
.jt-row:hover .jt-trace { opacity: 1; pointer-events: auto; }
.jt-trace:hover { background: rgba(251, 191, 36, 0.28); }
.jt-children { padding-left: 1.4ch; border-left: 1px dashed var(--border); margin-left: 0.4ch; }

.jt-show-all {
  display: inline-block;
  margin: 0.2rem 0 0.2rem 1.4ch;
  background: rgba(96, 165, 250, 0.12);
  border: 1px dashed rgba(96, 165, 250, 0.4);
  color: #93c5fd;
  font-family: inherit;
  font-size: 0.75rem;
  padding: 0.1rem 0.5rem;
  border-radius: 3px;
  cursor: pointer;
}
.jt-show-all:hover { background: rgba(96, 165, 250, 0.22); }

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

.jt-node.flashed > .jt-row {
  background: rgba(251, 191, 36, 0.22);
  outline: 2px solid #fbbf24;
  outline-offset: -2px;
  border-radius: 3px;
}

/* Per-envelope mutation mark, applied on both AR (mutation SITE — this
   envelope is about to change in this call) and AX (mutation RESULT —
   the post-mutation state). Sits on the envelope's own row, not the
   enclosing payload — so the eye lands on the BookEntity, not on the
   AuthorEntity that contains it. Slightly subtler than .flashed so a
   navigated row still wins visually if both apply. */
.jt-node.mutated > .jt-row {
  background: rgba(251, 191, 36, 0.10);
  box-shadow: inset 3px 0 0 #fbbf24;
}
.jt-node.mutated > .jt-row::after {
  content: '⚠ mutation';
  color: #fcd34d;
  background: rgba(251, 191, 36, 0.18);
  font-size: 0.68rem;
  padding: 0.05rem 0.4rem;
  border-radius: 3px;
  margin-left: 0.4rem;
  font-weight: 600;
}

/* Per-envelope "newly introduced" mark (AX only). The id appears in
   AX but not in AR — the method created or fetched a new object and
   placed it in args. Distinct signal from mutation; mutually exclusive
   in practice (added ids have no AR own_hash to compare against). */
.jt-node.added > .jt-row {
  background: rgba(110, 231, 183, 0.10);
  box-shadow: inset 3px 0 0 #6ee7b7;
}
.jt-node.added > .jt-row::after {
  content: '+ new';
  color: #6ee7b7;
  background: rgba(110, 231, 183, 0.18);
  font-size: 0.68rem;
  padding: 0.05rem 0.4rem;
  border-radius: 3px;
  margin-left: 0.4rem;
  font-weight: 600;
}
</style>
