# What's Left

Snapshot taken 2026-04-30, after the L-01/L-03 TTL-eviction work.
Sourced from `KNOWN_BUGS.md` and the "What's left — beyond Phase 3"
list in `SCHEMA_DESIGN.md`. Re-evaluate whenever a phase lands.

---

## Bugs (real correctness issues, no current fix)

- **B-04** — version dispatch in wire parser. *Dismissed earlier* —
  agent + processor ship together; revisit only if external consumers
  appear.
- **B-05** — JVM crash mid-method leaves orphan UUID on `CALL_STACK`.
  Self-heals at top-of-request; doable via periodic stack-depth alarm
  but no clean ByteBuddy try/finally fix.
- **B-06** — failed entry on request's originating root → spurious root
  in `requests`. Hard; documented degradation.

## Leaks

- **L-04** — accumulation in long-lived thread `CALL_STACK` after B-05.
  Tied to B-05 fix.

## Data quality (mostly accepted degradations)

- **D-01** — `agent_runs` row missing if first batch's insert fails
  *and* JVM dies before next batch. Now Low (Phase 4 made it idempotent
  per batch).
- **D-02** — `sessions.first_seen` wrong after processor restart (RMT
  picks latest insert). Fix needs `AggregatingMergeTree` switch.
- **D-03** — `ReplacingMergeTree(inserted_at)` uses 1-second
  resolution; matters only multi-processor. Switch to `DateTime64(3)`.
- **D-04** — `ALTER TABLE UPDATE retain` async race with TTL.
- **D-05** — `payload_size` is JSON byte count, misleading name.
- **D-06** — `is_exception` hardcodes `'EXCEPTION'` literal.
- **D-07** — UUID collision (cosmic-ray); not worth fixing.
- **D-08** — `runScoped` body sees swapped stack (intended behaviour,
  documented).

## Architectural / pre-existing

- **A-01..A-04** — raw `Thread.start`, custom executors, virtual
  threads untested, no FK enforcement. All known instrumentation/CH
  limits.

## Bug-finding UX (from BUG_FINDING_DESIGN.md, 2026-05-06)

**Part A — `own_hash` end-to-end. DONE.** `__meta__.own_hash` flows
through the rendered payloads, `payloads.own_hashes Array(String)`
indexed in ClickHouse, UI consumes it directly. WatchPanel deep-hash
column dropped; own-state is the sole row signal.

**B1 — Mutations view. DONE.** New `GET /api/analysis/mutations`
endpoint detects via the indexed `own_hashes` column and returns
groups (`{call, class, changed-field-set}`) — bulk transforms over
N items collapse to one row + expand-on-demand. New `MutationsPanel`
in the right pane is the default tab; expand a group to see per-
instance diffs computed client-side from already-loaded payloads.
Demo agent now emits AX (`emit_tags=...,AX,...`). Inline AX view
highlights the precise mutated envelopes (own_hash transitions) and
newly-added envelopes (AX-only ids) per row inside JsonTree, with
matching counts in the AX header. Block-level AX highlight removed
to avoid the Merkle-propagation framing.

**B7 — WatchPanel signal preference. DONE.** Done with Part A.

**B2..B6 — open.**
- **B2** Exception ring on FrameCard for `return_type=EXCEPTION`. Cheap.
- **B3** SessionsView per-request decoration: mutation/exception/
  duration/retain badges + session-level rollup. Builds on the
  shipped two-pane preview.
- **B4** ObjectHistoryView upgrade — vertical timeline keyed off
  own_hash transitions, reuses B1's diff renderer.
- **B5** Tiny hash chip on every envelope row in JsonTree.
- **B6** Always-show pin button (`⊕ watch` is opacity:0 by default).

