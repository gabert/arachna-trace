// Centralised provide/inject keys, typed as InjectionKey<T> so the
// matching inject() call site infers the right shape.
//
// Read this file before adding a new provide(): if the same shape is
// already published under another key, prefer reusing it.

import type { ComputedRef, Ref } from 'vue';
import type { InjectionKey } from 'vue';
import type { AppearanceKind, CallRow, Highlight, PayloadRow, TraceTarget } from './types';

// Loaded payloads grouped by call_id. Each payload has its `parsed`
// field filled in (one canonical parse per request, shared across
// FrameCards / panels).
// Provided by SessionDetailView; consumed by FrameCard, PayloadViewer,
// MutationsPanel.
export const PAYLOADS_BY_CALL_ID: InjectionKey<ComputedRef<Map<string, PayloadRow[]>>> =
  Symbol('payloadsByCallId');

// Parent → ordered children map. Roots live under the null key.
export const CHILDREN_BY_PARENT: InjectionKey<ComputedRef<Map<string | null, CallRow[]>>> =
  Symbol('childrenByParent');

// Default expanded-ness for FrameCards (toggled by expand-all/collapse-all).
export const EXPANSION_DEFAULT: InjectionKey<Ref<boolean>> =
  Symbol('expansionDefault');

// Per-callId expansion overrides. Frame's expanded-ness is
// `overrides.get(id) ?? default`.
export const EXPANSION_OVERRIDES: InjectionKey<Ref<Map<string, boolean>>> =
  Symbol('expansionOverrides');

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

// Setter for the currently-inspected call. FrameCard injects and calls
// this on row click; SessionDetailView holds the state and renders
// CallInspectionCard for it. Independent of the navigator highlight —
// selecting a call does not move the highlighted JSON node, only what
// the right-pane inspection card shows.
export const SELECT_CALL: InjectionKey<(callId: string) => void> =
  Symbol('selectCall');

// Currently-selected call id (the one the inspection card is showing).
// FrameCard reads this to render its "selected" affordance so the row
// matches the open inspection card.
export const SELECTED_CALL_ID: InjectionKey<Ref<string | null>> =
  Symbol('selectedCallId');

// Instance the user picked to trace on the tree (clicked 🔎 trace on
// an envelope row in an inspection card). FrameCard reads this to
// know whether to reserve space for the bubble mark column.
export const INSPECTED_INSTANCE: InjectionKey<Ref<TraceTarget | null>> =
  Symbol('inspectedInstance');

// Per-callId classification of the inspected instance — direct
// appearances only (no subtree rollup; collapsed parents do NOT
// inherit a descendant's mark). Empty map when no instance is being
// traced. FrameCard reads its own entry to decide which mark to
// render. Bubbling-up was tried and rejected (2026-05-09) — it
// conflated "instance is here" with "instance is somewhere below
// here", which the trace banner's ↑/↓ navigation handles better.
export const INSTANCE_APPEARANCES_BY_CALL_ID: InjectionKey<ComputedRef<Map<string, AppearanceKind>>> =
  Symbol('instanceAppearancesByCallId');

// "You are here" pointer for the call tree, distinct from selection.
// CallTreePanel owns this ref and exposes highlightCall(callId) as
// the public API for parents to flash + scroll a row into view —
// e.g. on trace ↑/↓ nav. FrameCard reads its own entry, renders the
// highlight class, and scrolls into view on the watched transition.
export const HIGHLIGHTED_CALL_ID: InjectionKey<Ref<string | null>> =
  Symbol('highlightedCallId');

// Counter bumped on every highlightCall(), so re-highlighting the
// same row still re-fires FrameCard's scroll-into-view watcher.
// Same trick the navigator uses with NAV_TICK for the JSON-node
// highlight.
export const HIGHLIGHT_CALL_TICK: InjectionKey<Ref<number>> =
  Symbol('highlightCallTick');

// Random-access nav for the inspected instance — click the bubble on
// any appearance row to jump there directly, equivalent to using ↑/↓
// to step through the chronological list. SessionDetailView provides
// the impl (gotoAndSelect + highlightCall in one shot); FrameCard
// invokes it from the bubble's click handler.
export const NAVIGATE_TO_APPEARANCE: InjectionKey<(callId: string) => void> =
  Symbol('navigateToAppearance');
