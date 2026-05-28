# Deployment Modes

Arachna Trace ships as a single agent with a clean separation between four
concerns: **capture**, **transport**, **storage**, and **query**. The
agent (capture) is fixed; the other three layers are swappable. That
gives three concrete deployment configurations, each suited to a
different audience.

| Mode | Storage | Writers | Scale | Audience |
|---|---|---|---|---|
| **Local** | in-memory `Map` | 1 (one JVM, file tail) | one developer's session | Solo dev, demo, plugin |
| **Embedded** | DuckDB file | 1 (file tail or single HTTP intake) | one team, GB-scale, persistent | Single-app team, audit-lite |
| **Distributed** | Kafka + ClickHouse | many (multi-host prod ingress) | TB-scale, replicated | Multi-service production, audit-shaped use, multi-tenant deployments |

Only the distributed mode currently ships. The local and embedded modes
are well-defined extension points; see [Contributing](../../../CONTRIBUTING.md)
if you want to build one.

## The agent is the substrate

Every deployment mode shares the same producer: the Java bytecode
instrumentation agent. It writes one of two destinations:

- `destination=file` — per-thread `.dft` files under
  `<session_dump_location>/SESSION-<timestamp>/`. Already rendered to
  tag-line text and Merkle-hashed at write time.
- `destination=http` — POST batched binary records to a collector.

Both `.dft` files and HTTP batches encode the same wire format (see
[spec/SPEC.md](../../../spec/SPEC.md)). Any deployment mode is a question of
*what reads that output*, not what produces it.

## Distributed mode (current)

```
agent --HTTP--> collector --Kafka--> processor --HTTP--> ClickHouse
                                                              |
                                                          query API
                                                              |
                                                          arachna-trace-ui
```

Used as the production reference today. Shaped around:

- **Multi-host capture.** N agents in N pods all post to one collector.
- **Burst absorption.** Kafka decouples agent throughput from store
  ingest. A slow ClickHouse does not back-pressure the application's hot
  path.
- **Replicated, retention-policied storage.** ClickHouse handles TB-scale
  data with multi-year retention and TTL.
- **Indexed queries at scale.** `payload_tokens` and `object_ids`
  bloom-filter indexes turn value and identity lookups into indexed
  probes rather than scans over JSON text.
- **Multi-consumer fan-out.** Audit + analytics + alerting + LLM agents
  can all read the same Kafka topic without contending.

Today the distributed mode is the only one shipped, so any deployment that
requires multi-host capture or replicated storage uses it. See
[architecture.md](../../../doc/architecture.md) for the data flow in detail.

## Embedded mode (DuckDB) — extension point

```
agent --file--> .dft files --tail--> RecordParser --insert--> DuckDB file
                                                                    |
                                                                query API
                                                                    |
                                                                arachna-trace-ui
```

DuckDB is an embedded analytical SQL engine: columnar, vectorized,
supports the same analytical queries ClickHouse does, persists to a
single file, runs in-process. It runs the same analytical queries
in-process without a separate server.

Suited to:

- One team, one application, one server
- Persistent trace history without a Kafka / ClickHouse cluster
- Cross-session analytics: "every mutation of `book.price` across the
  last 7 days," "all sessions where `validateOrder` threw"
- Audit-lite use cases that need persistence but not replication

Does **not** satisfy:

- Multi-host concurrent writers (DuckDB tolerates one writer well)
- Replication / HA (single-host)
- TB-class data with multi-year retention
- Production back-pressure isolation (no queue between agent and store)

The existing `QueryHandler` SQL is mostly portable to DuckDB with light
dialect tweaks, so this mode reuses the most code.

## Local mode (in-memory) — extension point

```
agent --file--> .dft files --tail--> RecordParser --insert--> Map<...>
                                                                    |
                                                                query API
                                                                    |
                                                                arachna-trace-ui
```

Pure in-memory store, ~50 MB RAM at the scale of one developer's
session. Suited to:

- Solo developer debugging one application on one laptop
- Demo / first-impression / "see what this thing does" without Docker
- IDE plugin packaging (IntelliJ, VS Code, etc.) — the JAR sits inside
  the plugin, the UI sits inside an embedded browser

Does **not** satisfy:

- Persistence across restarts (in-memory only)
- Anything beyond a single dev's working session in scale
- Anything in the embedded or distributed mode columns above

## What is shared across modes

The agent and the postprocessing logic are bit-for-bit reusable:

| Component | Shared across all modes |
|---|---|
| Agent (`arachna-trace-agents/jvm/core/agent` + `core/serializer`, on top of `arachna-trace-shared/`) | yes |
| `.dft` file format | yes (already rendered + Merkle-hashed at write time) |
| `RecordRenderer`, `RecordHashEnricher` | yes (already applied at write) |
| `RecordParser` (UUID-keyed MS↔ME pairing) | yes |
| `ParsedCall`, `AgentRun` types | yes |
| `ObjectIdCollector`, `ScalarTokenCollector` | yes (pure JSON walks) |
| `arachna-trace-ui` (Vue app) | yes (consumes a stable JSON API contract) |

Roughly 80% of the codebase is independent of storage and transport.
Building a new deployment mode means swapping two layers, not rewriting
the system.

## What changes per mode

