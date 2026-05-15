<script setup lang="ts">
// Right-pane card showing every recorded payload (TI / AR / AX / RE)
// for one call. All cards are equal — there is no current/pinned
// distinction; opening one never displaces another. Order is purely
// the order the user opened them, with manual drag-to-reorder via
// the ⋮⋮ handle in the header. Closed by the ✕ button.
//
// Show-everything-that-was-recorded principle: we don't filter AX out
// when it's identical to AR. The user opened this card asking "what
// was this call's data" — they get it. The PayloadViewer's own
// collapsed-by-default state keeps an unmutated AX from being noisy.
//
// Body is wrapped in CollapsiblePanel's v-show so the PayloadViewer's
// per-tree expansion state survives folding the card away.

import { computed, inject, onBeforeUnmount, ref, watch } from 'vue';
import CollapsiblePanel from './CollapsiblePanel.vue';
import KindLegend from './KindLegend.vue';
import PayloadViewer from './PayloadViewer.vue';
import ExceptionChip from './ExceptionChip.vue';
import ProgressSpinner from 'primevue/progressspinner';
import { fmtBytes, fmtTime, shortSig } from '../util/format';
import { PAYLOADS_BY_CALL_ID, SESSION_PAYLOADS, CALL_HIGHLIGHT } from '../keys';
import { useScrollIntoViewOnHighlight } from '../composables/useScrollIntoViewOnHighlight';
import type { CallRow, OriginTarget, PayloadKind, PayloadRow, TraceTarget, Watch } from '../types';

const props = withDefaults(defineProps<{
  call: CallRow;
  collapsed?: boolean;
  // Transient cards are the single browsing slot — replaced on the
  // next nav, until the user pins them. They render a 📌 button
  // instead of the ⋮⋮ drag handle and get a left-edge accent stripe
  // so the user can tell at a glance "this one will go away if I
  // step further".
  transient?: boolean;
}>(), {
  collapsed: false,
  transient: false
});

const emit = defineEmits<{
  (e: 'pin', payload: Watch): void;
  (e: 'origin', target: OriginTarget): void;
  (e: 'trace', target: TraceTarget): void;
  (e: 'close'): void;
  // User clicked 📌 on a transient card to promote it to the pinned
  // list. Distinct from `pin` (which carries a Watch payload from
  // PayloadViewer / JsonTree).
  (e: 'pin-card'): void;
  // Card collapse delegates to the parent, which holds the
  // collapsedCards Set so navigator jumps can auto-uncollapse a target.
  (e: 'set-collapsed', collapsed: boolean): void;
  // Drag-handle initiated dragstart. Parent owns the reorder state;
  // dragover/dragleave/drop/dragend are bound directly on the card
  // root and bubble naturally from the underlying DOM, no re-emit.
  // Only emitted for pinned cards (transient cards have no drag
  // handle), so the parent's pinned-relative idx stays accurate.
  (e: 'handle-drag-start', evt: DragEvent): void;
  // User clicked ↙ on the header asking "show this call in the
  // tree". Inverse of FrameCard's ↗. Parent runs the same
  // CallTreePanel.highlightCall primitive every other navigator
  // uses (BOX outline + EXPAND ancestors + SCROLL into view).
  (e: 'reveal'): void;
}>();

function onHandleDragStart(e: DragEvent): void {
  emit('handle-drag-start', e);
}

const payloadsByCallId = inject(PAYLOADS_BY_CALL_ID, computed(() => new Map<string, PayloadRow[]>()));
const payloads = computed<PayloadRow[]>(() => payloadsByCallId.value.get(props.call.call_id) || []);

// Lazy payload acquisition. The card holds one ref on its call's
// payloads while mounted; the cache evicts the entry when the last
// holder releases. acquire() returns immediately if the call's data
// is already cached; otherwise it kicks off a fetch and the loading
// state is reflected via SESSION_PAYLOADS.loadingCallIds.
const sessionPayloads = inject(SESSION_PAYLOADS, undefined);
const isLoading = computed(() =>
  sessionPayloads?.loadingCallIds.value.has(props.call.call_id) ?? false);

