# DeepFlow

A Java application tracing tool that captures the complete runtime data flow
of your application -- method arguments, return values, exceptions, object
identity, and object mutations -- without any code changes.

Attach it via `-javaagent`, point it at your packages, reproduce the problem,
and read the trace.

## What we built this for

Five use cases drove the design. They all rely on the same kind of
data — a recording of every captured method's inputs, outputs, and
mutations — but they read it for different reasons.

### Observability for AI-generated code

**Problem.** AI coding tools (Claude, Cursor, Copilot, agent-style
pipelines) are now in heavy day-to-day use, and the volume of code
they produce can easily outpace what reviewers can deeply read.
CI passing and a reviewer's approval show that the structure
compiles and the unit tests cover the inputs someone anticipated.
Neither shows that the data actually flowed correctly through the
new code under realistic conditions.

**Approach.** Run the feature with the agent attached and capture
what happened end to end. A reviewer can read the trace for the
changed paths, or an LLM can query the trace store with SQL — for
example, "find every call where `Order.total` was set to a value
that doesn't equal the sum of its line items". The result is
runtime evidence of correctness, alongside the unit tests' evidence
of expected behaviour.

### Debugging silent data errors

**Problem.** Crashes give you a stack trace; data errors don't. The
application returns HTTP 200, commits to the database, and the
result is wrong — a price miscalculated, a permission check passing
on the wrong path. By the time someone notices, the runtime state
that produced the bug is gone, and reproducing it usually means
another redeploy-and-print-statement cycle.

**Approach.** Reproduce once with the agent attached and read the
resulting trace. Every argument, return value, and mutation is
there, in the order it happened. You can read it yourself or query
it via ClickHouse.

### Forensics and audit support for regulated systems

**Problem.** When an application's behaviour needs to be
reconstructed after the fact — an incident review, a regulatory
inquiry, a customer complaint about a wrong calculation, an
internal compliance check — standard application logs are usually
too narrow. Logs capture what the developer chose to log, not the
full data flow that produced the result. In financial services,
healthcare, and other regulated domains, this gap tends to show up
at the worst possible moments.

**Approach.** A DeepFlow trace records how data moved through the
instrumented code during a session, attributed to a specific JVM
run, host, build version, and environment. Traces of interest can
be flagged retain-forever in the trace store to survive the
default 30-day TTL. This is not a replacement for normal audit
tooling (access logs, change-management records, controlled
tests) — it's the kind of forensic evidence those tools cannot
produce on their own.

### Understanding code you didn't write

**Problem.** Inheriting a complex codebase with thin docs, or
onboarding onto a service someone else built. Static analysis can
show what the code *can* do; what you usually want is a quick map
of what it *actually* does on a representative request.

**Approach.** Instrument the relevant packages, trigger one user
flow, read the trace. The result is the actual call tree, the
actual values that flowed, and the actual exceptions caught —
useful for orientation before changing anything.

### Regression detection across releases

**Problem.** Unit tests check that expected behaviour is preserved
across changes. They don't catch regressions in the actual data
flow: a refactor can pass every test while silently changing which
method receives which intermediate value, or what gets mutated
along the way.

**Approach.** Capture a verified trace as a baseline, run the same
scenario after the change, and compare. Each captured value
carries a content hash, so the diff points at exactly which method
received different arguments, returned a different result, or
mutated something it didn't before.

## Capabilities

- **Full data capture.** Not just "method X was called" -- you see what it
  was called with, what it returned, and what blew up. Arguments, return
  values, and exceptions are serialized as JSON with type information.

- **Object identity tracking.** Every object instance receives a stable unique
  ID. When the same `Order` passes through `validate`, `calculateTax`, and
  `save`, all three share the same `object_id`. If the contents changed, you
  see which method mutated it.

- **Mutation detection.** Capture arguments at both method entry and exit
  and compare them to find which methods modify their inputs.

- **Request correlation.** Every method call carries a request ID that groups
  all calls in a single request, including nested ones, and propagates across
  `ThreadPoolExecutor` / `ForkJoinPool` submissions so async work joins the
  originating request.

- **Session correlation.** A pluggable SPI tags every trace record with a
  session ID — an HTTP session, a request ID, or any custom correlation key.

- **JPA proxy unwrapping.** Hibernate proxies and collection wrappers are
  resolved to real objects before serialization.

