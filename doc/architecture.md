# DeepFlow — System Architecture

This document maps the full DeepFlow pipeline: which components produce
what, what they hand off to whom, and the contracts that hold them
together. For *what DeepFlow is and why* you'd reach for it, start at
[the overview](README.md) or the
[parent README](../README.md). This doc assumes you've been there.

> **Two architecture docs.** This one (`doc/architecture.md`) is the
> system-wide view — language-agnostic, the whole agent →
> collector → Kafka → processor → ClickHouse → UI pipeline. The
> sibling doc at
> [`deepflow-agent/docs/architecture.md`](../deepflow-agent/docs/architecture.md)
> zooms into the **agent's** internal data flow — the only
> language-specific piece. Read this one for the end-to-end
> picture; read that one for what happens inside the JVM.

---

## The pipeline at a glance

```
            ┌─────────────────────────────────────────────────────────┐
            │   Your Java application JVM                             │
            │                                                         │
            │   +--------------------+         +-----------------+    │
            │   |  application code  |  call   |  DeepFlow       |    │
            │   |  (instrumented at  |◄------► |  agent          |    │
            │   |  -javaagent time)  |         |  (in-process)   |    │
            │   +--------------------+         +-----------------+    │
            │                                          │              │
            │                                          ▼              │
            │                                   binary CBOR records   │
            │                                          │              │
            └──────────────────────────────────────────┼──────────────┘
                                                       │
                          file destination ◄───────────┤───────────► HTTP destination
                                  │                                          │
                                  ▼                                          ▼
                       SESSION-<ts>/                                ┌──────────────────┐
                         run.json                                   │   collector      │
                         <thread>.dft                               │  (Netty HTTP)    │
                       (one process,                                └──────┬───────────┘
                        local files)                                       │
                                                                           ▼
                                                              ┌──────────────────────────┐
                                                              │   Kafka                  │
                                                              │   topic: deepflow-       │
                                                              │   records (short-        │
                                                              │   retention burst        │
                                                              │   buffer)                │
                                                              └──────────┬───────────────┘
                                                                         │
                                                                         ▼
                                                              ┌──────────────────────────┐
                                                              │   processor              │
                                                              │   render → hash → parse  │
                                                              │   → ClickHouse insert    │
                                                              └──────────┬───────────────┘
                                                                         │
                                                                         ▼
                                                              ┌──────────────────────────┐
                                                              │   ClickHouse             │
                                                              │   calls / payloads /     │
                                                              │   agent_runs             │
                                                              └──────────────────────────┘
```

The agent's job ends at the destination boundary. Everything below the
HTTP destination is the centralised pipeline; everything below the file
destination is your local disk and a debugger's editor.

Both paths capture the same data. The difference is where the bytes
land and who reads them.

---

## The language boundary lives at the agent's output

DeepFlow has exactly one language-specific component: **the agent**.
Everything below the binary-record boundary — the collector, Kafka,
the processor, ClickHouse — operates on wire bytes alone. A Python
agent, a Go agent, a Rust agent, a .NET agent producing the same
bytes is a first-class citizen of the same pipeline.

```
┌────────────────────────────────────┐
│   LANGUAGE-SPECIFIC                │  ◄── only this side knows
│                                    │      what language the
│   DeepFlow agent                   │      application is in.
│   (Java is the reference;          │      Bytecode instrumentation,
│    any host language possible)     │      object-graph traversal,
│                                    │      identity tracking — all
└────────────────┬───────────────────┘      language-bound.
                 │
                 │   wire format = binary frames + CBOR envelopes
                 │                 + content hashes + transport headers
                 ▼
┌────────────────────────────────────┐
│   LANGUAGE-AGNOSTIC                │  ◄── none of this knows
│                                    │      or cares what produced
│   Collector → Kafka                │      the bytes. Same code
│   → Processor → ClickHouse         │      ingests Java traces,
│                                    │      Python traces, Go
└────────────────────────────────────┘      traces — interchangeably.
```

This boundary is positioned **as early as possible by design**. If
it were any later — say at the Kafka topic or at the processor —
those layers would be Java-specific too, and adopting DeepFlow would
mean adopting the JVM end-to-end. By making the wire format itself
the boundary, every infrastructure-heavy piece (queue, processor
logic, storage schema, query, UI) is built once and reused across
every source language.

The contract that makes this work is the wire-format spec at
[deepflow-agent/docs/spec/SPEC.md](../deepflow-agent/docs/spec/SPEC.md).
[PORTING-GUIDE.md](../deepflow-agent/docs/spec/PORTING-GUIDE.md) is
the practical walkthrough for writing an agent in another language.

---

## The components

### The agent

The producer — the language-specific side of the system (see the
section above). The reference implementation is a Java agent that
attaches at JVM startup via `-javaagent`, instruments classes you
select with a regex, and captures method entry/exit at runtime:
arguments, return values, exceptions, object identity, and
(optionally) arguments-at-exit for mutation detection. No source
changes, no annotations, no SDK.

