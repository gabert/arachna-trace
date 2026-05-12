# Agent Architecture

This document describes the architecture of the **language-specific
side** of DeepFlow — the Java agent that captures runtime data, the
in-process recording pipeline, and how output reaches a server-side
sink. The agent is the only Java-specific component in the system;
everything below its output boundary (collector, Kafka, processor,
ClickHouse) operates on the wire format alone and supports any
source language that emits conformant bytes.

> **Two architecture docs.** This one zooms into the **agent's**
> internal data flow — the only language-specific piece. For the
> system-wide pipeline view (agent → collector → Kafka →
> processor → ClickHouse → UI, language-agnostic), see
> [`doc/architecture.md`](../../doc/architecture.md) at the repo
> root. For solution-level positioning, see
> [`doc/README.md`](../../doc/).

## Data flow

The agent itself ends at the `Destination` boundary. From there the path
diverges depending on `destination=file` (local) or `destination=http`
(centralised pipeline).

```
-javaagent flag
  -> DeepFlowAgent.premain()           Load config, set up ByteBuddy
    -> AgentConfig                     Parse deepagent.cfg + CLI args
    -> DeepFlowAdvice (ByteBuddy)      Thin static facade installed on matched
                                       classes/methods; reads RECORDER and
                                       delegates to the live RequestRecorder
      -> RequestRecorder.recordEntry/recordExit()
                                       Per-call recording owner
        -> ValueEncoder.encode()       Wraps Codec.encode() with truncation cap
          -> Codec.encode()            Serialize to CBOR with identity envelopes
        -> RecordWriter                Produce binary record frames
          -> RecordBuffer              Enqueue for async draining
            -> RecordDrainer           Poll buffer, deliver to destination
              -> FileDestination       Write per-thread .dft files (local mode)
              -> HttpDestination       POST batched binary frames to collector
                                       (centralised mode -- see below)
```

In `destination=http`, the agent's responsibility ends at the POST. The
rest is the server-side pipeline:

```
HttpDestination
  -> POST /records                     record-collector-server (Netty)
       forwards POST body unchanged to Kafka, agent-run identity copied
       to record headers
  -> Kafka topic 'deepflow-records'
       short-retention burst buffer
  -> record-processor-server
       KafkaRecordConsumer poll loop
         -> RecordRenderer             CBOR -> JSON per TI/AR/AX/RE value
         -> RecordHashEnricher         inject __meta__ Merkle hashes
         -> RecordParser               pair MS/TE by callId -> ParsedCalls
  -> ClickHouseSink
       buffered HTTP INSERT JSONEachRow
  -> ClickHouse tables 'deepflow.calls' + 'deepflow.payloads'
```

For the wire-format contract that ties producer and processor together,
see [docs/spec/SPEC.md](spec/SPEC.md). For agent-run identity carriage
across the HTTP / Kafka / file boundaries, see
[docs/spec/TRANSPORT.md](spec/TRANSPORT.md).

## Why this shape — protecting the application's hot path

Every layer in the diagram exists because of a specific cost the agent
must avoid. `recordEntry` and `recordExit` run on the application's
own threads — every operation in them steals CPU and (more painfully)
latency variance from the traced call. The pipeline is shaped top to
bottom to keep that cost minimal.

### On the application thread (the hot path)

- **Binary on the hot path, not text.** The agent encodes captured
  values to CBOR via `Codec.encode()`. CBOR is several times faster
  than JSON for typical object graphs: integer field IDs in the
  envelope, no whitespace decisions, no string escaping, no UTF
  validation. Producing readable JSON on the application thread
  would multiply per-call cost; CBOR is what the agent emits, JSON
  is what somebody else renders later.

- **Identity is a single map lookup.** `ObjectIdRegistry` issues
  stable object IDs via a `ConcurrentHashMap` keyed by an
  identity-equality wrapper. Amortised O(1). No graph walks beyond
  what CBOR encoding already performs; no per-call allocation
  beyond the encoded bytes themselves.

