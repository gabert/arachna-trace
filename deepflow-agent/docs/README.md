# DeepFlow Agent — Documentation

The Java agent that captures method-level runtime data flow via
bytecode instrumentation. For solution-level context (what
DeepFlow is, what it's for, how it compares), see the repo root
[README.md](../README.md).

## Start here

- [Getting Started](getting-started.md) — build, attach, run, read traces
- [Architecture](architecture.md) — high-level data journey, agent → ClickHouse → UI

## Reference

User-facing reference. Look here for "how do I configure X" or
"how does feature Y work".

- [Concepts](reference/concepts.md) — vocabulary that runs through every other doc (`object_id`, `own_hash`, `call_id`, `agent_run_id`, payload kinds, record types)
- [Deployment Modes](reference/deployment-modes.md) — file, HTTP, embedded, distributed, plus the cross-component size-limit alignment contract

### Component configuration

DeepFlow ships four components; each has its own config file and its own reference doc:

- [Agent Configuration](reference/agent-config.md) — every `deepagent.cfg` option (the JVM agent)
- [Collector Configuration](reference/collector-config.md) — `deepserver.cfg` (Netty HTTP → Kafka relay)
- [Processor Configuration](reference/processor-config.md) — `deepprocessor.cfg` (Kafka consumer → render → hash → ClickHouse)
- [Query Server Configuration](reference/query-server-config.md) — `deepquery.cfg` (read-only HTTP API for the UI)
- [Reading a Trace](reference/reading-a-trace.md) — interpreting `.dft` output
- [Bug-Finding Workflow](reference/bug-finding.md) — `own_hash`, Mutations panel, diff walker, provenance, value search
- [Mutation Detection](reference/mutation-detection.md) — detecting argument changes via AX
- [Request ID](reference/request-id.md) — request correlation and cross-thread propagation
- [Serialize Modes](reference/serialize-modes.md) — full vs structural-only
- [Truncation](reference/truncation.md) — capping serialized value size
- [Argument Names](reference/argument-names.md) — parameter-name capture
- [Session Resolver SPI](reference/session-resolver.md) — pluggable session ID source
- [JPA Proxy Resolver SPI](reference/jpa-proxy-resolver.md) — Hibernate proxy/collection unwrapping

## Internals

Low-level per-module implementation deep-dives.

- [Agent](internals/agent.md) — bytecode instrumentation, recording flow
- [Codec](internals/codec.md) — Java implementation of the CBOR envelope (defers to spec)
- [Executor Instrumentation](internals/executor-instrumentation.md) — bootstrap injection, JPMS, request-ID propagation
- [Serializer](internals/serializer.md) — recording pipeline (buffer, drainer, destinations)
- [Record Format and Collector](internals/record-format.md) — binary frame layout (Java) and the Netty → Kafka relay
- [Processor Server](internals/processor.md) — Kafka consumer pipeline: render → hash → parse → ClickHouse insert
- [Query Server](internals/query-server.md) — read-only HTTP API the UI talks to
- [UI](internals/ui.md) — Vue 3 UI architecture: navigation, payload viewer, panels

## Wire-format spec

The language-neutral protocol contract that any DeepFlow agent or
processor must implement. The Java agent in this repository is one
reference implementation of it.

- [SPEC.md](spec/SPEC.md) — conformance language and document map
- [WIRE-FORMAT.md](spec/WIRE-FORMAT.md) — binary frame layout
- [CBOR-ENVELOPE.md](spec/CBOR-ENVELOPE.md) — envelope shape, field IDs, cycle refs, truncation
- [HASHING.md](spec/HASHING.md) — Merkle content hash construction
- [TAGS.md](spec/TAGS.md) — rendered text view (`.dft` line format)
- [TRANSPORT.md](spec/TRANSPORT.md) — HTTP / Kafka / file carriage of agent-run identity
- [IDENTITY-MODEL.md](spec/IDENTITY-MODEL.md) — cross-language identity contract
- [PORTING-GUIDE.md](spec/PORTING-GUIDE.md) — writing an agent in another language

## Process (internal)

Team-facing tracking docs. Bug catalog, backlog, roadmap.

- [KNOWN_BUGS.md](process/KNOWN_BUGS.md) — bug catalog with stable IDs and status
- [ROADMAP.md](process/ROADMAP.md) — open work + user-facing feature ideas
