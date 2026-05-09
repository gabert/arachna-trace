<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue';
import FrameChildrenGroup from './FrameChildrenGroup.vue';
import { fmtTime, shortSig } from '../util/format';
import {
  CHILDREN_BY_PARENT,
  EXPANSION_DEFAULT, EXPANSION_OVERRIDES,
  SELECT_CALL, SELECTED_CALL_ID,
  INSPECTED_INSTANCE, INSTANCE_APPEARANCES_BY_CALL_ID,
  HIGHLIGHTED_CALL_ID, HIGHLIGHT_CALL_TICK,
  NAVIGATE_TO_APPEARANCE
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

// Bubble mark for the inspected instance — direct appearances only.
// Collapsed parents do NOT inherit a descendant's mark; the trace
// banner's ↑/↓ navigation is the right primitive for "find the
// next call that touches this instance".
const inspectedInstance = inject(INSPECTED_INSTANCE, ref<TraceTarget | null>(null));
const instanceAppearancesByCallId = inject(INSTANCE_APPEARANCES_BY_CALL_ID,
  computed(() => new Map<string, AppearanceKind>()));
const tracingActive = computed(() => inspectedInstance.value != null);
const bubbleKind = computed<AppearanceKind | null>(() =>
  instanceAppearancesByCallId.value.get(props.call.call_id) || null);
const bubbleTitle = computed(() => {
  if (!inspectedInstance.value) return '';
  const inst = inspectedInstance.value;
  const cls = String(inst.className).split('.').pop() || inst.className;
  if (bubbleKind.value === 'mutated') {
    return `Click to inspect — ${cls} #${inst.objectId} is mutated here (own_hash changes between AR and AX)`;
  }
  return `Click to inspect — ${cls} #${inst.objectId} appears here`;
});

// Random-access bubble click: jumps the workspace to this exact
// appearance (opens inspection card pointed at the instance's path,
// flashes + scrolls the row in the tree). Same end state as ↑/↓
// landing on this row.
const navigateToAppearance = inject(NAVIGATE_TO_APPEARANCE, (_id: string) => {});
function onBubbleClick(): void {
  if (!bubbleKind.value) return;
  navigateToAppearance(props.call.call_id);
}

// Programmatic highlight from CallTreePanel.highlightCall(). Distinct
// from `selected` (persistent, blue, tracks the open inspection card)
// — this is the search-cursor pointer set by the parent after trace
// nav etc. Two coverage paths for scrollIntoView, mirroring the
// JsonTree pattern: an isHighlighted watch (catches highlight/clear
// transitions) and a tick watch (catches re-highlights of the same
// call where isHighlighted didn't transition).
const highlightedCallId = inject(HIGHLIGHTED_CALL_ID, ref<string | null>(null));
const highlightTick = inject(HIGHLIGHT_CALL_TICK, ref(0));
const isHighlighted = computed(() => highlightedCallId.value === props.call.call_id);
const rowEl = ref<HTMLElement | null>(null);

function scrollSelfIfHighlighted(): void {
  if (!isHighlighted.value || !rowEl.value) return;
  requestAnimationFrame(() => {
    if (!isHighlighted.value || !rowEl.value) return;
    if (isFullyVisible(rowEl.value)) return;
    rowEl.value.scrollIntoView({ block: 'center' });
  });
}
function isFullyVisible(el: HTMLElement): boolean {
  const rect = el.getBoundingClientRect();
  let parent: HTMLElement | null = el.parentElement;
  while (parent) {
    const style = window.getComputedStyle(parent);
    if (style.overflowY === 'auto' || style.overflowY === 'scroll') break;
    parent = parent.parentElement;
  }
  const top = parent ? parent.getBoundingClientRect().top : 0;
  const bottom = parent ? parent.getBoundingClientRect().bottom : window.innerHeight;
  return rect.top >= top && rect.bottom <= bottom;
}
watch(isHighlighted, scrollSelfIfHighlighted, { flush: 'post' });
watch(highlightTick, scrollSelfIfHighlighted, { flush: 'post' });

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
      :class="['layer-' + layer, { open: expanded, exception: call.is_exception, leaf: !hasChildren, selected: isSelected, highlighted: isHighlighted }]">
    <div class="rec-head">
      <!-- Whole-row click toggles expansion: browsing the call tree is
           the primary interaction when first reading a trace. Showing
           values is opt-in via the explicit ↗ inspect button on the
           right edge of the row. The bubble is its own button, so
           it lives outside .rec-row-btn (nested <button> is invalid
           HTML); when no row matches the trace, an empty span
           reserves the same width to keep alignment stable. -->
      <button v-if="tracingActive && bubbleKind"
              class="rec-bubble"
              :class="bubbleKind"
              :title="bubbleTitle"
              @click.stop="onBubbleClick">→</button>
      <span v-else-if="tracingActive" class="rec-bubble empty" aria-hidden="true"></span>
      <button class="rec-row-btn"
              @click="toggle"
              :title="call.signature">
        <span class="rec-disclosure">{{ expanded ? '▾' : '▸' }}</span>
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
      <button class="rec-inspect-btn"
              @click.stop="select"
              title="Inspect TI / AR / AX / RE in the right pane">↗</button>
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
/* Programmatic highlight from CallTreePanel.highlightCall — amber
   outline matching JsonTree's flash and the trace banner palette,
   so trace ↑/↓ navigation reads as one feature. Outline lives on
   the head row only (NOT the whole .rec-row li, which contains the
   nested-children body when expanded — outlining that wraps the
   entire subtree). Persistent until the next highlightCall or trace
   clear; not a transient flash. */
.rec-row.highlighted > .rec-head {
  outline: 2px solid #fbbf24;
  outline-offset: -2px;
  border-radius: 3px;
}

/* Row head: one big click target that toggles expansion (the whole
   row, including the disclosure glyph) plus a small inspect button
   on the right edge that opens the call's TI / AR / AX / RE in the
   right pane. Wrapping div carries hover/selected styling so the
   whole row reads as a single visual unit. */
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
  padding: 0.3rem 0.5rem 0.3rem 0.5rem;
  min-width: 0;
}
.rec-row-btn:hover .rec-disclosure { color: var(--text-primary); }

