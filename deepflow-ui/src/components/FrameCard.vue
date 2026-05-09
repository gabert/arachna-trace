<script setup lang="ts">
import { computed, inject, ref } from 'vue';
import FrameChildrenGroup from './FrameChildrenGroup.vue';
import { fmtTime, shortSig } from '../util/format';
import {
  CHILDREN_BY_PARENT,
  EXPANSION_DEFAULT, EXPANSION_OVERRIDES,
  SELECT_CALL, SELECTED_CALL_ID,
  INSPECTED_INSTANCE, SUBTREE_APPEARANCES_BY_CALL_ID
} from '../keys';
import type { AppearanceKind, CallRow, OriginTarget, TraceTarget, Watch } from '../types';

const props = defineProps<{ call: CallRow }>();

// Pin / origin events still bubble up — they originate from PayloadViewer
// instances that now live inside the inspection cards on the right
// (CallInspectionCard); the call tree itself only emits these
// indirectly through the FrameChildrenGroup chain when the inspection
// card on a nested call delegates back. Keeping the emits in the
// signature avoids breaking the prop-drill chain.
const emit = defineEmits<{
  (e: 'pin', payload: Watch): void;
  (e: 'origin', target: OriginTarget): void;
}>();

const childrenByParent   = inject(CHILDREN_BY_PARENT, computed(() => new Map<string | null, CallRow[]>()));
const expansionDefault   = inject(EXPANSION_DEFAULT, ref(false));
const expansionOverrides = inject(EXPANSION_OVERRIDES, ref(new Map<string, boolean>()));

// Click splits two ways: the disclosure button toggles expand/collapse,
// the rest of the row sets this call as the inspection target. They
// must not conflict — selecting a row should never accidentally
// collapse it.
const selectCall = inject(SELECT_CALL, (_id: string) => {});
const selectedCallId = inject(SELECTED_CALL_ID, ref<string | null>(null));
const isSelected = computed(() => selectedCallId.value === props.call.call_id);
function select(): void { selectCall(props.call.call_id); }

// Bubble mark for the inspected instance. The "subtree" map already
// rolls a descendant's mark up to this row, so a collapsed parent
// shows an indicator when something below it touches the instance.
const inspectedInstance = inject(INSPECTED_INSTANCE, ref<TraceTarget | null>(null));
const subtreeAppearancesByCallId = inject(SUBTREE_APPEARANCES_BY_CALL_ID,
  computed(() => new Map<string, AppearanceKind>()));
const tracingActive = computed(() => inspectedInstance.value != null);
const bubbleKind = computed<AppearanceKind | null>(() =>
  subtreeAppearancesByCallId.value.get(props.call.call_id) || null);
