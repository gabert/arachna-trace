# Arachna Trace Overview

## The problem

Software breaks in two ways. Crashes and exceptions are loud -- you get a
stack trace, you find the bug, you fix it. Data errors are silent. The
application runs fine, returns HTTP 200, commits to the database, and the
result is wrong. A price is off. A permission check passes when it shouldn't.
A transaction settles with the wrong amount.

These bugs are hard because the code did what it was told. The problem is
in the values, not the structure. To find it you need to see what actually
went into each method and what came out.

Log statements capture values at lines the developer chose to log. APM and
distributed-tracing tools (Datadog, Honeycomb, OpenTelemetry-emitted spans)
instrument service boundaries — request entry, outbound calls, span events —
surfacing latency, error counts, and span attributes at those points.
Debuggers require reproducing the exact scenario interactively, which is
often impossible in production-like environments.

Without runtime evidence, the practical fallback is the print-statement
cycle: add logging, redeploy, reproduce, read, repeat. The cost of that
cycle scales with the cost of reproducing the issue.

## What Arachna Trace does

Arachna Trace is a Java agent that records what your code actually does with
data at runtime. Attach it via `-javaagent`, point it at your packages, run
the application. For every instrumented method it captures:

- Method signature, arguments, return value (or exception)
- Object identity -- the same `Order` instance gets the same ID everywhere
- Arguments at exit (optional) -- did the method mutate its inputs?
- Millisecond timestamps, request ID, session ID, caller line number

Attached via `-javaagent`; the application source is unchanged.

The result is a recording of what happened during execution — every method
call within the configured scope with its arguments, return values,
mutations, and timestamps. The data lands in a queryable store rather than
in a stream the user has to re-step through.

Two traces of the same scenario can be compared via the content hashes
attached to each captured value. Methods that received different arguments
or returned different values appear as hash differences between the two
recordings.

The agent supports two destinations. **File** writes structured text files
locally (one `.dft` file per thread) -- suitable for local debugging and
development. **HTTP** sends binary records to a collector server that
stores them via Kafka into ClickHouse -- suitable for shared environments,
production tracing, and team-wide analysis through a query interface.

Both destinations capture the same data. The difference is where it lands.

What gets recorded is configurable per agent run -- which tags are
emitted, whether values are serialized at all, whether `this` is captured
by reference or by content, and how large a single payload may grow before
it is truncated. The agent records the subset of records configured for
that run.