let acquired: string | null = null;
function acquireFor(callId: string): void {
  if (acquired === callId) return;
  if (acquired) sessionPayloads?.release(acquired);
  acquired = callId;
  sessionPayloads?.acquire(callId).catch(() => { /* surfaced via empty payloads list */ });
}
// Acquire on mount and on call_id change. Release on unmount.
watch(() => props.call.call_id, (id) => acquireFor(id), { immediate: true });
onBeforeUnmount(() => {
  if (acquired) {
    sessionPayloads?.release(acquired);
    acquired = null;
  }
});

// Render in the order the values flow through the call: receiver
// instance, arguments at entry, arguments at exit, return.
const ORDER: PayloadKind[] = ['TI', 'AR', 'AX', 'RE'];

const ordered = computed<PayloadRow[]>(() => {
  const m = new Map<PayloadKind, PayloadRow>();
  for (const p of payloads.value) m.set(p.kind, p);
  const out: PayloadRow[] = [];
  for (const k of ORDER) {
    const p = m.get(k);
    if (p) out.push(p);
  }
  return out;
});

// Per-section collapse, scoped to this card's call. Pinned cards each
// have a unique :key, so their section state persists; the current-
// card slot reuses one CallInspectionCard instance, so we reset on
// call change to avoid carrying a previous call's TI-collapsed state
// into a freshly-selected one. Controlled via CollapsiblePanel's
// v-model:collapsed so PayloadViewer's per-tree state survives folds.
const collapsedSections = ref<Set<PayloadKind>>(new Set());
function isSectionCollapsed(k: PayloadKind): boolean {
  return collapsedSections.value.has(k);
}
function setSectionCollapsed(k: PayloadKind, v: boolean): void {
  const next = new Set(collapsedSections.value);
  if (v) next.add(k); else next.delete(k);
  collapsedSections.value = next;
}
watch(() => props.call.call_id, () => {
  collapsedSections.value = new Set();
});

// Mirror the call-tree's yellow focus outline on the matching
// inspection card so the two read as one linked pair. When the user's
// focused row in the tree is this card's call, the card gets the same
// outline — closes the visual loop "this row is what that card is
// showing". Works for both pinned and transient cards.
const callHighlight = inject(CALL_HIGHLIGHT, {
  callId: ref<string | null>(null),
  tick: ref(0)
});
const isHighlighted = computed(() => callHighlight.callId.value === props.call.call_id);

// Pane-side half of the highlight API contract: when this card
// becomes the focused one, scroll its container so the card lands in
// view. Same composable the call tree uses for FrameCard rows, so the
// behaviour is identical on both sides — highlightCall(id) up in
// CallTreePanel results in BOTH the matching row scrolling into view
// on the left AND the matching card scrolling into view on the right.
// runOnMount covers the transient-card case: a freshly-shown card may
// mount with isHighlighted already true (selectCall sets the highlight
// before the card finishes mounting), so the watch on isMatch wouldn't
// fire on its own.
const rootEl = ref<HTMLElement | null>(null);
function setRoot(inst: unknown): void {
  // Function ref on a component instance: Vue passes the component
  // proxy, whose $el is the root DOM element. We unwrap once here so
  // the composable just sees a plain HTMLElement ref.
  rootEl.value = (inst as { $el?: HTMLElement } | null)?.$el ?? null;
}
useScrollIntoViewOnHighlight(rootEl, isHighlighted, callHighlight.tick, { runOnMount: true });
</script>

