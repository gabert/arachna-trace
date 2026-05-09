<script setup lang="ts">
// Right-pane card showing every recorded payload (TI / AR / AX / RE)
// for one call. Renders independently of the call tree on the left;
// the user picks which call to inspect by clicking it in the tree, and
// SessionDetailView feeds the matching CallRow in.
//
// Show-everything-that-was-recorded principle: we don't filter AX out
// when it's identical to AR. The user clicked into this card asking
// "what was this call's data" — they get it. The PayloadViewer's own
// collapsed-by-default state keeps an unmutated AX from being noisy.
//
// Cards live in two slots in the inspection area: one "current" (the
// active selection — replaced when the user clicks a different call)
// and any number of "pinned" (parked, survive new selections). Pinned
// cards can be collapsed; the body stays mounted via v-show so the
// PayloadViewer's per-tree expansion state survives folding.

import { computed, inject, ref, watch } from 'vue';
import CollapsiblePanel from './CollapsiblePanel.vue';
import PayloadViewer from './PayloadViewer.vue';
import { fmtTime, shortSig } from '../util/format';
import { PAYLOADS_BY_CALL_ID } from '../keys';
import type { CallRow, OriginTarget, PayloadKind, PayloadRow, TraceTarget, Watch } from '../types';

const props = withDefaults(defineProps<{
  call: CallRow;
  pinned?: boolean;
  collapsed?: boolean;
}>(), {
  pinned: false,
  collapsed: false
});

const emit = defineEmits<{
  (e: 'pin', payload: Watch): void;
  (e: 'origin', target: OriginTarget): void;
  (e: 'trace', target: TraceTarget): void;
  (e: 'pin-card'): void;
  // Pinned-card collapse delegates to the parent, which holds the
  // collapsedCards Set so navigator jumps can auto-uncollapse a target.
  // Carries the new collapsed state so the parent doesn't have to
  // re-toggle by id.
  (e: 'set-collapsed', collapsed: boolean): void;
}>();

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
  <!-- Pinned cards use CollapsiblePanel so the chevron + click-to-fold
       affordance matches every other collapsible in the app. Current
       (un-pinned) cards aren't foldable — they're the active selection,
       not parked workspace — so they render as a plain article. The
       body content is identical between branches. -->
  <CollapsiblePanel v-if="pinned"
                    class="cic pinned"
                    :class="{ collapsed }"
                    :collapsed="collapsed"
                    @update:collapsed="(v) => emit('set-collapsed', v)">
    <template #header>
      <div class="cic-sig" :title="call.signature">{{ shortSig(call.signature) }}</div>
      <div class="cic-meta">
        <span class="cic-pin-badge" title="This card is pinned and survives new selections.">PINNED</span>
        <span class="cic-time">{{ fmtTime(call.ts_in) }}</span>
        <span class="cic-dur">{{ call.duration_ms }} ms</span>
        <span class="cic-ret" :class="(call.return_type || 'VOID').toLowerCase()">{{ call.return_type }}</span>
        <button class="cic-pin-btn"
                @click.stop="emit('pin-card')"
                title="Close pinned card">✕</button>
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

  <article v-else class="cic">
    <header class="cic-head">
      <div class="cic-sig" :title="call.signature">{{ shortSig(call.signature) }}</div>
      <div class="cic-meta">
        <span class="cic-time">{{ fmtTime(call.ts_in) }}</span>
        <span class="cic-dur">{{ call.duration_ms }} ms</span>
        <span class="cic-ret" :class="(call.return_type || 'VOID').toLowerCase()">{{ call.return_type }}</span>
        <button class="cic-pin-btn"
                @click="emit('pin-card')"
                title="Pin this card so it survives new selections">📌</button>
      </div>
    </header>

    <div class="cic-body">
      <div v-if="!ordered.length" class="cic-empty">No payloads recorded for this call.</div>
      <CollapsiblePanel v-for="p in ordered" :key="p.kind"
                        class="cic-section"
                        :collapsed="isSectionCollapsed(p.kind)"
                        @update:collapsed="(v) => setSectionCollapsed(p.kind, v)">
        <template #header>
          <span class="kind" :class="p.kind">{{ p.kind }}</span>
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
  </article>
</template>

<style scoped>
.cic {
  background: var(--bg-surface);
  border: 1px solid var(--border-strong);
  border-radius: 6px;
  margin-bottom: 0.75rem;
  overflow: hidden;
}
/* Pinned cards get an amber left edge so the eye can pick them out
   from the current selection at a glance — they're the parked
   workspace, not what was just clicked. */
.cic.pinned { border-left: 3px solid var(--accent-amber); }

/* Pinned card uses CollapsiblePanel as its root; style its head as
   the card header (background, padding, border) and let the slotted
   header content handle layout via cic-meta below. */
.cic.pinned :deep(.cp-head) {
  padding: 0.5rem 0.75rem;
  background: var(--bg-elevated);
  border-bottom: 1px solid var(--border);
  border-radius: 0;
  align-items: center;
}
.cic.pinned.collapsed :deep(.cp-head) { border-bottom: 0; }
.cic.pinned :deep(.cp-head:hover) { background: var(--bg-hover); }

.cic-head {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.5rem 0.75rem;
  background: var(--bg-elevated);
  border-bottom: 1px solid var(--border);
}
.cic-pin-badge {
  background: rgba(251, 191, 36, 0.18); color: #fcd34d;
  font-size: 0.6rem; font-weight: 700; letter-spacing: 0.05em;
  padding: 0.05rem 0.35rem; border-radius: 3px;
}
.cic-pin-btn {
  background: none; border: 0; color: var(--text-muted); cursor: pointer;
  font-size: 0.85rem; line-height: 1; padding: 0.1rem 0.3rem; border-radius: 3px;
  flex-shrink: 0;
}
.cic-pin-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
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
.cic-ret.exception { background: rgba(248, 113, 113, 0.18); color: #fca5a5; }
.cic-ret.void      { background: var(--bg-elevated); color: var(--text-muted); }

/* CollapsiblePanel handles the header chevron and click affordance;
   we just style the per-section spacing + dividers + the body indent.
   Body padding pushes PayloadViewer slightly inward from the chevron
   column so the header stays the visual anchor. */
.cic-section { padding: 0.3rem 0.75rem 0.4rem; }
.cic-section + .cic-section { border-top: 1px dashed var(--border); }
.cic-section :deep(.cp-body) { padding: 0.2rem 0 0 1.4ch; }
.cic-section-meta { color: var(--text-muted); font-size: 0.75rem; }

.cic-empty { padding: 1rem 0.75rem; color: var(--text-muted); text-align: center; font-size: 0.85rem; }
</style>
