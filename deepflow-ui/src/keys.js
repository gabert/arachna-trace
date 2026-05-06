// Centralised provide/inject keys. Strings (not Symbols) so a stale
// HMR module doesn't break inject reactivity by holding a stale Symbol
// reference — Vue compares keys by ===, and string keys survive HMR
// trivially. After TS migration these become InjectionKey<T> exports.
//
// Read this file before adding a new provide(): if the same shape is
// already published under another key, prefer reusing it.

// Loaded payloads grouped by call_id. Map<callId, Array<payload>> where
// each payload has { kind, payload_json, parsed, ts_in, signature, ... }.
// Provided by SessionDetailView; consumed by FrameCard, PayloadViewer,
// MutationsPanel.
export const PAYLOADS_BY_CALL_ID = 'payloadsByCallId';

// Parent → ordered children map. Map<parentCallId|null, Array<call>>.
// Roots live under the null key. Provided by SessionDetailView, consumed
// by FrameCard.
export const CHILDREN_BY_PARENT = 'childrenByParent';

// Default expanded-ness for FrameCards (toggled by expand-all/collapse-all).
export const EXPANSION_DEFAULT = 'expansionDefault';

// Per-callId expansion overrides. Map<callId, boolean>. Frame's
// expanded-ness is `overrides.get(id) ?? default`.
export const EXPANSION_OVERRIDES = 'expansionOverrides';

// Per-callId Set<objectId> of envelope ids whose own_hash moved between
// AR and AX. Scoped down to the AX viewer by PayloadViewer; JsonTree
// reads its scoped version (MUTATED_OBJECT_IDS) to mark mutated rows.
export const MUTATED_OBJECTS_BY_CALL_ID = 'mutatedObjectsByCallId';

// Per-callId Set<objectId> of envelope ids present in AX but absent from
// AR. Same scoping pattern as mutated.
export const ADDED_OBJECTS_BY_CALL_ID = 'addedObjectsByCallId';

// Mutated-id Set scoped to the current PayloadViewer (null unless it's
// rendering AX). Consumed by JsonTree.
export const MUTATED_OBJECT_IDS = 'mutatedObjectIds';

// Added-id Set scoped to the current PayloadViewer (null unless it's
// rendering AX). Consumed by JsonTree.
export const ADDED_OBJECT_IDS = 'addedObjectIds';

// Single highlight ref. Shape: { callId, kind, pathKey } | null.
// Drives the navigator: PayloadViewer compares (callId, kind) to its
// own to decide whether to forward pathKey to JsonTree, JsonTree's
// per-node compare drives the flash class + scrollIntoView, WatchPanel
// reads it to mark the currently-selected row.
export const HIGHLIGHT = 'highlight';

// Counter bumped on every navigation. JsonTree watches it so re-clicking
// the already-current row still re-scrolls (without it, isMatch never
// transitions and the watcher doesn't fire).
export const NAV_TICK = 'navTick';
