# Contributing to Arachna Trace

Thank you for your interest in Arachna Trace. This document describes the
project's scope, where contribution is most welcome, and how to get a
change merged.

## Project scope

Arachna Trace is a **runtime tracing substrate** with two cleanly
separated parts. Contributors should understand this split before
picking a task — it determines which language, which docs, and which
constraints apply.

### Part 1 — Agent (runtime data capture)

The agent hooks into a running program (no source changes required),
captures method calls, argument values, return values, and object
mutations, and emits records in a **language-neutral wire format**.

An agent is necessarily language-specific — instrumentation reaches
into runtime internals (bytecode, IL, interpreter frames, etc.). The
**only agent implementation today is the JVM agent** (under
`arachna-trace-agents/jvm/`), usable from any JVM language (Java, Kotlin,
Scala, Groovy, …).

Agents for other runtimes — **.NET, Python, Node, Go, Ruby, native** —
are an explicit goal and one of the most impactful contribution
surfaces. The contract any new agent must satisfy is fully specified
under [spec/SPEC.md](spec/SPEC.md); see "Agent for another
language" below.

### Part 2 — Infrastructure (everything downstream of the agent)

The collector (Netty HTTP intake), Kafka transport, processor
(render + Merkle-hash + ClickHouse insert), read-only query API, and
the Vue UI are **language-agnostic**. They consume the wire format,
not Java types. A conformant agent in any language plugs into this
layer without any change to it.

The infrastructure is implemented in Java + TypeScript today, but
that is an implementation detail of *this* infrastructure — not a
constraint of the project. Alternative infrastructures (a local
single-binary viewer, an embedded DuckDB-backed server, a different
storage engine) are also welcome as long as they speak the same wire
format.

### The contract between the two parts

The wire-format spec under `spec/` is the load-bearing contract —
every agent and every piece of infrastructure must conform:

- [SPEC.md](spec/SPEC.md) — top-level overview
- [WIRE-FORMAT.md](spec/WIRE-FORMAT.md) — binary record framing
- [CBOR-ENVELOPE.md](spec/CBOR-ENVELOPE.md) — value serialization
- [HASHING.md](spec/HASHING.md) — Merkle content hashing
- [TAGS.md](spec/TAGS.md) — record tag catalog
- [TRANSPORT.md](spec/TRANSPORT.md) — HTTP intake + agent-run identity
- [IDENTITY-MODEL.md](spec/IDENTITY-MODEL.md) — object identity
- [PORTING-GUIDE.md](spec/PORTING-GUIDE.md) — checklist for new agents

## Current focus

The maintainers' active work is on the **distributed deployment mode**
(Kafka + ClickHouse). This mode is targeted at:

- Multi-service production debugging
- Regulated-industry audit substrates
- AI-era observability at fleet scale
- The hosted SaaS path

See [arachna-trace-infra/docs/reference/deployment-modes.md](arachna-trace-infra/docs/reference/deployment-modes.md) for the full
positioning of each mode.

## Primary direction — finding software problems

The strategic thrust shaping where effort goes is making Arachna
Trace better at the thing it exists for: shortening the path from
*"something is wrong"* to *"I understand why."* Three mutually
reinforcing pillars sit under that, and contributions that move
any of them forward are the highest priority.

**Richer agents.** The agent is the diagnostic instrument. Today's
JVM agent emits method calls, argument and return values, and
object mutations — already more than logs, but a fraction of what
a runtime makes visible when things go wrong. Caught-and-swallowed
exceptions, state that escapes a request boundary, identity drift,
contention, retries, side-effects inconsistent with return values,
async hand-offs across threads — these are all candidate signals.
New agents in other languages shouldn't just port the existing
signal set; each runtime exposes its own diagnostic surface, and
the agent should reach for it.

**Heuristics.** Heuristics turn raw signals into hypotheses. An
exception count is a signal; *"an exception was caught inside a
request that returned 200"* is a heuristic — the kind of pattern
that catches bugs nobody else finds. State mutated and never read,
return values inconsistent with arguments, identical inputs
producing different outputs, retried work that should have been
idempotent, objects with the same content but diverging identity —
each one is a low-cost *"look here first"* signpost. The processor
is where heuristics live, and the catalog of useful ones is wide
open.

**UI for hypothesis-driven debugging.** Not a log viewer with
filters bolted on. The operator arrives with *"X is broken"* and
the tool's job is to propose candidates, let them follow one,
refute or confirm, and pivot. Surfacing heuristic findings
prominently, making cross-request / cross-thread / cross-session
navigation feel like one motion, and treating identity and value
lineage as first-class are the direction. The UI should compress
the gap between *"I have a suspicion"* and *"I can see the
evidence."*

