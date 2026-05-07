// Single source of truth for chronological ordering of any (call, event-kind)
// pair within a request. Every consumer that needs to sort events
// chronologically — watch-panel appearance rows, origin-panel chains,
// and any future panel — imports from here and never reinvents the
// rule locally.
//
// The model
//
//   Each call has two indices computed in useRequestData via DFS over
//   the call tree:
//     - pre  — DFS pre-order index, set when DFS enters the call.
//              Equivalent to the chronological position of AR/TI events.
//     - post — DFS post-order index, set when DFS leaves the call.
//              Equivalent to the chronological position of AX/RE events.
//
//   Together, pre and post form a monotonic total order over every
//   entry/exit event in the request. The DFS-derived invariants
//   (parent.pre < child.pre < child.post < parent.post; siblingA.post
//   < siblingB.pre) make sub-ms wall-clock ties resolvable
//   correctly in any nested-vs-sibling configuration — something
//   neither wall-clock ts nor the agent's seq counter can do alone.
//
// Stability of same-side ties
//
//   Within a single call, AR and TI both fire at entry (same `pre`),
//   and AX and RE both fire at exit (same `post`). These pairs tie on
//   chronoIndex and rely on the JavaScript stable-sort guarantee plus
//   the SQL `ORDER BY seq, kind, ts_in` bucket insertion order
//   (alphabetical kind: AR < AX < RE < TI) to render in the
//   conventional sequence AR → TI → AX → RE. No explicit kind tier-
//   break is needed in the comparator.

import type { CallMeta, PayloadKind } from '../types';

export function isExitKind(kind: PayloadKind): boolean {
  return kind === 'AX' || kind === 'RE';
}

/**
 * Chronological position of one event. Returns a finite integer for
 * any call present in {@code meta}; returns {@code Number.MAX_SAFE_INTEGER}
 * for unknown calls so they sort last (defensive — should not happen
 * in practice).
 */
export function chronoIndex(
  callId: string,
  kind: PayloadKind,
  meta: Map<string, CallMeta>
): number {
  const m = meta.get(callId);
  if (!m) return Number.MAX_SAFE_INTEGER;
  return isExitKind(kind) ? m.post : m.pre;
}

/**
 * Returns a comparator over event-bearing rows. Rows must expose
 * {@code callId} and {@code kind}; the comparator uses
 * {@link chronoIndex} alone — no fallback to wall-clock ts, no
 * KIND_ORDER tier. Stable sort handles same-side ties (AR/TI;
 * AX/RE) via bucket insertion order.
 */
export function eventComparator<R extends { callId: string; kind: PayloadKind }>(
  meta: Map<string, CallMeta>
): (a: R, b: R) => number {
  return (a, b) => chronoIndex(a.callId, a.kind, meta) - chronoIndex(b.callId, b.kind, meta);
}