| Layer | Local | Embedded | Distributed |
|---|---|---|---|
| Transport | file tail (`WatchService`) | file tail or single HTTP intake | HTTP collector → Kafka |
| Storage | in-memory `Map` + inverted indexes | DuckDB JDBC + persistent file | ClickHouse cluster |
| Query backend | `Map` lookups | DuckDB SQL | ClickHouse SQL |
| Query API surface | identical | identical | identical (current) |
| HTTP server | JDK `HttpServer` (localhost) | JDK `HttpServer` (localhost or LAN) | Netty (current) |

The *query API surface* — the JSON contract the UI consumes — is the
contract. Every mode must implement it; the UI does not change.

## Size limits across the pipeline — alignment contract

Trace payload size is bounded at every layer, with each layer
imposing its own ceiling. **These caps are not independent.** A
batch that fits at one layer but exceeds the next gets silently
dropped, returns 413, or fails to insert. The deployment must
align the chain top-to-bottom.

### The chain (per captured value)

For an argument, return value, or `this` payload:

| Layer | Cap | Configured by | What happens at the cap |
|---|---|---|---|
| 1. Agent capture | `max_value_size` bytes per single CBOR-encoded value | `arachna-agent.cfg` (`0` = unlimited, default) | Replaced with `{"__truncated": true, "original_size": N}` marker. Intended; lossy by design. |
| 2. CBOR encoding | No practical limit | — | CBOR lengths up to 2⁶⁴−1; never the bottleneck. |
| 3. Wire frame `payload_len` | int32 ≈ 2 GiB per record frame | — | Producer hard-fails; nobody approaches this. |
| 4. HTTP destination batch | `http_flush_threshold` bytes per POST | `arachna-agent.cfg` (default `65536`) | Flushes a POST once the buffer reaches the threshold; a single record larger than threshold still posts as one. |
| 5. Collector HTTP body | `maxContentLength` of `HttpObjectAggregator` | `ServerConfig` (collector) | Exceeds → connection drop / 413; the batch never reaches Kafka. |
| 6. Kafka `max.message.bytes` | per-record cap (broker setting) | Kafka broker / topic config (default 1 MiB) | Producer error; the batch is rejected. |
| 7. ClickHouse `String` column | No hard limit | — | Effectively unbounded; column size grows. |

### Synchronisation rule

**The ceiling at any downstream layer must be ≥ the maximum
output the previous layer can produce.** If layer 5's cap is
below layer 4's, layer 4 will produce batches layer 5 silently
rejects. Concretely: if the agent flushes at 256 KiB but the
collector's `HttpObjectAggregator` caps at 128 KiB, every large
batch is lost — and the only visible signal is a stack trace in
the collector's stderr.

Practical alignment when deploying the distributed mode:

1. Decide the **maximum tolerable per-value payload size**.
   This is your truncation budget.
2. Set `max_value_size` on the **agent** to that value. Above
   this, the truncation marker kicks in deliberately.
3. Size `http_flush_threshold` to a small multiple of
   `max_value_size` (one POST batches several records).
4. Set `HttpObjectAggregator` `maxContentLength` on the
   **collector** to **≥** `http_flush_threshold`.
5. Set Kafka `max.message.bytes` (broker / topic) and the
   collector's producer `max.request.size` to **≥** the
   collector's `maxContentLength`.
6. ClickHouse imposes no separate per-row size limit.

A sane operating envelope for the reference defaults:

```
max_value_size       = 32768 bytes  (32 KiB per value, agent)
http_flush_threshold = 65536 bytes  (64 KiB per POST, agent)
collector body cap   = 1 MiB        (HttpObjectAggregator)
Kafka max.message.bytes = 1 MiB     (broker)
```

~16× headroom above the agent's per-value cap at every
downstream layer. Captures that approach `max_value_size`
are truncated on the agent before any downstream layer sees
them, so the marker — not the raw bytes — is what flows.

### Separate structural cap: binary string fields

The wire format's three binary string fields are bounded by the
uint16 length prefix at **65 535 UTF-8 bytes**:

- `sid` — session id text
- `signature` — method signature text
- `threadName` — executing thread / coroutine name

This is structural, not data-size-driven, and applies to **every
deployment mode**. These three fields never carry user payload —
the spec routes large blobs through CBOR-payload records. In
practice they sit well under 1 KiB; producers that exceed the
limit here have an upstream bug. See
[../spec/WIRE-FORMAT.md §9](../../../spec/WIRE-FORMAT.md).

### Why this matters across components

Today the agent and collector ship from the same codebase, so
their defaults align by construction. The chain becomes a
**multi-party contract** the moment a non-Java agent, a custom
collector, a non-default Kafka broker, or a tightened CI ingress
enters the picture — and a porter who sets
`http_flush_threshold = 1 MiB` without telling the broker
operator gets silent batch drops.

Document the chosen ceiling in the deployment runbook, not just
in agent config. The chain is only as wide as its narrowest
layer.

## Choosing a mode

| If you need... | Use |
|---|---|
| Try Arachna Trace on one app, no Docker | Local |
| One team, one app, persistent history, analytical queries | Embedded |
| Multiple services / multiple hosts in production | Distributed |
| Multi-year retention, replication, compliance | Distributed |
| AI agents querying production traces at scale | Distributed |
| IDE plugin / standalone JAR distribution | Local (embedded UI) |

The modes are not in tension. They share an agent and a UI; they
target different points on the spectrum from "one developer at a
laptop" to "audit substrate for a regulated organization."

## Extension points open for contribution

Both lighter modes are well-scoped projects. See
[../CONTRIBUTING.md](../../../CONTRIBUTING.md) for what already exists, what
needs to be built, and how the seams are designed.

The cloud / distributed mode is the current focus of the maintainers.
