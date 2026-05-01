# DeepFlow

A Java application tracing tool that captures the complete runtime data flow
of your application -- method arguments, return values, exceptions, object
identity, and object mutations -- without any code changes.

Attach it via `-javaagent`, point it at your packages, reproduce the problem,
and read the trace.

## What we built this for

Five problems, one primitive: a complete, deterministic recording of
what data actually flowed through what code. Each use case consumes
that primitive differently.

### Observability for AI-generated code

**The problem.** AI agents — Claude, Cursor, Copilot, autonomous
coding pipelines — are now writing code at orders of magnitude higher
volume than humans ever did. Tests pass, CI is green, reviewers sign
off. But none of those prove the *data actually flowed correctly*
through the generated code. And no human can deeply review every PR
when 30 of them land per hour.

**How DeepFlow handles it.** Run the feature with the agent attached
and capture what actually happened. Hand the trace to a reviewer for
spot-checks, or to an LLM with SQL over the trace store for
systematic audits ("find every call where `Order.total` was set to a
value that doesn't equal the sum of its line items"). Code review
and AI code generation no longer need to scale together —
observability gives review leverage. As AI writes more code, the
need for runtime *evidence* of correctness grows in lockstep; this
is what DeepFlow exists to provide.

### Debugging silent data errors

**The problem.** Crashes are loud — stack trace, fix, done. Data
errors are silent. The app returns HTTP 200, commits to the DB, and
the value is wrong. A price is off by two cents. A permission check
passes when it shouldn't. By the time someone notices, nobody knows
what the data looked like when it flowed through the system. Print-
statement debugging means redeploy, reproduce, repeat.

**How DeepFlow handles it.** Reproduce once with the agent attached.
Read the trace forward and backward — every argument, every return
value, every mutation, timestamped to the millisecond. Read it
yourself, or feed it to an LLM with SQL access to ClickHouse. The
bug is wherever the right value goes in and the wrong one comes out;
the trace makes that location visible without breakpoints, log
additions, or another reproduction cycle.

### Audit evidence for regulated industries

**The problem.** "Tests passed" isn't enough in financial services,
healthcare, or other compliance-heavy domains. Auditors need to
verify that data was actually handled correctly — prices computed
from the right inputs, sensitive fields touched only by authorised
code paths, transactions reconciled the right way. Sampling-based
tracing is not the right artefact; what's needed is a complete,
replayable record.

**How DeepFlow handles it.** A trace is exactly that: every
instrumented method's inputs, outputs, and mutations, with content
hashes attached to every captured value, attributed to a specific
JVM run, host, build version, and environment. A flagged trace can
be marked retain-forever in ClickHouse and survive the default
30-day TTL. Evidence auditors can query, not just spot-check.

### Understanding unfamiliar code

**The problem.** Inheriting a complex codebase with thin docs, or
onboarding onto a system someone else built. Static analysis tells
you what's *possible* through the code; what you want to know is
what *actually happens* during a real user flow.

**How DeepFlow handles it.** Instrument the relevant packages,
trigger one user flow, read the trace. Real execution with real
data — the actual call tree, the actual values that flowed, the
actual exceptions caught, in the order they happened. Five minutes
of trace beats five hours of code-reading.

### Regression detection across releases

**The problem.** Unit tests check that expected behaviour is
preserved across changes. They don't check that *actual data flow*
is preserved — a refactor can pass every test while silently
changing which method receives which intermediate value or what
gets mutated when.

**How DeepFlow handles it.** Capture a verified trace as a baseline.
After the change, run the same scenario and diff. The content-hash
on every captured value lets you point at exactly which method
received different arguments, returned a different result, or
mutated something it didn't before. Regression as a diff over
actual runtime data, not just over test fixtures.

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

- **Session correlation.** Pluggable session ID resolution via SPI tags every
  trace record with an HTTP session, request ID, or custom key.

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
MS;com.example::BookService.findByAuthor(long) -> java.util::List [public]
TN;http-nio-8080-exec-3
RI;5
CL;42
TI;17
AR;[3]
TE;1730412345712
RT;VALUE
RE;[{"object_id":101,"class":"java.util.ArrayList","value":[...]}]
```

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
