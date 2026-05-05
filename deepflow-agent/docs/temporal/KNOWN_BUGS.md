# Known Bugs and Leaks

Cataloged during the post-Phase-3 review (2026-04-30). Each entry has
an ID so we can reference it elsewhere. **Origin** says whether the
bug existed before the schema/wire-format refactor or was introduced
by it.

---

## ✓ Resolved architectural item

**Topic: move `agent_run_id` from per-record payload to transport-layer metadata. — DONE (2026-04-30, Phase 4)**

Implemented in three sub-phases (1, 2, 3) of the 2026-04-30 session.
See `SCHEMA_DESIGN.md` Phase 4 for the full narrative. Summary of
what landed below; original plan retained for historical context.

**What landed:**

- `core/serializer/.../recorder/AgentRun.java` — immutable record + `Headers` constants
  (`X-Deepflow-Agent-Run-Id`, `…-Hostname`, `…-Agent-Version`, `…-Code-Version`,
  `…-Env`, `…-Jvm-Pid`, `…-Started-At-Millis`)
- `Destination` interface — `default void setAgentRun(AgentRun)`. `HttpDestination`
  applies the 7 fields as HTTP headers on every POST. `FileDestination` writes
  a pretty-printed `run.json` sidecar into `SESSION-<ts>/`. `TestDestination`
  inherits the no-op default.
- Collector (`RecordHandler`) reads the headers and copies them onto the
  Kafka `ProducerRecord` headers. `KafkaRecordForwarder.send(byte[], Map<String,String>)`.
- Processor (`KafkaRecordConsumer`) extracts `AgentRunMetadata` from each
  Kafka message's headers. `RecordSink.accept(Result, AgentRunMetadata)`.
  `ClickHouseSink` upserts `agent_runs` from the headers (one row per batch —
  `ReplacingMergeTree` collapses), then stamps every `calls`/`payloads`/`sessions`/`requests`
  row with the header `agent_run_id`. **A batch arriving without the
  required header is dropped with an error log** — agent-run identity is
  no longer optional at the sink.
- Wire format clean break: `MethodStartRecord` and `MethodEndRecord` lost
  the `agentRunId` field (no `VERSION_MINOR` bump — pre-1.0, no users).
  `RunHeaderRecord` deleted entirely; `RecordType.RUN_HEADER` (0x0A) gone;
  `RecordWriter.runHeader/initAgentRunId/agentRunId()` removed; `RH`/`AI`/`AV`/`CV`/`HN`/`PD`/`EN`/`RS`
  tags pruned from `RecordRenderer`.
- `RecordParser` lost the RH state machine, `RhBuilder`, `agentRunId` field
  on the `Builder`, and the `ParseResult` wrapper — `parse()` now returns
  `List<ParsedCall>` directly. `ParsedCall` lost its `agentRunId` field.

**Effects on previously catalogued bugs:**

- **D-01** — was: agent_runs row missing if its single insert fails → all
  rows orphan-reference the missing run. **Now**: agent_runs is upserted
  on every Kafka batch. A single CH-insert failure for one batch leaves
  the row not yet present, but the next batch retries it (idempotent via
  `ReplacingMergeTree`). Severity drops from Medium to Low.
- "Pre-RH calls problem" — eliminated entirely. Every Kafka message
  carries the metadata.
- Per-call wire overhead — −32 bytes per MS/ME pair.

The original plan and rationale follows below for the historical record.

---

## Original pending-item write-up (for reference)

**Topic: move `agent_run_id` from per-record payload to transport-layer metadata.**

This was discussed at the end of the 2026-04-30 session but **not yet
implemented**. User explicitly asked to not rush it. Next session
picks up here.

### Why we want this

The current design embeds `agent_run_id` in every `MS` and `ME`
record (16 bytes each, 32 bytes per call). This was Phase 0 decision
#1 — picked at the time for simplicity. Subsequent review surfaced
that this design causes:

- **D-01** (lost RH → all calls reference a non-existent agent_run).
  Currently Medium severity; would drop to Low under transport-layer
  carrying.
- **Pre-RH calls problem** — if the processor sees calls before any
  RH (cold start, Kafka rebalance, partial replay), it can't attribute
  them. Forces sentinel/buffer/drop trade-offs, all of which are
  band-aids on a wrong-data-model.
- **Per-call wire overhead** — 32 bytes × every call. Real cost at
  high call volumes.

### The architecturally clean answer

`agent_run_id` is **metadata about who produced the data**, not
content of the data. It belongs in the envelope (HTTP/Kafka headers,
file metadata), not in the records.

```
Agent → Collector:
  HTTP POST /records
  Headers: X-Deepflow-Agent-Run-Id: <uuid>
  Body: <wire records, no agent_run_id anywhere>

Collector → Kafka:
  Producer record:
    headers: [agent_run_id = <uuid>]
    value: <wire records, unchanged>

Processor → ClickHouse:
  reads Kafka header on each message → stamps every ParsedCall
  in that message

File destination:
  agent_run_id encoded into filename (or in the existing RH-at-top-of-file).
  Reader latches once per file.
```