const bubbleTitle = computed(() => {
  if (!inspectedInstance.value) return '';
  const inst = inspectedInstance.value;
  const cls = String(inst.className).split('.').pop() || inst.className;
  if (bubbleKind.value === 'mutated') {
    return `${cls} #${inst.objectId} — own_hash changes inside this subtree (this call or a descendant mutates the instance)`;
  }
  return `${cls} #${inst.objectId} — appears inside this subtree`;
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

// Compact glyph for the return-type chip on the row. Tooltip carries
// the full word for readers who don't recognise the symbol.
const RETURN_GLYPH: Record<string, string> = {
  VOID:      '⊘',
  VALUE:     '↵',
  EXCEPTION: '⚠'
};
const returnGlyph = computed(() => RETURN_GLYPH[props.call.return_type] || '·');
</script>

<template>
  <li class="rec-row"
      :class="['layer-' + layer, { open: expanded, exception: call.is_exception, leaf: !hasChildren, selected: isSelected }]">
    <div class="rec-head">
      <button class="rec-disclosure-btn"
              @click.stop="toggle"
              :title="expanded ? 'Collapse' : 'Expand'">
        <span class="rec-disclosure">{{ expanded ? '▾' : '▸' }}</span>
      </button>
      <span v-if="tracingActive"
            class="rec-bubble"
            :class="bubbleKind || 'empty'"
            :title="bubbleTitle"></span>
      <button class="rec-select-btn"
              @click="select"
              :title="call.signature">
        <span class="rec-time">{{ fmtTime(call.ts_in) }}</span>
        <span class="rec-dur">{{ call.duration_ms }} ms</span>
        <span class="layer-stripe" :class="'layer-' + layer"></span>
        <span class="rec-sig">{{ shortSig(call.signature) }}</span>
        <span v-if="hasChildren"
              class="rec-childcount"
              :title="children.length + ' nested call' + (children.length === 1 ? '' : 's')">
          {{ children.length }}↳
        </span>
        <span class="rec-ret"
              :class="(call.return_type || 'VOID').toLowerCase()"
              :title="call.return_type">{{ returnGlyph }}</span>
      </button>
    </div>

    <!-- Body now carries only the nested call structure. TI / AR / AX
         / RE inspection moved to CallInspectionCard on the right. -->
    <div v-if="expanded && hasChildren" class="rec-body">
      <FrameChildrenGroup :callId="call.call_id"
                          :children="children"
                          @pin="(p) => emit('pin', p)"
                          @origin="(t) => emit('origin', t)" />
    </div>
  </li>
</template>

<style scoped>
.rec-row { border-top: 1px solid var(--border); list-style: none; }
/* Exception rows pop with a stronger red tint and a solid red bar on
   the very left edge, so a failed call is scannable from across the
   tree. Selected + exception combine via the box-shadow staying put
   while the background tint stacks. */
.rec-row.exception {
  background: rgba(248, 113, 113, 0.10);
  box-shadow: inset 3px 0 0 var(--accent-red);
}
.rec-row.open      { background: var(--bg-base); }
.rec-row.selected  { background: rgba(96, 165, 250, 0.12); }
.rec-row.selected.exception { background: rgba(248, 113, 113, 0.18); }

/* Row head splits into two click targets. Disclosure on the left
   toggles expansion; the rest of the row selects this call as the
   inspection target. Wrapping div carries hover/selected styling so
   the whole row reads as a single visual unit. */
.rec-head {
  display: flex; align-items: stretch;
  color: var(--text-primary);
  position: relative;
}
.rec-head:hover { background: var(--bg-hover); }

.rec-disclosure-btn,
.rec-select-btn {
  background: none; border: 0; color: inherit; cursor: pointer;
  font: inherit; text-align: left; padding: 0;
}
.rec-disclosure-btn {
  padding: 0.3rem 0.35rem 0.3rem 0.5rem;
  flex-shrink: 0;
}
.rec-disclosure-btn:hover .rec-disclosure { color: var(--text-primary); }
.rec-select-btn {
  flex: 1;
  display: flex; align-items: center; gap: 0.5rem;
  padding: 0.3rem 0.5rem 0.3rem 0;
  min-width: 0;
}

.rec-disclosure { width: 1ch; color: var(--text-muted); user-select: none; }

/* Bubble mark for the inspected instance. Reserved column appears on
   every row only while tracing is active, so the rest of the tree
   stays unaffected when no instance is selected. The mark itself
   only paints on rows whose subtree actually contains the instance —
   absent rows render an empty placeholder of equal width so the
   alignment doesn't ripple. Sized + glow-haloed so the eye lands on
   it from across the tree, not just at hover distance. */
.rec-bubble {
  width: 0.9rem;
  height: 0.9rem;
  border-radius: 50%;
  flex-shrink: 0;
  align-self: center;
  margin: 0 0.35rem 0 0.15rem;
}
.rec-bubble.appears { background: var(--accent-blue); }
.rec-bubble.mutated { background: var(--accent-amber); }
.rec-bubble.empty   { background: transparent; }

.layer-stripe { width: 3px; height: 1.1rem; border-radius: 2px; flex-shrink: 0; }
.layer-stripe.layer-controller { background: #60a5fa; }
.layer-stripe.layer-service    { background: #34d399; }
.layer-stripe.layer-repository { background: #fbbf24; }
.layer-stripe.layer-mapper     { background: #c4b5fd; }
.layer-stripe.layer-other      { background: #6b7280; }

.rec-time { font-family: ui-monospace, monospace; color: var(--text-muted); width: 13ch; font-size: var(--mono-size); flex-shrink: 0; }
.rec-dur  { font-family: ui-monospace, monospace; color: var(--text-secondary); width: 8ch; text-align: right; font-size: var(--mono-size); flex-shrink: 0; }
.rec-sig  { flex: 1; font-family: ui-monospace, monospace; font-size: var(--mono-size); color: var(--text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* Child-count chip — informational, only renders when the row has
   nested calls. Compact so it doesn't compete with the signature. */
.rec-childcount {
  font-family: ui-monospace, monospace;
  font-size: 0.72rem;
  color: var(--text-muted);
  padding: 0.05rem 0.35rem;
  border-radius: 3px;
  background: var(--bg-elevated);
  flex-shrink: 0;
}

/* Return-type now rendered as a single glyph (⊘ void / ↵ value /
   ⚠ exception) — keeps the row compact and scannable. Tooltip
   carries the full word. */
.rec-ret {
  font-size: 0.95rem;
  line-height: 1;
  padding: 0.1rem 0.35rem;
  border-radius: 3px;
  background: var(--bg-elevated);
  color: var(--text-secondary);
  flex-shrink: 0;
  text-align: center;
  min-width: 1.6rem;
}
.rec-ret.value     { background: rgba(110, 231, 183, 0.15); color: #6ee7b7; }
.rec-ret.exception { background: rgba(248, 113, 113, 0.22); color: #fca5a5; }
.rec-ret.void      { background: var(--bg-elevated); color: var(--text-muted); }

.rec-body {
  padding: 0.25rem 0 0.25rem 1.4rem;
  border-left: 1px dashed var(--border-strong);
  margin-left: 1.4rem;
}
</style>