/* Inspect arrow on the right edge — chip-styled affordance to "open
   in right pane". Same blue palette as JsonTree's ⊕ watch chip so
   the per-row interactive elements share a visual language. */
.rec-inspect-btn {
  background: rgba(96, 165, 250, 0.18);
  border: 1px solid rgba(96, 165, 250, 0.4);
  color: #93c5fd;
  font-size: 0.9rem;
  font-weight: 600;
  line-height: 1;
  padding: 0.1rem 0.5rem;
  border-radius: 3px;
  cursor: pointer;
  flex-shrink: 0;
  align-self: center;
  margin: 0 0.5rem 0 0.4rem;
}
.rec-inspect-btn:hover {
  background: rgba(96, 165, 250, 0.32);
  color: #bfdbfe;
}
.rec-row.selected .rec-inspect-btn {
  background: rgba(96, 165, 250, 0.32);
  color: #bfdbfe;
}

.rec-disclosure { width: 1ch; color: var(--text-muted); user-select: none; }

/* Bubble for the inspected instance — chip with → glyph that suggests
   "click to go there". Random-access alternative to ↑/↓ stepping;
   uses → to distinguish from the right-edge ↗ inspect chip ("open in
   right pane"). Reserved column appears on every row only while
   tracing is active so the rest of the tree stays unaffected when
   nothing's traced. Empty rows render a same-width span so alignment
   doesn't ripple. */
.rec-bubble {
  flex-shrink: 0;
  align-self: center;
  margin: 0 0.4rem 0 0.45rem;
  padding: 0.05rem 0.45rem;
  font-size: 0.85rem;
  font-weight: 700;
  line-height: 1;
  border-radius: 3px;
  border: 1px solid;
  cursor: pointer;
  font-family: inherit;
}
.rec-bubble.appears {
  background: rgba(96, 165, 250, 0.32);
  border-color: rgba(96, 165, 250, 0.65);
  color: #bfdbfe;
}
.rec-bubble.appears:hover {
  background: rgba(96, 165, 250, 0.55);
  color: #dbeafe;
}
.rec-bubble.mutated {
  background: rgba(251, 191, 36, 0.32);
  border-color: rgba(251, 191, 36, 0.65);
  color: #fde68a;
}
.rec-bubble.mutated:hover {
  background: rgba(251, 191, 36, 0.55);
  color: #fef3c7;
}
.rec-bubble.empty {
  /* Same-width invisible placeholder so non-appearance rows align. */
  visibility: hidden;
  cursor: default;
  background: transparent;
  border-color: transparent;
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
