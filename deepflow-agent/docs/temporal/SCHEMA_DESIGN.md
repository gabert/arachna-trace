# Schema Design Decisions — ClickHouse Trace Store

Notes from the 2026-04-30 design session. Captures the decisions made for the
trace storage schema, the reasoning behind each, and what was deliberately
deferred or rejected. Return here before changing the schema.

## Context

- Goal: design a ClickHouse schema for trace data that serves the primary use
  cases without compromise, while staying extensible for secondary ones.
- Decision was made to design the **schema only** in this session. The
  agent-facing query layer (verbs / DSL / SQL escape hatch) is **deferred** to
  a follow-up session.

## Use case categorization (agreed)

- **Primary** — drives core schema shape:
  - Human debugging
  - AI-assisted debugging
  - AI code observability / verification (watching what AI-generated code does
    at runtime; the differentiator)
- **Secondary** — must be possible via augmentation, not core schema changes:
  - Regression / before-after comparison
  - Compliance audit
  - Codebase comprehension / onboarding
- **Feature, not category**: dead code detection (a query, not a use case)
- **Out of scope** (from prior memory): performance/APM positioning, lethal
  systems

## Schema decisions

### 1. `call_id` and `parent_call_id` — processor synthesizes

- **Choice:** processor (`RecordParser`) generates UUIDs and assigns
  `parent_call_id` from its existing stack.
- **Why:** lean agent principle. The wire format stays unchanged. The parser
  already reconstructs parent/child from intervals — we reuse proven logic
  rather than expand the agent.
- **Rejected:** agent-generated UUIDs (cleaner if traces are ever consumed
  outside this pipeline, but adds wire format and bandwidth cost).

### 2. `business_keys` — deferred entirely

- **Choice:** no `business_keys` table for v1. No extraction logic in
  processor.
- **Why:** at insurance-application scale (hundreds of domain verbs and
  entities), per-class config is unmanageable, and convention scans risk being
  noisy and missing domain-specific names. We'd be guessing at extractors
  without usage signal.
- **Cost accepted:** the agent's "find trace by human bug report" entry path
  (e.g. "order #123 broke") is weaker for v1. Entry happens via session, time
  range, or exception. Easy to add later when concrete extractors prove their
  worth.

### 3. `requests` table — processor writes explicitly

- **Choice:** processor inserts one `requests` row when a request's root call
  closes. Carries denormalized roll-up: entry signature, start/end, call count,
  exception count.
- **Why:** request is the natural unit of debugging. "List requests in this
  session, flag the broken ones" should be a point query, not a `calls`
  aggregation. `RecordParser` already holds the per-request state needed.
- **Rejected:** ClickHouse `MaterializedView` with `AggregatingMergeTree` —
  more native but adds a layer of "magic" that's painful to debug if it
  drifts. Explicit writes are easier to reason about.

### 4. Retention TTL — 30d default with `tags['retain']` escape

- **Choice:** every table gets `TTL started_at + INTERVAL 30 DAY DELETE WHERE
  NOT mapContains(tags, 'retain')`. Tag a session with `retain=true` and it
  survives.
- **Why:** prevents disk bloat by default; gives audit/long-keep path for free
  via the existing `tags` map. Costs nothing now (one TTL clause per table).
- **Rejected:** separate tables per retention class (`calls_debug` vs
  `calls_audit`) — premature; doubles sink logic before we have audit
  customers.

### 5. Cross-session object identity — no new column

