# Arachna Trace Overview

## The problem

Software breaks in two ways. Crashes and exceptions are loud -- you get a
stack trace, you find the bug, you fix it. Data errors are silent. The
application runs fine, returns HTTP 200, commits to the database, and the
result is wrong. A price is off. A permission check passes when it shouldn't.
A transaction settles with the wrong amount.

These bugs are hard because the code did what it was told. The problem is
in the values, not the structure. To find it you need to see what actually
went into each method and what came out. And today there's no good way to
do that.

Log statements only capture what someone thought to log. APM tools
(OpenTelemetry, Datadog) work at service boundaries -- they tell you a
request took 200ms, not that the discount was applied to the wrong line
item. Debuggers require reproducing the exact scenario interactively, which
is often impossible in production-like environments.

So data errors get debugged the way they always have: add print statements,
redeploy, reproduce, read the output, repeat. For a simple bug that's just
annoying. For a production issue in a bank or a defence system, it's a
serious problem.

## What Arachna Trace does

Arachna Trace is a Java agent that records what your code actually does with
data at runtime. Attach it via `-javaagent`, point it at your packages, run
the application. For every instrumented method it captures:

- Method signature, arguments, return value (or exception)
- Object identity -- the same `Order` instance gets the same ID everywhere
- Arguments at exit (optional) -- did the method mutate its inputs?
- Millisecond timestamps, request ID, session ID, caller line number

No code changes. No annotations. No SDK. Just attach and run.

The result is a complete recording of what happened during execution --
every method call with its actual data, ordered in time. Think of it as
a debugging session that you can move forward and backward through without
restarting the application or reproducing the scenario. The data is
already there: every argument, every return value, every mutation,
timestamped to the millisecond. You navigate the recording, not the live
process.

Because traces are deterministic records, a verified trace can serve as
a baseline. Run the same scenario after a code change and compare the
two traces. If the data flow changed, you see exactly where it diverged --
which method received different arguments, which return value shifted.
This turns traces into regression tests over actual runtime data, not
just over expected outputs.

The agent supports two destinations. **File** writes structured text files
locally (one `.dft` file per thread) -- suitable for local debugging and
development. **HTTP** sends binary records to a collector server that
stores them via Kafka into ClickHouse -- suitable for shared environments,
production tracing, and team-wide analysis through a query interface.

Both destinations capture the same data. The difference is where it lands.

What gets recorded is fully configurable per agent run -- which tags are
emitted, whether values are serialized at all, whether `this` is captured
by reference or by content, and how large a single payload may grow before
it is truncated. The agent records exactly what you need, nothing more.

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

## Why this matters

**Data bugs are expensive.** A null pointer is found in minutes. A wrong
calculation that produces plausible results can go undetected for months.
When it's finally discovered, nobody knows what the data looked like when
it flowed through the system. With Arachna Trace attached, the answer is in
the trace -- locally in `.dft` files or centrally in ClickHouse.

**AI agents write a lot of code, and LLMs can't reliably verify it.**
Tools like Claude Code, Cursor, and Copilot generate significant
portions of application code. It compiles, tests pass, CI is green.
But does the data actually flow correctly? Self-verification — "is
my own code correct?" — is one of the genuinely unresolved problems
with current LLMs. Models are confidently wrong on a regular basis,
and asking a model to critique its own work is unreliable: the same
reasoning that produced the bug usually rationalises it. Unit tests
only cover the cases someone anticipated, and AI-written code
multiplies the unanticipated cases.

The missing piece is **evidence** — the actual values that flowed
through the code at runtime, not a model's belief about them.
Arachna Trace captures that evidence: every instrumented method
call with its arguments, return values, mutations, and exceptions,
identified by stable object IDs and Merkle content hashes. That
turns "I think this is correct" into "here is what it actually
did," which is the substrate any verification loop — whether the
reviewer is a human or another LLM — needs to land on truth instead
of on guesses.

This is the deepest reason Arachna Trace exists. The other use cases
(silent data bugs, audit substrates, debugging code you didn't
write) are real and important, but the one that most clearly is
not addressed by anything else today is: **giving AI-era development
a substrate of runtime evidence**, so the work produced by AI agents
can be verified against what the program actually does — not against
what it appears to do or what its author claims it does.

**Regulated industries can't just trust the tests.** In financial services,
defence, and healthcare, "it passes the tests" isn't enough. Auditors and
compliance teams need to verify that data flows correctly -- that a price
was calculated from the right inputs, that classified data stayed in the
right code path, that patient records were accessed only by authorized
services. Arachna Trace captures every method call with its actual data. It's
not sampling, not probabilistic -- it's a complete record that can serve
as evidence.

## How it compares

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

No other tool combines automatic value capture, mutation detection, and
object identity tracking with zero code changes.

## Use cases

The five use cases that drove the design — *Observability for
AI-generated code*, *Debugging silent data errors*, *Forensics
and audit for regulated systems*, *Understanding code you didn't
write*, *Regression detection across releases* — are written up
on the [repo root README](../README.md#what-we-built-this-for).

## Components

- **arachna-trace-agents/** -- parent dir for language-specific agents.
  Today contains only `jvm/` (the JVM agent: ByteBuddy-based bytecode
  instrumentation). Generic any-agent docs (concepts, mutation
  detection, request-id propagation) live under
  [`arachna-trace-agents/docs/`](../arachna-trace-agents/docs/).
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
