# Processor Server

`server/record-processor-server` is the workshop: it pulls binary
frames out of Kafka, decodes them to JSON, walks the JSON to
inject content hashes, pairs method-start frames with their
matching method-end frames, and inserts the result into
ClickHouse.

For the agent's wire frames that arrive here, see
[record-format.md](record-format.md). For the high-level
pipeline shape (agent → CH), see
[../architecture.md](../architecture.md).

## Pipeline shape

For each Kafka record:

```
KafkaRecordConsumer.pollLoop()
  └─ processRecord(record)
       ├─ RecordRenderer.render(record.value())     # CBOR → JSON per TI/AR/AX/RE value
       ├─ RecordHashEnricher.enrich(rendered)       # walk JSON, inject __meta__
       ├─ AgentRunMetadata.from(record.headers())   # X-Deepflow-* → AgentRunMetadata
       └─ sink.accept(enriched, headerMetadata)
            └─ ClickHouseSink                       # buffered INSERT JSONEachRow
                 ├─ RecordParser.parse(...)         # pair MS↔ME by call_id → ParsedCall
                 ├─ ObjectIdCollector.collect(...)  # walk hashed JSON → object_ids[]
                 ├─ ScalarTokenCollector.collect()  # walk hashed JSON → payload_tokens[]
                 └─ flush() every 1 s or 500 rows
```

The renderer (`RecordRenderer`) and hash enricher
(`RecordHashEnricher`) live in `core/serializer`, not the
processor module — the file destination uses the same code to
produce `.dft` files. One implementation, two deployment paths.

## Entry point

```
RecordProcessorServer.main()
  ├─ ProcessorConfig.load(args)
  ├─ createSink(config)
  │    └─ "clickhouse" → ClickHouseSink
  │       "logging"    → LoggingSink            # opt-in, stdout for debugging
  ├─ KafkaRecordConsumer(config, sink)
  └─ consumer.pollLoop()                        # blocks until shutdown
```

Shutdown hook calls `consumer.shutdown()` (wakeup the poller),
then `consumer.close()` (closes the consumer and the sink).

## Kafka consumer

`KafkaRecordConsumer`:

- One consumer subscribed to `deepflow-records` (or whatever
  `kafka_topic` is set to).
- `auto.offset.reset=earliest`, `enable.auto.commit=true`.
- 500 ms poll timeout.
- Single-threaded poll loop — no per-thread fanout. The work
  itself (render + hash + parse + insert) is fast enough at
  expected load that one consumer keeps up. If it doesn't, scale
  horizontally by running more processor instances with the same
  consumer group.

`extractAgentRun(record.headers())` lifts the seven
`X-Deepflow-*` Kafka headers (set by the collector) into an
`AgentRunMetadata` record. A batch with a missing or unparseable
`agent_run_id` header returns null `AgentRunMetadata`, and the
sink drops it with an error log — see
[../spec/TRANSPORT.md](../spec/TRANSPORT.md) for the rationale.

## Why stateful UUID-keyed pairing

`RecordParser` pairs each `TS` (entry) with its matching `TE`
(exit) **by `call_id` UUID**, not by stack ordering.

The previous implementation used a method-local
`ArrayDeque`-as-stack discarded when `parse()` returned. That had
a latent bug: a request whose `TS` arrived in Kafka batch N and
matching `TE` arrived in batch N+1 was silently dropped — the
in-flight builder sat in the local stack and was thrown away.
Multi-thread interleaving in one batch also pretended to share
one stack, mispairing across threads.

The current implementation keeps a `Map<UUID, Builder> openCalls`
that **persists across `parse()` invocations**, so a late `TE` can
find its `TS` no matter which batch each lived in. Multi-thread
interleaving works because every call is uniquely addressable by
id.

### TTL eviction prevents leaks

A `TS` whose `TE` never arrives (agent crashed mid-call, network
drop) would otherwise leak forever. At the end of every `parse()`
call, entries older than `OPEN_CALL_TTL_MS` (10 minutes) are
swept. The sweep itself is throttled to `SWEEP_INTERVAL_MS` (60 s)
so the O(n) cost is amortised across many batches. A late `TE`
for an evicted call is treated as an orphan and dropped silently.