### Consequences

- No pre-RH problem (every message carries the header by construction).
- No latch state in the processor for attribution.
- `RH` becomes purely metadata (hostname / version / env). Losing RH
  drops rows from `agent_runs` but **does not corrupt calls**.
- MS and ME wire records shrink by 16 bytes each.
- D-01 drops from Medium to Low.
- Reverses Phase 0 decision #1 (real reversal — needs SCHEMA_DESIGN.md
  update with the new rationale).

### Scope (estimated)

- **Agent** — `HttpDestination` adds `X-Deepflow-Agent-Run-Id` header
  on every POST. `MethodStartRecord` and `MethodEndRecord` lose the
  `agentRunId` field (wire format change → bump VERSION_MINOR).
  `RecordWriter.initAgentRunId(...)` and the static field become dead.
- **Collector** — read the HTTP header on each request, tag every
  Kafka record with `agent_run_id` as a Kafka header.
- **Processor** — `KafkaRecordConsumer` reads the Kafka header per
  message, passes `agent_run_id` into `RecordParser.parse()` as a
  context arg. Parser stamps it on every `ParsedCall`. Parser no
  longer reads `AI` from MS/ME (still reads it from RH for the
  `agent_runs` row).
- **File destination** — write `agent_run_id` into filename (e.g.
  `<timestamp>-<thread>-<runid>.dft`). Or document that the
  authoritative source is the RH at top of file.
- **Tests** — wire-format goldens, parser tests, integration tests.
- **Docs** — SCHEMA_DESIGN.md decision #1 reversal with new rationale.

Bigger than B-01/B-02; smaller than the original Phase 0 work.
Estimated ~250–400 lines including tests.

### Open sub-decisions (pin before coding)

1. **Backward compatibility** — current wire format has
   `agent_run_id` embedded. Drop the field outright (clean wire,
   pre-existing agents break) or keep it nullable for one release
   (transitional)? Lean: drop, deal with B-04 (version dispatch)
   together.
2. **File destination encoding** — filename embedding (`-runid.dft`
   suffix) vs. RH-at-top-of-file is the only source. Lean:
   filename, makes per-file run_id discoverable without parsing.
3. **Periodic RH re-emit** — still useful for keeping `agent_runs`
   metadata fresh, but no longer load-bearing for attribution. Keep
   as 60s default? Or one-shot at startup is enough?

---

## Index (quick scan)

| ID  | Severity | Category    | Origin       | Title |
|-----|----------|-------------|--------------|-------|
| B-01 | ~~High~~ FIXED | Correctness | New          | ~~`recordExit` null-pop emits wrong-id ME~~ — fixed 2026-04-30 |
| B-02 | ~~High~~ FIXED | Correctness | New          | ~~Double CI in entry block silently leaks builder~~ — fixed 2026-04-30 |
| B-03 | ~~High~~ FIXED | Correctness | New | ~~Async-after-root undercounts `requests.call_count`~~ — fixed 2026-04-30 (Phase 5: requests is now an MV-maintained `AggregatingMergeTree`) |
| B-04 | High     | Architectural | Pre-existing | No version-aware parsing → rolling deploys mis-parse |
| B-05 | Medium   | Correctness | New          | JVM crash mid-method leaves orphan UUID on `CALL_STACK` |
| B-06 | Medium   | Correctness | New          | Failed entry on request's originating root → spurious root |
| L-01 | ~~Medium~~ FIXED | Leak | New | ~~`RecordParser.openCalls` map — never evicts~~ — fixed 2026-04-30 (TTL sweep at end of `parse()`) |
| L-02 | ~~Medium~~ FIXED | Leak | New | ~~`ClickHouseSink.openRequests` map — never evicts~~ — deleted 2026-04-30 (Phase 5: in-memory aggregator removed) |
| L-03 | ~~Medium~~ FIXED | Leak | New | ~~`ClickHouseSink.seenSessions` set — grows monotonically~~ — fixed 2026-04-30 (TTL sweep on `periodicFlush`) |
| L-04 | Low      | Leak        | New          | Long-lived thread `CALL_STACK` accumulates after B-05 |
| D-01 | ~~Medium~~ Low | Data quality | New | `agent_runs` row missing if its single insert fails — partly mitigated by Phase 4 (per-batch upsert) |
| D-02 | Medium   | Data quality | New         | `sessions.first_seen` is wrong after processor restart |
| D-03 | Low      | Data quality | New         | `ReplacingMergeTree` version uses 1-second resolution |
| D-04 | Low      | Data quality | New         | `ALTER TABLE UPDATE retain` is async — race with TTL pass |
| D-05 | Low      | Data quality | New         | `payload_size` is JSON UTF-8 byte count, not CBOR/object size |
| D-06 | Low      | Coupling    | New          | `is_exception` materialized expression hardcodes literal `'EXCEPTION'` |
| D-07 | Low      | Cosmic-ray   | New          | UUID collision (2^-128) overwrites `openCalls` entry |
| D-08 | Low      | Subtle      | New          | `runScoped` body sees swapped stack — caller's outer call invisible |
| D-09 | Low      | Data quality | New         | Same envelope can hash differently across observations of a cyclic graph (Merkle hash is direction-dependent on cycles) |
| U-01 | ~~Medium~~ FIXED | UI       | New          | ~~Tree pane stops responding to expand/collapse clicks after a few watch-row navigations~~ — fixed by replacing the global-broadcast highlight model with an imperative navigator + local-only payload viewers |
| U-02 | Low      | UI           | New          | Watch-row click correctly highlights the matching JsonTree node but the left pane does not always scroll it into view |
| A-01 | -        | Pre-existing | Pre-existing | `new Thread(r).start()` without instrumentation: no propagation |
| A-02 | -        | Pre-existing | Pre-existing | Custom executors via `CompletableFuture.runAsync` not covered |
| A-03 | -        | Pre-existing | Pre-existing | Virtual threads — should work via thread-locals, not tested |
| A-04 | -        | Pre-existing | Pre-existing | No FK enforcement; cross-table joins must tolerate missing rows |

