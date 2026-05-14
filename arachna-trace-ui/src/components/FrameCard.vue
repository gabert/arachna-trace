<script setup lang="ts">
import { computed, inject, ref } from 'vue';
import FrameChildrenGroup from './FrameChildrenGroup.vue';
import { fmtTime, shortSig } from '../util/format';
import {
  CHILDREN_BY_PARENT,
  EXPANSION_DEFAULT, EXPANSION_OVERRIDES,
  CALL_SELECTION, INSTANCE_TRACE, EXCEPTION_NAV, CALL_HIGHLIGHT
} from '../keys';
import { useScrollIntoViewOnHighlight } from '../composables/useScrollIntoViewOnHighlight';
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

// Click splits two ways, file-tree-style: the chevron button on the
// left toggles expand/collapse, the row body opens the call in the
// right pane via CALL_SELECTION. The two actions never overlap —
// clicking anywhere on the row inspects; only the chevron's square
// hit area toggles structure. `select` is more than a setter
// (see CALL_SELECTION docs). The provider also paints the unified
// yellow focus outline on the row, so we don't track an extra
// "selected" class here.
const callSelection = inject(CALL_SELECTION, {
  selectedId: ref<string | null>(null),
  select: (_id: string) => {}
});
function select(): void { callSelection.select(props.call.call_id); }

// Trace / exception markers — non-interactive. The whole row is
// already clickable (select + reveal + cursor advance happens at the
// row level), so the column-1 / column-2 marks here are pure visual
// signals: "this row is an exception" / "the traced instance appears
// or mutates here". No chip background, no click handler — a colored
// glyph in a reserved column so the eye can scan a long tree for
// these states without losing alignment between rows that have them
// and rows that don't.
const trace = inject(INSTANCE_TRACE, {
  instance: ref<TraceTarget | null>(null),
  appearances: computed(() => new Map<string, AppearanceKind>()),
  navigateTo: (_id: string) => {}
});
const tracingActive = computed(() => trace.instance.value != null);
const bubbleKind = computed<AppearanceKind | null>(() =>
  trace.appearances.value.get(props.call.call_id) || null);
const bubbleTitle = computed(() => {
  if (!trace.instance.value) return '';
  const inst = trace.instance.value;
  const cls = String(inst.className).split('.').pop() || inst.className;
  if (bubbleKind.value === 'mutated') {
    return `${cls} #${inst.objectId} is mutated here (own_hash changes between AR and AX)`;
  }
  return `${cls} #${inst.objectId} appears here`;
});

const exceptionNav = inject(EXCEPTION_NAV, {
  active: computed(() => false),
  navigateTo: (_id: string) => {}
});

// Programmatic highlight from CallTreePanel.highlightCall(). Distinct
// from `selected` (persistent, blue, tracks the open inspection card)
// — this is the search-cursor pointer set by the parent after trace
// nav etc. The composable handles both transition (isHighlighted) and
// re-trigger (tick) coverage paths.
const callHighlight = inject(CALL_HIGHLIGHT, {
  callId: ref<string | null>(null),
  tick: ref(0)
});
const isHighlighted = computed(() => callHighlight.callId.value === props.call.call_id);
const rowEl = ref<HTMLElement | null>(null);
// runOnMount: highlightCall() in CallTreePanel auto-expands ancestors,
// which can cause this FrameCard to mount with isHighlighted already
// true. The post-flush watch wouldn't fire in that case; the onMount
// hook handles it.
useScrollIntoViewOnHighlight(rowEl, isHighlighted, callHighlight.tick, { runOnMount: true });

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

</script>

<template>
  <li ref="rowEl" class="rec-row"
      :class="['layer-' + layer, { open: expanded, exception: call.is_exception, leaf: !hasChildren, highlighted: isHighlighted }]">
    <div class="rec-head">
      <!-- File-tree interaction model: the chevron button on the left
           toggles expand/collapse; clicking anywhere else on the row
           opens the call in the right pane. The chevron is its own
           button (nested <button> is invalid HTML) with a square
           row-height hit area so users don't hunt for the icon. Leaf
           rows render an empty placeholder of identical width to keep
           columns aligned across the tree. -->
      <!-- Marker columns — visual signals, not buttons. Each
           (exception, trace) reserves its own column whenever EITHER
           nav is active, so the trace marker always sits in column 1
           regardless of whether exception nav happens to be active.
           Without this the trace dot shifts left when no exception
           markers are around. Empty placeholder spans keep alignment
           on rows that don't carry the marker. -->
      <span v-if="exceptionNav.active.value && call.is_exception"
            class="rec-marker exception"
            title="This call threw an exception"
            aria-hidden="true">●</span>
      <span v-else-if="exceptionNav.active.value || tracingActive"
            class="rec-marker empty" aria-hidden="true">●</span>

      <span v-if="tracingActive && bubbleKind"
            class="rec-marker"
            :class="bubbleKind"
            :title="bubbleTitle"
            aria-hidden="true">●</span>
      <span v-else-if="exceptionNav.active.value || tracingActive"
            class="rec-marker empty" aria-hidden="true">●</span>
      <button v-if="hasChildren"
              class="rec-disclosure-btn"
              @click.stop="toggle"
              :title="expanded ? 'Collapse' : 'Expand'"
              :aria-expanded="expanded">{{ expanded ? '▾' : '▸' }}</button>
      <span v-else class="rec-disclosure-btn empty" aria-hidden="true"></span>
      <button class="rec-row-btn"
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
/* Exception rows: red background tint bounded to the row head, so a
   chain of nested exception rows doesn't paint the parent's
   indentation gutter and merge into one continuous red column.
   `.open` stays on the <li> on purpose: it tints the expanded body
   so the children read as a grouped block. */