- **Per-payload truncation cap.** `ValueEncoder` enforces
  `max_value_size`. Without it, a pathological argument — a giant
  collection, a stringified XML blob, a deeply nested entity graph —
  could pin the application thread on serialisation for tens of
  milliseconds. With it, the hot path's worst-case duration is
  bounded by configuration, not by input shape.

- **Non-blocking enqueue.** Once the byte[] record is built, the
  agent does a single `ConcurrentLinkedQueue.offer()` into an
  unbounded `RecordBuffer` and returns. The application never waits
  on disk, network, or any drainer state. Memory grows during bursts
  and GC reclaims it once the drainer catches up — strictly
  preferable to either dropping records silently (incomplete traces
  are hard to diagnose) or blocking the producer (timing
  perturbation).

### Off the application thread (the drainer)

- **Single dedicated daemon does all I/O.** `RecordDrainer` is one
  thread that polls the buffer and hands records to the configured
  destination. Disk writes (file mode) or HTTP POSTs (collector
  mode) happen *here*, not on application threads. Destination
  latency, network jitter, fsync — all invisible to the traced
  code.

- **Render and hash live downstream.** `RecordRenderer` (CBOR → JSON)
  and `RecordHashEnricher` (Merkle hashes per envelope) are NOT on
  the application thread. In file mode they run on the drainer;
  in HTTP mode they run on the processor server entirely off the
  agent's machine. The agent's wire format is binary precisely so
  this division of labour is possible.

### Beyond the agent — the same philosophy at coarser scale

- **The collector is a thin relay.** Netty handler, no parsing, no
  decoding, no augmentation — bytes in from HTTP, bytes out to
  Kafka, plus copy seven `X-Deepflow-*` headers across. Single
  responsibility means the network ingress is fast and predictable;
  a slow processor cannot backpressure into agents.

- **Kafka is a burst absorber, not the system of record.** Trace
  traffic is spiky; the processor's work (CBOR decode, JSON
  canonicalisation, Merkle hashing, batched INSERTs) is non-trivial
  and operationally separate from the agent. A topic between them
  lets the processor restart without record loss and lets agents
  continue posting while it catches up. Short retention, no
  compaction — ClickHouse is the trace store, Kafka is the airlock.

- **The processor batches INSERTs.** ClickHouse rewards batches and
  punishes single-row writes. The processor buffers parsed calls
  and payloads on a 1-second timer (or buffer-full trigger) so the
  amortised insert cost is low even under sustained load.

