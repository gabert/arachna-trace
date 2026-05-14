# Plain Java Demo

The smallest possible "agent attached, traces produced" example.
Two classes, no framework dependencies. Plus one deliberate
in-place mutation so you can see AR/AX side-by-side without
JPA, Spring, or a database in the way.

For the realistic integration (Spring Boot, JPA proxy unwrapping,
HTTP session tracking), see [`demo-spring-boot/`](../demo-spring-boot/).

## What it does

`Main` builds a `Greeter`, calls `greet("World")`, then calls
`sneakyMutate(items)` on a small `ArrayList`. `sneakyMutate`
appends an item *and* overwrites index 0 — a textbook silent
mutation that the trace makes visible.

## Build

From the project root:

```bash
cd arachna-trace-agents/jvm
mvn clean install
cd ../../arachna-trace-demos/jvm
mvn clean install
```

This produces the agent JAR at
`arachna-trace-agents/jvm/core/agent/target/arachna-trace-agent.jar`
and the demo JAR at `arachna-trace-demos/jvm/demo-plain/target/`.

## Run

```bash
cd arachna-trace-demos/jvm/demo-plain
java -javaagent:../../../arachna-trace-agents/jvm/core/agent/target/arachna-trace-agent.jar="config=./arachna-agent.cfg" \
     -cp target/classes com.github.gabert.arachna.trace.demo.plain.Main
```

`arachna-agent.cfg` uses the file destination (`session_dump_location`
defaults to `D:\temp` on Windows; edit for your OS). Each run
creates a new `SESSION-<ts>/` directory with one `.dft` per
thread.

To see the mutation: enable `AX` in `emit_tags` (commented hint
at the top of `arachna-agent.cfg`) and look for the AR / AX block on
`sneakyMutate` — AR shows `["original"]`, AX shows
`["CHANGED", "sneaky"]`.

For how to read the resulting `.dft` files, see
[`../../../arachna-trace-agents/docs/reading-a-trace.md`](../../../arachna-trace-agents/docs/reading-a-trace.md).
