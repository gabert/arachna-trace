# Porting Guide — Writing a Arachna Trace Agent in Your Language

**Informative.** This document walks through implementing a Arachna Trace
agent for a language other than Java. It does not introduce new
normative requirements; it explains how to satisfy the existing ones
in practice.

If anything here contradicts the normative documents
([SPEC.md](SPEC.md), [WIRE-FORMAT.md](WIRE-FORMAT.md),
[CBOR-ENVELOPE.md](CBOR-ENVELOPE.md), [HASHING.md](HASHING.md),
[TAGS.md](TAGS.md), [TRANSPORT.md](TRANSPORT.md),
[IDENTITY-MODEL.md](IDENTITY-MODEL.md)) — the normative documents
win.

## 1. Decide the scope first

Before any code, decide what your agent will instrument. The wire
format does not care; the cost of capture does. The Java agent
captures every method on every matched class. A Python agent might
capture only top-level Flask handlers and SQLAlchemy queries. A Go
agent might require explicit decorator-style opt-in per function.

Smaller scope = simpler implementation. Start narrow.

Three sane starting scopes:

- **One function manually wrapped.** Useful smoke test of the wire
  format end-to-end before any auto-instrumentation.
- **All exported functions in one module/package.** Useful first
  real test.
- **All functions matched by a regex on fully-qualified name.** The
  reference Java agent's model.

## 2. Minimum viable agent — six things to ship

To produce a usable trace stream you need:

1. **A way to intercept function/method entry and exit.** Language-
   specific (decorators, monkey-patching, AST rewrite, bytecode
   instrumentation, source-time codegen, FFI hooks).
2. **An identity registry.** Per [IDENTITY-MODEL.md](IDENTITY-MODEL.md) —
   pick one of the three strategies for your language.
3. **A CBOR encoder** capable of writing the envelope shape from
   [CBOR-ENVELOPE.md](CBOR-ENVELOPE.md). All major languages have
   one; the work is wrapping each captured value in the envelope
   map.
4. **A wire-format writer** producing the binary frames in
   [WIRE-FORMAT.md](WIRE-FORMAT.md). Pure bytes; no library needed.
5. **A destination.** Either:
   - **HTTP destination** — POST batches to a collector. ~30 lines
     in any language.
   - **File destination** — write one `.dft` file per thread + one
     `run.json` sidecar (per [TRANSPORT.md §5](TRANSPORT.md)).
   The file destination is easier to debug end-to-end without
   running the collector + Kafka + processor stack.
6. **Per-call state on the executing thread/coroutine** — a stack
   of `(call_id, request_id, parent_call_id)` so the entry hook can
   set `parent_call_id = top.call_id` and the exit hook can pop.

That is the entire MVP. Skip configuration files, sampling, mutation
tracking (`ARGUMENTS_EXIT`), the `SEQUENCE` record (causal ordering —
see step 10 below), session-id resolution, and JPA-proxy unwrapping
for v1.

## 3. The recommended build order

This sequence keeps you in working state at each step.

**Step 1 — Hello world:** emit a hard-coded `VERSION` record + one
fully-fabricated METHOD_START / METHOD_END pair to a local file.
Verify the bytes match
[WIRE-FORMAT.md §7's worked example](WIRE-FORMAT.md) (or one of the
golden tests in
`arachna-trace-shared/codec/src/test/java/.../recorder/WireFormatGoldenTest.java`).
The simplest cross-check: compute the expected payload length
yourself, compare to the byte that should appear at offsets 1–4 of
each frame. If those four bytes match the goldens for matching
inputs, your framing is correct.

Optionally, drop your bytes into the reference `RecordReader` from
a tiny Java harness:

```java
byte[] bytes = Files.readAllBytes(Path.of("hello.bin"));
List<TraceRecord> records = RecordReader.readAll(bytes);
records.forEach(r -> System.out.println(r));
```

If `readAll` throws `Truncated record at offset N`, your length
prefix is wrong; if it throws `Unknown record type`, your type byte
is wrong. Both are easy to fix early.