Legend: **B**ug (correctness/wrong data), **L**eak (memory/state),
**D**ata quality (degradation/inaccuracy), **A**rchitectural (limitations),
**U**I (frontend issue in `deepflow-ui/`).

---

## Bugs

### B-01 — `recordExit` null-pop emits wrong-id ME — FIXED (2026-04-30)

**Where:** `RequestRecorder.recordExit` (core/agent).

**Was:** `recordExit` running without a matching `recordEntry` would
get null from `popCallId()` and emit ME with the all-zero sentinel.
Worse, `endRequest()` ran first and double-decremented depth.

**Fix landed:** Pop reordered to come first; bail before any state
mutation if pop returns null. Test added:
`DeepFlowAdviceRecordingTest.recordExitWithoutMatchingEntryIsSilentlyIgnored`.

---

### B-02 — Double CI in entry block silently leaks builder — FIXED (2026-04-30)

**Where:** `RecordParser.parse` — `case "CI"` in entry context.

**Was:** Two `CI` tags in one MS block would put the same builder under
two keys; only one would be matched on TE, the other leaked in
`openCalls` forever.

**Fix landed:** Guard `if (currentEntry.callId != null) break;`
ignores subsequent CI within the same entry context. Test added:
`RecordParserTest.duplicateCiInEntryBlockIsIgnoredNotLeaked` —
verifies pairing succeeds on first id and the second id is treated
as orphan (proving it never made it into the map).

---

### B-03 — Async-after-root undercounts `requests.call_count` — FIXED (2026-04-30, Phase 5)

**Where (was):** `ClickHouseSink.aggregateRequest`.

**Was:** A worker thread emitting a `ParsedCall` belonging to a
`request_id` whose root call had already closed would re-create a
fresh `RequestAggregator` via `computeIfAbsent`. The new aggregator
incremented but no root would ever arrive to close it — so it stayed
in `openRequests` forever (also feeding **L-02**) and the original
`requests` row's `call_count` was permanently under-reported.

The root cause was an upstream model error: the in-process aggregator
required the processor to know "when is the request done," which is
unanswerable in the presence of fire-and-forget async work.

**Fix landed:** Aggregation moved to ClickHouse via a materialized
view (`requests_mv` in `clickhouse-init/01-schema.sql`) that reads
every insert into `calls` and folds it into a `requests`
`AggregatingMergeTree` table via `SimpleAggregateFunction` columns.
Late async calls are folded into the rollup automatically when their
`calls` row is inserted. The processor no longer holds any per-request
state — `aggregateRequest`, `RequestAggregator`, `RequestKey`,
`requestRow`, `openRequests`, `requestBuffer`, `insertRequestsUri`
are all deleted.

Semantic shift: `requests.ended_at` is now `max(ts_out)` across all
calls in the request rather than the synchronous root's `ts_out`. For
async-using requests this is a more honest answer (the request really
isn't done until its async work finishes); for purely synchronous
requests it's identical. Read via `requests_view` for clean column
types.

---

### B-04 — No version-aware parsing → rolling deploys mis-parse

**Where:** `TraceRecord.parse` and `RecordParser`.

**Trigger:** A v1.2-format wire stream (pre-Phase-0 agent) is read by
a v1.3 processor.

**Symptom:** Old MS records lack the trailing 48 bytes of UUID fields.
The new parser reads garbage from the next record's bytes thinking
they're UUIDs, gets a wrong frame length, and corrupts the entire
batch.

**Mitigation today:** Always deploy agent + processor together. Agent
versions are not pinned anywhere, so a rolling deploy of just one side
breaks parsing.

**Fix:** Dispatch on `VersionRecord` at the start of each stream.
Either keep two parser implementations (cost: maintenance), or refuse
to parse if version doesn't match (cost: silent skip during deploy
windows). The cleaner long-term path is making the wire format
self-describing per record (TLV-style), but that's a much bigger
change.

