# Deployment Modes

DeepFlow ships as a single agent with a clean separation between four
concerns: **capture**, **transport**, **storage**, and **query**. The
agent (capture) is fixed; the other three layers are swappable. That
gives three concrete deployment configurations, each suited to a
different audience.

| Mode | Storage | Writers | Scale | Audience |
|---|---|---|---|---|
| **Local** | in-memory `Map` | 1 (one JVM, file tail) | one developer's session | Solo dev, demo, plugin |
| **Embedded** | DuckDB file | 1 (file tail or single HTTP intake) | one team, GB-scale, persistent | Single-app team, audit-lite |
| **Distributed** | Kafka + ClickHouse | many (multi-host prod ingress) | TB-scale, replicated | Multi-service prod, regulated audit, AI-era observability, SaaS |

Only the distributed mode currently ships. The local and embedded modes
are well-defined extension points; see [Contributing](../CONTRIBUTING.md)
if you want to build one.

## The agent is the substrate

Every deployment mode shares the same producer: the Java bytecode
instrumentation agent. It writes one of two destinations:

- `destination=file` — per-thread `.dft` files under
  `<session_dump_location>/SESSION-<timestamp>/`. Already rendered to
  tag-line text and Merkle-hashed at write time.
- `destination=http` — POST batched binary records to a collector.

Both `.dft` files and HTTP batches encode the same wire format (see
[spec/SPEC.md](spec/SPEC.md)). Any deployment mode is a question of
*what reads that output*, not what produces it.

## Distributed mode (current)

```
agent --HTTP--> collector --Kafka--> processor --HTTP--> ClickHouse
                                                              |
                                                          query API
                                                              |
                                                          deepflow-ui
```

Production-grade. Designed for:

- **Multi-host capture.** N agents in N pods all post to one collector.
- **Burst absorption.** Kafka decouples agent throughput from store
  ingest. A slow ClickHouse never back-pressures the application's hot
  path.
- **Replicated, retention-policied storage.** ClickHouse handles TB-scale
  data with multi-year retention and TTL. Compliance-grade.
- **Indexed queries at scale.** `payload_tokens` and `object_ids` bloom
  filters return value/identity searches in seconds at billions of rows.
- **Multi-consumer fan-out.** Audit + analytics + alerting + LLM agents
  can all read the same Kafka topic without contending.

This is the only mode that satisfies regulated-industry audit
requirements, multi-service production debugging, AI-era observability
at fleet scale, and the SaaS path. See
[architecture.md](architecture.md) for the data flow in detail.

## Embedded mode (DuckDB) — extension point

```
agent --file--> .dft files --tail--> RecordParser --insert--> DuckDB file
                                                                    |
                                                                query API
                                                                    |
                                                                deepflow-ui
```

DuckDB is an embedded analytical SQL engine: columnar, vectorized,
supports the same analytical queries ClickHouse does, persists to a
single file, runs in-process. For analytical queries over GB-scale
trace data, it is "ClickHouse without the cluster."

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
                                                                deepflow-ui
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
| Agent (`core/agent`, `core/codec`, `core/serializer`, `core/record-format`) | yes |
| `.dft` file format | yes (already rendered + Merkle-hashed at write time) |
| `RecordRenderer`, `RecordHashEnricher` | yes (already applied at write) |
| `RecordParser` (UUID-keyed MS↔ME pairing) | yes |
| `ParsedCall`, `AgentRunMetadata` types | yes |
| `ObjectIdCollector`, `ScalarTokenCollector` | yes (pure JSON walks) |
| `deepflow-ui` (Vue app) | yes (consumes a stable JSON API contract) |

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

## Choosing a mode

| If you need... | Use |
|---|---|
| Try DeepFlow on one app, no Docker | Local |
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
[../CONTRIBUTING.md](../CONTRIBUTING.md) for what already exists, what
needs to be built, and how the seams are designed.

The cloud / distributed mode is the current focus of the maintainers.