**Step 2 — Real method instrumentation:** swap the hard-coded
records for ones produced by your interception mechanism. Trace one
function. Stack of `call_id`s on a thread-local. Verify output
matches the reference's expectations on a second known function.

**Step 3 — Argument capture without identity:** add `ARGUMENTS`
records with bare CBOR primitives and arrays. No envelope yet. The
reference processor will accept them but won't have stable IDs to
join on — fine for a first end-to-end.

**Step 4 — Identity envelope:** wrap captured composite objects in
the envelope. Implement your chosen strategy from
IDENTITY-MODEL.md. Verify in the reference processor that
`payloads.object_ids` for your records is non-empty and that the
same instance gets the same id across two records.

**Step 5 — `this` capture:** add THIS_INSTANCE / THIS_INSTANCE_REF
on instance methods (where applicable to your language).

**Step 6 — Return / Exception:** add RETURN and EXCEPTION records.
Verify void vs value vs exception render correctly.

**Step 7 — HTTP transport:** swap file destination for HTTP. Add
the seven `X-Arachna-Trace-*` headers (TRANSPORT.md §3). Verify records
land in Kafka with the headers attached.

**Step 8 — Async / cross-thread propagation:** propagate
`request_id` (and the `parent_call_id` stack as appropriate) across
your language's async boundaries (futures, goroutines, coroutines).
Without this, async-spawned calls look like new request roots and
the call tree breaks.

**Step 9 — `ARGUMENTS_EXIT` (mutation capture):** re-serialize
arguments at exit so the processor's hash compare can detect
mutations. Optional but high-value.

**Step 10 — `SEQUENCE` (causal ordering):** emit one `SEQUENCE`
record per method invocation, carrying the call's UUID and a
per-agent-run monotonic ordinal. The consumer uses it to
disambiguate sub-millisecond `ts_in` ties — without `SEQUENCE`,
calls with the same millisecond timestamp end up in undefined
order, which breaks narrative reading of dense request traces.
Costs 24 bytes per call on the wire. Optional but recommended;
the reference Java agent emits it by default
([TAGS.md §SQ](TAGS.md)).

## 4. CBOR encoding — what the envelope looks like in code

For a captured composite object, your encoder should produce:

```
map(3 entries):
  key int(1)  → uint(<object_id>)
  key int(2)  → text("<class.name>")
  key int(3)  → <encoded value content>
```

Most CBOR libraries expose either a streaming API (`writeFieldId(1)`
etc.) or a tree API (`{1: id, 2: name, 3: value}`). Either works.
What matters is that the keys are CBOR integers, not strings — a
consumer that sees string keys `"1"`, `"2"`, `"3"` will reject the
envelope.

For a cycle reference (when the encoder has already started writing
this same instance higher up the stack):

```
map(2 entries):
  key int(4)  → uint(<previously-assigned id>)
  key int(5)  → bool(true)
```

Maintain the cycle-detection set per top-level encode call (one
ARGUMENTS record, one RETURN record, …) — not globally.

## 5. File destination details

The file destination is the easiest path to a working agent. Layout:

```
<dump_location>/SESSION-<yyyyMMdd-HHmmss>/
   run.json
   <yyyyMMdd-HHmmss>-<thread_or_coroutine_name>.dft
```

`run.json` carries the `AgentRun` fields as snake_case JSON
(TRANSPORT.md §5.2). Each `.dft` file starts with `VR;<major>.<minor>`.

The reference processor has a "directory-replay" mode (not yet
shipped, but trivial: walk a SESSION directory, read run.json,
parse each `.dft` as a tag stream, emit ParsedCalls). Until that
ships, you can validate file output by hand or by writing a
file-replay client of your own.

## 6. HTTP destination details

POST to `<collector>/records` with `Content-Type:
application/octet-stream`. Body = concatenated wire-format frames.
Headers per TRANSPORT.md §3.1. Response 200 = accepted.