---

### B-05 — JVM crash mid-method leaves orphan UUID on `CALL_STACK`

**Where:** `RequestRecorder.recordEntry` push + `recordExit` pop balance.

**Trigger:** `OutOfMemoryError`, `StackOverflowError`, JVM-internal
crash, or any path that lets `onEnter` complete but prevents `onExit`
from firing. The bulletproof contract handles failures *during*
`recordEntry`/`recordExit`, but not failures *between* them inside
the user code.

**Symptom:** Push happened; pop didn't. Subsequent traced methods on
the same thread see the orphan UUID as their parent. Parent linkage
for the rest of the request is corrupted. Self-heals when the JVM
stack drains to empty (top-of-request).

**Fix options:**

1. **Periodic `CALL_STACK` size sanity check** with an alarm if depth
   exceeds N. Doesn't fix, just observes.
2. **Try/finally pop in the advice itself** — but ByteBuddy `@Advice`
   doesn't easily wrap user code in try/finally without bytecode
   manipulation that may break inlining optimizations.
3. **Accept it as a known degradation** and document it. The
   compromised data is observable (parent_call_id pointing to a
   non-existent call_id in `calls`) so it can be filtered out at
   query time.

---

### B-06 — Failed entry on request's originating root → spurious root

**Where:** `RequestRecorder.recordEntry` (failed-entry contract) +
`ClickHouseSink.aggregateRequest` (root detection).

**Trigger:** The very first traced method of a request fails entry
(per the bulletproof contract, suppressed). The second call's
`peekParentCallId()` returns null (nothing was pushed), so it appears
to be the root.

**Symptom:** The `requests` row records the second call as the root.
`started_at` is later than reality. `entry_signature` is wrong.
`call_count` undercounts by 1+.

**Fix:** Hard. The agent doesn't know "first call of a request"
explicitly. Possible paths:

1. **Track `request_id → first_call_seen` set** in the parser/sink and
   only treat parent_call_id == null as root if it's the first call
   for that request_id.
2. **Always push** a sentinel in `recordEntry` even on failure (but
   then `recordExit` would pop it as if it were a real call → wrong
   ME, breaks the contract).
3. **Accept it** as a known degradation and document it. Already in
   SCHEMA_DESIGN.md.

---

## Leaks

### L-01 — `RecordParser.openCalls` map — never evicts — FIXED (2026-04-30)

**Where:** `RecordParser.openCalls`.

**Was:** A traced method whose `MS` arrived but `ME` never did (agent
crashed mid-call, network drop, etc.) left a `Builder` in the map
forever. Memory grew monotonically per leaked call, bounded only by
JVM heap.

**Fix landed:** Each `Builder` now records `openedAtMillis` (processor
wall-clock at admission). At the end of every `parse()` we run an
eviction sweep that drops entries whose admission age exceeds
`OPEN_CALL_TTL_MS` (10 minutes). The sweep is throttled to
`SWEEP_INTERVAL_MS` (60 s) so the O(n) cost is amortised. The clock
is injectable for tests via a package-private constructor.

A late `ME` for an evicted call is treated as a normal orphan and
dropped silently — same behaviour as a stray `ME` from a failed-entry
contract.

**Test added:** `RecordParserTest.staleOpenCallIsEvictedAfterTtl` —
drives a fake clock past TTL and asserts `openCallCount()` drains.

---

### L-02 — `ClickHouseSink.openRequests` map — never evicts — FIXED (2026-04-30, Phase 5)

**Where (was):** `ClickHouseSink.openRequests`.

**Was:** A request whose root never closes (or whose root closed but
later async calls re-created the entry — see B-03) would leave a
`RequestAggregator` in the map forever. Memory grew monotonically,
bounded only by the JVM heap.

**Fix landed:** The map and its surrounding aggregator code were
deleted entirely as part of the B-03 fix. There is no per-request
in-memory state in the processor anymore — `requests` is an
`AggregatingMergeTree` maintained server-side by `requests_mv`.

---

### L-03 — `ClickHouseSink.seenSessions` set — grows monotonically — FIXED (2026-04-30)

**Where:** `ClickHouseSink.seenSessions`.

**Was:** Many distinct `(agent_run_id, session_id)` pairs over a
long-lived processor instance. With short-lived sessions (e.g. one
per HTTP user session), the set grew continuously at ~64 bytes per
entry. Matters at millions of distinct sessions per processor
instance.

**Fix landed:** Replaced `Set<SessionKey>` with
`Map<SessionKey, Long>` where the value is the processor wall-clock
time at admission. `periodicFlush` (which already runs every second)
calls `evictStaleSessions` first, dropping entries older than
`SESSION_TTL_MS` (1 hour). The sweep is throttled to
`SESSION_SWEEP_INTERVAL_MS` (5 minutes). A re-emit of an evicted
session produces one duplicate `sessions` row, which the
`ReplacingMergeTree` engine collapses on the server.

