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

import type { CycleRef, Envelope, EnvelopeMeta, Path, PayloadNode } from '../types';

export const ENVELOPE_NOISE: ReadonlySet<string> = new Set([
  '__meta__', 'object_id', 'class', 'ref_id', 'cycle_ref'
]);

export function isEnvelope(v: unknown): v is Envelope {
  if (v == null || typeof v !== 'object') return false;
  const meta = (v as { __meta__?: unknown }).__meta__;
  if (meta == null || typeof meta !== 'object') return false;
  return (meta as { id?: unknown }).id != null;
}

export function isCycleRef(v: unknown): v is CycleRef {
  if (v == null || typeof v !== 'object') return false;
  const o = v as { cycle_ref?: unknown; ref_id?: unknown };
  return o.cycle_ref === true && o.ref_id != null;
}

export function metaOf(v: unknown): EnvelopeMeta | null {
  return isEnvelope(v) ? v.__meta__ : null;
}

export function refIdOf(v: unknown): number | null {
  if (isEnvelope(v)) return v.__meta__.id;
  if (isCycleRef(v)) return v.ref_id;
  return null;
}

export function ownKeys(obj: Record<string, unknown>): string[] {
  return Object.keys(obj).filter(k => !ENVELOPE_NOISE.has(k));
}

// Find the first envelope node whose object_id matches. Returns the
// node itself, or null. Skips __meta__ to avoid descending into the
// metadata block (which can contain its own structure).
export function findByObjectId(root: unknown, targetId: number): Envelope | null {
  if (root == null || typeof root !== 'object') return null;
  if (isEnvelope(root) && root.__meta__.id === targetId) return root;
  if (Array.isArray(root)) {
    for (const item of root) {
      const r = findByObjectId(item, targetId);
      if (r) return r;
    }
    return null;
  }
  const obj = root as Record<string, unknown>;
  for (const k of Object.keys(obj)) {
    if (k === '__meta__') continue;
    const r = findByObjectId(obj[k], targetId);
    if (r) return r;
  }
  return null;
}

// Find the path from `root` to the first envelope whose object_id
// matches. Returns null if not found.
export function findPathToObjectId(root: unknown, targetId: number): Path | null {
  return walkForPath(root, targetId, []);
}

function walkForPath(node: unknown, targetId: number, path: Path): Path | null {
  if (node == null || typeof node !== 'object') return null;
  if (isEnvelope(node) && node.__meta__.id === targetId) return path;
  if (Array.isArray(node)) {
    for (let i = 0; i < node.length; i++) {
      const r = walkForPath(node[i], targetId, [...path, i]);
      if (r) return r;
    }
    return null;
  }
  const obj = node as Record<string, unknown>;
  for (const k of Object.keys(obj)) {
    if (k === '__meta__') continue;
    const r = walkForPath(obj[k], targetId, [...path, k]);
    if (r) return r;
  }
  return null;
}

export type EnvelopeVisitor = (envelope: Envelope, path: Path) => void;

// Visit every envelope reachable from `root` in document order. Cycle
// refs are not visited (they aren't envelopes).
export function walkEnvelopes(root: unknown, visitor: EnvelopeVisitor): void {
  visit(root, [], visitor);
}

function visit(node: unknown, path: Path, visitor: EnvelopeVisitor): void {
  if (node == null || typeof node !== 'object') return;
  if (isEnvelope(node)) visitor(node, path);
  if (Array.isArray(node)) {
    for (let i = 0; i < node.length; i++) visit(node[i], [...path, i], visitor);
    return;
  }
  const obj = node as Record<string, unknown>;
  for (const k of Object.keys(obj)) {
    if (k === '__meta__') continue;
    visit(obj[k], [...path, k], visitor);
  }
}

// Path-free variant of walkEnvelopes for callers that only care about
// the envelope identity, not where it lives in the tree. Skips the
// per-step `[...path, seg]` allocation, which dominates the cost on
// deep payload trees.
export type EnvelopeVisitorNoPath = (envelope: Envelope) => void;

export function walkEnvelopesNoPath(root: unknown, visitor: EnvelopeVisitorNoPath): void {
  visitNoPath(root, visitor);
}

function visitNoPath(node: unknown, visitor: EnvelopeVisitorNoPath): void {
  if (node == null || typeof node !== 'object') return;
  if (isEnvelope(node)) visitor(node);
  if (Array.isArray(node)) {
    for (let i = 0; i < node.length; i++) visitNoPath(node[i], visitor);
    return;
  }
  const obj = node as Record<string, unknown>;
  for (const k of Object.keys(obj)) {
    if (k === '__meta__') continue;
    visitNoPath(obj[k], visitor);
  }
}

// Collect every appearance of `targetId` along with each appearance's
// path. A single object_id can appear multiple times in one payload
// tree (same instance referenced from different fields), and the
// watcher wants every row.
export interface EnvelopeAppearance {
  envelope: Envelope;
  path: Path;
}

export function findAllByObjectId(root: unknown, targetId: number): EnvelopeAppearance[] {
  const out: EnvelopeAppearance[] = [];
  walkEnvelopes(root, (env, path) => {
    if (env.__meta__.id === targetId) out.push({ envelope: env, path });
  });
  return out;
}

// Resolve a path from `node` step by step, returning the leaf value or
// `undefined` if any step traverses through a non-object. Generic
// over object/array — segment can be a string key or array index.
export function resolvePath(node: unknown, path: Path): unknown {
  let v: unknown = node;
  for (const seg of path) {
    if (v == null || typeof v !== 'object') return undefined;
    v = (v as Record<string | number, unknown>)[seg];
  }
  return v;
}

// Walk `path` from the payload root and find the deepest envelope on
// the way to the leaf. Returns:
//   - envelopeId: the deepest enclosing envelope's __meta__.id
//   - fieldPath:  the suffix of `path` from that envelope to the leaf
// Used by provenance to identify "the same field on the same instance"
// for later-mutation lookups. Returns null when no envelope is on the
// path (e.g. the value lives inside a plain Map that isn't envelope-
// wrapped) — provenance skips the mutation lookup in that case.
export interface EnclosingEnvelope {
  envelopeId: number;
  fieldPath: Path;
}

export function enclosingEnvelopeFromPath(root: unknown, path: Path): EnclosingEnvelope | null {
  let node: unknown = root;
  let lastId: number | null = null;
  let lastIndex = -1;

  if (isEnvelope(node)) {
    lastId = node.__meta__.id;
    lastIndex = 0;
  }

  // Stop one segment short — the final segment lands on the scalar
  // leaf, not on a containing envelope.
  for (let i = 0; i < path.length - 1; i++) {
    if (node == null || typeof node !== 'object') return null;
    node = (node as Record<string | number, unknown>)[path[i]];
    if (isEnvelope(node)) {
      lastId = node.__meta__.id;
      lastIndex = i + 1;
    }
  }

  if (lastId == null) return null;
  return { envelopeId: lastId, fieldPath: path.slice(lastIndex) };
}

// Re-export types so callers can keep importing from this module if
// they were already doing so.
export type { Envelope, EnvelopeMeta, CycleRef, Path, PathSegment, PayloadNode } from '../types';
