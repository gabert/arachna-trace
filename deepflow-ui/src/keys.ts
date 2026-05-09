// Centralised provide/inject keys, typed as InjectionKey<T> so the
// matching inject() call site infers the right shape.
//
// Read this file before adding a new provide(): if the same shape is
// already published under another key, prefer reusing it. Bundles
// related state into single-shape providers so consumers inject one
// thing instead of three.

import type { ComputedRef, Ref } from 'vue';
import type { InjectionKey } from 'vue';
import type { AppearanceKind, CallRow, Highlight, PayloadRow, TraceTarget } from './types';

// ---------------------------------------------------------------------
// Loaded-data providers
// ---------------------------------------------------------------------

// Loaded payloads grouped by call_id. Each entry is a small
// per-call array; the map only contains call_ids whose payloads have
// actually been fetched into the lazy cache, NOT every call in the
// session. Consumers must therefore handle missing entries gracefully
// (typically by calling acquire via SESSION_PAYLOADS first).
export const PAYLOADS_BY_CALL_ID: InjectionKey<ComputedRef<Map<string, PayloadRow[]>>> =
  Symbol('payloadsByCallId');

// Session-wide payload cache management. Components that need a
// specific call's payloads (CallInspectionCard) call
// `acquire(callId)` on mount and `release(callId)` on unmount; the
// cache evicts entries when refcount drops to zero. `loadingCallIds`
// reports which fetches are in flight so consumers can render a
// loading state.
export interface SessionPayloadsCtx {
  loadingCallIds: ComputedRef<Set<string>>;
  acquire: (callId: string) => Promise<PayloadRow[]>;
  release: (callId: string) => void;
}
export const SESSION_PAYLOADS: InjectionKey<SessionPayloadsCtx> =
  Symbol('sessionPayloads');

// Parent → ordered children map. Roots live under the null key.
export const CHILDREN_BY_PARENT: InjectionKey<ComputedRef<Map<string | null, CallRow[]>>> =
  Symbol('childrenByParent');

// ---------------------------------------------------------------------
// Frame-tree expansion
// ---------------------------------------------------------------------

// Default expanded-ness for FrameCards (toggled by expand-all/collapse-all).
export const EXPANSION_DEFAULT: InjectionKey<Ref<boolean>> =
  Symbol('expansionDefault');

// Per-callId expansion overrides. Frame's expanded-ness is
// `overrides.get(id) ?? default`.
export const EXPANSION_OVERRIDES: InjectionKey<Ref<Map<string, boolean>>> =
  Symbol('expansionOverrides');

// ---------------------------------------------------------------------
// Mutation / added object id sets (per call, scoped per viewer)
// ---------------------------------------------------------------------

// Per-callId Set<objectId> of envelope ids whose own_hash moved between
// AR and AX. Scoped down to the AX viewer by PayloadViewer; JsonTree
// reads its scoped version (MUTATED_OBJECT_IDS) to mark mutated rows.
export const MUTATED_OBJECTS_BY_CALL_ID: InjectionKey<ComputedRef<Map<string, Set<number>>>> =
  Symbol('mutatedObjectsByCallId');

// Per-callId Set<objectId> of envelope ids present in AX but absent from
// AR. Same scoping pattern as mutated.
export const ADDED_OBJECTS_BY_CALL_ID: InjectionKey<ComputedRef<Map<string, Set<number>>>> =
  Symbol('addedObjectsByCallId');

// Mutated-id Set scoped to the current PayloadViewer (null unless it's
// rendering AX). Consumed by JsonTree.
export const MUTATED_OBJECT_IDS: InjectionKey<ComputedRef<Set<number> | null>> =
  Symbol('mutatedObjectIds');

// Added-id Set scoped to the current PayloadViewer (null unless it's
// rendering AX). Consumed by JsonTree.
export const ADDED_OBJECT_IDS: InjectionKey<ComputedRef<Set<number> | null>> =
  Symbol('addedObjectIds');

// ---------------------------------------------------------------------
// Navigator highlight (JSON-tree side)
// ---------------------------------------------------------------------

// Single highlight ref. Drives the navigator: PayloadViewer compares
// (callId, kind) to its own to decide whether to forward pathKey down
// to JsonTree, JsonTree's per-node compare drives the flash class +
// scrollIntoView, WatchPanel reads it to mark the currently-selected
// row.
export const HIGHLIGHT: InjectionKey<Ref<Highlight | null>> =
  Symbol('highlight');

// Counter bumped on every navigation. JsonTree watches it so re-clicking
// the already-current row still re-scrolls (without it, isMatch never
// transitions and the watcher doesn't fire).
export const NAV_TICK: InjectionKey<Ref<number>> =
  Symbol('navTick');

// ---------------------------------------------------------------------
// Call selection — bundle of "which call's inspection card is focused"
// + the setter that the row-click handler invokes.
// ---------------------------------------------------------------------
//
// `select(id)` is more than a setter — it adds the call to the
// inspection stack if absent, focuses it, and (when an instance is
// being traced and the call has the instance) auto-navigates the
// PayloadViewer to where the instance lives. Consumers just call it;
// the orchestration lives in SessionDetailView.

export interface CallSelection {
  selectedId: Ref<string | null>;
  select: (callId: string) => void;
}

export const CALL_SELECTION: InjectionKey<CallSelection> =
  Symbol('callSelection');

// ---------------------------------------------------------------------
// Instance trace — bundle of trace target + per-call appearance map +
// random-access nav. All three travel together: anything that reads
// the appearance map needs the target to interpret it, and anything
// that wants to jump to an appearance uses navigateTo.
// ---------------------------------------------------------------------
//
// `appearances` is direct only — no subtree rollup; collapsed parents
// do NOT inherit a descendant's mark. Bubbling-up was tried and
// rejected (2026-05-09) — it conflated "instance is here" with
// "instance is somewhere below here", which the trace banner's ↑/↓
// navigation handles better.

export interface InstanceTraceCtx {
  instance: Ref<TraceTarget | null>;
  appearances: ComputedRef<Map<string, AppearanceKind>>;
  navigateTo: (callId: string) => void;
}

export const INSTANCE_TRACE: InjectionKey<InstanceTraceCtx> =
  Symbol('instanceTrace');

// ---------------------------------------------------------------------
// Exception nav — random-access counterpart to the cycle ↑/↓ in the
// header overlay. FrameCard renders a red → bubble on every row that
// is itself an exception, when at least one exception exists in the
// loaded request; clicking it calls `navigateTo(callId)` which selects
// + highlights, mirroring the trace bubble pattern.
// `active` reserves the bubble column on every row so the layout
// doesn't shift between exception and non-exception rows.
// ---------------------------------------------------------------------

export interface ExceptionNavCtx {
  active: ComputedRef<boolean>;
  navigateTo: (callId: string) => void;
}

export const EXCEPTION_NAV: InjectionKey<ExceptionNavCtx> =
  Symbol('exceptionNav');

// ---------------------------------------------------------------------
// Call-tree row highlight — bundle of "you are here" id + a tick
// counter so re-highlighting the same row still re-fires consumers'
// scroll-into-view watchers. Owned by CallTreePanel; the panel also
// exposes a public highlightCall() method via defineExpose for parents
// to trigger from outside (trace ↑/↓ etc.).
// ---------------------------------------------------------------------

export interface CallHighlightCtx {
  callId: Ref<string | null>;
  tick: Ref<number>;
}

export const CALL_HIGHLIGHT: InjectionKey<CallHighlightCtx> =
  Symbol('callHighlight');