---

### L-04 — Long-lived thread `CALL_STACK` accumulates after B-05

**Where:** `RequestContext.CALL_STACK` ThreadLocal `ArrayDeque`.

**Trigger:** Repeated occurrences of B-05 on a long-lived worker thread
(Tomcat, Netty event loop). Each occurrence may leave one entry
stuck.

**Symptom:** Slow growth bounded by thread liveness. Each leaked entry
also pollutes parent_call_id assignment for future calls on that thread.

**Fix:** Tied to B-05 fix. Periodic stack-size check could trigger an
emergency clear with logging.

---

## Data quality

### D-01 — `agent_runs` row missing if its single insert fails — PARTLY MITIGATED (2026-04-30 Phase 4)

**Where:** `ClickHouseSink.flushLocked` for `agentRunBuffer`.

**Was (pre-Phase-4):** The one POST to `agent_runs` for this JVM run
fails (CH down, network blip). Existing sink contract: log and discard.
Resulting in `agent_runs` permanently missing the row; joins on
`agent_run_id` would miss for every row written by this JVM run.

**Now (post-Phase-4):** `agent_runs` is upserted on **every** Kafka
batch from the headers (idempotent via `ReplacingMergeTree`). A single
CH-insert failure for one batch leaves the row not yet present, but
the next batch retries it. The window of vulnerability shrinks from
"rest of JVM lifetime" to "until the next batch lands."

**Residual:** If the JVM emits one batch and dies before any subsequent
batch, that one failed insert is the only chance — we still lose the
row. Mitigation if needed: retry agent_runs inserts with bounded
backoff (small per-row cost, idempotent so safe).

---

### D-02 — `sessions.first_seen` is wrong after processor restart

**Where:** `ClickHouseSink.noteSessionIfNew`.

**Trigger:** Processor restarts. `seenSessions` is empty in memory.
The first `ParsedCall` for any session triggers a re-emit. But that
call may be the second-or-later for that session in real time
(Kafka backlog replay).

**Symptom:** `first_seen` set to "first call seen by this processor
instance," not the actual first call. `ReplacingMergeTree` picks the
latest insert (highest `inserted_at`), so `first_seen` ends up =
first-call-seen-by-the-most-recent-processor. Not actual first call.

**Fix:** Use `MIN(first_seen)` aggregation server-side — but
`ReplacingMergeTree` doesn't aggregate. Alternative: switch to
`AggregatingMergeTree` with `minState(first_seen)` /
`maxState(last_seen)` — different query model. Or: accept the
degradation.

---

### D-03 — `ReplacingMergeTree` version uses 1-second resolution

**Where:** `agent_runs` and `sessions` use `ReplacingMergeTree(inserted_at)`
where `inserted_at` is `DateTime` (1-second resolution).

**Trigger:** Two processors emit the same key within 1 second. Multi-
processor deployments.

**Symptom:** Arbitrary winner at merge time. Single-processor (today)
is unaffected.

**Fix:** Change `inserted_at` to `DateTime64(3)` or `DateTime64(6)`,
or use a UInt64 sequence number column for versioning.

---

### D-04 — `ALTER TABLE UPDATE retain` is async — race with TTL pass

**Where:** Phase 3 retention escape mechanism.

**Trigger:** User runs `ALTER TABLE ... UPDATE retain = true` to
promote a session/call to long retention. ClickHouse mutations are
async — there's a window between SQL submission and on-disk effect
during which the next TTL pass can still delete the row.

**Symptom:** Retain promotion may not stick if a TTL pass races it.
Probabilistic failure.

**Fix:** Run `OPTIMIZE TABLE ... FINAL` after the UPDATE to force
mutation completion. Or: handle this via the deferred "retain at
insert time" path (set `retain=true` from the start so no UPDATE is
needed).

---

### D-05 — `payload_size` is JSON UTF-8 byte count, not CBOR/object size

**Where:** `ClickHouseSink.payloadRow` — `json.getBytes(UTF_8).length`.

**Trigger:** Always.

**Symptom:** Naming is misleading. A user querying "find calls with
huge payloads" gets the JSON-bloat-inflated proxy, not the original
object size in memory or on the wire.

**Fix:** Either rename to `payload_json_size_bytes` for clarity, or
also capture the original CBOR size at the agent and ship it through.

---

### D-06 — `is_exception` materialized expression hardcodes `'EXCEPTION'`

**Where:** `calls` table DDL — `is_exception MATERIALIZED return_type = 'EXCEPTION'`.

**Trigger:** If `return_type` enum is ever extended (e.g., adding
`SUSPENDED` for coroutines, `CANCELLED` for futures), `is_exception`
silently returns false even for cases that morally should be
exceptional.

**Symptom:** Future extension footgun. Coupled to one literal.

