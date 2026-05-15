# Contributing to Arachna Trace

Thank you for your interest in Arachna Trace. This document describes the
project's scope, where contribution is most welcome, and how to get a
change merged.

> The documentation in this repository is human-driven but
> AI-drafted, with maintainer review that spot-checks rather
> than audits every paragraph
> (see [README → About this documentation](README.md#about-this-documentation)).
> Doc corrections are first-class contributions — if you spot
> a mismatch between the docs and the code, please file an
> issue or a PR. Reader pushback is how the docs keep up with
> what the code actually does.

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

**Heuristics and analyses on top of the trace.** Heuristics turn
raw signals into hypotheses. An exception count is a signal;
*"an exception was caught inside a request that returned 200"* is
a heuristic — the kind of pattern that catches bugs nobody else
finds. The one heuristic that already ships is **value-origin
tracing** — given a value observed somewhere in a trace, walk
backwards through the recorded data flow to find the call that
first produced it. Useful as a worked example of the shape: a
small piece of analysis that turns the captured trace into a
direct answer to a debugger's question.

Beyond that, the catalog is wide open. Plausible directions —
none implemented today, all reachable from the existing trace
data: state mutated and never read, return values inconsistent
with arguments, identical inputs producing different outputs,
retried work that should have been idempotent, objects with the
same content but diverging identity, exceptions silently caught
inside successful-looking requests. Each one is a low-cost
*"look here first"* signpost.

LLM-driven analysis is the other half of this space and largely
unexplored: a captured trace is a structured artefact an LLM can
read and reason about (*"is the discount being applied to the
right line item?", "did this method actually do what its name
suggests?"*). Where the line falls between deterministic
heuristics and LLM judgment is itself a research direction —
contributions that propose an experiment here are welcome.

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

## Where contribution is needed

The categories below describe the **shapes** of work that move
the project forward. For specific entries inside any category —
open items, motivation, approach sketches, status — see:

- **[arachna-trace-agents/docs/process/ROADMAP.md](arachna-trace-agents/docs/process/ROADMAP.md)** — open features and follow-ups, with motivation and approach sketches.
- **[arachna-trace-agents/docs/process/KNOWN_BUGS.md](arachna-trace-agents/docs/process/KNOWN_BUGS.md)** — bug catalog with stable IDs. Items marked `Status: OPEN` are candidates; `ACCEPTED` are intentional trade-offs — don't touch without discussion.

Open an issue or a PR against any category below.

### Agents for other runtimes

The single most impactful contribution surface. Today's only agent
is for the JVM (`arachna-trace-agents/jvm/`). The wire-format spec
under [`spec/`](spec/) defines exactly what a conformant agent in
any other language must produce — candidates are .NET, Python,
Node, Go, Ruby, native (C/C++/Rust). Each runtime exposes its own
diagnostic surface (CLR profiler API, CPython `sys.monitoring`,
V8 inspector, Go runtime traces, eBPF uprobes, …) and a new agent
shouldn't just port the existing signal set; it should reach for
what its runtime makes naturally visible. Architecturally clean
— the contract is fully specified and conformance is verifiable
by pointing the new agent at the existing collector and reading
the resulting UI. [`spec/PORTING-GUIDE.md`](spec/PORTING-GUIDE.md)
is the checklist; the JVM agent is the reference implementation.

### SPI implementations for unsupported frameworks

The JVM agent has two SPI extension points (`SessionIdResolver`,
`JpaProxyResolver`). Thin api jars live under
[`arachna-trace-shared/spi/`](arachna-trace-shared/spi/); shipped
reference implementations live under
[`arachna-trace-jvm-extensions/`](arachna-trace-jvm-extensions/),
each a self-contained single-class plugin JAR. New impls for
Quarkus, Micronaut, Vert.x, EclipseLink, OpenJPA, message-queue
session contexts, etc. are welcome — the recipe is at
[`spi-wiring.md`](arachna-trace-agents/jvm/docs/reference/spi-wiring.md);
each existing module is a worked example to copy as a template.
Non-JVM agents will grow their own equivalent SPIs as they need
them.

### Heuristics and analyses on top of the trace

The captured trace is a structured artefact — a queryable graph
of calls, values, identities, and mutations. Analyses sit on top:
deterministic heuristics turn raw signals into hypotheses; LLM
inspection asks intent-level questions of the trace; hybrids are
likely the most interesting territory. The shape of "an analysis
as a feature" is established (the shipped value-origin tracer is
a worked example); the catalog of useful analyses is wide open.

### Local / IDE-plugin viewer for `.dft` files

A way to read `.dft` files (the agent's local file output, already
fully rendered and Merkle-hashed) directly from an IDE — typically
an IntelliJ plugin — so a developer can run the agent against
their app and inspect traces with no infrastructure standing up.
The existing `RecordParser` and the Vue UI are reusable; the gap
is the plugin shell, an in-memory query layer, and the embedding
glue. Either a JVM impl reusing the existing parser or a non-JVM
port is valid since the wire format is language-neutral.

### Alternative infrastructure components

The collector / processor / query / UI interfaces speak the wire
format on the ingest side and a documented HTTP API on the read
side. Replacements that speak the same contracts are welcome — a
different transport (NATS, Redpanda, plain TCP), a different
storage engine (Postgres + JSONB, Parquet on object store, DuckDB
file), or a different query layer (GraphQL).

### UI features

The UI is in `arachna-trace-ui/` (Vue 3 + TypeScript). It talks
to the query API over a documented HTTP surface and is fully
decoupled from the agent. Contributions matching the project's
hypothesis-driven debugging direction (cross-request /
cross-thread / cross-session navigation as one motion, identity
and value lineage as first-class, surfacing analysis findings
prominently) are particularly welcome.

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

- **JVM agent + Java infrastructure.** JDK 17 is the current source
  target across the codebase. Standard formatting; existing code is
  the reference. Maven module tests.

  **Heads-up on the runtime modules.** The modules that load into
  the user's JVM — `arachna-trace-shared/`,
  `arachna-trace-agents/jvm/core/`, and
  `arachna-trace-jvm-extensions/` — are tagged for an eventual
  Java 11 source retarget so the agent can run in enterprise JVMs
  still on the 11 LTS. See the roadmap entry "Java 11 source
  compatibility for runtime modules" for scope and rationale. The
  retarget hasn't shipped yet, so any Java 17 idiom is fine today;
  but if you're adding code to a runtime module and you can do the
  same thing with a Java 11–compatible construction at no
  expressivity cost, prefer it — every record / sealed interface /
  pattern `instanceof` / pattern-switch you add becomes mechanical
  conversion work later. Infrastructure code under
  `arachna-trace-infra/` runs in our containers and stays on
  Java 17 with no constraint.
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
