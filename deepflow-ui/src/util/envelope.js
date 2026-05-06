// Envelope shape helpers.
//
// Every serialized object in a payload tree is wrapped by the agent's
// EnvelopeSerializer with a `__meta__` block:
//
//   { "__meta__": { "id", "class", "hash", "own_hash" }, ...userFields }
//
// Cycles are encoded in-tree as `{ cycle_ref: true, ref_id }` (no
// __meta__). These helpers centralise the shape checks so callers
// don't re-derive them per-file.

export const ENVELOPE_NOISE = new Set([
  '__meta__', 'object_id', 'class', 'ref_id', 'cycle_ref'
]);

export function isEnvelope(v) {
  return v != null && typeof v === 'object' && v.__meta__ && v.__meta__.id != null;
}

export function isCycleRef(v) {
  return v != null && typeof v === 'object' && v.cycle_ref === true && v.ref_id != null;
}

export function metaOf(v) {
  return isEnvelope(v) ? v.__meta__ : null;
}

export function refIdOf(v) {
  if (isEnvelope(v)) return v.__meta__.id;
  if (isCycleRef(v)) return v.ref_id;
  return null;
}

export function ownKeys(obj) {
  return Object.keys(obj).filter(k => !ENVELOPE_NOISE.has(k));
}

// Find the first envelope node whose object_id matches. Returns the
// node itself, or null. Skips __meta__ to avoid descending into the
// metadata block (which can contain its own structure).
export function findByObjectId(root, targetId) {
  if (root == null || typeof root !== 'object') return null;
  if (isEnvelope(root) && root.__meta__.id === targetId) return root;
  if (Array.isArray(root)) {
    for (const item of root) {
      const r = findByObjectId(item, targetId);
      if (r) return r;
    }
    return null;
  }
  for (const k of Object.keys(root)) {
    if (k === '__meta__') continue;
    const r = findByObjectId(root[k], targetId);
    if (r) return r;
  }
  return null;
}

// Find the path (array of string/number segments) from `root` to the
// first envelope whose object_id matches. Returns null if not found.
export function findPathToObjectId(root, targetId) {
  return walk(root, targetId, []);
}
function walk(node, targetId, path) {
  if (node == null || typeof node !== 'object') return null;
  if (isEnvelope(node) && node.__meta__.id === targetId) return path;
  if (Array.isArray(node)) {
    for (let i = 0; i < node.length; i++) {
      const r = walk(node[i], targetId, [...path, i]);
      if (r) return r;
    }
    return null;
  }
  for (const k of Object.keys(node)) {
    if (k === '__meta__') continue;
    const r = walk(node[k], targetId, [...path, k]);
    if (r) return r;
  }
  return null;
}

// Visit every envelope reachable from `root` in document order. The
// visitor receives `(envelope, path)` where path is an array of
// segments. Cycle refs are not visited (they aren't envelopes).
export function walkEnvelopes(root, visitor) {
  visit(root, [], visitor);
}
function visit(node, path, visitor) {
  if (node == null || typeof node !== 'object') return;
  if (isEnvelope(node)) visitor(node, path);
  if (Array.isArray(node)) {
    for (let i = 0; i < node.length; i++) visit(node[i], [...path, i], visitor);
    return;
  }
  for (const k of Object.keys(node)) {
    if (k === '__meta__') continue;
    visit(node[k], [...path, k], visitor);
  }
}

// Collect every appearance of `targetId` along with each appearance's
// path. Used by WatchPanel — a single object_id can appear multiple
// times in one payload tree (same instance referenced from different
// fields), and the watcher wants every row.
export function findAllByObjectId(root, targetId) {
  const out = [];
  walkEnvelopes(root, (env, path) => {
    if (env.__meta__.id === targetId) out.push({ envelope: env, path });
  });
  return out;
}
