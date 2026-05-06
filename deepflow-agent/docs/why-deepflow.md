# How DeepFlow Changes Debugging

## The classical debugging session

Experienced developers tend to converge on roughly the same procedure
when chasing a bug:

1. **Reproduce** the failure reliably.
2. **Characterize** the symptoms — error message, expected vs. actual,
   environment.
3. **Localize** the suspect surface, often by bisection.
4. **Hypothesize** a specific, testable theory of the cause.
5. **Probe** by adding logs, attaching a debugger, inspecting state.
6. **Fix and verify** — apply the change and confirm the bug and its
   siblings are gone.
7. **Root-cause and record** — was this a symptom or a cause? What
   guardrail prevents recurrence?

Each step exists because **observation is expensive**. The developer
cannot see what the program did, so they reproduce to get another
chance to look, localize to spend their limited observation budget
wisely, hypothesize because guessing is cheaper than measuring, and
probe to spend that budget on the most promising spot.

## What changes when observation is no longer scarce

A DeepFlow trace turns the program's behaviour — within the recorded
scope — from something the developer must reconstruct into a queryable
artifact. That collapses the seven steps into three phases:

1. **Orientation.** The developer arrives with a symptom and a trace.
   The first task is not to form a hypothesis but to build a mental
   model of what the program actually did — where execution went, which
   objects were involved, the rough shape of the call tree.

2. **Narrowing.** With the territory understood, the developer locates
   the anomaly through *selection* over the trace rather than
   *experiment* on the program: filter to a single `object_id`, find
   where a value first became wrong, diff arguments at entry and exit,
   compare against a known-good run. Each step is cheap and reversible.

3. **Explanation.** Once the bad event is found, the developer leaves
   the trace and returns to the source code to understand *why*. The
   trace tells you what happened with high fidelity; understanding the
   cause still belongs to the human.

Hypothesize-and-verify doesn't go away, but it gets cheaper. The
developer can be looser with hypotheses — try ten in a minute — because
verification is just another selection.

## Why this matters for AI-assisted debugging

Language models are at their weakest when reasoning about unobservable
program state — they don't know which branch was taken, which value an
argument actually held, or which `Order` instance got mutated. A trace
converts that unobservable state into ground truth: an AI agent
debugging with this kind of data is not guessing what the program did,
it is querying a record. The same primitives that make the session
fluent for a human — selection by `object_id`, request correlation,
content-hash diffs — are exactly what an AI assistant needs to drive an
investigation. One supplies *what* happened; the other reasons about
*why*.