Batching guidance: 32–256 KiB per POST is a reasonable default. The
reference Java agent flushes at 64 KiB.

Handle network errors at-most-once: log and discard. Don't retry
unless you're sure your collector deduplicates (the reference one
does not).

## 7. Identity in your language — quick reference

See [IDENTITY-MODEL.md](IDENTITY-MODEL.md) for the full design
discussion. Quick map:

| Language | Recommended path |
|---|---|
| Python | `weakref.finalize` with a per-id eviction callback. |
| C# | `ConditionalWeakTable<object, IdBox>`. |
| JavaScript | `WeakMap` keyed by object, BigInt counter. |
| Go | `runtime.SetFinalizer` over pointer-bearing values. Document the lifetime extension. |
| Rust | Identity at `Arc<T>` / `Rc<T>` granularity; per-call IDs for everything else. |
| Other | Match against the table in IDENTITY-MODEL.md §3. |

## 8. Cross-thread / async propagation (the part everyone forgets)

Every traced async dispatch boundary must propagate
**`request_id`** (so the spawned work joins the same request) and
the **current `call_id`** (so the spawned work's first traced call
gets the right `parent_call_id`).

The reference Java agent does this by instrumenting
`ThreadPoolExecutor.execute` and `ForkJoinPool.execute` to wrap the
submitted Runnable in one that copies the parent thread's state into
the worker thread before calling the original Runnable.

For your language:

- **Python `asyncio`** — propagate via `contextvars.ContextVar`,
  copied automatically by `asyncio.create_task`.
- **Python `threading.Thread`** — wrap `Thread.start` or hand off
  via an explicit context object.
- **Go** — `context.Context` is the natural carrier. Wrap your
  goroutine launch helper to attach a "arachna-trace context" to the
  child's `ctx`.
- **JavaScript** — `AsyncLocalStorage` (Node) or the experimental
  `AsyncContext` proposal (browsers).
- **C#** — `AsyncLocal<T>` propagates across `await`.

If you skip propagation, async-spawned work appears as new request
roots. The trace is still well-formed; it just loses cross-async
linkage.

## 9. Testing your agent — minimum cross-implementation test

A useful conformance test:

1. Generate a known sequence of calls in your language, with known
   identity behavior (one instance shared, one mutated, one fresh).
2. Pipe the output through the reference processor into ClickHouse.
3. Run a fixed query set. Compare results to the same query set on
   output from the reference Java agent given the same logical
   workload.

The reference test corpus that `WireFormatGoldenTest` uses for byte-
level pinning will eventually be promoted to a portable
language-agnostic conformance corpus — until then, use the Java
goldens via JNI / subprocess if you want byte-level tests.

## 10. Where to ask

- Issues against this spec or its prose: file in this repo.
- Design questions about identity / hashing semantics that the spec
  doesn't answer: file in this repo with a `[spec]` prefix.
- Reference-agent bugs: file in this repo against the appropriate
  module path.

A future RFC process and a separate `arachna-trace-spec` repo will follow
once there is more than one implementation in the wild.

## 11. The 80/20 — what to ship first

If you only have a weekend:

- VERSION + METHOD_START + METHOD_END + RETURN + ARGUMENTS records.
- File destination only.
- Per-call identity (Strategy B from IDENTITY-MODEL.md), no
  cross-call ID stability.
- One thread; no async propagation.
- No SEQUENCE record. Ordering will fall back to ms-resolution
  `ts_in`; ms-tied calls land in undefined order. Acceptable for
  smoke testing.
- No EXCEPTION record. Wrap your interception in your language's
  try/finally so a thrown error still emits METHOD_END (with no
  RETURN) — the consumer treats that as a void exit. **Do not** let
  exceptions skip METHOD_END: if METHOD_END never lands, the
  consumer's open-call entry leaks until TTL eviction (see the
  reference `RecordParser.evictStaleOpenCalls`), which is a
  legitimate fallback for crashes but not for normal exception
  paths.

That's enough to render a working call tree in the existing tools,
prove the format is portable, and unblock the rest.