**Fix:** Accept the coupling for now (it's documented), or rebuild the
expression as a `multiIf` with a comment listing all the values.

---

### D-07 — UUID collision overwrites `openCalls` entry

**Where:** `RecordParser.openCalls.put(callId, builder)`.

**Trigger:** `UUID.randomUUID()` produces the same UUID twice. Probability
2^-128. In practice never.

**Symptom:** Second `put` overwrites first. First call is lost from
the map; its eventual ME is treated as orphan.

**Fix:** Not worth fixing. Documented for completeness.

---

### D-08 — `runScoped` body sees swapped stack — caller's outer call invisible

**Where:** `RequestContext.runScoped` — `CALL_STACK.set(workerStack)`.

**Trigger:** A worker thread is currently inside a traced method
(its `CALL_STACK` non-empty), AND the body of that method calls a
nested `runScoped` (e.g., further async submission). During the body,
the worker's "outer" call is invisible.

**Symptom:** Calls inside the body have parent = seeded UUID, not the
outer call's UUID. Almost certainly the right semantic for async
(the seeded UUID *is* the logical parent across the boundary), but
it's a subtle behavior that could surprise.

**Fix:** None needed — this is the intended behavior for async parent
linkage. Documented for clarity.

---

### D-09 — Cyclic graphs: same envelope hashes differently depending on traversal entry

**Where:** `core/codec/Hasher.java` (Merkle walk) +
`core/codec/envelope/EnvelopeSerializer.java` (cycle marker emission).

**Trigger:** Any object graph with a cycle (e.g. JPA bidirectional
`Author` ⇌ `Book`). Two traces of the same logical state can produce
different envelope hashes.

**Cause:** When a cycle is closed, `EnvelopeSerializer` emits
`{ref_id: <id-of-the-already-seen-object>, cycle_ref: true}`. The id
inside that marker — and the *position* where the marker lands —
depends on which side of the cycle was the traversal root. The Merkle
hash incorporates that marker, so the asymmetry propagates upward.

Concretely, with `Author#1 ⇌ Book#5`:
- Root = Author → cycle marker lands inside Book as `{ref_id: 1}`.
- Root = Book → cycle marker lands inside Author as `{ref_id: 5}`.

Author's hash (or Book's) ends up direction-dependent.

**Symptom in the UI:** The watch panel shows a "hash transition" when
two methods observed the same object from opposite sides of a cycle,
even though no field actually changed. False positive in the change
detection.

**Fix:** None planned. The principled fix would be to drop the
inline-children optimization in `Hasher.walkMap` and treat every child
envelope as `{ref_id: id}` — but that downgrades the project's core
property: a single Merkle hash compare detects any change anywhere in
a subtree. We accept the cyclic-graph edge case as the cost of keeping
the Merkle property for the common (acyclic) case.

**Mitigation in clients:** When inspecting a transition flagged on a
cyclic object, verify the field-level diff manually before trusting it
as a real change.

#### Follow-up — practical case 2026-05-05 and proposed UI fix

Working on the demo, watching `BookEntity #193` in session
`01FAFCB8161E242D9CBD36F64B1C57B8`, the watch panel showed four
distinct deep hashes for what seemed to be the same instance:
`dd3deb9c`, `7e0950ec`, `fe0d7f3c`, `9279c45c`.

Decomposing the 13 appearances by Book#193's *own* fields revealed
the four hashes as the cross product of **two cycle entry directions
× two real ISBN states**:

