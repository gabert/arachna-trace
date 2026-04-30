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

1. **Agent shutdown emit** — biggest remaining correctness/UX gap.
   Without it, `agent_runs.ended_at` and `completed_clean` are always
   null, which the UI will eventually want.
2. **D-02** — silently incorrect `first_seen` after every restart.
   Cheap fix once we accept the schema change to `AggregatingMergeTree`.
3. **Retain wiring** — unblocks the user-facing payoff of Phase 3
   (promote a debugging session for long retention).
4. **Verb layer / query DSL** — the next greenfield design problem and
   was the original starting point; the schema is now stable enough to
   design on.

Lower-leverage and can wait: B-05/B-06/L-04 (rare crash paths,
self-healing or documented), D-03..D-08 (cosmetic or only matter at
multi-processor scale), A-01..A-04 (well-known instrumentation limits).