The agent emits binary records into a configurable destination. There
are two: **file** (per-thread `.dft` files in a session directory) and
**http** (POST batched bytes to a collector). Same data either way.

### The collector

A small Netty HTTP server. It accepts POSTs from one or more agents
and forwards each request body, **byte-for-byte unchanged**, to Kafka.
The body is one or more wire-format frames; the collector does not
parse, render, or augment them. Agent-run identity (a UUID per JVM
run, plus hostname, agent version, environment label, etc.) travels
on HTTP headers and is copied verbatim to Kafka record headers — never
re-encoded into the bytes.

Single responsibility on purpose: a thin, dumb relay isolates the
network ingress from the heavier rendering work, so a slow processor
or a stalled ClickHouse never backpressures into the agent's hot
path.

### Kafka

A burst absorber, not a system of record. The `deepflow-records`
topic holds binary frames for as long as it takes the processor to
chew through them — typically minutes, not days. If the processor
goes down, frames pile up; when it comes back, they drain.

DeepFlow's storage of record is ClickHouse, not Kafka. Kafka exists
because the processor is non-trivial work (CBOR decode, Merkle hash
walk, JSON canonicalisation, INSERT batching), and trace traffic is
spiky. A queue between collector and processor decouples them.

### The processor

The work shop. For each Kafka record:

1. **Render** — decode each CBOR-encoded value (`TI`, `AR`, `AX`,
   `RE` payloads) to JSON.
2. **Hash-enrich** — walk every JSON envelope and inject a `__meta__`
   block carrying the object's stable ID, runtime class, and a
   Merkle-style content hash over the value's subtree.
3. **Parse** — pair `MS` (method start) records with their matching
   `ME` (method end) records by **call UUID** to produce one
   `ParsedCall` per invocation. Pairing by UUID, not by stack: a
   single call's start and end can land in different Kafka batches,
   from different threads, in any order.
4. **Insert** — buffer parsed calls and payloads, periodically
   `INSERT JSONEachRow` into ClickHouse.

The renderer and the hash enricher are *the same code* the file
destination uses for `.dft` output. One implementation, two
deployment paths.

### ClickHouse

The query substrate. Three tables:

- **`calls`** — one row per method invocation. Light. Columns include
  the agent-run UUID, the call's UUID, the parent call's UUID,
  session and request IDs, thread name, entry and exit timestamps,
  the formatted method signature, the caller line, the return type
  enum (`VOID` / `VALUE` / `EXCEPTION`), and the `this`-instance ID.
  Indexed for time-slice queries.
- **`payloads`** — one row per `(call, kind)` where kind is one of
  `TI`, `AR`, `AX`, `RE`. Heavy. Carries the JSON value, its content
  hash, and a bloom-filter index over the object IDs that appear
  inside it — so "find every call that touched instance `42`" is a
  single SQL predicate.
- **`agent_runs`** — one row per JVM-with-agent run. Lets you
  attribute traces to a specific deployment, host, or build SHA.

Tables are partitioned by day and TTL'd at 30 days; rows can opt out
of TTL via a `retain` flag for long-lived debug or audit data.

### The wire-format spec

Not a runtime component — a contract. Lives at
[deepflow-agent/docs/spec/](../deepflow-agent/docs/spec/SPEC.md). It
defines the binary frame layout, the CBOR identity envelope, the
Merkle hashing construction, the rendered tag-line view, and the
HTTP/Kafka/file transports that carry agent-run identity.

The Java agent in this repo is one implementation of the spec. The
processor is another. A Python agent producing the same wire bytes,
or an alternative consumer that reads from Kafka and writes to
something other than ClickHouse, would participate in the same
ecosystem without any side knowing about the other.

---

## The contracts

What's stable, what's swappable, and what an outside team would have
to honour.

### Stable contracts (don't break these)

- **The wire format** — binary frame layout, CBOR envelope shape,
  Merkle hash construction. Versioned (`major.minor`); breaking
  changes bump the major. Defined in `docs/spec/`.
- **The transport** — HTTP POST with `X-Deepflow-*` headers, Kafka
  records with the same headers copied through, a `run.json` sidecar
  for the file destination. Defined in `docs/spec/TRANSPORT.md`.
- **The ClickHouse schema** — column names and types in
  `deepflow.calls` / `payloads` / `agent_runs`. Anyone querying the
  trace store relies on these.

### Swappable

- **The agent (the only language-specific seam).** Anything emitting
  conformant wire bytes is a valid producer. The Java agent is the
  reference; a Go, Python, .NET, or Rust agent is a finite
  engineering exercise on top of the spec, not a redesign.
- **The collector** — a thin relay; replaceable with anything that
  forwards bytes plus headers to Kafka.
- **The processor sink** — the reference inserts into ClickHouse, but
  the processor is structured so that swapping in a different sink
  (a different OLAP store, an S3 archive, a Splunk-style index) is a
  module substitution.
- **The destination on the agent** — `file` and `http` ship today; a
  third (gRPC, NATS, an in-VPC sink) is straightforward to add.

### Configuration

