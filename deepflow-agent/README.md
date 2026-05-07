# DeepFlow Agent

The Java bytecode-instrumentation agent — the producer side of DeepFlow.
Captures method arguments, return values, exceptions, object identity, and
object mutations from a target JVM without any code changes.

For solution-level context (what DeepFlow is and why), see
[../README.md](../README.md). For the language-neutral wire-format contract
that any DeepFlow agent must implement, see [docs/spec/SPEC.md](docs/spec/SPEC.md).

## Build

```bash
cd deepflow-agent
mvn clean install
```

Produces the shaded fat JAR at `core/agent/target/deepflow-agent.jar`. The
JAR is self-contained (ByteBuddy, Jackson, codec, serializer are all shaded
in). SPI resolver JARs are **not** bundled — they live on the application
classpath so they can access framework classes (Hibernate, Spring session).

## Configure and attach

Minimal `deepagent.cfg`:

```properties
session_dump_location=D:\temp
matchers_include=com\.example\.myapp\..*
```

Attach via `-javaagent`:

```bash
java -javaagent:path/to/deepflow-agent.jar="config=path/to/deepagent.cfg" \
     -jar your-app.jar
```

Spring Boot via the Maven plugin:

```bash
mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments="-javaagent:path/to/deepflow-agent.jar=config=./deepagent.cfg"
```

Inline config overrides file values:

```bash
java -javaagent:agent.jar="config=deepagent.cfg&serialize_values=false" -jar app.jar
```

For all configuration options, see
[docs/configuration.md](docs/configuration.md). For SPI resolver setup, the
Spring Boot demo, and reading traces, see
[docs/getting-started.md](docs/getting-started.md).

## Trace format

Output goes to `<session_dump_location>/SESSION-<yyyyMMdd-HHmmss>/` with one
`.dft` file per thread. A fragment looks like this:

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

| Tag | Meaning |
|-----|---------|
| `VR` | Wire-format version (`<major>.<minor>`) |
| `TS` | Timestamp at entry (milliseconds since Unix epoch) |
| `SI` | Session ID (omitted if absent) |
| `MS` | Method signature |
| `TN` | Thread name |
| `RI` | Request ID (groups all calls in one request) |
| `CL` | Caller line number |
| `CI` / `PI` | Call ID / parent call ID (UUIDs) |
| `TI` | This instance (object ID or full JSON) |
| `AR` | Arguments as JSON |
| `TE` | Timestamp at exit |
| `RT` | Return type: `VOID`, `VALUE`, or `EXCEPTION` |
| `RE` | Return/exception value as JSON |
| `AX` | Arguments at exit (for mutation detection) |

The full normative tag specification is in [docs/spec/TAGS.md](docs/spec/TAGS.md).

## Documentation

Full agent documentation is in [docs/](docs/):

- [Getting Started](docs/getting-started.md) -- build, attach, configure
- [Configuration Reference](docs/configuration.md) -- all options
- [Architecture](docs/architecture.md) -- agent data flow and modules
- [Deployment Modes](docs/deployment-modes.md) -- local, embedded, distributed
- [Trace Format](docs/spec/TAGS.md) -- rendered tag-line specification
- [Wire-format spec](docs/spec/SPEC.md) -- the language-neutral protocol contract

For contributing, see [CONTRIBUTING.md](CONTRIBUTING.md) — extension
points for IDE plugins, embedded-DuckDB mode, and non-Java agents are
described there.

## License

Apache License 2.0. See [LICENSE](../LICENSE) at the repository root.

Features: [Request ID](docs/features/request-id.md) |
[Truncation](docs/features/truncation.md) |
[Mutation Detection](docs/features/mutation-detection.md) |
[Serialize Modes](docs/features/serialize-modes.md)

## Project structure

```
deepflow-agent/
  deepagent.cfg                     Reference config (all options documented)
  core/
    agent/                          Bytecode instrumentation (entry point)
    codec/                          Object serialization with identity envelopes
    record-format/                  Binary wire format
    serializer/                     Buffer, drainer, file destination
  spi/
    session-resolver-api/           SessionIdResolver SPI interface
    session-resolver-config/        Built-in "config" resolver
    jpa-proxy-resolver-api/         JpaProxyResolver SPI interface
    jpa-proxy-resolver-hibernate/   Hibernate proxy/collection unwrapping
  server/
    record-collector-server/        Netty HTTP server (receives binary records)
    record-processor-server/        Kafka consumer → render → hash → ClickHouse
    clickhouse-init/                Schema DDL for the ClickHouse container
  demos/
    demo-spring-boot/               Working Spring Boot example
```
