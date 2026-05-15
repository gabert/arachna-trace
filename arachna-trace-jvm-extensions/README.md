# arachna-trace-jvm-extensions

Reference implementations of the Arachna Trace SPI contracts for the JVM ecosystem.

The SPI **interfaces** (the contract) live in
[`../arachna-trace-shared/spi/`](../arachna-trace-shared/spi/) — pure Java
interfaces, zero dependencies, normative.

The SPI **implementations** (this directory) are each a self-contained JAR
that any JVM application can drop onto its classpath and activate via the
agent's `arachna-agent.cfg` config.

## Modules

| Module | Implements | Activate with | Purpose |
|---|---|---|---|
| [`session-resolver-config/`](session-resolver-config/) | `SessionIdResolver` | `session_resolver=config` | Static / externally-supplied session ID — read `session_id` from agent config. Useful for CLI tools, batch jobs, single-tenant services, debugging. |
| [`session-resolver-spring/`](session-resolver-spring/) | `SessionIdResolver` | `session_resolver=spring-session` | HTTP session ID for any Jakarta Servlet web app (Spring Boot included). Drop the JAR on the classpath; Spring auto-discovers the included `@Component` filter. |
| [`jpa-proxy-resolver-hibernate/`](jpa-proxy-resolver-hibernate/) | `JpaProxyResolver` | `jpa_proxy_resolver=hibernate` | Unwrap Hibernate proxy entities and persistent collections into their concrete state, so `AR` / `AX` / `RE` payloads carry the real data instead of `<proxy>`. |

## Writing your own

Each module here is a complete worked example of the pattern. To write your
own SPI impl:

1. **Pick the right SPI interface** in `arachna-trace-shared/spi/`.
2. **Create a new Maven module.** Look at any module in this directory — the
   pom shows the entire dependency footprint (one line for the SPI api jar,
   plus whatever framework you're integrating with).
3. **Implement the interface.** A single class is usually enough.
4. **Register it via Java ServiceLoader** by adding a file at
   `src/main/resources/META-INF/services/<fully-qualified-interface-name>`
   containing the fully-qualified name of your impl class.
5. **Activate it** via the matching `*_resolver=<your-name>` key in
   `arachna-agent.cfg`. The `name()` method on your impl returns the value
   the agent matches against.

That's the entire recipe. The reference impls in this directory don't do
anything more elaborate — what you see is what you get.

**Before you go to production, read
[SPI wiring](../arachna-trace-agents/jvm/docs/reference/spi-wiring.md).**
It walks the full chain end-to-end — what each file does, how the agent's
ServiceLoader picks impls, the common failure modes (with the exact error
messages they produce), and the inspection commands you'd run against a
deployed JVM to verify each piece. Worth ~5 minutes of reading; saves
hours of "why isn't my resolver being used?" later.

## Why a separate reactor

These impls have **no dependency on the agent's internals** — no ByteBuddy,
no codec, no renderer, nothing. They depend only on the SPI api jar and
their target framework. Keeping them in their own top-level reactor (rather
than co-located with the agent's bytecode-instrumentation guts) makes that
boundary physically obvious: the agent and the SPI impls are different
software, with different audiences, ad you compose them at runtime via the
classpath.

A future Python or Go agent will ship its own parallel
`arachna-trace-python-extensions/` / `arachna-trace-go-extensions/` reactor
with the same shape: language-specific impls of the same shared SPI
contracts.