See [the agent's configuration reference](../arachna-trace-agents/jvm/docs/reference/agent-config.md)
for all options. Each server component (collector, processor,
query) has its own config doc next to it under
[../arachna-trace-infra/docs/reference/](../arachna-trace-infra/docs/reference/).

## Two modes of use

Arachna Trace is designed to work in two modes. Both use the same agent and
produce the same trace data -- the difference is who reads them.

**Human mode.** A developer attaches the agent, reproduces the scenario,
and reads the trace directly. In file mode, the `.dft` files are
structured text -- method signatures, argument values, return values,
timestamps -- readable in any editor. In HTTP mode, the same data is
queryable from ClickHouse. Either way, a human reads the trace and finds
the bug. No AI involved.

This mode matters for two reasons. First, data sensitivity: financial
transactions, classified data, patient records, cryptographic material --
when the trace contains values that must not leave a controlled
environment, only verified humans access the data. No external system,
including LLMs, ever sees it. Second, cost: LLM-based analysis of
detailed traces consumes significant tokens and adds up quickly. A
developer reading a structured trace costs nothing beyond their time and
is often faster for focused debugging.

**AI-assisted mode.** The same trace data can be fed to an LLM for
automated analysis. In HTTP mode, traces land in ClickHouse — an LLM
agent armed with SQL can query specific calls, find mutations, follow
an `object_id` across a request, and surface unexpected control flow.
This is useful for verifying AI-generated code (run the feature, ask
the agent to confirm the data flowed correctly) or for large traces
where manual reading is impractical.

Both modes can work with either destination. File mode keeps everything
local. HTTP mode centralizes traces but access is still controlled --
nothing reaches an LLM unless you choose to send it.

## Context

Data bugs are usually found long after they occur. By the time someone
notices, the runtime state that produced the bug is gone, and reproducing
it usually involves another redeploy-and-print-statement cycle. With a
recording attached, the values that flowed through the code at the time
are in the trace — locally in `.dft` files or in ClickHouse.

AI-generated code is reviewed against tests and structure. Whether the data
actually flowed correctly through it at runtime is a different question;
that question is the same regardless of who wrote the code. The recording
provides runtime evidence the application produced — a substrate that human
reviewers and LLM agents can both query. An LLM with SQL access to the
trace store can ask "did the data flow correctly through this changed
path?" against the recording, instead of reasoning about the code in the
abstract.

That turns *"I think this is correct"* into *"here is what it actually
did"* — useful both as input to a human review and as the ground a
verification loop runs on.

Regulated environments tend to need evidence of the actual data flow, not
only test outcomes. The recording is one such evidence source — a record
of every captured method call with its actual data, attributed to a
specific JVM run, host, build version, and environment. Whether it is
appropriate for any specific regulatory regime is a judgment for the
operator.

## Relation to other tools

Most tracing tools capture structure (which methods were called, how long
they took). Arachna Trace captures data (what values went in, what came out,
what changed).

| | OpenTelemetry / APM | Profilers | Arachna Trace |
|---|---|---|---|
| Granularity | Service boundaries | Sampled methods | Every instrumented method |
| Argument capture | Manual | No | Automatic |
| Return value capture | Manual | No | Automatic |
| Mutation detection | No | No | Yes (AR vs AX) |
| Object identity | No | No | Yes (stable object_id) |
| Session grouping | Trace ID | No | Session resolver SPI |
| Code changes needed | Yes | No | No |

## Use cases

The five use cases that drove the design — *Observability for
AI-generated code*, *Debugging silent data errors*, *Forensics
and audit for regulated systems*, *Understanding code you didn't
write*, *Regression detection across releases* — are written up
on the [repo root README](../README.md#what-we-built-this-for).

## Components

- **arachna-trace-shared/** -- language-neutral umbrella reactor:
  `codec/` (CBOR encode/decode + envelope + Hasher + AgentRun + binary
  wire types), `renderer/` (RecordRenderer + RecordHashEnricher), and
  `spi/` (the SessionIdResolver + JpaProxyResolver thin API
  interfaces). Depended on by both agents and infra.
- **arachna-trace-agents/** -- parent dir for language-specific agents.
  Today contains only `jvm/` (the JVM agent: ByteBuddy-based bytecode
  instrumentation, plus the serializer that owns FileDestination and
  HttpDestination). Generic any-agent docs (concepts, mutation
  detection, request-id propagation) live under
  [`arachna-trace-agents/docs/`](../arachna-trace-agents/docs/).
- **arachna-trace-jvm-extensions/** -- reference implementations of
  the SPI contracts for the JVM ecosystem
  (`session-resolver-config`, `session-resolver-spring`,
  `jpa-proxy-resolver-hibernate`). Each is a self-contained
  single-class plugin JAR; users either drop them on the application
  classpath or copy them as templates for their own. See its
  [README](../arachna-trace-jvm-extensions/README.md) for the
  recipe.
- **arachna-trace-infra/** -- language-neutral server-side pipeline:
  Netty collector, Kafka-fed processor, query HTTP API, ClickHouse
  schema, and a docker-compose for local development. Consumes any
  conformant agent in any language via the wire format.
- **arachna-trace-ui/** -- Vue / TypeScript UI; talks to the query
  HTTP API.
- **arachna-trace-demos/** -- sample apps with the agent attached
  (today: `jvm/demo-plain`, `jvm/demo-spring-boot`).
- **spec/** -- the language-neutral wire-format contract between
  agents and infra.

## Further reading

- [System Architecture](architecture.md) -- the full pipeline at a
  glance: components, contracts, design choices, what Arachna Trace is NOT

The JVM agent's own documentation lives next to its source:

- [Getting Started](../arachna-trace-agents/jvm/docs/getting-started.md) -- build, attach, configure, read output
- [Agent Configuration](../arachna-trace-agents/jvm/docs/reference/agent-config.md) -- the JVM agent's options
- [Agent Architecture](../arachna-trace-agents/jvm/docs/architecture.md) -- JVM-internal data flow, modules, design decisions

Generic and infra docs:

- [Generic agent concepts](../arachna-trace-agents/docs/concepts.md) -- terminology any agent in any language must implement
- [Wire-format spec](../spec/SPEC.md) -- the language-neutral protocol contract
- [Infra deployment modes](../arachna-trace-infra/docs/reference/deployment-modes.md)
