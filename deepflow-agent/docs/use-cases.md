# Use cases

Five use cases drove the design. They all rely on the same kind of
data — a recording of every captured method's inputs, outputs, and
mutations — but they read it for different reasons.

## Observability for AI-generated code

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

## Debugging silent data errors

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

## Forensics and audit support for regulated systems

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

## Understanding code you didn't write

**Problem.** Inheriting a complex codebase with thin docs, or
onboarding onto a service someone else built. Static analysis can
show what the code *can* do; what you usually want is a quick map
of what it *actually* does on a representative request.

**Approach.** Instrument the relevant packages, trigger one user
flow, read the trace. The result is the actual call tree, the
actual values that flowed, and the actual exceptions caught —
useful for orientation before changing anything.

## Regression detection across releases

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