**Part C — Field provenance (NEW, deferred design-only).** For any
field of any object at any moment in the trace, show *where its
value came from*. Walks backward across calls: argument flow
(scalar value matches an arg of the call), chained returns (that
arg was the return of an earlier call), object-id linkage (child
ref's id appeared earlier as arg/return — high confidence). Likely
a new component: a "construction" view that, given an `(object_id,
field_path)`, renders the inbound dataflow as an explorable chain.
Pairs with B1: mutations point at *who wrote here*; provenance
walks *where the value came from*. Together they close the
debugging loop "symptom → cause" without re-running.

Server-side endpoint sketch (`POST /api/analysis/provenance`) and
heuristic-vs-confident-source split documented in
`BUG_FINDING_DESIGN.md` Part C. Client-side single-request version
ships first (~a day, reuses B1's diff walker); cross-session
version needs a tokenized-values index for scale.

**Part D — Value search (NEW).** Developer reading a trace often
spots a suspicious value (`discount: 0.15`, `userId: "alice"`, an
ISBN, a UUID) and wants to find every other place in the same
session or request where that value appears. Today this requires
ad-hoc ClickHouse SQL or scrolling. Backlog this as:
`GET /api/analysis/value-search?session_id=...&request_id=...&value=...`
returning `[{call_id, kind, path, signature}, ...]`. UI: a search box
(header bar or right-pane tab) — click any hit → jumps to that exact
JsonTree node via the existing highlight machinery.

Implementation crude-first: `payload_json LIKE '%value%'` plus a
JSON walk in Java to refine each match into `(path, kind)`. Scales
to a single request fine; cross-session needs the same tokenized-
values index Part C will eventually want — natural shared primitive.
Same endpoint also serves provenance's scalar-source step internally.
And it's exactly the kind of tool an LLM agent debugging a trace
would call ("find every payload where `discount=0.15`"); UI is one
consumer, agents another.

## Frontend / dev experience

- **TS migration of `deepflow-ui` (HIGH PRIORITY, deferred).** The UI is
  100% JS today (no `tsconfig.json`, no `.ts` files, no `lang="ts"`).
  Decided 2026-05-06 to migrate, but not now. The biggest concrete
  wins:
  - Envelope shape (`__meta__.{id,class,hash,own_hash}`, cycle-ref
    discriminant) typed once instead of re-checked in 5 places
    (`envelopeDiff.js`, `JsonTree`, `WatchPanel`, `MutationsPanel`,
    `SessionDetailView`'s walks).
  - API response shapes become a compile-time contract — endpoint
    changes (e.g. mutations endpoint's `rows` → `groups` rewrite)
    caught at build, not on first click. UI and future LLM clients
    consume the same typed contract.
  - Diff `kind` (`scalar | idSwap | added | removed`) becomes a
    discriminated union — exhaustiveness check forces renderers to
    handle every kind.
  - Provenance (Part C) brings richer shapes (chain entries,
    confidence flags, source kinds); typing them at introduction is
    cheaper than converting later.
  - Migration scope: add `typescript` + `vue-tsc`, generate
    `tsconfig.json`, convert ~10 small JS files + ~10 Vue components,
    type the API client and the envelope/diff shapes. ~half a day
    plus surfacing real type errors.
  - Do this **before Part C** so provenance types land natively.

## Other follow-ups (from SCHEMA_DESIGN.md)

- **Agent shutdown emit** — write an "ended" record on graceful
  shutdown so `agent_runs.ended_at` / `completed_clean` get populated.
  Phase 4 made `RH` transport-layer, so this needs reformulation —
  probably an extra `X-Deepflow-*` POST on shutdown or a sidecar
  update for the file destination.
- **`last_seen` updates on sessions** — emit per call or batched.
- **Retain wiring** (the deferred half of Phase 3) — agent config flag
  + sink stamping for "promote this debugging session for long
  retention."
- **Verb layer / agent query DSL** — original Question 1 from the very
  first design session. Schema is now stable enough to design on.

---

## Honest take on what's worth doing next

Ranked by ratio of (user-facing payoff or lurking-bug elimination) /
(effort), not by ID order:

1. **TS migration of `deepflow-ui`** — high-priority dev-experience
   work, gates Part C provenance landing on typed shapes from day
   one. ~half a day. Not happening immediately but should be the
   next non-feature work.
2. **Part C — Provenance** — workflow-changer per BUG_FINDING_DESIGN.
   Pairs with B1 to close symptom→cause. Land after TS migration so
   chain / confidence shapes are typed at introduction.
3. **Agent shutdown emit** — biggest remaining correctness/UX gap.
   Without it, `agent_runs.ended_at` and `completed_clean` are always
   null, which the UI will eventually want.
4. **D-02** — silently incorrect `first_seen` after every restart.
   Cheap fix once we accept the schema change to `AggregatingMergeTree`.
5. **Retain wiring** — unblocks the user-facing payoff of Phase 3
   (promote a debugging session for long retention).
6. **Verb layer / query DSL** — the next greenfield design problem and
   was the original starting point; the schema is now stable enough to
   design on.

Lower-leverage and can wait: B-05/B-06/L-04 (rare crash paths,
self-healing or documented), D-03..D-08 (cosmetic or only matter at
multi-processor scale), A-01..A-04 (well-known instrumentation limits).
