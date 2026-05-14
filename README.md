# Arachna Trace

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
mkdir arachna-trace && cd arachna-trace
curl -fsSLO https://raw.githubusercontent.com/gabert/arachna-trace/main/release/compose.yml
docker-compose up
```

Open <http://localhost:8080>. Full notes (services, troubleshooting,
release process) in [`release/`](release/README.md).

## Upgrading

To pull a newer release into the same `arachna-trace/` directory:

```bash
docker-compose down -v
curl -fsSLO https://raw.githubusercontent.com/gabert/arachna-trace/main/release/compose.yml
docker-compose pull
docker-compose up
```

`-v` wipes ClickHouse so the new schema initialises from scratch —
fine for the demo, since the demo container regenerates traffic on
boot. To keep existing session data across an upgrade, see
[release/README.md → Upgrading](release/README.md#upgrading) for the
schema-migration path.

## Why this exists

The deepest reason this product exists is verifying what
**AI-written code actually does at runtime**. LLM self-verification
is one of the genuinely unresolved problems in current AI: a model
that wrote a piece of code is unreliable at telling you whether
it's correct, and asking it to critique its own work is unreliable
on a regular basis — the same reasoning that produced a bug usually
rationalises it. Unit tests only cover the cases someone thought to
check, and AI-generated code multiplies the unanticipated ones.

The missing piece is **runtime evidence** — the actual values that
flowed through the code as it ran, not a model's belief about them.
Arachna Trace captures that evidence: every instrumented method call
with its arguments, return values, mutations, and exceptions,
identified by stable object IDs and Merkle content hashes. That
turns *"I think the code is correct"* into *"here is what it
actually did,"* giving any verification loop — human reviewer or
LLM agent — something to land on other than guesses. The five use
cases below all read the same trace data; this is the deepest
reason the product is shaped the way it is.

## What we built this for

Five use cases drove the design. They all read the same data — a
recording of every captured method's inputs, outputs, and
mutations — for different reasons.

### Observability for AI-generated code

AI coding tools (Claude, Cursor, Copilot, agent-style pipelines) are
now in heavy day-to-day use, and the volume of code they produce can
easily outpace what reviewers can deeply read. CI passing and a
reviewer's approval show that the structure compiles and the unit
tests cover the inputs someone anticipated. Neither shows that the
data actually flowed correctly through the new code under realistic
conditions.

Run the feature with the agent attached and capture what happened
end-to-end. A reviewer can read the trace for the changed paths, or
an LLM can query the trace store — for example, *"find every call
where `Order.total` was set to a value that doesn't equal the sum of
its line items"*. The result is runtime evidence of correctness,
alongside the unit tests' evidence of expected behaviour.

### Debugging silent data errors

Crashes give you a stack trace; data errors don't. The application
returns HTTP 200, commits to the database, and the result is wrong —
a price miscalculated, a permission check passing on the wrong path.
By the time someone notices, the runtime state that produced the bug
is gone, and reproducing it usually means another redeploy-and-
print-statement cycle.

Reproduce once with the agent attached and read the resulting trace.
Every argument, return value, and mutation is there, in the order it
happened.

### Forensics and audit for regulated systems

When an application's behaviour needs to be reconstructed after the
fact — an incident review, a regulatory inquiry, a customer complaint
about a wrong calculation — standard application logs are usually
too narrow. Logs capture what the developer chose to log, not the
full data flow that produced the result. In financial services,
healthcare, and other regulated domains, this gap tends to show up
at the worst possible moments.

A Arachna Trace trace records how data moved through the instrumented
code during a session, attributed to a specific JVM run, host, build
version, and environment. Traces of interest can be flagged
retain-forever in the trace store to survive the default 30-day TTL.

### Understanding code you didn't write

Inheriting a complex codebase with thin docs, or onboarding onto a
service someone else built. Static analysis can show what the code
*can* do; what you usually want is a quick map of what it *actually*
does on a representative request.

Instrument the relevant packages, trigger one user flow, read the
trace. The result is the actual call tree, the actual values that
flowed, and the actual exceptions caught — useful for orientation
before changing anything.

### Regression detection across releases

Unit tests check that expected behaviour is preserved across
changes. They don't catch regressions in the actual data flow: a
refactor can pass every test while silently changing which method
receives which intermediate value, or what gets mutated along the
way.

Capture a verified trace as a baseline, run the same scenario after
the change, and compare. Each captured value carries a content
hash, so the diff points at exactly which method received different
arguments, returned a different result, or mutated something it
didn't before.

## How Arachna Trace changes debugging

Experienced developers tend to converge on the same procedure when
chasing a bug: reproduce, characterize, localize, hypothesize,
probe, fix, root-cause. Each step exists because **observation is
expensive** — the developer cannot see what the program did, so they
reproduce to get another chance to look, localize to spend their
limited observation budget wisely, and hypothesize because guessing
is cheaper than measuring.

A Arachna Trace trace turns the program's behaviour — within the recorded
scope — from something the developer must reconstruct into a
queryable artifact. That collapses the seven classical steps into
three phases:

1. **Orientation.** The developer arrives with a symptom and a
   trace. Build a mental model of what the program actually did:
   where execution went, which objects were involved, the rough
   shape of the call tree.
2. **Narrowing.** Locate the anomaly through *selection* over the
   trace rather than *experiment* on the program: filter to a
   single `object_id`, find where a value first became wrong, diff
   arguments at entry and exit, compare against a known-good run.
   Each step is cheap and reversible.
3. **Explanation.** Once the bad event is found, leave the trace
   and return to the source to understand *why*. The trace tells
   you what happened with high fidelity; understanding the cause
   still belongs to the human.

Hypothesize-and-verify doesn't go away, but it gets cheaper — try
ten hypotheses in a minute, because verification is just another
selection.

For AI-assisted debugging, the same primitives — selection by
`object_id`, request correlation, content-hash diffs — are exactly
what an LLM agent needs to drive an investigation. Trace data
converts unobservable program state into ground truth: the agent is
not guessing what the program did, it is querying a record. One
supplies *what* happened; the other reasons about *why*.

## Capabilities

- **Full data capture.** Not just "method X was called" -- you see what it
  was called with, what it returned, and what blew up. Arguments, return
  values, and exceptions are serialized as JSON with type information.
  Arguments are keyed by parameter name (`{ "isbn": "9780…", "year": 1937 }`)
  when the target was compiled with `-parameters` *or* with debug info
  (`-g`, the Maven / Gradle default); falls back to `arg0..argN` for
  stripped jars. See
  [Argument names](arachna-trace-agents/docs/argument-names.md).

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

## What Arachna Trace is NOT

- **Not an APM.** APM tells you a request took 200 ms; Arachna Trace tells you
  the discount was applied to the wrong line item.
- **Not a profiler.** No flame graphs, no CPU/memory attribution. Arachna Trace
  records what happened to *data*, not where time went.
- **Not structured logs.** Logs need anticipation (`log.info(...)`); Arachna Trace
  captures values whether or not anyone thought to log them. Logs survive
  forever; traces have a 30-day TTL by default.
- **Not zero-cost.** Realistic in production with a narrow `matchers_include`;
  capturing everything everywhere on a hot service is not.

## Quick start

```bash
cd arachna-trace-agents/jvm && mvn clean install
# Produces core/agent/target/arachna-trace-agent.jar.
```

Minimal config (`arachna-agent.cfg`):

```properties
session_dump_location=/tmp
matchers_include=com\.example\.myapp\..*
```

Attach and run:

```bash
java -javaagent:path/to/arachna-trace-agent.jar="config=path/to/arachna-agent.cfg" \
     -jar your-app.jar