<template>
  <CollapsiblePanel :ref="setRoot"
                    class="cic"
                    :class="{ collapsed, transient, highlighted: isHighlighted }"
                    :collapsed="collapsed"
                    @update:collapsed="(v) => emit('set-collapsed', v)">
    <template #header>
      <button v-if="transient"
              class="cic-pin-btn"
              @click.stop="emit('pin-card')"
              title="Pin to keep this card open (otherwise it gets replaced on the next trace step or inspect click)">📌</button>
      <span v-else
            class="cic-drag-handle"
            draggable="true"
            @dragstart="onHandleDragStart"
            @click.stop
            title="Drag to reorder">⋮⋮</span>
      <div class="cic-sig" :title="call.signature">{{ shortSig(call.signature) }}</div>
      <KindLegend />
      <div class="cic-meta">
        <span class="cic-time">{{ fmtTime(call.ts_in) }}</span>
        <span class="cic-dur">{{ call.duration_ms }} ms</span>
        <ExceptionChip v-if="call.return_type === 'EXCEPTION'" />
        <button class="cic-reveal-btn"
                @click.stop="emit('reveal')"
                title="Show this call in the tree">↙</button>
        <button class="cic-close-btn"
                @click.stop="emit('close')"
                title="Close inspection card">✕</button>
      </div>
    </template>

    <div class="cic-body">
      <div v-if="isLoading && !ordered.length" class="cic-loading">
        <ProgressSpinner style="width:1.25rem;height:1.25rem" />
        <span>Loading payloads…</span>
      </div>
      <div v-else-if="!ordered.length" class="cic-empty">No payloads recorded for this call.</div>
      <CollapsiblePanel v-for="p in ordered" :key="p.kind"
                        class="cic-section"
                        :collapsed="isSectionCollapsed(p.kind)"
                        @update:collapsed="(v) => setSectionCollapsed(p.kind, v)">
        <template #header>
          <span class="kind" :class="p.kind">{{ p.kind }}</span>
          <ExceptionChip v-if="p.kind === 'RE' && call.return_type === 'EXCEPTION'" />
          <span v-if="p.payload_size != null"
                class="cic-section-meta"
                :title="`${p.payload_size.toLocaleString()} B`">{{ fmtBytes(p.payload_size) }}</span>
        </template>
        <PayloadViewer :data="p.parsed"
                       :callId="call.call_id"
                       :kind="p.kind"
                       @pin="(payload) => emit('pin', payload)"
                       @origin="(t) => emit('origin', t)"
                       @trace="(t) => emit('trace', t)" />
      </CollapsiblePanel>
    </div>
  </CollapsiblePanel>
</template>

<style scoped>
.cic {
  background: var(--bg-surface);
  border: 1px solid var(--border-strong);
  border-radius: 6px;
  margin-bottom: 0.75rem;
  overflow: hidden;
  position: relative;
}

/* Mirror of FrameCard's .rec-row.highlighted outline. Painted when
   the focused call (CALL_HIGHLIGHT) is this card's call, so the row
   on the left and the card on the right wear the same yellow box —
   visually linking the two. Same colour and stroke as the row
   outline; offset is positive here because the card has a real
   border and ambient spacing, so the outline reads cleanly sitting
   just outside the border. */
.cic.highlighted {
  outline: 2px solid #fbbf24;
  outline-offset: 1px;
}

/* Card root uses CollapsiblePanel; style its head as the card header
   (background, padding, border) and let the slotted header content
   handle layout via cic-meta below. */
.cic :deep(.cp-head) {
  padding: 0.5rem 0.75rem;
  background: var(--bg-elevated);
  border-bottom: 1px solid var(--border);
  border-radius: 0;
  align-items: center;
}
.cic.collapsed :deep(.cp-head) { border-bottom: 0; }
.cic :deep(.cp-head:hover) { background: var(--bg-hover); }

/* Drag handle — small grip glyph, cursor:grab so it reads as a
   drag affordance distinct from the rest of the clickable header. */
.cic-drag-handle {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 0.85rem;
  line-height: 1;
  padding: 0 0.3rem;
  cursor: grab;
  user-select: none;
}
.cic-drag-handle:hover { color: var(--text-primary); }
.cic-drag-handle:active { cursor: grabbing; }

