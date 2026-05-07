// Centralised provide/inject keys, typed as InjectionKey<T> so the
// matching inject() call site infers the right shape.
//
// Read this file before adding a new provide(): if the same shape is
// already published under another key, prefer reusing it.

import type { ComputedRef, Ref } from 'vue';
import type { InjectionKey } from 'vue';
import type { CallRow, Highlight, PayloadRow } from './types';

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

// Per-callId override for the children-fold inside an expanded frame.
// Independent of frame expansion: a frame can be expanded while its
// children fold is collapsed (showing AR / AX / RE without the nested
// calls). Default is expanded — only explicit collapses are stored.
// Navigator's goto walks the ancestor chain and forces this true so a
// targeted descendant is mounted.
export const CHILDREN_EXPANDED_OVERRIDES: InjectionKey<Ref<Map<string, boolean>>> =
  Symbol('childrenExpandedOverrides');

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
