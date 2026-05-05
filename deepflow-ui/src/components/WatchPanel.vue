<script setup>
import { computed, inject, ref } from 'vue';

// The same `highlight` ref the navigator updates. Provided by
// SessionDetailView. Shared with the left pane so a single click
// keeps both sides in sync — the JsonTree node lights up on the
// left, the originating watch row stays selected on the right.
const highlight = inject('highlight', ref(null));

const props = defineProps({
  watches: { type: Array, required: true },
  payloads: { type: Array, required: true },
  callOrder: { type: Map, default: () => new Map() }
});

const KIND_ORDER = { AR: 0, TI: 1, AX: 2, RE: 3 };

const emit = defineEmits(['remove', 'jump']);

function tryParse(s) {
  if (s == null) return null;
  try { return JSON.parse(s); } catch (_) { return null; }
}

function fmtTime(ts) {
  if (!ts) return '';
  return String(ts).replace(/^\d{4}-\d\d-\d\d /, '').slice(0, 12);
}

function shortSig(s) {
  if (!s) return '';
  return s.replace(/\(.*$/, '').split('.').slice(-2).join('.');
}

function shortHash(h) {
  if (!h) return '∅';
  return String(h).slice(0, 16);
}

function shortClass(c) {
  if (!c) return 'Object';
  return String(c).split('.').pop();
}

// 64-bit FNV-1a via BigInt — small, fast, good enough as a visual
// identifier for the own-state column. Renders 16 hex chars to match
// the deep-hash column's visual width. Not cryptographic; we just need
// a stable hex string from a JSON string.
const FNV64_OFFSET = 0xcbf29ce484222325n;
const FNV64_PRIME  = 0x100000001b3n;
const FNV64_MASK   = 0xFFFFFFFFFFFFFFFFn;
function fnv1a(str) {
  let h = FNV64_OFFSET;
  for (let i = 0; i < str.length; i++) {
    h = (h ^ BigInt(str.charCodeAt(i))) & FNV64_MASK;
    h = (h * FNV64_PRIME) & FNV64_MASK;
  }
  return h.toString(16).padStart(16, '0');
}

const parsedPayloads = computed(() =>
  props.payloads.map(p => ({ ...p, parsed: tryParse(p.payload_json) }))
);

function findEnvelopes(node, targetId, results, ctx, currentPath = []) {
  if (node == null || typeof node !== 'object') return;
  const meta = node.__meta__;
  if (meta && meta.id === targetId) {
    results.push({
      ...ctx,
      hash: meta.hash,
      snapshot: node,
      envelopePath: [...currentPath]
    });
  }
  if (Array.isArray(node)) {
    for (let i = 0; i < node.length; i++) {
      findEnvelopes(node[i], targetId, results, ctx, [...currentPath, i]);
    }
  } else {
    for (const k of Object.keys(node)) {
      if (k === '__meta__') continue;
      findEnvelopes(node[k], targetId, results, ctx, [...currentPath, k]);
    }
  }
}

// Own-state fingerprint: this envelope's *own* scalar fields plus the
// **ids** of its referenced children. Nested envelopes are collapsed
// to `ref:<id>`; cycle markers are also collapsed to `ref:<id>` (same
// shape). The result is invariant under cycle-entry direction and
// under mutations of sibling envelopes elsewhere in the subtree —
// it answers "did *this* object's own data change", not "did anything
// in this subtree change". Sister signal to the agent's deep Merkle
// hash; both are honest, neither is hidden.
function ownStateKey(envelope) {
  return JSON.stringify(collapse(envelope, /* atRoot */ true));
}

function collapse(v, atRoot) {
  if (v == null) return v;
  if (typeof v !== 'object') return v;
  if (Array.isArray(v)) return v.map(x => collapse(x, false));
  // Both shapes — full envelope and cycle-ref — collapse to the same
  // {__ref__: id}. Including class would diverge here because the wire
  // doesn't carry class on cycle markers, so adding it for full
  // envelopes would make own-state move on every cycle-direction flip.
  // Class is decoration; identity is the id.
  if (!atRoot && v.cycle_ref === true && v.ref_id != null) {
    return { __ref__: v.ref_id };
  }
  if (!atRoot && v.__meta__ && v.__meta__.id != null) {
    return { __ref__: v.__meta__.id };
  }
  const out = {};
  const keys = Object.keys(v).filter(k => k !== '__meta__').sort();
  for (const k of keys) out[k] = collapse(v[k], false);
  return out;
}

function appearancesFor(watch) {
  const out = [];
  for (const p of parsedPayloads.value) {
    findEnvelopes(p.parsed, watch.objectId, out, {
      call_id: p.call_id,
      kind: p.kind,
      ts_in: p.ts_in,
      signature: p.signature
    });
  }
  out.sort((a, b) => {
    const ai = props.callOrder.get(a.call_id);
    const bi = props.callOrder.get(b.call_id);
    const ao = ai == null ? Number.MAX_SAFE_INTEGER : ai;
    const bo = bi == null ? Number.MAX_SAFE_INTEGER : bi;
    if (ao !== bo) return ao - bo;
    return (KIND_ORDER[a.kind] ?? 99) - (KIND_ORDER[b.kind] ?? 99);
  });

  if (watch.kind === 'field') {
    for (const r of out) {
      const v = resolvePath(r.snapshot, watch.fieldPath);
      r.fieldValue = v;
      r.repr = formatFieldValue(v);
      r.compareKey = normalizeFieldKey(v);
    }
  } else {
    // Instance watch: deep hash and own-state both shown honestly.
    for (const r of out) {
      r.repr = shortHash(r.hash);                       // deep
      const owk = ownStateKey(r.snapshot);
      r.ownStateKey = owk;
      r.ownStateRepr = fnv1a(owk);                      // own-state
      r.compareKey = r.hash;                            // deep drives bands
    }
  }

  // Bands always come from the primary signal (deep hash for instance,
  // resolved value for field). Per-row markers tell the user which
  // signal moved: ▲ on deep-hash transition, △ on own-state transition.
  // Both markers visible when both moved; neither when stable. No
  // signal is hidden.
  let band = 0;
  let lastKey = null;
  let lastOwn = null;
  for (const r of out) {
    const moved = lastKey !== null && r.compareKey !== lastKey;
    if (moved) band ^= 1;
    r.band = band;
    r.changed = moved;
    if (watch.kind === 'instance') {
      r.ownChanged = lastOwn !== null && r.ownStateKey !== lastOwn;
      lastOwn = r.ownStateKey;
    } else {
      r.ownChanged = false;
    }
    lastKey = r.compareKey;
  }
  return out;
}

function resolvePath(envelope, path) {
  let v = envelope;
  for (const seg of path) {
    if (v == null || typeof v !== 'object') return undefined;
    v = v[seg];
  }
  return v;
}

function formatFieldValue(v) {
  if (v === undefined) return '∅';
  if (v === null) return 'null';
  if (typeof v === 'string') return JSON.stringify(v);
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  if (typeof v !== 'object') return String(v);
  if (Array.isArray(v)) return `[${v.length}]`;
  if (v.__meta__ && v.__meta__.id != null) return `${shortClass(v.__meta__.class)} #${v.__meta__.id}`;
  if (v.cycle_ref === true && v.ref_id != null) return `↺ #${v.ref_id}`;
  return `{${Object.keys(v).filter(k => k !== '__meta__').length}}`;
}

function normalizeFieldKey(v) {
  if (v === undefined) return '__undef__';
  if (v === null) return 'null';
  if (typeof v !== 'object') return JSON.stringify(v);
  if (v.cycle_ref === true && v.ref_id != null) return `ref:${v.ref_id}`;
  if (v.__meta__ && v.__meta__.id != null) return `ref:${v.__meta__.id}`;
  if (Array.isArray(v)) return JSON.stringify(v.map(normalizeFieldKey));
  const keys = Object.keys(v).filter(k => k !== '__meta__').sort();
  return JSON.stringify(keys.map(k => [k, normalizeFieldKey(v[k])]));
}

function changeCount(rows) {
  return rows.filter(r => r.changed).length;
}

function ownChangeCount(rows) {
  return rows.filter(r => r.ownChanged).length;
}

// Address each row would set if clicked. Used to mark the currently
// selected row (the one whose address matches the global `highlight`).
function rowAddress(w, r) {
  const path = w.kind === 'field'
    ? [...(r.envelopePath || []), ...(w.fieldPath || [])]
    : (r.envelopePath || []);
  return { callId: r.call_id, kind: r.kind, pathKey: JSON.stringify(path) };
}

function isCurrent(w, r) {
  const h = highlight.value;
  if (!h) return false;
  const a = rowAddress(w, r);
  return h.callId === a.callId && h.kind === a.kind && h.pathKey === a.pathKey;
}
</script>

<template>
  <aside class="watch-panel">
    <header class="wp-head">
      <h3>Watches</h3>
      <small v-if="payloads.length">{{ payloads.length }} payloads in request</small>
    </header>

    <p v-if="!watches.length" class="wp-empty">
      Hover any value in the recording on the left and click <code>⊕ watch</code>.
      Hovering an object pins the whole instance — tracked by both the agent's
      deep hash and a UI-computed own-state hash side by side. Hovering a field
      pins that field on the enclosing instance — tracked by value (literal for
      primitives, <code>#id</code> for object references).
    </p>

    <div v-for="(w, i) in watches" :key="i" class="watch"
         :class="['watch-' + w.kind]">
      <header class="watch-head">
        <div class="watch-title">
          <strong>{{ shortClass(w.className) }}</strong>
          <code>#{{ w.objectId }}</code>
          <code v-if="w.kind === 'field'" class="watch-field">.{{ (w.fieldPath || []).join('.') }}</code>
        </div>
        <button class="watch-rm" @click="emit('remove', i)" title="Remove watch">×</button>
      </header>

      <div class="watch-meta">
        {{ appearancesFor(w).length }} appearances
        <template v-if="w.kind === 'instance'">
          · <span class="changes">{{ changeCount(appearancesFor(w)) }} hash transitions</span>
          · <span class="own-changes">{{ ownChangeCount(appearancesFor(w)) }} own-state transitions</span>
        </template>
        <template v-else>
          · <span class="changes">{{ changeCount(appearancesFor(w)) }} value transitions</span>
        </template>
      </div>

      <table class="watch-results">
        <colgroup v-if="w.kind === 'instance'">
          <col class="c-time"><col class="c-kind"><col class="c-sig">
          <col class="c-hash"><col class="c-own"><col class="c-marker">
        </colgroup>
        <colgroup v-else>
          <col class="c-time"><col class="c-kind"><col class="c-sig">
          <col class="c-value"><col class="c-marker">
        </colgroup>
        <thead v-if="w.kind === 'instance'">
          <tr>
            <th></th><th></th><th></th>
            <th title="Agent's deep Merkle hash. Includes nested envelope content; moves on cycle direction or sibling subtree change.">deep</th>
            <th title="UI-computed fingerprint over this object's own scalar fields and child reference IDs. Moves only when this object's own data changes.">own</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(r, j) in appearancesFor(w)" :key="r.call_id + ':' + r.kind + ':' + j"
              :class="['band-' + r.band, { changed: r.changed, 'own-changed': r.ownChanged, current: isCurrent(w, r) }]"
              @click="emit('jump', {
                callId: r.call_id,
                kind: r.kind,
                objectId: w.objectId,
                path: w.kind === 'field'
                  ? [...(r.envelopePath || []), ...(w.fieldPath || [])]
                  : r.envelopePath
              })">
            <td class="r-time">{{ fmtTime(r.ts_in) }}</td>
            <td class="r-kind"><span :class="r.kind">{{ r.kind }}</span></td>
            <td class="r-sig">{{ shortSig(r.signature) }}</td>

            <template v-if="w.kind === 'instance'">
              <td class="r-hash" :title="r.hash">{{ r.repr }}</td>
              <td class="r-own"  :title="'own-state ' + r.ownStateRepr">{{ r.ownStateRepr }}</td>
            </template>
            <template v-else>
              <td class="r-hash">{{ r.repr }}</td>
            </template>

            <td class="r-marker">
              <span v-if="r.changed" class="m-deep" title="deep hash differs from previous appearance">▲</span>
              <span v-if="r.ownChanged" class="m-own" title="own-state differs from previous appearance">△</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </aside>