The thread running through all three is that this is a tool for
*finding software problems*, not for measuring software. That
framing drives what agents capture, what heuristics get
prioritised, and how the UI presents.

## Adjacent objectives — auditing and AI observability

Two further objectives shape what counts as a meaningful
contribution. Work that moves these forward is welcome on its
own merits; work on the debugging direction above is doubly
welcome when it also moves these.

**Regulated-industry auditing.** A trace is an evidentiary
artefact. Industries where every consequential decision needs
a defensible record — finance, healthcare, public-sector,
anything under emerging AI regulation — need more than logs
that *"should be enough."* Broad directions: strengthening the
chain of custody from runtime event to reviewer
(tamper-evidence built on the existing Merkle hashing,
agent-side attestation of origin, signed sessions); retention
that understands legal hold and selective preservation;
provenance that lets a reviewer point at a value in production
and prove which code produced it from which inputs; replay of
captured sessions for after-the-fact audit; access auditing on
the trace store itself.

**AI-era observability.** AI agents and AI-written code change
what software *is*, and review-by-reading is no longer enough.
Two threads sit here. *Observing AI systems* — capturing the
runtime of LLM agents, tool invocations, multi-step plans, and
decision loops with the same forensic depth the project already
gives to traditional code; making it possible to tell whether an
autonomous run did the right thing for the right reason. *Verifying
AI-written code* — runtime behaviour as the source of truth:
trace-based diffing of before/after refactors, behaviour-preserving
change checks, intent-versus-behaviour comparisons, dataset
curation from real runtime examples. Contributions in this space
treat traces as the evidence layer that makes AI-augmented
software trustworthy.

These two adjacent objectives and the debugging direction share
one substrate: the agent, the wire format, the heuristics, and
the UI. A contribution that strengthens the substrate usually
moves all three forward at once.

## Where contribution is most welcome

If you're looking for a specific concrete starting point, two places
to scan first:

- **[arachna-trace-agents/docs/process/ROADMAP.md](arachna-trace-agents/docs/process/ROADMAP.md)** — open work
  with motivation and approach sketches. The "Bug-finding UX
  backlog" (B2–B6) items and the FR-1..FR-5 user-facing feature
  ideas are good entry points for someone new to the codebase.
- **[arachna-trace-agents/docs/process/KNOWN_BUGS.md](arachna-trace-agents/docs/process/KNOWN_BUGS.md)** — bug
  catalog with stable IDs. Items marked `Status: OPEN` are
  candidates; `ACCEPTED` are intentional trade-offs, don't touch
  without discussion.

Beyond those, deliverables below are grouped by which **part** of
the project they live in — agent or infrastructure.

## Agent-side contributions

### A1. Agent for another language

The single most impactful contribution surface. The pipeline is
language-agnostic, so any agent emitting conformant wire-format bytes
plugs straight into the existing collector → Kafka → processor →
ClickHouse → UI flow.

Candidates: **.NET, Python, Node, Go, Ruby, native (C/C++/Rust)**.

What this requires from a new agent:

- Hook into the target runtime's instrumentation point (CLR profiler
  API, CPython `sys.setprofile` / `sys.monitoring`, V8 inspector,
  Go runtime traces, eBPF uprobes, …).
- Emit the binary record format defined in
  [spec/WIRE-FORMAT.md](spec/WIRE-FORMAT.md), with the tag
  set from [spec/TAGS.md](spec/TAGS.md) and the CBOR
  envelope shape from [spec/CBOR-ENVELOPE.md](spec/CBOR-ENVELOPE.md).
- Compute Merkle content hashes per
  [spec/HASHING.md](spec/HASHING.md) — or leave that to
  the processor (the JVM agent leaves hashing to the processor;
  the spec allows either).
- POST batches to the collector per
  [spec/TRANSPORT.md](spec/TRANSPORT.md), including the
  agent-run identity headers.

[spec/PORTING-GUIDE.md](spec/PORTING-GUIDE.md) is the
checklist for porting; the JVM agent under
`arachna-trace-agents/jvm/core/` is the reference implementation.

Substantial undertaking but architecturally clean — the contract is
fully specified, and conformance can be verified by pointing the new
agent at the existing collector and reading the resulting UI.

### A2. JVM agent — SPI resolvers

The JVM agent has two SPI extension points:

- `SessionIdResolver` — extracts a session ID from runtime context
  (HTTP request, message header, etc.). Built-in resolvers:
  `config`, with a Spring servlet resolver in the demo module.
- `JpaProxyResolver` — unwraps lazy-loaded ORM proxies. Built-in:
  Hibernate.

New resolvers for other frameworks (Quarkus, Micronaut, Vert.x,
non-Hibernate JPA implementations, message-queue session contexts)
are welcome. Non-JVM agents will grow their own equivalent SPIs as
they need them.

