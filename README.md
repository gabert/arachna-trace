# DeepFlow

A Java application tracing tool that captures the complete runtime data flow
of your application -- method arguments, return values, exceptions, object
identity, and object mutations -- without any code changes.

Attach it via `-javaagent`, point it at your packages, reproduce the problem,
and read the trace.

## Try it in 60 seconds

Only Docker plus Docker Compose are required. Docker Desktop
(Mac / Windows) ships Compose. On stock Linux, install it with:

```bash
sudo apt install docker-compose       # Debian / Ubuntu
sudo dnf install docker-compose       # Fedora / RHEL
```

The demo runs a Spring Boot app with the agent already attached,
fires a small traffic burst on boot, and lands you on populated
traces.

```bash
mkdir deepflow && cd deepflow
curl -fsSLO https://raw.githubusercontent.com/gabert/deepflow/main/release/compose.yml
docker-compose up
```

Open <http://localhost:8080>. Full notes (services, troubleshooting,
release process) in [`release/`](release/README.md).

## What we built this for

Five use cases drove the design — same data captured for each, read for
different reasons.

- **Observability for AI-generated code.** CI shows the structure
  compiled; a trace shows the data actually flowed correctly.
- **Debugging silent data errors.** HTTP 200 with the wrong number —
  no stack trace, but every value is in the trace.
- **Forensics in regulated systems.** A queryable record of what a JVM
  did during a session, attributed to host / build / env.
- **Understanding code you didn't write.** The actual call tree on a
  representative request, not what static analysis claims.
- **Regression detection.** Capture a baseline, run after the change,
  diff by content hash.

Full case studies in [Use cases](deepflow-agent/docs/use-cases.md).
DeepFlow also changes the *shape* of a debugging session — see
[How DeepFlow changes debugging](deepflow-agent/docs/why-deepflow.md).

## Capabilities

- **Full data capture.** Not just "method X was called" -- you see what it
  was called with, what it returned, and what blew up. Arguments, return
  values, and exceptions are serialized as JSON with type information.
  Arguments are keyed by parameter name (`{ "isbn": "9780…", "year": 1937 }`)
  when the target was compiled with `-parameters` *or* with debug info
  (`-g`, the Maven / Gradle default); falls back to `arg0..argN` for
  stripped jars. See
  [Argument names](deepflow-agent/docs/features/argument-names.md).

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

- **Not an APM.** APM tells you a request took 200 ms; DeepFlow tells you
  the discount was applied to the wrong line item.
- **Not a profiler.** No flame graphs, no CPU/memory attribution. DeepFlow
  records what happened to *data*, not where time went.
- **Not structured logs.** Logs need anticipation (`log.info(...)`); DeepFlow
  captures values whether or not anyone thought to log them. Logs survive
  forever; traces have a 30-day TTL by default.
- **Not zero-cost.** Realistic in production with a narrow `matchers_include`;
  capturing everything everywhere on a hot service is not.

## Quick start

```bash
cd deepflow-agent && mvn clean install
# Produces core/agent/target/deepflow-agent.jar.
```

Minimal config (`deepagent.cfg`):

```properties
session_dump_location=/tmp
matchers_include=com\.example\.myapp\..*
```

Attach and run:

```bash
java -javaagent:path/to/deepflow-agent.jar="config=path/to/deepagent.cfg" \
     -jar your-app.jar
```

Full setup (Spring Boot, SPI resolvers, all config options) in the
[getting-started guide](deepflow-agent/docs/getting-started.md).

## Reading a trace

A trace fragment is plain text — timestamp, method, args, return value.
A cross-thread mutation bug or a deeply-nested change shows up as a
pair of `AR` / `AX` blocks the eye can scan. See three worked
examples — a calculation bug, a cross-thread mutation, and walking a
Merkle hash through a deep object — in
[Reading a trace](deepflow-agent/docs/reading-a-trace.md).

## Going deeper

- **[deepflow-agent/](deepflow-agent/)** — Java multi-module project: the
  bytecode-instrumentation agent, the Netty collector, the Kafka-fed
  processor, the ClickHouse schema, the wire-format spec, and a Spring
  Boot demo.
- **[deepflow-agent/docs/](deepflow-agent/docs/)** —
  [getting started](deepflow-agent/docs/getting-started.md),
  [configuration](deepflow-agent/docs/configuration.md),
  [architecture](deepflow-agent/docs/architecture.md),
  [deployment modes](deepflow-agent/docs/deployment-modes.md), features,
  internals, SPI, and the
  [language-neutral wire-format spec](deepflow-agent/docs/spec/SPEC.md).

## License

Apache License 2.0. See [LICENSE](LICENSE). Contributions welcome —
see [CONTRIBUTING.md](deepflow-agent/CONTRIBUTING.md) for extension
points and contribution guidelines.