- **ClickHouse is columnar.** Trace queries scan a small number of
  columns over many rows ("find every call where `object_id` 42
  appeared", "filter by `session_id` and time range"). A row-store
  would touch every byte of every row, including the heavy
  `payload_json`. ClickHouse reads only the bloom-filter index
  column for that predicate; payloads are decompressed only when
  selected. The schema is shaped around what the queries need, not
  around the way the data was produced.

For the system-wide picture (component roles, what's stable vs
swappable, what DeepFlow is NOT), see
[../../doc/architecture.md](../../doc/architecture.md).

## Module structure

```
deepflow-agent/
  deepagent.cfg                          Reference config

  core/                                  Core modules
    agent/                               Bytecode instrumentation (entry point)
    codec/                               CBOR envelope serialization
    record-format/                       Binary wire format (shared agent <-> server)
    serializer/                          Buffer, drainer, destinations

  spi/                                   Service Provider Interfaces
    session-resolver-api/                SessionIdResolver interface
    session-resolver-config/             Built-in "config" resolver
    jpa-proxy-resolver-api/              JpaProxyResolver interface
    jpa-proxy-resolver-hibernate/        Hibernate proxy/collection unwrapping

  server/                                Server-side pipeline
    record-collector-server/             Netty HTTP server: receives POSTs,
                                         forwards unchanged bytes to Kafka
    record-processor-server/             Kafka consumer: render, hash, parse,
                                         insert into ClickHouse
    clickhouse-init/                     DDL mounted into the ClickHouse
                                         container on first start

  demos/
    demo-spring-boot/                    Spring Boot library app with agent + SPIs
```

## Module boundaries

Each module has a clear responsibility and communicates through defined
interfaces:

**agent** depends on codec, record-format, serializer. Entry point for the
JVM. Instruments classes via ByteBuddy, captures method entry/exit events,
encodes values, writes binary records to a buffer.

**codec** has no internal dependencies. Provides `Codec.encode()` /
`Codec.decode()` for CBOR serialization with identity envelopes. Handles
cycle detection, proxy detection, JPA proxy unwrapping.

**record-format** has no internal dependencies. Defines the binary frame
format (`RecordWriter` / `RecordReader`) shared between agent and server.
This is the wire protocol contract.

**serializer** depends on codec, record-format. Contains the recording
pipeline: `RecordBuffer` (concurrent queue), `RecordDrainer` (background
thread), `Destination` interface, `FileDestination` (per-thread .dft files),
`RecordRenderer` (binary to text conversion).

**SPI modules** define interfaces (`SessionIdResolver`, `JpaProxyResolver`)
with implementations loaded via `ServiceLoader`. They are separate JARs on
the application classpath so they can access framework classes. See the
[Extension points](#extension-points-spis) section below for the full
discussion.

## Extension points (SPIs)

The agent core has no compile-time dependency on Hibernate, Spring,
Servlet containers, or any specific framework. Framework-specific
behaviour — unwrapping a Hibernate lazy proxy so its real fields can
be serialised, reading the active HTTP session ID off a thread-local
populated by a Servlet filter — happens through Service Provider
Interfaces. The agent ships the contract; implementations live as
separate JARs that the application loads on its own classpath.

This is the deliberate extension surface of DeepFlow: anything that
needs a framework's API to behave correctly is plugged in here, not
baked into the agent.

### What ships today

- **`SessionIdResolver`** (in `spi/session-resolver-api/`). Returns
  the current logical session ID for the calling thread on every
  method entry/exit. Built-in: `config` (reads a static ID from the
  agent config). Demo-shipped: `spring-session` (reads from a
  `ThreadLocal` populated by a Servlet filter). Plausible custom
  implementations: MDC correlation IDs, OpenTelemetry trace IDs,
  gRPC metadata, per-tenant request markers — anything with a
  notion of "which request am I in right now" reachable from a
  ThreadLocal.

- **`JpaProxyResolver`** (in `spi/jpa-proxy-resolver-api/`). Asked
  on every captured value: "is this a proxy you recognise, and if
  so, what's the real object?". Built-in: `hibernate` (unwraps
  Hibernate lazy proxies and `org.hibernate.collection.*` wrappers
  via reflection — no compile-time Hibernate dependency on the
  agent). Plausible custom implementations: EclipseLink, OpenJPA,
  custom proxy frameworks.

### Loading

The agent uses Java's standard `ServiceLoader` to discover candidates,
selects the one whose `name()` matches the agent's config
(`session_resolver=<name>`, `jpa_proxy_resolver=<name>`), and caches
it. Loading is **lazy** — on first instrumented method entry, not at
agent startup. This matters in container environments like Spring
Boot, where the application classloader isn't fully initialised when
`premain` runs.

### Why SPI JARs live on the application classpath, not in the agent JAR

A Hibernate-aware proxy resolver needs to call Hibernate APIs at
runtime; an HTTP-session resolver needs to read the container's
session attribute. The agent itself shades all of its own
dependencies under a private namespace
(`com.github.gabert.deepflow.shaded.*`) precisely so it can attach
to any application without version conflicts. Bundling Hibernate or
Servlet types into the agent would defeat that — they would collide
with whatever the application brought. Keeping integration JARs
separate keeps each side's classpath clean and lets each pick the
framework version it actually runs against.

### Adding a new SPI implementation

1. Implement the interface (`SessionIdResolver` or `JpaProxyResolver`).
2. Register via `META-INF/services/<fully-qualified-interface>` in
   your JAR.
3. Drop the JAR on the application's classpath alongside the
   framework you depend on.
4. Set the matching name in `deepagent.cfg` (`session_resolver=…`
   or `jpa_proxy_resolver=…`).
5. No agent rebuild.

See [reference/session-resolver.md](reference/session-resolver.md) and
[reference/jpa-proxy-resolver.md](reference/jpa-proxy-resolver.md) for the
practical walkthroughs.

### Adding a new SPI interface

A new framework concern that doesn't fit either existing SPI — e.g.
a "redact secret-ish field values before serialisation" hook, a
"per-tenant destination routing" hook, a "custom signature
formatter" hook — is a new `spi/<name>-api/` module plus a load
point in `SpiBootstrap`. The two existing SPIs are the working
examples; both are small (an interface + a `default init(Map)` hook
+ a `name()` selector + the actual method).

## Key design decisions

### Executor instrumentation requires bootstrap injection

To propagate request IDs across thread boundaries, the agent instruments
JDK classes (`ThreadPoolExecutor`, `ForkJoinPool`). These classes are loaded
by the bootstrap classloader and governed by the Java module system, which
requires special handling: bootstrap class injection, ByteBuddy ignore rule
overrides, and JPMS module reads edges. See
[Executor Instrumentation](internals/executor-instrumentation.md) for the
full explanation.

### Agent's own packages excluded from instrumentation

The agent must never instrument its own classes. `DeepFlowAgent` adds
four prefixes to the exclusion list to prevent infinite recursion
(instrumented method -> recordEntry -> Codec.encode -> instrumented
again -> ...):

- `com.github.gabert.deepflow.agent`
- `com.github.gabert.deepflow.recorder`
- `com.github.gabert.deepflow.codec`
- `com.github.gabert.deepflow.shaded`

### Dependency shading

The agent shares the target application's classloader namespace. All
third-party dependencies (ByteBuddy, Jackson) are relocated via
`maven-shade-plugin` to `com.github.gabert.deepflow.shaded.*` to avoid
version conflicts with the application's own dependencies.

### SPI resolvers loaded lazily

Both SPI resolvers are loaded on the first instrumented method entry, not
at agent startup. `premain` runs before application classloaders are
initialized. In Spring Boot, the context classloader is not ready until the
application starts, so a `ServiceLoader` call during `premain` would fail
to find resolver implementations.

- `SessionIdResolver` -- double-checked locking on first `getResolver()` call.
- `JpaProxyResolver` -- double-checked locking in `initJpaProxyResolver()`.

### Error isolation

Both `recordEntry` and `recordExit` wrap all work in `try/catch(Throwable)`.
If the agent fails (e.g. serialization error), it prints to `stderr` and
continues. The target application is never affected.

### Unbounded buffer

The `RecordBuffer` (backed by `ConcurrentLinkedQueue`) never blocks and never
drops records. Dropping records silently would produce incomplete traces.
Blocking would alter the application's timing behavior. Memory grows during
bursts and is reclaimed by GC once the drainer catches up.

### Destination receives raw bytes

The `Destination` interface operates on raw `byte[]` records, not decoded
text. `FileDestination` decodes to text via `RecordRenderer` so `.dft`
files are human-readable. `HttpDestination` forwards the raw bytes
unchanged — no decoding on the agent's hot path; rendering and hashing
happen on the server side (`record-processor-server`).

### Config resolution order

Inline key-value pairs from the `-javaagent` argument string are parsed
first. If a `config` key is present, the referenced file is loaded. Inline
values override file values.

### Shutdown sequence

`RecorderManager` registers a JVM shutdown hook that:
1. `drainer.stop()` -- sets running flag to false, joins the thread, drains
   remaining records, calls `destination.flush()`
2. `destination.close()` -- releases resources

The drainer is a daemon thread, so it doesn't prevent JVM shutdown.