.rec-row.exception > .rec-head {
  background: rgba(248, 113, 113, 0.10);
}
.rec-row.open      { background: var(--bg-base); }
/* Unified focus outline. Painted by CallTreePanel.highlightCall, which
   every focus path (manual row click, trace ↑/↓, exception ↑/↓,
   "reveal in tree", watch goto) routes through. One yellow box per
   tree at a time — replaces the older blue "selected" tint so there's
   a single visual answer to "which row am I looking at?". Outline lives
   on the head only (NOT the whole .rec-row li, which contains the
   nested-children body when expanded — outlining that wraps the
   entire subtree). */
.rec-row.highlighted > .rec-head {
  outline: 2px solid #fbbf24;
  outline-offset: -2px;
  border-radius: 3px;
}

/* Row head: chevron button on the left toggles expand/collapse; the
   rest of the row body is a single click target that opens the call
   in the right pane via CALL_SELECTION. Wrapping div carries
   hover/selected styling so the whole row reads as a single visual
   unit. */
.rec-head {
  display: flex; align-items: stretch;
  color: var(--text-primary);
  position: relative;
}
.rec-head:hover { background: var(--bg-hover); }

.rec-row-btn {
  flex: 1;
  display: flex; align-items: center; gap: 0.5rem;
  background: none; border: 0; color: inherit; cursor: pointer;
  font: inherit; text-align: left;
  padding: 0.3rem 0.5rem 0.3rem 0.25rem;
  min-width: 0;
}

/* Disclosure button — square hit area, row-height, with the chevron
   centered. Big enough that the user doesn't aim for the glyph: any
   click in the square toggles. Leaf rows render `.rec-disclosure-btn.empty`
   at the same width so the time/sig columns line up regardless of
   whether the row has children. */
.rec-disclosure-btn {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.8rem;
  height: 1.8rem;
  margin: 0 0.1rem 0 0.25rem;
  background: none;
  border: 0;
  border-radius: 3px;
  color: var(--text-muted);
  cursor: pointer;
  font: inherit;
  font-size: 0.85rem;
  line-height: 1;
  user-select: none;
}
.rec-disclosure-btn:hover {
  background: var(--bg-elevated);
  color: var(--text-primary);
}
.rec-disclosure-btn.empty {
  cursor: default;
  pointer-events: none;
}
.rec-disclosure-btn.empty:hover { background: none; }

/* Non-interactive marker for trace appearance / mutation / exception
   state. Plain colored glyph in a reserved column — no chip, no
   border, no hover, no cursor:pointer. The whole row is the click
   target; these are just visual signals. Reserved column appears on
   every row only while the corresponding nav is active so the rest
   of the tree stays unaffected when nothing's traced / no exceptions.
   Empty rows render a same-width span so alignment doesn't ripple. */
.rec-marker {
  flex-shrink: 0;
  align-self: center;
  margin: 0 0.35rem;
  width: 1rem;
  text-align: center;
  font-size: 1rem;
  line-height: 1;
  font-family: inherit;
  user-select: none;
}
.rec-marker.appears   { color: #60a5fa; }
.rec-marker.mutated   { color: #fbbf24; }
.rec-marker.exception { color: #f87171; }
.rec-marker.empty {
  /* Invisible placeholder — preserves the column's layout box so
     marker-bearing rows and plain rows align. */
  visibility: hidden;
}

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

/* Return-type chip removed; exception rows are already signaled by
   the red row treatment, void/value distinction wasn't carrying its
   weight. */

.rec-body {
  padding: 0.25rem 0 0.25rem 1.4rem;
  border-left: 1px dashed var(--border-strong);
  margin-left: 1.4rem;
}
</style>