| State (Book's own isbn)   | Entry direction                     | Hash       |
|---------------------------|-------------------------------------|------------|
| `978-0-618-39111-3`       | Book root, Author nested            | dd3deb9c   |
| `978-0-618-39111-3`       | Author root, Book nested            | 7e0950ec   |
| `9780618391113`           | Book root, Author nested            | fe0d7f3c   |
| `9780618391113`           | Author root, Book nested            | 9279c45c   |

The dashes are stripped at `seq=108` by
`LibraryDAO.normalizeIsbns(...)` — a real mutation. So 2 of the 4
transitions are honest signal, 2 are cycle-direction noise.

**Why the existing `↺ skip` UX hides the real mutation too:**
the watch panel skips *every* appearance whose snapshot contains any
cycle marker anywhere. When the watched envelope is itself in a
bidirectional cycle (Book ↔ Author is JPA-typical), every snapshot
has a cycle marker somewhere inside, so every row is skipped — and
the real ISBN change disappears along with the cycle noise.

**Proposed fix (UI only, no source change):**
add a second column to the watch panel for instance watches — call
it *own-state* — computed client-side from each snapshot. It is the
envelope's own scalar fields plus the **ids** of its referenced
children (refs collapsed, no Merkle rollup). Book#193's own-state
changes only when Book's own fields change, and is invariant under
cycle-entry direction and sibling envelope mutations.

Show both columns. Drop the blunt `skipped` mode.

```
hash       own-state
─────────────────────────
dd3deb9c   abc1234     ← hash A, state A
7e0950ec   abc1234     ← hash B, state A   (cycle noise: hash moved, state didn't)
fe0d7f3c   def5678     ← hash C, state B   (real mutation surfaces)
9279c45c   def5678     ← hash D, state B   (cycle noise on the new state)
```

The deep hash keeps the "did anything in the subtree change" semantic
(the project's stated value-prop). The own-state hash answers "did
*this* object's own data change", which is the question the user is
usually asking when tracking an instance through layers.

User left this open at end of session 2026-05-05; pick up here.

---

## UI

### U-01 — Tree pane stops responding after several watch-row navigations — FIXED

**Resolution:** the underlying architecture was the bug. The watch-row
click set a global `highlight` ref that every mounted `JsonTree`
listened to via deep prop chains, with reactive watchers that
auto-expanded matching nodes. With several open calls and deep
payloads, the per-click recompute fanout grew faster than Vue could
flush, eventually starving the toggle hit-tests.

**Fix (architectural reframe):**

- The page now treats a request as an addressable timeline of frames
  keyed by the `seq` ordering primitive. Navigation is imperative:
  `SessionDetailView` keeps a `frameRefs[callId] → FrameCard` map and a
  single `goto({callId, kind, path})` entry point that walks
  `frame.revealAt(kind, path)` → `payloadViewer.revealPath(path)`. Only
  the targeted component is touched per click; nothing else on the
  page reacts.
- `PayloadViewer` is the sole owner of expansion state for its tree
  (a `Set` of JSON-stringified path keys). User toggle and imperative
  reveal both mutate the same set — no two sources of truth fighting.
- `JsonTree` is now a dumb recursive renderer: it receives an
  `isExpanded(key)` callback and emits `toggle(key)` / `pin` /
  `follow-cycle`. No `highlight` prop, no `containsMatch`/`isMatch`
  computeds, no auto-expand watchers, no provide/inject of reactive
  refs.
- Highlight is pure DOM: `revealPath` queries `[data-path="…"]` after
  Vue commits, adds a `.flashed` class. No reactive cascade.
- Cycle-ref chip resolution now lives inside the `PayloadViewer` —
  the cycle target is by definition an ancestor in the same payload,
  so a local `findPathToObjectId(data, targetId)` walk suffices; no
  id-based highlight fallback at the global level.

**Why this also helps future products on the schema side:** the same
addressing model — `(seq, kind, path)` — composes cleanly into URLs,
which means "share me a link to that exact field at that exact
moment" becomes trivial when we add router state for it.

---

### U-02 — Left pane sometimes doesn't scroll to the highlighted node

**Where:** `deepflow-ui/src/components/JsonTree.vue`
(`scrollSelfIfMatching` — the `watch(isMatch, …)` and `watch(navTick, …)`
post-flush handlers) and the surrounding navigation flow in
`SessionDetailView.vue` / `PayloadViewer.vue`.

**Symptom:** clicking a watch row whose target is deep in the tree
(e.g. one of the later appearances of `BookEntity #193`) correctly
applies the `.flashed` highlight class to the matching JsonTree
node, **but** the left pane does not scroll the node into view —
the user has to scroll manually to find the amber-outlined row.
The watch panel's "current row" highlight is correct in lockstep,
so the navigation address is right; only the scrollIntoView side
is failing.

**Reproduction:**
1. Pick the demo session, pick a request that mutates a tracked object
   (e.g. session containing `BookEntity #193` from earlier).
2. Pin `BookEntity #193` as a watch.
3. Click the watch's last appearance row (deep in the tree).
4. Observe: row on the right turns amber, target node has `.flashed`
   on the left, but the visible scroll position of the left pane
   is unchanged.

**What was tried:**
- Two cooperating watchers (`watch(isMatch, …, immediate, post)` plus
  `watch(navTick, …, post)`) so re-clicks and freshly-mounted matches
  both run a scroll attempt.
- Switched the scroll target from `.jt-node` (whose bounding box is
  the entire expanded subtree, often thousands of pixels tall) to
  `.jt-row` (the visible row inside the node). User reports the issue
  persists.

**Suspected causes to investigate:**
- `scrollIntoView` may resolve to the wrong scrollable ancestor.
  PrimeVue's Splitter inserts wrapping divs around the SplitterPanel;
  the actual scroll container may differ from what `:deep(.left-pane)`
  CSS sets `overflow-y: auto` on. Walk up `parentElement` from the
  target with `getComputedStyle().overflowY` to confirm which element
  is doing the scrolling.
- Post-flush may fire before the deepest recursive levels mount, so
  `nodeRef.value` is the right element but its position in layout is
  premature; subsequent renders push it. Adding a
  `requestAnimationFrame` (or two) before the `scrollIntoView` call
  may help — wait for layout to settle.
- The matched element may be technically "in viewport" of the scroll
  container at the time of the call (so `scrollIntoView` becomes a
  no-op) yet visually obscured by another stacking context. Replace
  `scrollIntoView` with explicit `container.scrollTop = …` arithmetic,
  computing the target offset relative to the scroll container.

**Workaround for now:** the user can scroll manually; the highlight
class is correct so the target is findable visually once on screen.

**Mitigation in clients:** none required for now — both panes show
the selection consistently; only the auto-scroll convenience is
missing.

---

**Where:** `deepflow-ui/src/components/JsonTree.vue` and
`deepflow-ui/src/views/SessionDetailView.vue`.

**Symptom:** In the session view, after pinning a watch and clicking
on a few rows in the right pane, the JSON tree on the left stops
responding to expand/collapse clicks. The view sometimes renders an
empty container area where children should be. Occasionally a click
"kicks in" later. The condition accumulates across several jumps and
recovers irregularly.

**Reproduction:**
1. Open any session, pick a request.
2. Expand a call, hover an envelope or field, click ⊕ watch.
3. In the right pane, click several rows of that watch in succession.
4. After 3–5 clicks, expand/collapse on the left tree no longer reacts.

**Suspected cause:** Cascading reactive recomputes through the
recursive `JsonTree` component. Every watch-row click changes
`highlight`, which forces `containsMatch`/`isMatch` to re-evaluate at
every node along the path, which in turn auto-expands prefix nodes,
mounts new descendants, and re-runs their setup. The watch on
`containsMatch` (with `immediate: true`) re-fires on each new mount.
With several open calls and deep payloads, the update queue grows
faster than Vue can flush, and pointer/click events are missed.

**What was tried (didn't fix):**
- Replaced `provide`/`inject` for the envelope context with an
  explicit `:envContext` prop (same shape, more direct propagation).
- Memoised payload parsing — `tryParse(payload_json)` now runs once
  at fetch time; `:data` keeps a stable identity across renders.
- Removed an inline `:ref` callback that re-triggered
  `scrollIntoView({behavior: 'smooth'})` on every render.
- Set `pointer-events: none` on the invisible ⊕ watch button so it
  no longer eats clicks at the right edge of rows.

**Likely next steps:**
- Profile re-render counts per JsonTree on a watch-row click.
- Drop the `containsMatch` auto-expand watch and require the user to
  expand manually; or replace it with a one-shot expansion driven from
  the parent (`SessionDetailView.jumpToCall` walks the path and sets
  expanded flags on a small, known set of nodes instead of having
  every node react globally).
- Consider switching the recursive renderer to a flat virtualised
  list — the current approach instantiates one Vue component per
  node, which scales poorly with deep payloads and many open calls.

---

## Architectural / pre-existing

### A-01 — `new Thread(r).start()` without instrumentation

**Where:** Whatever code uses raw `Thread` instead of an executor.

**Symptom:** Worker thread has empty `CALL_STACK`, fresh
`request_id`. Call tree breaks at the boundary. Same gap as
pre-Phase-0 code.

**Fix:** Instrument `Thread.start` — possible but invasive.

---

### A-02 — Custom executors via `CompletableFuture.runAsync(r, customExecutor)`

**Where:** Only `ThreadPoolExecutor` and `ForkJoinPool` are
instrumented. A user-written executor is invisible.

**Fix:** Instrument the `Executor` interface itself — but that hits
many unintended classes. Or document the supported set.

---

### A-03 — Virtual threads

**Should work** via normal thread-locals on the carrier, but not
explicitly tested. Confidence: medium.

**Fix:** Add a smoke test using `Thread.ofVirtual()`.

---

### A-04 — No FK enforcement; cross-table joins must tolerate missing rows

**Where:** All table relationships in the ClickHouse schema.

**Symptom:** Pre-existing; ClickHouse doesn't enforce FKs at all.
Already true for `calls` and `payloads`; we just expanded the
relationship surface area.

**Fix:** Document the join semantics for each query path.
LEFT JOIN by default to tolerate missing rows.

---

## Suggested next fixes

Ordered by ratio of (correctness improvement) / (effort):

1. ~~**B-01** — null-pop guard in `recordExit`. 2-line change.~~ DONE
2. ~~**B-02** — duplicate-CI guard in parser. 2-line change.~~ DONE
3. ~~**L-03** — TTL sweep on `seenSessions`.~~ DONE (2026-04-30)
4. ~~**B-03** — `requests` as MV-maintained `AggregatingMergeTree`.~~ DONE (2026-04-30, Phase 5)
5. ~~**L-01 + L-02** — TTL eviction for in-memory state.~~ DONE
   (L-02 deleted in Phase 5; L-01 fixed via TTL sweep at end of `parse()`).
6. **B-04** — version dispatch in `TraceRecord.parse`. Dismissed for now —
   agent + processor ship as one product, so a wire-format change can
   land in lockstep. Revisit only if external consumers of the wire
   appear.

The remaining items (B-05/B-06, D-*, A-*) are either documented
trade-offs or beyond a single-sitting fix.
