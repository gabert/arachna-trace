# Contributing to Arachna Trace

Thank you for your interest in Arachna Trace. This document describes the
project's scope, where contribution is most welcome, and how to get a
change merged.

## Project scope

Arachna Trace is a **runtime tracing substrate**: a Java agent captures
method calls, argument values, return values, and object mutations from
a target JVM without code changes; the captured data is then rendered,
indexed, and made queryable for debugging, audit, and AI-era
observability.

The agent and the wire format are the load-bearing pieces. Everything
else — storage, transport, query backend, UI — is layered on top and
swappable.

## Current focus

The maintainers' active work is on the **distributed deployment mode**
(Kafka + ClickHouse). This mode is targeted at:

- Multi-service production debugging
- Regulated-industry audit substrates
- AI-era observability at fleet scale
- The hosted SaaS path

See [docs/reference/deployment-modes.md](docs/reference/deployment-modes.md) for the full
positioning of each mode.

## Where contribution is most welcome

If you're looking for a specific concrete starting point, two places
to scan first:

- **[docs/process/ROADMAP.md](docs/process/ROADMAP.md)** — open work
  with motivation and approach sketches. The "Bug-finding UX
  backlog" (B2–B6) items and the FR-1..FR-5 user-facing feature
  ideas are good entry points for someone new to the codebase.
- **[docs/process/KNOWN_BUGS.md](docs/process/KNOWN_BUGS.md)** — bug
  catalog with stable IDs. Items marked `Status: OPEN` are
  candidates; `ACCEPTED` are intentional trade-offs, don't touch
  without discussion.

Beyond those, the agent's clean separation of capture, transport,
storage, and query layers makes several deliverables tractable
independent projects.

### 1. Local mode — single-JAR / IDE plugin viewer

A standalone tool that watches `.dft` files written by the agent and
serves the existing UI from a localhost HTTP server. No Docker, no
Kafka, no ClickHouse. ~50 MB RAM.

What already exists:

- The agent already writes `.dft` files in fully-rendered, Merkle-hashed
  form. No rendering or hashing work is needed at read time.
- `RecordParser` (in `server/record-processor-server`) consumes the
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
IDE plugin.

### 2. Embedded mode — DuckDB-backed single server

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

### 3. Agents for other languages

Arachna Trace's wire format is language-neutral
([docs/spec/SPEC.md](docs/spec/SPEC.md)). A non-Java agent that emits
conformant bytes can plug into the existing pipeline without changes.
Likely candidates: .NET, Python, Node.

This is a substantial undertaking but architecturally clean — the
contract is fully specified.

### 4. SPI resolvers

The agent has two SPI extension points:

- `SessionIdResolver` — extracts a session ID from runtime context
  (HTTP request, message header, etc.). Built-in resolvers:
  `config`, with a Spring servlet resolver in the demo module.
- `JpaProxyResolver` — unwraps lazy-loaded ORM proxies. Built-in:
  Hibernate.

New resolvers for other frameworks (Quarkus, Micronaut, Vert.x,
non-Hibernate JPA implementations, message-queue session contexts)
are welcome.

### 5. UI features

The UI is in `arachna-trace-ui/` (Vue 3 + TypeScript). Features that match
the architectural ambitions of the project:

- Session diffing (compare two sessions side-by-side)
- Persistent saved queries
- LLM-agent integration via MCP
- Better mutation visualization at scale

## What is *not* in scope

To keep the project coherent:

- **Performance / APM features.** Arachna Trace is a debugging and audit
  tool, not an APM. Latency profiling, flame graphs, and similar
  belong elsewhere.
- **Lethal weapons systems.** The project will not accept contributions
  that target this domain. Defensive non-lethal applications are
  acceptable.

## Code conventions

- **Java.** JDK 17 target. Standard formatting; existing code is the
  reference.
- **TypeScript.** Strict mode; existing code is the reference.
- **Tests.** Maven module tests for Java; component-level tests for
  the UI when behaviour is non-trivial.
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

The project is licensed under the [Apache License 2.0](../LICENSE).
By submitting a contribution, you agree that it will be licensed
under the same terms. Apache 2.0 includes an explicit patent grant,
so contributors implicitly license any patents they hold that read
on their contribution.