## Infrastructure-side contributions

### I1. Local mode — single-JAR / IDE plugin viewer

A standalone tool that watches `.dft` files written by the agent and
serves the existing UI from a localhost HTTP server. No Docker, no
Kafka, no ClickHouse. ~50 MB RAM.

What already exists:

- The agent already writes `.dft` files in fully-rendered, Merkle-hashed
  form. No rendering or hashing work is needed at read time.
- `RecordParser` (in `arachna-trace-infra/record-processor-server`) consumes the
  exact line format `.dft` files contain.
- The Vue UI is API-shaped — point it at any host that serves the
  existing query endpoints, and it works.

What needs to be built:

- `SessionWatcher` — JDK `WatchService` + `RandomAccessFile` tail
- `InMemoryStore` — `Map`s with inverted indexes for value / identity
  search
- `LocalQueryHandler` — implements the existing query API on top of
  `InMemoryStore`
- Embedded `com.sun.net.httpserver.HttpServer` to serve the UI bundle
  and the JSON API
- Optional: IntelliJ plugin wrapper using JCEF + PSI for
  source-code-anchored navigation (jump-to-method, gutter icons,
  inline value hints)

This is approximately a 2–3 week prototype, 6–10 weeks for a polished
IDE plugin. The current Java implementation is convenient because the
existing parser is reusable; a non-JVM port is also valid since the
wire format is language-neutral.

### I2. Embedded mode — DuckDB-backed single server

A standalone server that reads `.dft` files (or accepts HTTP intake
from a single agent) and inserts into a DuckDB file. Persistent,
analytical-SQL-queryable, no cluster.

What already exists:

- Same as local mode plus the ClickHouse SQL in `QueryHandler` is
  mostly portable to DuckDB.

What needs to be built:

- `DuckDBSink` — replaces `ClickHouseSink`, same interface
- DDL adaptation — the `01-schema.sql` schema with DuckDB syntax tweaks
- `EmbeddedQueryHandler` — runs the existing query SQL against DuckDB
- File-tail or single-host HTTP intake (reuse the local-mode watcher)

Smaller scope than local mode in many ways because the SQL stays close
to what already exists.

### I3. UI features

The UI is in `arachna-trace-ui/` (Vue 3 + TypeScript). It talks to
the query API over a documented HTTP surface and is fully decoupled
from the agent. Features that match the architectural ambitions of
the project:

- Session diffing (compare two sessions side-by-side)
- Persistent saved queries
- LLM-agent integration via MCP
- Better mutation visualization at scale

### I4. Alternative infrastructure components

The collector / processor / query interfaces are not magic — anyone
can write a replacement that speaks the same wire format on the
ingest side and the same HTTP API on the read side. Examples:
a different transport (NATS, Redpanda, plain TCP), a different
storage engine (Postgres + JSONB, Parquet on object store), a
different query layer (GraphQL).

## What is *not* in scope

To keep the project coherent:

- **Performance / APM features.** Arachna Trace is a debugging and audit
  tool, not an APM. Latency profiling, flame graphs, and similar
  belong elsewhere.
- **Lethal weapons systems.** The project will not accept contributions
  that target this domain. Defensive non-lethal applications are
  acceptable.

## Code conventions

These apply to the existing codebases. A new agent in a different
language follows that language's idiomatic conventions — the project
does not impose Java style on a Python or Go port.

- **JVM agent + Java infrastructure.** JDK 17 target. Standard
  formatting; existing code is the reference. Maven module tests.
- **UI.** TypeScript strict mode; existing code is the reference.
  Component-level tests when behaviour is non-trivial.
- **New agents (other languages).** Conform to the wire-format spec
  ([spec/SPEC.md](spec/SPEC.md)); otherwise follow the
  target language's standard formatting and testing conventions.
- **Comments.** Explain *why*, not *what*. The code already shows what.
  Comments should capture invariants, design rationale, or
  non-obvious interactions.
- **Commits.** Imperative mood. Reference issue or PR number when one
  exists.

## How to contribute

1. Open an issue describing the change before writing significant code,
   especially for new deployment modes or new SPI implementations.
   Saves both sides time if scope or design needs negotiation.
2. Fork the repository.
3. Create a feature branch.
4. Make the change. Include tests where the existing module pattern
   includes them.
5. Open a pull request against `main`. Describe the *why* in the PR
   description; the diff shows the *what*.

## License

The project is licensed under the [Apache License 2.0](LICENSE).
By submitting a contribution, you agree that it will be licensed
under the same terms. Apache 2.0 includes an explicit patent grant,
so contributors implicitly license any patents they hold that read
on their contribution.