```

Full setup (Spring Boot, SPI resolvers, all config options) in the
[getting-started guide](arachna-trace-agents/jvm/docs/getting-started.md).

## Reading a trace

A trace fragment is plain text — timestamp, method, args, return value.
A cross-thread mutation bug or a deeply-nested change shows up as a
pair of `AR` / `AX` blocks the eye can scan. See three worked
examples — a calculation bug, a cross-thread mutation, and walking a
Merkle hash through a deep object — in
[Reading a trace](arachna-trace-agents/docs/reading-a-trace.md).

## Going deeper

The repo splits into four logical pieces, each with its own docs:

- **[arachna-trace-agents/](arachna-trace-agents/)** — language-specific
  runtime instrumentation. Today: `jvm/` (the JVM agent). Generic
  any-agent concepts (mutation detection, request-id propagation,
  truncation contract, identity model) live at
  [arachna-trace-agents/docs/](arachna-trace-agents/docs/); the JVM
  agent's quickstart, config, and internals live at
  [arachna-trace-agents/jvm/docs/](arachna-trace-agents/jvm/docs/).
- **[arachna-trace-infra/](arachna-trace-infra/)** — language-neutral
  server-side pipeline: Netty collector, Kafka-fed processor, query
  HTTP API, ClickHouse schema. Docs at
  [arachna-trace-infra/docs/](arachna-trace-infra/docs/) cover
  [deployment modes](arachna-trace-infra/docs/reference/deployment-modes.md)
  and per-component config.
- **[arachna-trace-ui/](arachna-trace-ui/)** — Vue/PrimeVue UI.
- **[spec/](spec/)** — language-neutral wire-format contract, the
  load-bearing interface between agents and infra. Start at
  [spec/SPEC.md](spec/SPEC.md).

Sample apps with the agent attached live under
[arachna-trace-demos/](arachna-trace-demos/) (today: `jvm/`).

## License

Apache License 2.0. See [LICENSE](LICENSE). Contributions welcome —
see [CONTRIBUTING.md](CONTRIBUTING.md) for extension points and
contribution guidelines.