/* Pin button — replaces the drag handle on transient cards. Same
   slot, same width, so the header layout doesn't shift when the card
   gets pinned. The 📌 emoji renders slightly off-baseline; nudged
   with line-height for a flush header row. */
.cic-pin-btn {
  flex-shrink: 0;
  background: transparent;
  border: 0;
  color: var(--accent-blue);
  font-size: 0.85rem;
  line-height: 1;
  padding: 0.05rem 0.3rem;
  cursor: pointer;
  border-radius: 3px;
}
.cic-pin-btn:hover { background: var(--bg-hover); }

/* Transient card — left-edge accent stripe so the user can tell it
   apart from pinned cards at a glance. Same accent-blue used on the
   pin button, reinforcing "click here to keep me". */
.cic.transient {
  border-left: 3px solid var(--accent-blue);
}

.cic-close-btn {
  background: none; border: 0; color: var(--text-muted); cursor: pointer;
  font-size: 0.85rem; line-height: 1; padding: 0.1rem 0.3rem; border-radius: 3px;
  flex-shrink: 0;
}
.cic-close-btn:hover { background: var(--bg-hover); color: var(--accent-red); }

/* Inverse of FrameCard's ↗ (open-in-card): ↙ sends the user back
   from the card to the call's row in the tree, running the same
   highlightCall primitive (yellow outline + expand ancestors +
   scroll). Same chip-blue palette as the FrameCard ↗ so the pair
   reads as one round-trip. */
.cic-reveal-btn {
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
}
.cic-reveal-btn:hover {
  background: rgba(96, 165, 250, 0.32);
  color: #bfdbfe;
}

/* Drop indicators — accent-blue line above or below the card during
   dragover, signals where the dragged card will land if released
   here. The .dragging source dims slightly so the user can see
   what's moving. */
.cic.drop-before::before,
.cic.drop-after::after {
  content: '';
  position: absolute;
  left: 0; right: 0;
  height: 2px;
  background: var(--accent-blue);
  pointer-events: none;
}
.cic.drop-before::before { top: -1px; }
.cic.drop-after::after { bottom: -1px; }
.cic.dragging { opacity: 0.55; }
.cic-sig {
  font-family: ui-monospace, monospace;
  font-size: var(--mono-size);
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}
.cic-meta { display: flex; align-items: center; gap: 0.6rem; flex-shrink: 0; }

.cic-time { font-family: ui-monospace, monospace; color: var(--text-muted); font-size: 0.8rem; }
.cic-dur  { font-family: ui-monospace, monospace; color: var(--text-secondary); font-size: 0.8rem; }
/* The return-type pill (VALUE / VOID) was removed — silence on the
   normal-return paths, only ExceptionChip when something interesting
   happened. */

/* CollapsiblePanel handles the header chevron and click affordance;
   we just style the per-section spacing + dividers + the body indent.
   Body padding pushes PayloadViewer slightly inward from the chevron
   column so the header stays the visual anchor. */
.cic-section { padding: 0.3rem 0.75rem 0.4rem; }
.cic-section + .cic-section { border-top: 1px dashed var(--border); }
.cic-section :deep(.cp-body) { padding: 0.2rem 0 0 1.4ch; }
.cic-section-meta { color: var(--text-muted); font-size: 0.75rem; }

/* The "exception" red chip used in two spots in this card (header
   when return_type is EXCEPTION, RE section header to flag that the
   payload is the throwable) is now a shared <ExceptionChip>. Same
   visual lives in RequestNode and is the inspiration for the
   NavOverlay variant=exception. One source, one look. */

.cic-empty { padding: 1rem 0.75rem; color: var(--text-muted); text-align: center; font-size: 0.85rem; }
.cic-loading {
  padding: 1rem 0.75rem;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.6rem;
  color: var(--text-muted);
  font-size: 0.85rem;
}
</style>
