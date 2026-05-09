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

import { computed, inject, ref, watch } from 'vue';
import CollapsiblePanel from './CollapsiblePanel.vue';
import PayloadViewer from './PayloadViewer.vue';
import { fmtTime, shortSig } from '../util/format';
import { PAYLOADS_BY_CALL_ID } from '../keys';
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
}>();

function onHandleDragStart(e: DragEvent): void {
  emit('handle-drag-start', e);
}

const payloadsByCallId = inject(PAYLOADS_BY_CALL_ID, computed(() => new Map<string, PayloadRow[]>()));
const payloads = computed<PayloadRow[]>(() => payloadsByCallId.value.get(props.call.call_id) || []);

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
</script>

<template>
  <CollapsiblePanel class="cic"
                    :class="{ collapsed, transient }"
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
      <div class="cic-meta">
        <span class="cic-time">{{ fmtTime(call.ts_in) }}</span>
        <span class="cic-dur">{{ call.duration_ms }} ms</span>
        <span v-if="call.return_type === 'EXCEPTION'"
              class="re-exception-chip"
              title="Call ended with a thrown exception (RE payload is the throwable)">⚠ exception</span>
        <span v-else
              class="cic-ret" :class="(call.return_type || 'VOID').toLowerCase()">{{ call.return_type }}</span>
        <button class="cic-close-btn"
                @click.stop="emit('close')"
                title="Close inspection card">✕</button>
      </div>
    </template>

    <div class="cic-body">
      <div v-if="!ordered.length" class="cic-empty">No payloads recorded for this call.</div>
      <CollapsiblePanel v-for="p in ordered" :key="p.kind"
                        class="cic-section"
                        :collapsed="isSectionCollapsed(p.kind)"
                        @update:collapsed="(v) => setSectionCollapsed(p.kind, v)">
        <template #header>
          <span class="kind" :class="p.kind">{{ p.kind }}</span>
          <span v-if="p.kind === 'RE' && call.return_type === 'EXCEPTION'"
                class="re-exception-chip"
                title="This RE payload is the thrown exception, not a return value">⚠ exception</span>
          <span class="cic-section-meta">{{ p.payload_size }} B</span>
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
.cic-ret {
  font-size: 0.68rem; padding: 0.05rem 0.4rem; border-radius: 3px;
  background: var(--bg-elevated); color: var(--text-secondary);
}
.cic-ret.value     { background: rgba(110, 231, 183, 0.15); color: #6ee7b7; }
.cic-ret.void      { background: var(--bg-elevated); color: var(--text-muted); }

/* CollapsiblePanel handles the header chevron and click affordance;
   we just style the per-section spacing + dividers + the body indent.
   Body padding pushes PayloadViewer slightly inward from the chevron
   column so the header stays the visual anchor. */
.cic-section { padding: 0.3rem 0.75rem 0.4rem; }
.cic-section + .cic-section { border-top: 1px dashed var(--border); }
.cic-section :deep(.cp-body) { padding: 0.2rem 0 0 1.4ch; }
.cic-section-meta { color: var(--text-muted); font-size: 0.75rem; }

/* Exception indicator next to the RE section header — matches the
   NavOverlay variant=exception treatment (light-red bg, dominant red
   border, rounded) so the in-card cue and the call-tree overlay read
   as the same signal. Static chip — not interactive. */
.re-exception-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  background: rgba(248, 113, 113, 0.16);
  border: 2px solid var(--accent-red);
  color: #fca5a5;
  font-size: 0.7rem;
  font-weight: 600;
  padding: 0.05rem 0.5rem;
  border-radius: 8px;
  line-height: 1.3;
}

.cic-empty { padding: 1rem 0.75rem; color: var(--text-muted); text-align: center; font-size: 0.85rem; }
</style>