The agent is reconfigurable per run via `deepagent.cfg`: which
classes to instrument, which tags to emit, whether to capture
arguments at all, how large a single value can grow before
truncation. None of this leaves the agent JVM.

### Extension points (SPIs)

The agent is built around two `ServiceLoader` SPIs that plug
framework-specific behaviour in at runtime:

- **`SessionIdResolver`** — given the current thread, return the
  logical session/request ID. The built-in `config` resolver reads
  a static ID from the agent config; the demo's `spring-session`
  resolver reads a Servlet session ID off a thread-local; a custom
  one might pull MDC, OpenTelemetry trace IDs, gRPC metadata, etc.
- **`JpaProxyResolver`** — given a captured value, decide if it's a
  proxy you recognise and unwrap it to its real object. The built-in
  `hibernate` resolver handles Hibernate lazy proxies and collection
  wrappers; a custom one could handle EclipseLink, OpenJPA, or any
  proxy framework.

Implementations live as separate JARs on the application's
classpath alongside the framework they wrap — never bundled into
the agent. Adding a new resolver is implement-the-interface, ship-a-
JAR, set-the-name-in-config; no agent rebuild. Adding a *new* SPI
interface (a redaction hook, a per-tenant routing hook, …) is a new
api module plus a load point in the agent's `SpiBootstrap`.

The result is that the agent core stays universal — it has no
compile-time dependency on Hibernate, Spring, Servlet containers, or
any specific framework — while still doing the right thing inside
those frameworks at runtime.

---

## Design choices that shape the product

A handful of decisions are load-bearing — the alternative would be a
materially different product.

- **Transport-layer agent identity, not per-record stamping.** The
  per-JVM-run UUID lives on HTTP / Kafka / file-sidecar metadata, not
  inside each record. A dropped or out-of-order "agent-header
  record" would otherwise orphan every subsequent call. Headers on
  every batch is robust to replays, partitions, and processor
  restarts.

- **UUID-keyed call pairing.** Each method invocation gets a fresh
  UUID at entry. The processor pairs `MS ↔ ME` by that UUID — never
  by stack ordering. This is what lets a single call's start and
  end live in different Kafka batches, different threads, even
  different processor instances, and still pair correctly.

- **Merkle content hashes.** Every captured object subtree carries a
  hash computed over its own data with each child object substituted
  by the child's hash. Same object before vs after a method call,
  same hash → no mutation. Different hash → something changed
  somewhere in the subtree. Cross-call object tracking, mutation
  detection, and dedup all fall out of this one construction.

- **CBOR on the hot path, JSON downstream.** The agent encodes once
  to CBOR (small, schema-free, fast). The processor renders to JSON
  for ClickHouse and for human reading. Decoding is push-side, not
  agent-side: the application's JVM never pays for the verbose
  representation.

- **Kafka as burst absorber, not as the system of record.** The
  topic exists to decouple ingest from processing. Short retention,
  small partitions, no compaction. ClickHouse holds the authoritative
  trace; Kafka is the airlock.

- **Zero application dependencies on the agent.** The agent JAR
  shades all of its third-party code (ByteBuddy, Jackson) under a
  private namespace, so it can attach to any application without
  version conflicts. A target app's classpath stays unchanged.

- **Capture surface is configurable per run.** An agent run can
  record everything, or just the call graph (no values), or just one
  package, or every package except utilities. Production traces and
  development traces use the same agent — they differ only in
  config.

---

## What DeepFlow is NOT

- **Not an APM.** OpenTelemetry, Datadog, New Relic capture spans at
  service boundaries (HTTP requests, DB calls). DeepFlow captures
  every method the agent is configured to instrument, including the
  values that flowed through. The two are complementary, not
  alternatives.
- **Not a profiler.** No sampling, no flame graphs, no CPU/memory
  attribution. DeepFlow records what the code did with data, not
  where the time went.
- **Not structured logs at scale.** Logs require explicit
  instrumentation (`log.info(...)` calls); DeepFlow needs no source
  changes. Logs survive forever; traces have a 30-day TTL by default.
- **Not zero-cost.** Capturing every value flowing through every
  matched method has overhead. Production use is realistic with a
  narrow `matchers_include` and value-size truncation; capturing
  *everything* in *every* class is not.

---

## Where to go next

- **Evaluating DeepFlow.** [overview](README.md) for the value
  proposition and the comparison table.
  [getting-started](../deepflow-agent/docs/getting-started.md) for
  build + attach.
- **Integrating a non-Java agent.**
  [docs/spec/SPEC.md](../deepflow-agent/docs/spec/SPEC.md) is the
  contract; [PORTING-GUIDE.md](../deepflow-agent/docs/spec/PORTING-GUIDE.md)
  walks through the build order.
- **Operating the pipeline.** [Agent
  Architecture](../deepflow-agent/docs/architecture.md) for
  agent-internal mechanics; [TRANSPORT.md](../deepflow-agent/docs/spec/TRANSPORT.md)
  for the carriage of agent-run identity across HTTP / Kafka /
  files.
- **Building or hiring.** This doc is your map.