</template>

<style scoped>
.watch-panel {
  background: var(--bg-surface);
  border-left: 1px solid var(--border);
  padding: 0.75rem;
  overflow-y: auto;
  height: 100%;
  color: var(--text-primary);
}
.wp-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 0.5rem; }
.wp-head h3 { margin: 0; font-size: 0.95rem; color: var(--text-primary); }
.wp-head small { color: var(--text-muted); font-size: 0.75rem; }
.wp-empty {
  color: var(--text-secondary);
  font-size: 0.85rem;
  background: var(--bg-elevated);
  border: 1px dashed var(--border-strong);
  border-radius: 4px;
  padding: 0.75rem;
  line-height: 1.55;
}
.wp-empty code { background: rgba(96, 165, 250, 0.15); padding: 0 0.3rem; border-radius: 3px; color: #93c5fd; font-size: 0.78rem; }

.watch {
  background: var(--bg-elevated); border: 1px solid var(--border-strong); border-radius: 4px;
  padding: 0.5rem; margin-bottom: 0.75rem;
}
.watch-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.15rem; }
.watch-title { display: flex; gap: 0.4rem; align-items: baseline; }
.watch-title strong { font-size: 0.9rem; color: var(--text-primary); }
.watch-title code { font-family: ui-monospace, monospace; color: #c4b5fd; font-size: 0.8rem; }
.watch-title .watch-field { color: var(--text-secondary); font-size: 0.9rem; }
.watch-rm { border: 0; background: transparent; color: var(--text-muted); cursor: pointer; font-size: 1.05rem; line-height: 1; }
.watch-rm:hover { color: var(--accent-red); }
.watch-meta { color: var(--text-muted); font-size: 0.75rem; margin-bottom: 0.4rem; }
.watch-meta .changes { color: var(--accent-amber); }
.watch-meta .own-changes { color: #fbbf24; }

.watch-results {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  font-family: ui-monospace, monospace;
  font-size: var(--mono-size);
}

/* Column widths via colgroup. table-layout: fixed honours these strictly. */
.watch-results col.c-time   { width: 13ch; }
.watch-results col.c-kind   { width: 4ch;  }
.watch-results col.c-sig    { width: auto; }
.watch-results col.c-hash   { width: 17ch; }
.watch-results col.c-own    { width: 17ch; }
.watch-results col.c-value  { width: auto; }
.watch-results col.c-marker { width: 5ch; }

.watch-results thead th {
  font-size: var(--mono-size);
  font-weight: 500;
  color: var(--text-muted);
  text-align: left;
  padding: 0 0.4rem 0.25rem;
  cursor: help;
}

.watch-results tbody tr {
  cursor: pointer;
  border-top: 1px solid rgba(255, 255, 255, 0.04);
}
.watch-results tbody tr:first-child { border-top: 0; }
.watch-results tbody tr.band-0 { background: rgba(110, 231, 183, 0.10); }
.watch-results tbody tr.band-1 { background: rgba(251, 191, 36, 0.10); }
.watch-results tbody tr:hover  { outline: 1px solid var(--accent-blue); outline-offset: -1px; }
.watch-results tbody tr.changed                 { box-shadow: inset 3px 0 0 var(--accent-red); }
.watch-results tbody tr.own-changed             { box-shadow: inset 3px 0 0 #fbbf24; }
.watch-results tbody tr.changed.own-changed     {
  box-shadow: inset 3px 0 0 var(--accent-red), inset 6px 0 0 #fbbf24;
}

/* Currently selected row — same amber palette as the .flashed node
   on the left, so both sides visually agree on what's selected. */
.watch-results tbody tr.current {
  background: rgba(251, 191, 36, 0.22) !important;
  outline: 2px solid #fbbf24;
  outline-offset: -2px;
}

.watch-results tbody td {
  padding: 0.3rem 0.4rem;
  vertical-align: baseline;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.watch-results td.r-time   { color: var(--text-muted); }
.watch-results td.r-kind   { padding: 0.3rem 0.2rem; }
/* Kind pill — same shape as the tree's payload-head kind pills in
   FrameCard so the eye reads them as the same thing. */
.watch-results td.r-kind > span { font-size: 0.7rem; padding: 0.05rem 0.4rem; border-radius: 3px; font-weight: 600; background: rgba(196, 181, 253, 0.18); color: #c4b5fd; }
.watch-results td.r-kind > span.AR { background: rgba(96, 165, 250, 0.18);  color: #93c5fd; }
.watch-results td.r-kind > span.AX { background: rgba(251, 191, 36, 0.18);  color: #fcd34d; }
.watch-results td.r-kind > span.RE { background: rgba(110, 231, 183, 0.18); color: #6ee7b7; }
.watch-results td.r-kind > span.TI { background: rgba(196, 181, 253, 0.18); color: #c4b5fd; }
.watch-results td.r-sig    { color: var(--text-secondary); }
.watch-results td.r-hash   { color: var(--text-muted); }
.watch-results td.r-own    { color: var(--text-secondary); }
.watch-results td.r-marker {
  padding: 0.3rem 0.2rem;
  white-space: nowrap;
  overflow: visible;
}
.m-deep { color: var(--accent-red); font-weight: 700; margin-right: 0.35rem; display: inline-block; }
.m-own  { color: #fbbf24;            font-weight: 700; display: inline-block; }
</style>