The clock is injectable for tests — see
`RecordParserTest.staleOpenCallIsEvictedAfterTtl` which drives a
fake clock past TTL and asserts `openCallCount()` drains.

### Exit-order quirk

The agent emits exit records in this wire order: `METHOD_END`
(carries `TE`, `TN`, `RI`, `CI`), then `RETURN` / `EXCEPTION` (the
`RT` / `RE` payload), then `ARGUMENTS_EXIT` (the `AX` payload).
So on the wire, `TE` comes **before** the call's own `RT` / `RE` /
`AX`.

The parser tracks "exit context" after a `TE`: subsequent
`RT` / `RE` / `AX` tags attach to the just-closed call's builder
until the next `TS` or `TE` resets context.

## ClickHouseSink

Inserts batched rows into `deepflow.calls`, `deepflow.payloads`,
and `deepflow.agent_runs` via the ClickHouse HTTP `INSERT
JSONEachRow` format.

- **Flush triggers**: every 1 s on a scheduled tick, or when the
  in-memory buffer reaches 500 rows.
- **Best-effort inserts**: a failure logs to stderr and discards
  the batch. The `requests` rollup table is *not* written by the
  sink — it's an `AggregatingMergeTree` maintained server-side by
  the `requests_mv` materialized view, which folds every
  `calls`-insert into the rollup. This eliminated the in-memory
  per-request aggregator (and the async-after-root undercount it
  caused — see bug B-03 in [../process/KNOWN_BUGS.md](../process/KNOWN_BUGS.md)).
- **Session deduplication**: `seenSessions` is a
  `Map<SessionKey, admittedAtMs>` with TTL eviction. A re-emit of
  an evicted session produces one duplicate `sessions` row, which
  the `ReplacingMergeTree` engine collapses server-side. Sweep
  cadence: every 5 minutes, drop entries older than 1 hour.
- **Agent-run upsert per batch**: every batch re-issues the
  `agent_runs` row idempotently via `ReplacingMergeTree`. A single
  CH-insert failure isn't fatal because the next batch retries it.

The two columns derived at insert time:

- `payloads.object_ids: Array(Int64)` — collected by
  `ObjectIdCollector.collect()` walking the hashed JSON for every
  `__meta__.id`. Bloom-filter indexed for "find every call that
  touched instance N".
- `payloads.payload_tokens: Array(String)` — collected by
  `ScalarTokenCollector.collect()`, every distinct canonicalized
  scalar in the payload tree (strings, numbers, booleans).
  Bloom-filter indexed for value-search and provenance lookups.

The schema DDL is in `server/clickhouse-init/01-schema.sql`.

## Alternative sink: LoggingSink

Opt-in via `sink_type=logging` in `ProcessorConfig`. Prints every
`ParsedCall` to stdout. Used for end-to-end debugging when
ClickHouse isn't part of the loop — useful for testing the agent
→ collector → Kafka segment in isolation.

## Source files

`server/record-processor-server/src/main/java/com/github/gabert/deepflow/processor/`

- `RecordProcessorServer.java` — entry point, wires the sink
- `ProcessorConfig.java` — Kafka config + sink_type
- `KafkaRecordConsumer.java` — poll loop, header extraction
- `RecordSink.java` — `accept(Result, AgentRunMetadata)` interface
- `ClickHouseSink.java` — buffered HTTP inserts, periodic flush
- `LoggingSink.java` — stdout sink, opt-in
- `RecordParser.java` — UUID-keyed `MS`↔`ME` pairer with TTL
- `ParsedCall.java` — value class output by the parser
- `AgentRunMetadata.java` — record built from Kafka headers
- `ObjectIdCollector.java` — walks hashed JSON for `object_ids[]`
- `ScalarTokenCollector.java` — walks hashed JSON for
  `payload_tokens[]`
