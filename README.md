# DeepFlow

A Java application tracing tool that captures the complete runtime data flow
of your application -- method arguments, return values, exceptions, object
identity, and object mutations -- without any code changes.

Attach it via `-javaagent`, point it at your packages, reproduce the problem,
and read the trace.

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
  use cases, comparison with APM and profilers.
- **[deepflow-agent/docs/](deepflow-agent/docs/)** -- the Java agent and
  pipeline: [getting started](deepflow-agent/docs/getting-started.md),
  [configuration](deepflow-agent/docs/configuration.md),
  [architecture](deepflow-agent/docs/architecture.md), features, internals,
  SPI. Also home to the
  [language-neutral wire-format spec](deepflow-agent/docs/spec/SPEC.md).