- **Choice:** nothing added. `root_hash` (Merkle content hash) handles
  content-identity across sessions. Logical identity ("the same Order entity
  even after mutation") returns when `business_keys` is built later.
- **Why:** the two existing concepts (`root_hash` for content,
  future-`business_keys` for logical) cover the space. Adding more would
  duplicate.

### 6. Materialized views — defer all

- **Choice:** no MVs in v1. Not `signatures_seen`, not `object_appearances`,
  none of them.
- **Why:** disciplined non-additions. Dead-code is `SELECT signature, count(*)
  FROM calls GROUP BY signature` — fine at current scale. Object history works
  via the existing bloom filter. Add MVs the day a real query is too slow,
  not before.

### 7. Session metadata wire format — new `SH` record alongside existing `VersionRecord`

- **Choice:** add a new `SH` (session header) record at session start carrying
  app/agent identity. Keep the existing `VersionRecord` unchanged.
- **Fields in `SH`:** `session_id`, `code_version`, `agent_version`,
  `hostname`, `jvm_pid`, `env`.
- **Why two records, not one:**
  - `VersionRecord` (existing) is the **wire-format** version
    (`[major:short][minor:short]`). Tells consumers how to parse the stream.
    Changes rarely.
  - `SH` (new) is the **application/agent** identity. Tells consumers what
    code produced the trace. Changes every deploy.
  - Different concepts, different lifecycles. Conflating them would couple a
    parser concern to a deploy concern.
  - `agent_version` and wire-version can drift (agent v3.4 may still emit wire
    v1.2). Keeping both is honest.
- **Why include the speculative fields (`hostname`, `jvm_pid`, `agent_version`):**
  the agent's first emit is naturally an "I am here" announcement. Fields are
  constants the agent already holds — emit cost is ~zero — and they pay off
  the moment a session goes weird and you need to triage which agent / JVM /
  host produced it. Earlier draft was over-austere; reversed.
- **Sequence at session start:** `VersionRecord` first (parser dispatch), then
  `SH`.
- **Rejected — Option B (extend `SI` to carry metadata):** would change an
  existing record's payload format. New record is cleaner.
- **Rejected — Option C (HTTP headers from agent → collector):** couples
  metadata to the HTTP transport, breaks for `file` destination.

## Final schema shape (target)

```
sessions(
  session_id, started_at, ended_at,
  hostname, jvm_pid, agent_version, code_version, env,
  completed_clean, tags Map(String,String)
)  -- written by processor on SH; ended_at on session close
   -- TTL 30d with retain tag escape

requests(
  session_id, request_id, started_at, ended_at,
  entry_signature, thread_name,
  call_count, exception_count, has_exception
)  -- written by processor when root call closes
   -- inherits TTL via session retention

calls(  -- existing + additions
  call_id UUID,                    -- NEW
  parent_call_id Nullable(UUID),   -- NEW
  depth UInt16,                    -- NEW
  session_id, request_id, thread_name, ts_in, ts_out,
  duration_ns MATERIALIZED ts_out - ts_in,  -- NEW
  signature, caller_line, return_type,
  is_exception MATERIALIZED return_type='EXCEPTION',  -- NEW
  this_id
)  -- skip indexes on signature, is_exception
   -- TTL 30d with retain tag escape

payloads(  -- existing + minimal additions
  call_id UUID,           -- NEW: direct join key
  session_id, request_id, ts_in, kind,
  payload_json,
  payload_size UInt32,    -- NEW
  root_hash,
  object_ids              -- existing bloom filter retained
)  -- TTL 30d with retain tag escape
```

## Wire format changes

- Keep `VersionRecord` unchanged (wire format version).
- Add `SH` record (session header) — fires once per session, after
  `VersionRecord`, carrying app/agent identity as a CBOR map.

## Processor changes

- Generate `call_id` UUIDs in `RecordParser`; set `parent_call_id` from the
  existing stack; compute `depth`.
- On root-call close: insert `requests` row.
- On `SH`: insert `sessions` row. On session close: update `ended_at` and
  `completed_clean`.

## Deferred (not in v1)

- `business_keys` table and extraction logic (see decision #2).
- All materialized views (`signatures_seen`, `object_appearances`).
- Multi-table retention split (separate `calls_audit` etc.).
- Per-call structural fingerprints for regression diffing (sidecar table when
  needed).

## Rejected

- Pre-computed anomaly flags ("automatically mark suspicious calls") — we
  don't yet know what's "suspicious" to the agent.
- Vector embeddings of payloads — sounds AI-native, solves nothing concrete
  for structural debugging queries.
- Audit-specific columns on `calls` — those go to a separate sink when audit
  becomes real.
- A `users` or domain-entity table — would couple the schema to one
  application's domain model.

## Open for next session (as of original lock)

- Verb layer / agent query DSL (postponed by user request).
- Whether `SH` replaces the existing `SI` record or supplements it.

---

# Post-feasibility revisions (same day, 2026-04-30)

After the decisions above were locked, we walked the proposed changes against
the existing code (`RecordParser`, `ClickHouseSink`, `RequestRecorder`,
`RequestContext`, the wire-format records, `01-schema.sql`). Two findings
reshaped parts of the design and one prior decision was reversed. This
section captures **why**, so a cold reader doesn't have to re-derive it.

## Findings from the code walk

### Finding A — `session_id` is per-call, not per-JVM

`RequestRecorder.recordEntry` and `recordExit` call
`spi.getSessionIdResolver().resolve()` on **every method invocation**. With
the `config` resolver this is static; with the Spring resolver it's
per-HTTP-session. So:

- "Session" is a logical identity (e.g. one user's browser session), not a
  JVM lifetime.
- Multiple sessions can be live concurrently in one JVM.
- The agent doesn't know when a session "starts" — sessions are *discovered*
  by the processor when records carrying a new `session_id` arrive.

**Consequence:** the original Decision 7 (`SH` = session header carrying
hostname/jvm_pid/agent_version/code_version/env) conflated **per-JVM-run
metadata** with **per-session identity**. The right model is two tables:

```
agent_runs(agent_run_id UUID, hostname, jvm_pid, agent_version,
           code_version, env, started_at, ended_at, completed_clean)

sessions(session_id, agent_run_id UUID, first_seen, last_seen,
         request_count, call_count, tags Map(String,String))
```

Plus an `agent_run_id` carried somewhere on the wire so the processor can
attribute each session to the JVM run that hosted it (otherwise two JVMs
sharing a config-derived session_id collide).

### Finding B — `RecordParser` is per-batch and stateless (latent bug)

`RecordParser.parse()` builds its TS/TE pairing stack as a **method-local**
`ArrayDeque`. It is invoked once per Kafka poll batch. The stack is
discarded when `parse()` returns.

Implication: any request whose `TS` arrives in poll N and whose matching
`TE` arrives in poll N+1 has its open builder dropped on the floor. The
call's row is never produced. **This is a pre-existing latent bug.** It's
been masked because tests run with small synthetic batches; under real load
with concurrent requests, calls leak.

Our schema additions (`call_id`, `parent_call_id`) made this visible because
they require parent context that the per-batch parser cannot supply.

## The statefulness debate

Initial fix proposal: make the parser stateful — keep open builders alive
across `parse()` invocations, keyed by `(agent_run, session, request,
thread)`, with TTL eviction for orphaned stacks.

User pushback: dislikes processor-side state on principle.

The honest reply: pairing TS with TE is fundamentally a stateful operation
because the data is split across two events in time and the link isn't
carried in the events. State must live somewhere. Options were:

| Where state lives | Wire | Agent | Processor |
|---|---|---|---|
| A. Processor (proposed) | unchanged | unchanged | parser holds per-(run, session, request, thread) stacks with TTL |
| B. Agent buffers entry data, emits combined entry+exit on exit | one record per call instead of two | per-call entry payload buffered until method returns | trivial |
| C. Agent emits parent linkage on the wire (UUIDs or parent_ts_in) | MS gains parent field, ME gains own-ID field | thread-local stack of IDs | trivial |
| D. Drop `parent_call_id`, reconstruct trees from intervals | unchanged | unchanged | still needs pairing state |
| E. Status quo, accept the bug | unchanged | unchanged | unchanged |

B is the leanest *parser*, the heaviest *agent*: the agent holds a full
entry payload per concurrent call, and live debugging is delayed until
methods return. C is the leanest *bounded* state — a small per-thread stack
of IDs. D doesn't escape the pairing problem.

## Resolved approach: agent-generated UUIDs on the wire (option C, refined)

User proposed: each call gets a UUID at entry; agent maintains a
thread-local stack only of UUIDs; emit own UUID on MS, own UUID on ME, and
parent UUID on MS.

This is the chosen mechanism. Reasoning:

- **Solves both problems with one wire change.**
  - TS↔TE pairing: each ME carries its own UUID; processor matches by ID,
    no stack reconstruction.
  - Tree shape: each MS carries its parent UUID; tree is a column lookup.
- **Smallest agent state possible.** `Deque<UUID>` per traced thread.
  Size = `16 bytes × current JVM stack depth`. Naturally bounded by the
  JVM stack itself; GC'd with the thread. Nothing approaching the per-call
  payload buffering of option B.
- **Stateless processor.** Parser becomes "on MS, buffer one open call;
  on ME, match by ID, emit row." No cross-batch stacks. No TTL eviction.
- **Bonus: clean addressable PK.** A single-field `call_id` is much easier
  to pass around in tool calls and `payloads` joins than a composite
  `(session, request, thread, ts_in)` key.
- **Side effect: the existing latent bug disappears.** Not as a separate
  fix — as a consequence of the mechanism.

### Mechanics

```
onEnter (per traced method):
  myId   = UUID.randomUUID()
  parent = threadLocalStack.peek()        // null at top of request
  threadLocalStack.push(myId)
  emit MS with (myId, parent, …existing fields…)
  return myId                              // rides @Advice.Enter to onExit

onExit (per traced method, also onThrowable):
  myId = the value from onEnter
  threadLocalStack.pop()
  emit ME with (myId, …existing fields…)
```

`@Advice.Enter` is ByteBuddy's per-call channel from `onEnter` to `onExit`.
The own-UUID rides it without needing a thread-local lookup at exit.

### Costs (acknowledged)

- Wire: MS +32 bytes (own + parent UUIDs), ME +16 bytes (own UUID).
  ~48 bytes per call.
- UUID generation: one `UUID.randomUUID()` per traced method. With
  `ThreadLocalRandom`, tens of nanoseconds.
- Push/pop discipline: must push **last** in onEnter (after anything that
  could throw) and pop **first** in onExit. ByteBuddy's
  `OnMethodExit(onThrowable = Throwable.class)` covers both normal and
  exceptional return paths.

## Async behavior — works via existing propagation infrastructure

Confirmed by reading `RequestContext`, `PropagatingRunnable`,
`PropagatingCallable`, `ExecutorAdvice`, and (by reference) `ForkJoinAdvice`.

The existing async story:

- `RequestContext` holds `CURRENT_REQUEST_ID` and `DEPTH` as ThreadLocals
  (via mutable holders, so bootstrap classloader instrumentation can share
  them).
- `ExecutorAdvice` wraps submitted runnables in `PropagatingRunnable`,
  capturing the submitter's `request_id`.
- On the worker thread, `PropagatingRunnable.run` calls
  `RequestContext.runScoped(parentRequestId, body)` — which saves prior
  state, sets request_id and depth=1, runs the body, restores.

The UUID-stack mechanism extends this **line-for-line**:

- Add `ThreadLocal<Deque<UUID>> CALL_STACK` to `RequestContext`.
- `runScoped` gains a `parentCallId` arg; on entry it saves the current
  stack, clears it, pushes `parentCallId` as the seed; on exit, restores.
- `PropagatingRunnable` captures both `parentRequestId` and `parentCallId`
  at submission.
- `ExecutorAdvice.onEnter` reads both from the submitter's thread-local.

**Semantic this gives you:** the first traced method on a worker thread has
`parent_call_id = <the call that submitted the runnable>`. Call trees span
executor boundaries as a contiguous parent-chain — better than most APMs,
which flatten async into separate spans.

**Coverage matches what `request_id` already covers** — Executor.execute,
ExecutorService.submit, ForkJoinPool, CompletableFuture (via common pool).
Doesn't cover: manual `new Thread().start()`, raw `LockSupport`,
cross-process. These were already gaps; the new mechanism inherits them
without adding new ones.

## Decisions reversed or refined by this section

### Q1 (call_id source) — REVERSED to A

Original lock: B (processor synthesizes). Reversed to **A (agent
generates)**. Rationale: not "we changed our minds about lean agent" — the
correct framing is "smaller bounded agent state vs unbounded processor
state." A small per-thread Deque of UUIDs is *less* state than a
per-(run, session, request, thread) reconstruction stack with TTL eviction
in the processor.

### Q3 (requests table) — UNCHANGED

Processor still writes the row, but trivially: on receipt of an ME whose
parent_call_id is null (root of request), close the request and emit one
`requests` row with rolled-up stats accumulated from the open-calls map.

### Q7 (sessions metadata) — REFINED into two tables

The `SH` record is renamed in concept to `RH` (run header) and carries
**agent-run** metadata only. New tables:

- `agent_runs` — populated on `RH`, completed on session-end / shutdown.
- `sessions` — populated when a new session_id is first seen, with
  `agent_run_id` foreign key.

Open: how `agent_run_id` reaches the processor on every record. Two
candidates:

- Embed `agent_run_id` in MS and ME records (16 bytes/call additional).
- Stamp `agent_run_id` as a Kafka header when the collector forwards the
  records of a single HTTP delivery.

Lean: **Kafka-header approach**. The `RH` is sent once at agent start over
HTTP; the collector knows the connection's run id and can tag every record
of that connection's batches when forwarding to Kafka. Avoids per-call
bandwidth cost. Couples the design to the HTTP→Kafka path but that is the
production path; `file` destination doesn't need agent_run disambiguation
(single consumer reads its own files).

### Other corrections from the code walk

- `request_id` is `UInt64` in existing schema (we had said String). Stays
  `UInt64`.
- `return_type` is `Enum8('VOID','VALUE','EXCEPTION')`, not
  `LowCardinality(String)`. `is_exception MATERIALIZED return_type='EXCEPTION'`
  works against the enum identically.
- `duration_ms` already exists as a materialized column; our proposed
  `duration_ns` is redundant. Keep `duration_ms`.
- `ts_in DateTime64(3)` (millis); we had proposed (6). Keep ms.
- `payloads.signature` is denormalized today; with `call_id` it becomes
  redundant for joins but `LowCardinality` makes the cost ~zero. **Keep**
  for query-locality.
- `object_ids Array(Int64)` — these are numeric IDs from
  `ObjectIdRegistry`, not Merkle hashes. Both serve different roles; keep
  the existing column unchanged.
- `inserted_at DateTime DEFAULT now()` exists on both tables — useful for
  pipeline-latency diagnostics. Preserve.
- The original `SH replaces SI` open question is **moot**: `SI` is
  renderer-output only, not a wire record. session_id rides inside
  `MethodStartRecord` / `MethodEndRecord` payloads. The new `RH` record is
  purely additive and unrelated.
- CLAUDE.md references `RecordHashEnricher.java` which does not exist —
  CLAUDE.md is stale on this point.

## Recommended sequencing (revised)

Three phases, in order, no interleaving:

**Phase 0 — wire format and agent changes for UUID propagation**

- Add `agent_run_id` UUID generation at agent start.
- Add per-thread `Deque<UUID>` to `RequestContext`.
- Extend `runScoped` / `callScoped` / `PropagatingRunnable` /
  `PropagatingCallable` / `ExecutorAdvice` / `ForkJoinAdvice` to
  capture+seed the parent UUID alongside `parent_request_id`.
- Add own-UUID and parent-UUID fields to `MethodStartRecord`; own-UUID
  to `MethodEndRecord`. Bump wire version.
- New `RunHeaderRecord` (`RH`) emitted once at agent start carrying
  `agent_run_id` + JVM/agent metadata.
- Collector stamps `agent_run_id` on Kafka records via headers (or chosen
  alternative).

**Phase 1 — schema additions on existing tables**

- `ALTER TABLE calls ADD call_id UUID, parent_call_id Nullable(UUID),
  depth UInt16` (defaults so old rows remain valid).
- `ALTER TABLE payloads ADD call_id UUID, payload_size UInt32`.
- Update `ParsedCall` and `ClickHouseSink` row-builders.
- `RecordParser` becomes simpler, not stateful: keep an open-calls map
  keyed by `call_id` (still has lifetime concerns for never-closed calls,
  but the map itself is trivial — eviction is a separate small concern).

**Phase 2 — new tables**

- `agent_runs` (DDL + processor write on `RH`).
- `sessions` (DDL + processor write on first-seen session_id).
- `requests` (DDL + processor write on root-call ME).

**Phase 3 — retention escape**

- Add `tags['retain']` honored by `TTL` clauses. Requires CH-version check
  for TTL-with-subquery or denormalization of the retain flag onto the
  `calls`/`payloads` rows.

## Open re-decisions before any code

Carried forward from the feasibility analysis:

1. **`agent_run_id` delivery — embed in records, or Kafka header?**
   (Lean: Kafka header.)
2. **Phase ordering — confirm Phase 0 before Phase 1.**
3. **Update SCHEMA_DESIGN.md final-shape DDL block to reflect all
   corrections from "Other corrections" section** (the original DDL block
   in this file still shows `request_id String`, `duration_ns`, etc.).
4. **Whether `depth` counts the seeded async parent on a worker thread or
   not.** Lean: no — depth is "depth in this thread's stack."

---

# Implementation progress

## Decisions locked at start of implementation

Re-decisions resolved before code began:

1. **`agent_run_id` delivery** → reversed from "Kafka header" lean to
   **embed in MS and ME records (16 bytes each)**. Simpler, works for the
   `file` destination too, robust to Kafka rebalancing. Trade-off
   accepted: ~5–10% per-record growth, negligible vs CBOR payloads.
2. **Phase ordering** → Phase 0 → 1 → 2 → 3 confirmed.
3. **Depth on worker thread** → does NOT count the seeded async parent.

## Step 0.1 — DONE (2026-04-30)

Foundation, no breaking changes:

- `BinaryUtil` — `putUuid` / `getUuid` / `getNullableUuid` (all-zero UUID
  is the sentinel for "no UUID present").
- `RecordType` — `UUID_SIZE = 16`, `RUN_HEADER = 0x0A`. Version
  unchanged at this step.
- `RunHeaderRecord.java` (new) — agent_run_id + hostname + agent_version +
  code_version + env + jvm_pid + started_at_millis. Fires once per JVM at
  agent start.
- `TraceRecord` — `RunHeaderRecord` added to `permits` and `parse()`
  switch.
- `RecordRenderer` — new tag set (`RH/AI/AV/CV/HN/PD/EN/RS`). `RH`
  joins always-emit alongside `MS/VR`. `tagsForRunHeader` branch added.
- `RecordWriter.runHeader(...)` facade added.
- Tests: round-trip + null-fields tests in `TypedRecordTest`; golden +
  parse-back tests in `WireFormatGoldenTest`; exhaustiveness test
  updated to include `RUN_HEADER`.

Test results: full repo green (22/22 modules).

## Step 0.2 + 0.3 (combined) — DONE (2026-04-30)

Wire format change for UUID-based call addressing, plus agent integration.
Combined because separating would have required throwaway placeholder
code.

- **`RequestContext`** — added `CALL_STACK ThreadLocal<Deque<UUID>>` and
  helpers `peekParentCallId() / pushCallId() / popCallId()`. Lives in
  bootstrap package so JDK-class advice can reach it.
- **`RecordType.VERSION_MINOR`** bumped from 2 → 3.
- **`MethodStartRecord`** — appended `agentRunId`, `callId`,
  `parentCallId` UUIDs (48 bytes added to payload). `parentCallId` is
  nullable (top of request); encoded as all-zero UUID sentinel.
  `agentRunId` and `callId` are required by contract but encoded
  identically — null is tolerated for tests / pre-init state.
- **`MethodEndRecord`** — appended `agentRunId`, `callId` (32 bytes
  added).
- **`RecordWriter`** — added `private static volatile UUID agentRunId`
  with `initAgentRunId(UUID)` setter. All `logEntry*` / `logExit*` /
  `methodEnd` / `logEntrySimple` / `logExitSimple` facades take new
  UUID parameters; `agentRunId` is read from the static field.
- **`RecordRenderer`** — `tagsForMethodStart` emits `AI/CI/PI` when
  non-null; `tagsForMethodEnd` emits `AI/CI`. `CI/PI` added to
  `ALL_TAGS`.
- **`RequestRecorder`** —
  - `recordEntry`: peeks parent UUID, generates own UUID via
    `UUID.randomUUID()`, emits MS with both, **then** pushes own UUID
    onto the stack. Push happens last so a throw before that line
    cannot leave a phantom UUID.
  - `recordExit`: pops the stack first to retrieve own UUID, emits ME
    with it.
  - `buildSerializedEntry` now takes `callId, parentCallId` and threads
    them through.

All affected tests updated in lockstep:

- `WireFormatGoldenTest` — golden hex strings updated for new MS/ME
  layouts; new `methodStart_agentRunIdRoundTrips` test verifies the
  static `initAgentRunId` path.
- `TypedRecordTest` — round-trip tests assert all three new UUID
  fields; null-fields test asserts they round-trip as null;
  exhaustiveness test fixture updated.
- `RecordWriterReaderTest`, `RecordRendererTest`, `FileDestinationTest`
  — all `RecordWriter.*` call sites updated to pass `null` for the new
  UUID parameters where they don't care.

Test results: full repo green (22/22 modules).

## Latent pairing bug — STATUS

The pre-existing bug (per-batch parser drops calls whose TS and TE land
in different Kafka polls) is **not yet eliminated**. The mechanism that
eliminates it requires:

- ✅ Step 0.2 — UUIDs on MS and ME wire records (DONE)
- ⏳ Step 1.3 — `RecordParser` rewritten to match MS↔ME by UUID instead
  of popping a stack

Until 1.3 lands, the bug remains. After 1.3 it disappears as a side
effect of the mechanism (no stack to lose at batch boundaries).

## Documentation corrections

While reading the code I noticed:

- `RecordHashEnricher.java` **does exist** (in
  `server/record-processor-server/.../processor/`) — the earlier
  feasibility-analysis note that "CLAUDE.md references
  RecordHashEnricher.java which does not exist" was wrong. CLAUDE.md
  is correct on this point. Tests for it pass (10/10).

## Step 0.4 — DONE (2026-04-30)

Async propagation: parent UUID now flows across executor / ForkJoin
boundaries the same way `request_id` already did.

- **`RequestContext.runScoped`** / **`callScoped`** — added
  `parentCallId UUID` parameter. Inside the scope: snapshot the worker
  thread's prior `CALL_STACK`, swap in a fresh deque seeded with
  `parentCallId` (or empty if null), restore the prior stack on exit
  (including exceptional exit). Replaced (not mutated) the ThreadLocal
  to avoid push/pop iteration gymnastics.
- **`PropagatingRunnable`** / **`PropagatingCallable`** — constructor
  takes `parentCallId`, passes it through to `runScoped` / `callScoped`.
- **`ExecutorAdvice.onEnter`** / **`ForkJoinAdvice.{ExecuteRunnable,SubmitCallable}.onEnter`** —
  at submission time, read `RequestContext.peekParentCallId()` (the top
  of the submitting thread's call stack, i.e. the currently-executing
  call) and pass to the wrapper. Same `requestId != 0L` gate as
  before — if no active request, no wrapping happens (and no parent UUID
  to propagate either).
- **Tests** — `RequestIdTest` updated for the new constructor; existing
  three tests (`propagatingRunnableCarriesRequestId`,
  `propagatingRunnableRestoresState`,
  `propagatingRunnableRestoresOnException`) pass `null` for parentCallId
  to preserve their original semantics. **Three new tests** added under
  "Layer 3":
  - `propagatingRunnableSeedsCallStackWithParent` — verifies worker's
    first `peekParentCallId()` returns the seeded UUID
  - `propagatingRunnableRestoresCallStackOnExit` — verifies worker stack
    is restored to pre-task state and submitter stack untouched
  - `propagatingRunnableWithNullParentLeavesStackEmpty` — verifies null
    parent ⇒ empty worker stack
  - `DeepFlowAdviceRecordingTest` updated for the new constructor (uses
    `RequestContext.peekParentCallId()` to pass the live parent).

Test results: full repo green (22/22 modules).

### Semantic this gives you on async

The first traced method on a worker thread has
`parent_call_id = <the call that submitted the runnable>`. Call trees
span executor boundaries as a contiguous parent chain — better than
most APMs which flatten async into separate spans.

Coverage matches what `request_id` already covers — Executor.execute,
ExecutorService.submit, ForkJoinPool, CompletableFuture (via common
pool). Same gaps too: manual `new Thread().start()`, raw `LockSupport`,
cross-process. No new gaps introduced.

## Bulletproof entry/exit contract — DONE (2026-04-30)

Out-of-band hardening triggered by analyzing an edge case in async
propagation: "if `recordEntry` throws *before* it pushes the call UUID,
does the cascade affect only one pairing or all subsequent ones?"
Answer: cascades within the request scope until the call stack drains.
Agent must not emit wrong data — fix at agent level.

The contract:

- **`RequestRecorder.recordEntry`** now returns `boolean`. `true` means
  *both* the call UUID was pushed onto `CALL_STACK` *and* the `MS`
  record was queued. `false` means neither happened.
- **`DeepFlowAdvice.onEnter`** returns this boolean and ByteBuddy
  passes it via `@Advice.Enter` to `onExit`. `onExit` skips
  `recordExit` when entry returned `false`.
- **All build/encode work** in `recordEntry` is inside a single
  `try`. `RequestContext.beginRequest()` (which mutates `DEPTH`)
  happens *inside* the try; if anything later throws, the catch calls
  `RequestContext.endRequest()` to roll back depth.
- **Push and buffer offer** happen *outside* the try, *after* it
  succeeds. Both are infallible (`Deque.push` and
  `RecordBuffer.offer` do not throw), so reaching that point
  guarantees the both-or-nothing invariant.

**What the contract guarantees:**

- Every push has a matching pop.
- `CALL_STACK` and `DEPTH` are never left in an inconsistent state by
  a failed entry.
- A failed entry suppresses its matching exit, so no later call ever
  pairs against a wrong UUID. The cascade is impossible.

**What it allows (acceptable failure modes):**

- A successful `MS` followed by a failed `recordExit` body produces
  an orphan `MS` (no `ME`). Stack/depth still consistent because the
  pop happens before the failable code in `recordExit`. Detectable in
  postprocessing via timeout on the open-calls map.
- A failed `recordEntry` produces nothing — the method runs without
  being traced, no records emitted. Visible in postprocessing as
  reduced trace coverage; never as wrong data.

**Tests added** in `DeepFlowAdviceRecordingTest`:

- `failedEntryReturnsFalseAndLeavesStateUntouched` — injects a
  throwing `SessionIdResolver`, asserts return value is `false` and
  `DEPTH`, `CALL_STACK`, and the buffer are all untouched.
- `failedEntryDoesNotPoisonSubsequentCalls` — fails one entry, then
  verifies the next entry is recorded normally with no parent
  (proving no phantom UUID was left on the stack), and that pairing
  via `callId` between MS and ME holds.
- `tearDown` updated to clear `CALL_STACK` between tests.

Test results: agent module 49/49 (was 47/47 + 2 new). Full repo run
in progress.

## Steps 0.5 + 0.6 — DONE (2026-04-30)

`agent_run_id` generation + `RH` record emission at agent start, with
metadata wired through from `AgentConfig`.

Combined into one step because they share the `RecorderManager.create`
code path — building the `RH` record needs both the freshly-generated
`agent_run_id` and the configured metadata.

### Changes

- **`AgentConfig`** — added `code_version` and `env` config keys with
  getters. Both optional (null = unset, encoded as zero-length on the
  wire and omitted from the `RH` render output).
- **`RecorderManager.AGENT_VERSION`** constant — hardcoded to
  `"0.0.1-SNAPSHOT"` for now. TODO when agent JAR's manifest carries
  `Implementation-Version`: read via `Package.getImplementationVersion()`
  to keep in sync with the POM automatically.
- **`RecorderManager.create`** — at startup:
  1. Generate `UUID agentRunId = UUID.randomUUID()`.
  2. Call `RecordWriter.initAgentRunId(agentRunId)` so every subsequent
     `MS` and `ME` carries it.
  3. Emit `VR` (wire-format version) directly to destination.
  4. Emit `RH` (run header) directly to destination, built from
     `agentRunId`, `InetAddress.getLocalHost().getHostName()`,
     `AGENT_VERSION`, `config.getCodeVersion()`, `config.getEnv()`,
     `ProcessHandle.current().pid()`, `System.currentTimeMillis()`.
  5. Then start the drainer thread.
  Both startup emits happen on the calling thread, **before** the
  drainer thread starts — destination implementations are not
  synchronized; the drainer becomes the sole writer once running. (The
  startup writes happen-before the drainer starts via
  `Thread.start()`'s happens-before edge, so the drainer sees
  `pendingHeader` correctly.)
- **`RecordDrainer.start`** — no longer emits `VR`. The drainer is now
  solely responsible for the streaming drain loop; startup-only
  records are owned by `RecorderManager`. Cleaner separation of
  concerns and prerequisite for adding `RH` without ordering hacks.
- **`RecordDrainerTest`** — three tests updated. `drainsRecordsToDestination`
  and `drainsRemainingRecordsOnStop` no longer expect a leading version
  record (count down by one). `continuesAfterDestinationException`
  reworked to put the failure trigger as the first *offered* record.
- **`deepagent.cfg`** — documented the new `code_version` and `env`
  optional config keys.

### How `RH` lands in `.dft` files

`RunHeaderRecord`'s renderer (`tagsForRunHeader`) does **not** emit a
thread-name tag. `FileDestination.accept` already has a fallback path
for thread-less records (`if (result.threadName() == null) {
pendingHeader.addAll(...) }`) — same pattern as `VersionRecord` —
which prepends the `RH` lines to every per-thread file the moment that
file is opened. So `RH` appears once at the top of each thread's
`.dft`, alongside `VR`.

Test results: full repo green (22/22 modules).

## Step 0.7 status

Integration tests (`test-spring-webflux`, `test-plain-executors`,
`test-spring-mvc`) all pass after the changes — they exercise the
agent end-to-end via real `-javaagent` runs. They don't yet **assert**
on the new UUID-bearing tags, but they would fail to load classes /
fail to start if any of the new wire format or RecorderManager logic
were broken. So coverage is implicit-but-real.

Optional follow-up: a small integration-test assertion that scans a
generated `.dft` for `AI;`/`CI;`/`PI;`/`RH;` lines. Cheap and worth
doing once we touch those tests next.

## Phase 0 — COMPLETE

All wire-format and propagation work done. Agent is bulletproof.

## Phase 1 — DONE (2026-04-30)

Schema additions on existing tables, plus the parser refactor that
**finally eliminates the latent pairing bug** (Finding B from
feasibility analysis).

### 1.1 — Schema (`server/clickhouse-init/01-schema.sql`)

`calls` gained:
- `agent_run_id UUID` — per-JVM-run identifier (from `RH` wire record)
- `call_id UUID` — per-method-invocation identifier (from `MS`/`ME` wire records)
- `parent_call_id Nullable(UUID)` — null at request root
- `is_exception Bool MATERIALIZED return_type = 'EXCEPTION'` — cheap filter
- bloom_filter skip indexes on `call_id` and `parent_call_id`

`payloads` gained:
- `agent_run_id UUID`, `call_id UUID` — joinable to `calls`
- `payload_size UInt32` — for "skip huge ones" / size-budget queries
- bloom_filter skip index on `call_id`

`depth` was deferred — agent doesn't emit it on the wire and we're not
willing to retroactively expand Phase 0. Can be derived at query time
via parent_call_id chain when needed.

### 1.2 — `ParsedCall` + `ClickHouseSink`

- `ParsedCall` gained `agentRunId`, `callId`, `parentCallId` fields.
- `ClickHouseSink.addRows` populates the new columns. UUID encoding:
  non-null UUIDs as their canonical string; null `parent_call_id`
  serialized as JSON null (ClickHouse ingests as SQL NULL into the
  `Nullable(UUID)` column). `agent_run_id` and `call_id` are non-nullable
  in the schema; if absent at the source we fall back to the all-zero
  UUID sentinel.
- `payload_size` computed from `json.getBytes(UTF_8).length`.

### 1.3 — `RecordParser` refactor (the key one)

**Before:** stateless static utility, method-local stack to pair MS↔ME by
ordering. Lost calls when MS and ME landed in different Kafka polls.
Mispaired multi-thread interleaving in one batch.

**After:** stateful instance, `Map<UUID, Builder> openCalls` keyed by
call UUID. State persists across `parse()` invocations. `ClickHouseSink`
holds one parser instance and serializes calls under its existing lock.

State machine summary:

- `TS` → start `currentEntry` builder, capture ts_in.
- `CI` (in entry context) → store `openCalls[callId] = builder`.
  Subsequent `TI`/`AR`/etc still write to `currentEntry`.
- `TE` → set `awaitingExitCallId`, capture ts_out.
- `CI` (in exit context) → `openCalls.remove(callId)` to recover the
  builder, set ts_out, become `currentExit`. Subsequent `RT`/`RE`/`AX`
  write to it. If the lookup misses (orphan ME from a failed-entry
  contract), drop silently.
- Next `TS` or `TE` → flush `currentExit` as a `ParsedCall`.
- End of batch → flush `currentExit`. Open entries that haven't seen
  CI yet stay in state for the next batch.

**What this guarantees:**

- MS and ME pair correctly across Kafka batch boundaries.
- Multi-thread interleaving in one batch pairs correctly (each call is
  uniquely addressable by UUID).
- Orphan ME (entry failed, exit slipped through somehow) drops silently
  rather than corrupting state.

**Open call leak (resolved 2026-04-30):** an MS that never gets a
matching ME (agent crashed mid-call) used to stay in `openCalls`
forever. A TTL eviction sweep now runs at the end of every `parse()`,
dropping entries whose admission age exceeds 10 minutes. The sweep
itself is throttled to once per minute so the O(n) walk is amortised
across many batches. Each `Builder` records its admission time on
construction; the clock is injectable for deterministic tests
(`RecordParserTest.staleOpenCallIsEvictedAfterTtl`).

**Tests:** `RecordParserTest` rewritten — 15 tests now (was 11). All
existing scenarios updated with CI/PI/AI tags. Three new tests
specifically exercising the bug fix:

- `msInOneBatchAndMeInAnotherStillPair` — proves cross-batch pairing
  works (the central reason for this refactor).
- `interleavedThreadsInOneBatchPairCorrectlyByCallId` — two concurrent
  threads' lines mixed in one batch; both pair correctly.
- `orphanMeIsDroppedWhenNoMatchingMsExists` — orphan ME drops silently.

Test results: full repo green (22/22 modules).

## Phase 2 — DONE (2026-04-30)

Three new tables (`agent_runs`, `sessions`, `requests`) plus the
processor logic to populate them.

### 2.1 — DDL

- **`agent_runs`** — `ReplacingMergeTree(inserted_at)` keyed by
  `agent_run_id`. Re-emits from a re-played Kafka batch dedupe at the
  server. Carries identity + environment + start time. `ended_at` is
  nullable, defaults null until shutdown handling lands.
- **`sessions`** — `ReplacingMergeTree(inserted_at)` keyed by
  `(session_id, agent_run_id)`. Lightweight in v1: identity +
  `first_seen` / `last_seen`. Counts and tags can be added when concrete
  queries demand them (deferred per "no speculative columns" rule).
- **`requests`** — `MergeTree`, partitioned by day, ordered by
  `(agent_run_id, session_id, started_at, request_id)`. One row per
  closed request with rolled-up `call_count`, `exception_count`, plus
  materialized `duration_ms` and `has_exception`. TTL 30 days.

### 2.2a — Parser RH extraction

- New `AgentRunMetadata` record: `agentRunId, hostname, agentVersion,
  codeVersion, env, jvmPid, startedAtMillis`.
- `RecordParser.parse()` return type changed from `List<ParsedCall>` to
  `ParseResult(calls, agentRuns)`.
- New parser state: `currentRh: RhBuilder`. Activated on `RH` tag,
  collects `AI/AV/CV/HN/PD/EN/RS` tags, finalized when the next `TS`,
  `TE`, or `RH` arrives (or end-of-batch).
- `AI` is context-routed: in RH context → run id; in entry context →
  call's run id; in exit context → ignored (duplicate from MS side).
- Defensive: an `RH` block that lacks `AI` is dropped (no
  `AgentRunMetadata` emitted).

### 2.2b — Processor writers (`ClickHouseSink`)

- Three new HTTP insert URIs: `agent_runs`, `sessions`, `requests`.
- Three new row buffers, flushed in `flushLocked` alongside the
  existing two.
- **agent_runs**: every `AgentRunMetadata` from the parser is buffered
  as a row. `ended_at = null`, `completed_clean = false` for now.
- **sessions**: `seenSessions: Map<SessionKey, Long>` tracks
  `(agent_run_id, session_id)` pairs already announced *in this
  processor instance*, mapped to the processor wall-clock time at
  admission. On first sight of a `ParsedCall`, a row is buffered with
  `first_seen = call.tsIn`, `last_seen = call.tsOut`. Processor restart
  re-emits; the `ReplacingMergeTree` engine dedupes. A TTL sweep
  (1 hour, throttled to every 5 minutes) inside `periodicFlush` keeps
  the map from growing monotonically over a long-lived processor —
  any session that re-appears past TTL re-emits one row, which RMT
  collapses. Updating `last_seen` on every call would generate one row
  per call — deferred until a concrete query needs accurate live
  `last_seen`.
- **requests**: `openRequests: Map<RequestKey, RequestAggregator>`
  tracks running totals per `(agent_run_id, request_id)`. Every
  `ParsedCall` increments `callCount` (and `exceptionCount` if
  `returnType = "EXCEPTION"`). When a root call (`parent_call_id == null`)
  arrives — which the parser emits last in post-order — the aggregator
  is finalized into a `requests` row and removed from the map.

### Acknowledged limitations (v1)

- **`openRequests` leak**: resolved by Phase 5 — the in-memory
  aggregator was deleted entirely; `requests` is now an MV-maintained
  `AggregatingMergeTree`. There is no per-request state in the
  processor anymore.
- **Async after root**: if a worker thread emits more calls *after*
  the originating root closes (fire-and-forget background task in the
  same `request_id`), they are not counted in the row that was already
  emitted. Needs `ReplacingMergeTree` on `requests` if/when this matters
  for production semantics.
- **Failed entry on the originating root**: if entry recording for the
  request's first call fails (failed-entry contract), the second call
  becomes a "spurious root" (its `parent_call_id` is null because the
  failed predecessor didn't push). The `requests` row would record
  that later call as the root — under-counts the request slightly.
  Acceptable v1 trade-off.

### Tests

- `RecordParserTest` — 17 tests now (was 15). New tests:
  `rhBlockIsExtractedAsAgentRunMetadata`,
  `rhWithoutAgentRunIdIsDropped`. All pre-existing tests updated to
  `parse(...).calls()` for the new return shape.

Test results: full repo green (22/22 modules).

## Phase 3 — DONE (2026-04-30, with caveats)

TTL retain-escape mechanism. The schema-level plumbing is in; the
agent-/sink-level wiring (i.e. *who decides a row should be retained*)
is deferred — flagged as a known gap below.

### Issue found during planning

Original SCHEMA_DESIGN proposal:

```
TTL toDateTime(...) + INTERVAL 30 DAY DELETE
WHERE NOT mapContains((SELECT tags FROM sessions WHERE ...), 'retain')
```

**This does not work in ClickHouse.** The `WHERE` clause of a TTL
expression cannot contain subqueries — it must be a column expression
of the same table. Discovered while planning Phase 3; documented here
so we don't re-derive it next time.

### Fallback (chosen): denormalize the retain flag

Add a `Bool retain DEFAULT false` column to every TTL'd table. The
TTL clause becomes:

```
TTL toDateTime(ts_in) + INTERVAL 30 DAY DELETE WHERE NOT retain
```

Which CH happily accepts. Trade-offs accepted:

- 1 extra byte per row (negligible).
- Setting `retain=true` after the row is inserted requires
  `ALTER TABLE ... UPDATE retain = true WHERE ...`, which is CH's
  async mutation engine — slow on large tables, but acceptable for
  the rare "promote this trace to long retention" case.
- Cascade is manual: setting `sessions.retain=true` does NOT
  automatically retain that session's `calls`/`payloads`/`requests`.
  The user has to write the matching UPDATE for each. Or set retain
  at insert time (not yet wired).

### What landed in `01-schema.sql`

- `calls`     — `retain Bool DEFAULT false`, TTL `WHERE NOT retain`
- `payloads`  — `retain Bool DEFAULT false`, TTL `WHERE NOT retain`
- `requests`  — `retain Bool DEFAULT false`, TTL `WHERE NOT retain`
- `sessions`  — `retain Bool DEFAULT false`,
                `tags Map(String, String) DEFAULT map()` (open user-facing bag).
                Sessions has no TTL clause (it's tiny derived state),
                but the column is added for consistency and so an
                external tool can use the same name everywhere.

No Java changes required: the row maps the sink builds for each
JSONEachRow insert simply omit `retain` and `tags`, and ClickHouse
applies the column defaults.

### Known gap: nothing sets `retain=true` yet

Phase 3 ships the **schema mechanism** for retention. It does NOT
ship a path for *deciding* a row should be retained. Setting
`retain=true` is currently an external SQL operation, e.g.:

```sql
-- "Keep this session's data forever"
ALTER TABLE deepflow.sessions   UPDATE retain = true,
                                       tags = mapInsert(tags, 'retain', 'true')
                                 WHERE session_id = 'sess-xyz' AND agent_run_id = '…';
ALTER TABLE deepflow.calls      UPDATE retain = true
                                 WHERE session_id = 'sess-xyz';
ALTER TABLE deepflow.payloads   UPDATE retain = true
                                 WHERE session_id = 'sess-xyz';
ALTER TABLE deepflow.requests   UPDATE retain = true
                                 WHERE session_id = 'sess-xyz';
```

Eventual paths to wire it in (deferred):

- **At insert time**: `AgentConfig` exposes `retain=true`; agent
  emits it on RH or with each MS; sink stamps `retain` on every row.
  Suitable for "this whole JVM run is audit-tagged."
- **Post-hoc cascade job**: a periodic background task watches
  `sessions.retain=true` and runs the matching `ALTER TABLE UPDATE`
  on `calls`/`payloads`/`requests`. Suitable for ad-hoc developer
  tagging via a UI.
- **Materialized view**: `sessions_retained` MV that projects
  retain-flagged session ids; calls/payloads/requests TTL JOIN
  against it — but TTL doesn't support joins either, so this would
  also require denormalization.

For v1 we keep the column, the TTL clause, and the empty-promise
documentation; the wiring slots cleanly into a future task.

---

## Phase 4 — `agent_run_id` to transport-layer metadata (2026-04-30)

### Why this phase

Phase 0 made `agent_run_id` a per-record field on `MS` and `ME` (and
the wire `RH` record). Post-Phase-3 review surfaced that this was a
band-aid for an upstream model error — `agent_run_id` is **metadata
about the producer of the data**, not content of the data. Putting it
inside the payload caused three concrete problems:

1. **Pre-RH calls problem.** The processor cannot reliably attribute
   calls until it has seen an `RH` record. Cold start, Kafka rebalance,
   partial replay — all create windows where calls arrive without a
   prior RH. Forces sentinel/buffer/drop trade-offs, all band-aids.
2. **Lost RH = lost attribution.** A single dropped `RH` record (Kafka
   retention, processor restart between RH and the matching calls)
   means every subsequent call references a non-existent `agent_runs`
   row. Catalogued as **D-01**.
3. **Per-call wire overhead.** 32 bytes (2 × UUID) on every method
   invocation for a value that is invariant for the entire JVM run.

The architecturally clean answer (per the user's "always architecturally
clean solutions" feedback): carry `agent_run_id` and the rest of the
RH metadata as **transport-layer headers** (HTTP, Kafka, file sidecar)
on every message. Stateless at every hop; cannot be missed; payload
records shrink.

### What landed

**Agent side** (`core/serializer`, `core/agent`):

- New `com.github.gabert.deepflow.recorder.AgentRun` record (UUID,
  hostname, agentVersion, codeVersion, env, jvmPid, startedAtMillis).
  Companion `AgentRun.Headers` class holds the canonical wire-header
  names so producers and consumers cannot drift.
- `Destination` interface gained `default void setAgentRun(AgentRun)`,
  invoked once at agent startup before the drainer starts.
- `HttpDestination` overrides `setAgentRun` and applies all 7 fields as
  `X-Deepflow-*` HTTP headers on every POST (`sendBuffer`).
- `FileDestination` overrides `setAgentRun` and writes a pretty-printed
  `run.json` sidecar into `SESSION-<ts>/`. The `.dft` files stay pure
  records-only.
- `RecorderManager.create()` builds the `AgentRun`, calls
  `destination.setAgentRun(...)` *before* the drainer starts (so
  destination access remains single-threaded).

**Server side** (`server/record-collector-server`,
`server/record-processor-server`):

- `RecordHandler` extracts the 7 `X-Deepflow-*` HTTP headers and passes
  them to `KafkaRecordForwarder.send(byte[], Map<String,String>)`,
  which copies them onto Kafka record headers (UTF-8 bytes).
- `KafkaRecordConsumer.processRecord(ConsumerRecord)` reads the Kafka
  record headers, builds an `AgentRunMetadata`, and passes both rendered
  result and metadata to the sink via the new
  `RecordSink.accept(Result, AgentRunMetadata headerMetadata)` signature.
- `ClickHouseSink.accept(...)`:
  - **A batch arriving without the required `agent_run_id` header is
    dropped with an error log.** The transport layer is the only
    source of identity; missing it = malformed batch.
  - When metadata is present: upserts one `agent_runs` row per batch
    from headers (idempotent — `ReplacingMergeTree` collapses), then
    stamps every `calls`/`payloads`/`sessions`/`requests` row with the
    header `agent_run_id`.

**Wire format clean break** (no `VERSION_MINOR` bump — pre-1.0, no
external users):

- `MethodStartRecord` lost its `agentRunId` field (size: 77 → 61 bytes
  with strings; goldens updated).
- `MethodEndRecord` lost its `agentRunId` field (size: 54 → 38 bytes).
- `RunHeaderRecord` deleted entirely. `RecordType.RUN_HEADER` (`0x0A`)
  removed. `TraceRecord.permits` and the `parse` switch cleaned up.
- `RecordWriter.runHeader(...)`, `initAgentRunId(...)`, and the static
  `agentRunId` field all deleted. The `MS`/`ME` builders no longer read
  any static state.
- `RecordRenderer` no longer emits `RH`, `AI`, `AV`, `CV`, `HN`, `PD`,
  `EN`, `RS` tags. The `tagsForRunHeader` method and the `RunHeaderRecord`
  `instanceof` branch are gone.

**Parser side**:

- `RecordParser.parse(Result)` now returns `List<ParsedCall>` directly
  (was `ParseResult` wrapping calls + agentRuns). The RH state machine,
  `RhBuilder`, and the `agentRunId` field on the internal `Builder` are
  removed.
- `ParsedCall` lost its `agentRunId` field. The sink supplies it from
  headers per batch.

### Effects on the bug catalogue

- **D-01** ("agent_runs row missing if its single insert fails")
  partly mitigated. Was: one POST per JVM run; if it failed, no row
  ever. Now: upserted on every batch (idempotent), so the next batch
  retries. Severity: Medium → Low. Residual: a JVM that emits exactly
  one batch and then dies before any subsequent batch can still lose
  the row.
- "Pre-RH calls problem" — eliminated entirely.
- "Lost RH" — eliminated entirely (no `RH` wire record exists).

### What got reversed

- **Phase 0 decision #1** ("agent_run_id stamped on every MS and ME")
  reversed. The compromise it papered over (stateful processor needed
  to reconcile across RH and per-record values) is no longer needed.
  Documented honestly as a reversal so future readers understand the
  evolution rather than seeing two contradictory designs.

### Open re-decisions after Phase 4

None — clean. The "Pending architectural item" section in
`KNOWN_BUGS.md` has been moved to "Resolved."

---

## Phase 5 — `requests` becomes a CH-side aggregating rollup (2026-04-30)

### Why this phase

Phase 2 made `requests` a regular `MergeTree` written by the
processor: an in-memory `RequestAggregator` per open `(agent_run_id,
request_id)` accumulated counters as `ParsedCall`s arrived; when the
root call (`parent_call_id IS NULL`) closed, the aggregator emitted
one row and was removed from the map.

The model was **clean for strictly-nested calls** but had a subtle
upstream error: it required the processor to know "when is the
request done?" — an unanswerable question in the presence of
fire-and-forget async work. A worker thread spawned during a request
that finishes after the synchronous root closes still emits calls
carrying that `request_id`. Those late calls re-created a fresh
aggregator under the now-dead key, undercounting the original
`requests` row and leaking the resurrected aggregator forever
(catalogued as **B-03** + **L-02**).

The architecturally clean answer: aggregation belongs in
ClickHouse, not in the processor. There the question stops being
"when is the request done" and becomes "what's the latest aggregate
state of the calls we've seen so far for this request" — a question
that has a correct, eventually-consistent answer regardless of when
calls arrive.

### What landed

**Schema** (`server/clickhouse-init/01-schema.sql`):

- `requests` rebuilt as `AggregatingMergeTree`. Columns use
  `SimpleAggregateFunction(min/max/sum, …)` for `started_at`,
  `ended_at`, `call_count`, `exception_count`. For root-only fields
  (`entry_signature`, `thread_name`) we use
  `SimpleAggregateFunction(max, String)` against an
  `if(parent_call_id IS NULL, signature, '')` projection: empty
  strings sort before any non-empty value, so `max` deterministically
  picks the root's value across all calls in the request.
- `requests_mv` materialized view reads every insert into `calls`,
  aggregates by `(agent_run_id, session_id, request_id)`, and writes
  the partial state into `requests`. The destination table merges
  partials across batches via the `SimpleAggregateFunction` columns.
- `requests_view` exposes a clean read API — collapses unmerged
  parts at query time and surfaces the derived columns
  (`has_exception`, `duration_ms`) so callers don't need to know
  about `SimpleAggregateFunction`.

**Processor** (`ClickHouseSink`):

- Deleted: `aggregateRequest()`, `RequestAggregator`, `RequestKey`,
  `requestRow()`, `openRequests`, `requestBuffer`,
  `insertRequestsUri`, the `requests` flush block, and the unused
  `HashMap` import. Net code reduction.
- The sink now writes only to `calls`, `payloads`, `agent_runs`,
  `sessions`. The `requests` rollup is a pure side-effect of
  inserting into `calls`.

### Effects on the bug catalogue

- **B-03** (Async-after-root undercounts) — fixed. Late async calls
  are folded into the rollup automatically when their `calls` row is
  inserted.
- **L-02** (`openRequests` never evicts) — fixed (deleted). No
  in-memory request state exists.

### Semantic shift

`requests.ended_at` is now `max(ts_out)` across all calls in the
request, rather than the synchronous root's `ts_out`.

For purely synchronous requests this is identical. For requests with
fire-and-forget async work it's a more honest answer — the request
isn't really "done" until its async work finishes. The previous
"firm root-close timestamp" was silently wrong in the async case.

Same shift applies to `call_count` and `exception_count`: they now
reflect *all* calls including async tails, not just the
synchronously-rooted subtree.

### Tradeoffs accepted

- **More inserts into `requests`** pre-merge — one row per `calls`
  insert into the MV, collapsed by `AggregatingMergeTree` background
  merges. Bounded write amplification; CH is built for this.
- **`SimpleAggregateFunction` reads** require either the
  `requests_view` wrapper or explicit `min`/`max`/`sum` GROUP BY in
  ad-hoc queries. We added `requests_view` so the convention is
  "query the view, not the table."

### What got reversed

- **Phase 2 design choice** ("processor maintains the rollup
  in-memory, emits at root close") reversed. The compromise it papered
  over (an unanswerable "is the request done?" question) is no longer
  needed.

### Open re-decisions after Phase 5

None — clean.

### Migration

Pre-1.0, no users. Drop the existing `clickhouse_data` Docker volume
on next dev start; `01-schema.sql` recreates the schema fresh.

---

### What's left — beyond Phase 3

Natural follow-ups, in roughly increasing scope:

- ~~**Eviction / TTL sweep for in-memory state**~~ — DONE (2026-04-30).
  `RecordParser.openCalls` evicts at end of `parse()` (10-min TTL,
  1-min throttle). `ClickHouseSink.seenSessions` evicts on
  `periodicFlush` (1-hour TTL, 5-min throttle). `openRequests` was
  deleted entirely in Phase 5.
- **Agent shutdown emit** — write an `RH`-style "ended" record on
  graceful shutdown so `agent_runs.ended_at` and `completed_clean`
  get populated.
- **`last_seen` updates on sessions** — emit one row per call with
  current `last_seen` (cheap due to `ReplacingMergeTree`) or do
  periodic batched updates.
- **Retain wiring** (the deferred half of Phase 3): agent config
  flag, sink stamping, and/or post-hoc cascade job. Pick when there's
  a concrete user story.
- **Verb layer / agent query DSL** — the original Question 1 from
  the very first design session, postponed in favor of finishing the
  schema first. Now ready to design on top of a stable backend.
