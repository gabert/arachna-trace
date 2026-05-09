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

import { computed, inject } from 'vue';
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
  (e: 'toggle-collapsed'): void;
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
</script>

<template>
  <article class="cic" :class="{ pinned, collapsed }">
    <header class="cic-head">
      <button v-if="pinned"
              class="cic-collapse-btn"
              @click="emit('toggle-collapsed')"
              :title="collapsed ? 'Expand' : 'Collapse'">
        {{ collapsed ? '▸' : '▾' }}
      </button>
      <div class="cic-sig" :title="call.signature">{{ shortSig(call.signature) }}</div>
      <div class="cic-meta">
        <span v-if="pinned" class="cic-pin-badge" title="This card is pinned and survives new selections.">PINNED</span>
        <span class="cic-time">{{ fmtTime(call.ts_in) }}</span>
        <span class="cic-dur">{{ call.duration_ms }} ms</span>
        <span class="cic-ret" :class="(call.return_type || 'VOID').toLowerCase()">{{ call.return_type }}</span>
        <button class="cic-pin-btn"
                @click="emit('pin-card')"
                :title="pinned ? 'Close pinned card' : 'Pin this card so it survives new selections'">
          {{ pinned ? '✕' : '📌' }}
        </button>
      </div>
    </header>

    <!-- Body wrapped in v-show so the PayloadViewer's per-tree expansion
         state survives a collapse/expand cycle on a pinned card. -->
    <div v-show="!collapsed" class="cic-body">
      <div v-if="!ordered.length" class="cic-empty">No payloads recorded for this call.</div>

      <section v-for="p in ordered" :key="p.kind" class="cic-section">
        <header class="cic-section-head">
          <span class="kind" :class="p.kind">{{ p.kind }}</span>
          <span class="cic-section-meta">{{ p.payload_size }} B</span>
        </header>
        <PayloadViewer :data="p.parsed"
                       :callId="call.call_id"
                       :kind="p.kind"
                       @pin="(payload) => emit('pin', payload)"
                       @origin="(t) => emit('origin', t)"
                       @trace="(t) => emit('trace', t)" />
      </section>
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
.cic.collapsed .cic-head { border-bottom: 0; }

.cic-head {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.5rem 0.75rem;
  background: var(--bg-elevated);
  border-bottom: 1px solid var(--border);
}
.cic-collapse-btn {
  background: none; border: 0; color: var(--text-secondary); cursor: pointer;
  font-size: 0.85rem; line-height: 1; padding: 0 0.2rem;
  flex-shrink: 0;
}
.cic-collapse-btn:hover { color: var(--text-primary); }
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

.cic-section { padding: 0.5rem 0.75rem; }
.cic-section + .cic-section { border-top: 1px dashed var(--border); }
.cic-section-head {
  display: flex; align-items: baseline; gap: 0.6rem; margin-bottom: 0.3rem;
}
.cic-section-meta { color: var(--text-muted); font-size: 0.75rem; }

.cic-empty { padding: 1rem 0.75rem; color: var(--text-muted); text-align: center; font-size: 0.85rem; }
</style>
