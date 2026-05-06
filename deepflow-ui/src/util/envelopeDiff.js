// Pure diff walker over two hashed envelope snapshots of the SAME
// object id. Scoped to what __meta__.own_hash captures:
//
//   - scalar field changes
//   - child reference id changes (idSwap)
//   - list shape / element-id changes
//   - added / removed keys
//
// Does NOT descend into nested envelopes' contents. A child envelope's
// content drift moves THAT envelope's own_hash, not this one's; the
// caller surfaces it via the child's own watch row. Mirrors the
// collapse rule in core/codec/Hasher.java#ownHashInput (children →
// {__ref__: id}, drop object_id/class at the root).

import { ownKeys, refIdOf } from './envelope.js';

export function diffOwnState(before, after) {
  if (before == null || after == null) return [];
  const out = [];
  walkObject(before, after, [], out);
  return out;
}

function walkObject(a, b, path, out) {
  const aKeys = new Set(ownKeys(a));
  const bKeys = new Set(ownKeys(b));
  const all = new Set([...aKeys, ...bKeys]);
  for (const k of all) {
    const p = [...path, k];
    if (!aKeys.has(k)) { out.push({ path: p, kind: 'added', after: b[k] }); continue; }
    if (!bKeys.has(k)) { out.push({ path: p, kind: 'removed', before: a[k] }); continue; }
    diffValue(a[k], b[k], p, out);
  }
}

function diffValue(a, b, path, out) {
  const aRef = refIdOf(a);
  const bRef = refIdOf(b);
  if (aRef != null && bRef != null) {
    if (aRef !== bRef) out.push({ path, kind: 'idSwap', before: a, after: b });
    // Same id: child's own changes belong to its own watch, not here.
    return;
  }
  if (aRef != null || bRef != null) {
    // One side is a ref, the other isn't — record as a value change.
    out.push({ path, kind: 'scalar', before: a, after: b });
    return;
  }
  if (Array.isArray(a) && Array.isArray(b)) {
    diffList(a, b, path, out);
    return;
  }
  if (a && typeof a === 'object' && b && typeof b === 'object'
      && !Array.isArray(a) && !Array.isArray(b)) {
    walkObject(a, b, path, out);
    return;
  }
  if (a !== b) out.push({ path, kind: 'scalar', before: a, after: b });
}

function diffList(a, b, path, out) {
  const max = Math.max(a.length, b.length);
  for (let i = 0; i < max; i++) {
    const p = [...path, i];
    if (i >= a.length) { out.push({ path: p, kind: 'added', after: b[i] }); continue; }
    if (i >= b.length) { out.push({ path: p, kind: 'removed', before: a[i] }); continue; }
    diffValue(a[i], b[i], p, out);
  }
}

export function formatPath(path) {
  let s = '';
  for (const seg of path) {
    if (typeof seg === 'number') s += `[${seg}]`;
    else s += s ? `.${seg}` : seg;
  }
  return s;
}
