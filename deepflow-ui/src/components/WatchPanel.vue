<script setup>
import { computed } from 'vue';

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
  return String(h).slice(0, 8);
}

function shortClass(c) {
  if (!c) return 'Object';
  return String(c).split('.').pop();
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
      hasCycleRef: containsCycleRef(node),
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

// True if the snapshot contains any back-reference cycle marker
// anywhere inside its rendered tree. These appearances are excluded
// from diff comparison because the marker's position is direction-
// dependent (see docs/temporal/KNOWN_BUGS.md → D-09).
function containsCycleRef(node) {
  if (node == null || typeof node !== 'object') return false;
  if (!Array.isArray(node) && node.cycle_ref === true && node.ref_id != null) return true;
  if (Array.isArray(node)) {
    for (const v of node) if (containsCycleRef(v)) return true;
    return false;
  }
  for (const k of Object.keys(node)) {
    if (k === '__meta__') continue;
    if (containsCycleRef(node[k])) return true;
  }
  return false;
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
    for (const r of out) {
      r.repr = shortHash(r.hash);
      r.compareKey = r.hash;
    }
  }

  // Bands and "changed" markers.
  // Instance watches: cycle-bearing snapshots are skipped (hash is
  //   direction-dependent on cyclic graphs). They render greyed.
  // Field watches: never skipped — cycle refs and full envelopes
  //   both reduce to `ref:<id>` in compareKey, so cycle direction
  //   doesn't produce false transitions.
  let band = 0;
  let lastKey = null;
  for (const r of out) {
    const skip = watch.kind === 'instance' && r.hasCycleRef;
    if (skip) {
      r.band = band;
      r.changed = false;
      r.skipped = true;
      continue;
    }
    if (lastKey !== null && r.compareKey !== lastKey) band ^= 1;
    r.band = band;
    r.changed = lastKey !== null && r.compareKey !== lastKey;
    r.skipped = false;
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

</script>

<template>
  <aside class="watch-panel">
    <header class="wp-head">
      <h3>Watches</h3>
      <small v-if="payloads.length">{{ payloads.length }} payloads in request</small>
    </header>

    <p v-if="!watches.length" class="wp-empty">
      Hover any value in the recording on the left and click <code>⊕ watch</code>.
      Hovering an object pins the whole instance (tracked by hash). Hovering
      a field pins that field on the enclosing instance (tracked by value —
      the literal for primitives, or <code>#id</code> for object references).
    </p>

    <div v-for="(w, i) in watches" :key="i" class="watch">
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
        · <span class="changes">{{ changeCount(appearancesFor(w)) }} {{ w.kind === 'field' ? 'value' : 'hash' }} transitions</span>
      </div>

      <ol class="watch-results">
        <li v-for="(r, j) in appearancesFor(w)" :key="r.call_id + ':' + r.kind + ':' + j"
            :class="[r.skipped ? 'skipped' : ('band-' + r.band), { changed: r.changed }]"
            @click="emit('jump', {
              callId: r.call_id,
              kind: r.kind,
              objectId: w.objectId,
              path: w.kind === 'field'
                ? [...(r.envelopePath || []), ...(w.fieldPath || [])]
                : r.envelopePath
            })">
          <div class="r-line">
            <span class="r-time">{{ fmtTime(r.ts_in) }}</span>
            <span class="r-kind" :class="r.kind">{{ r.kind }}</span>
            <span class="r-sig">{{ shortSig(r.signature) }}</span>
            <span class="r-hash" :title="w.kind === 'field' ? '' : r.hash">{{ r.repr }}</span>
            <span v-if="r.skipped" class="r-skip" title="contains a cycle reference — excluded from diff comparison">↺</span>
            <span v-else-if="r.changed" class="r-change" :title="w.kind === 'field' ? 'value differs from previous appearance' : 'hash differs from previous comparable appearance'">▲</span>
            <span v-else></span>
          </div>
        </li>
      </ol>
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

.watch-results { list-style: none; margin: 0; padding: 0; border-radius: 3px; overflow: hidden; }
.watch-results li {
  padding: 0.3rem 0.4rem;
  font-family: ui-monospace, monospace;
  font-size: var(--mono-size);
  cursor: pointer;
  border-top: 1px solid rgba(255, 255, 255, 0.04);
}
.watch-results li:first-child { border-top: 0; }
.watch-results li.band-0 { background: rgba(110, 231, 183, 0.10); }
.watch-results li.band-1 { background: rgba(251, 191, 36, 0.10); }
.watch-results li:hover { outline: 1px solid var(--accent-blue); outline-offset: -1px; }
.watch-results li.changed { box-shadow: inset 3px 0 0 var(--accent-red); }

.watch-results li.skipped {
  background: rgba(255, 255, 255, 0.02);
  color: var(--text-muted);
}
.watch-results li.skipped .r-time,
.watch-results li.skipped .r-sig,
.watch-results li.skipped .r-hash { color: var(--text-muted); }
.watch-results li.skipped .r-kind { opacity: 0.5; }

.r-line { display: grid; grid-template-columns: 13ch 3ch 1fr minmax(8ch, 18ch) 1ch; gap: 0.4rem; align-items: baseline; }
.r-hash { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-align: right; }
.r-time { color: var(--text-muted); }
.r-kind { font-size: 0.62rem; padding: 0 0.25rem; border-radius: 2px; background: rgba(196, 181, 253, 0.18); color: #c4b5fd; text-align: center; }
.r-kind.AR { background: rgba(96, 165, 250, 0.18); color: #93c5fd; }
.r-kind.AX { background: rgba(251, 191, 36, 0.18); color: #fcd34d; }
.r-kind.RE { background: rgba(110, 231, 183, 0.18); color: #6ee7b7; }
.r-kind.TI { background: rgba(196, 181, 253, 0.18); color: #c4b5fd; }
.r-sig { color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.r-hash { color: var(--text-muted); }
.r-change { color: var(--accent-red); font-weight: 700; }
.r-skip { color: var(--text-muted); font-size: 0.85rem; }

</style>