- **Two destinations.** Write per-thread `.dft` files locally (great for
  development), or POST binary records to a collector server that lands them
  in ClickHouse via Kafka (great for shared environments).

- **Configurable depth.** Skip serialization entirely (`serialize_values=false`)
  for dead-code detection, or cap individual payload size (`max_value_size`)
  to keep large objects from dominating traces.

- **Zero application dependencies.** Self-contained fat JAR.

## What DeepFlow is NOT

- **Not an APM.** OpenTelemetry, Datadog, and New Relic capture spans
  at service boundaries — HTTP requests, DB calls, microservice hops.
  DeepFlow captures every method the agent is configured to
  instrument, including the *values* that flowed through. The two
  are complementary, not alternatives: APM tells you a request took
  200 ms; DeepFlow tells you that the discount was applied to the
  wrong line item.

- **Not a profiler.** No sampling, no flame graphs, no CPU or memory
  attribution. DeepFlow records what the code did with data, not
  where the time went.

- **Not structured logs at scale.** Logs require explicit
  `log.info(...)` calls — every interesting value has to be
  anticipated by whoever wrote that line. DeepFlow captures the
  values whether or not anyone thought to log them, with no source
  changes. Trade-off: logs survive forever; DeepFlow traces have a
  30-day TTL by default.

- **Not zero-cost.** Capturing every value flowing through every
  matched method has overhead. Production use is realistic with a
  narrow `matchers_include` filter and value-size truncation;
  capturing *everything* in *every* class on a hot service is not.

## Quick start

```bash
cd deepflow-agent
mvn clean install
```

Produces `core/agent/target/deepflow-agent.jar`.

Create `deepagent.cfg` next to your application:

```properties
session_dump_location=D:\temp
matchers_include=com\.example\.myapp\..*
```

Attach and run:

```bash
java -javaagent:path/to/deepflow-agent.jar="config=path/to/deepagent.cfg" \
     -jar your-app.jar
```

Read the trace:

```bash
ls D:/temp/SESSION-*/
head -30 D:/temp/SESSION-*/main.dft
```

A trace fragment looks like this:

```
VR;1.3
TS;1730412345678
SI;alice-session-01
MS;com.example::Pricing.applyDiscount(int, int) -> int [public static]
TN;http-nio-8080-exec-3
RI;5
CL;42
AR;[100, 10]
TE;1730412345680
RT;VALUE
RE;1000
```

How to read it. The first line is the wire-format version. Then comes
the entry block: at timestamp `TS` (epoch ms), inside session
`alice-session-01`, on thread `http-nio-8080-exec-3` as part of
request `RI=5`, the method `Pricing.applyDiscount` was called from
caller line 42 with arguments `[100, 10]` — price 100, discount
percent 10. The exit block records that the call returned 2 ms later
with the value `1000`.

The bug is visible directly. A 10% discount on a price of 100 should
return 90, not 1000 — `applyDiscount` is multiplying where it should
be subtracting. Finding this without the trace would typically mean
adding a print statement to that method and running the scenario
again.

For Spring Boot, attach via the Maven plugin:

```bash
mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments="-javaagent:path/to/deepflow-agent.jar=config=./deepagent.cfg"
```

For deeper integration (SPI resolvers on the application classpath, demo
walkthrough, all configuration options), see the
[agent's getting-started guide](deepflow-agent/docs/getting-started.md).

## Components

- **[deepflow-agent/](deepflow-agent/)** -- Java multi-module project
  containing everything: the bytecode-instrumentation agent itself, the
  Netty collector that ingests POSTs, the Kafka-fed processor that
  renders, hashes, and inserts into ClickHouse, the ClickHouse schema,
  the wire-format spec, and a Spring Boot demo.

## Documentation

Documentation is split between solution-level and component-level:

- **[doc/](doc/)** -- the solution: what DeepFlow is, problem framing,
  use cases, comparison with APM and profilers, plus the
  [system-wide architecture](doc/architecture.md).
- **[deepflow-agent/docs/](deepflow-agent/docs/)** -- the Java agent and
  pipeline: [getting started](deepflow-agent/docs/getting-started.md),
  [configuration](deepflow-agent/docs/configuration.md),
  [architecture](deepflow-agent/docs/architecture.md), features, internals,
  SPI. Also home to the
  [language-neutral wire-format spec](deepflow-agent/docs/spec/SPEC.md).
