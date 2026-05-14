# Concepts

The vocabulary that runs through every other doc. Read this once
to anchor the terms; each entry points to where the concept is
implemented or specified in depth.

---

## Identity

### `object_id`

Stable per-JVM long that the agent assigns to every distinct
object instance. Same `Order` instance → same `object_id`
everywhere it appears (arguments, return values, `this`).
Issued by `ObjectIdRegistry` via a `ConcurrentHashMap` keyed by
an identity-equality wrapper; backed by `WeakReference` so
garbage-collected objects don't pin the map.

Used to follow one instance through a request, watch it across
calls, and locate every payload that mentioned it (the
`payloads.object_ids` bloom-filter probe).

### `call_id`

Per-method-invocation UUID. The producer (agent) generates one
fresh UUID per `recordEntry`; the same UUID rides on both `MS`
(start) and `ME` (end). The processor pairs `MS`↔`ME` by
`call_id`, not by stack ordering — see
[bug-finding.md](bug-finding.md) and
[../internals/processor.md](../internals/processor.md). Wire
tag: **`CI`**.

### `parent_call_id`

The `call_id` of the lexically (sync) or by-submitter (async)
enclosing call. `NULL` at the root of a request. Wire tag: **`PI`**.

### `agent_run_id`

Per-JVM-run UUID. Disambiguates traces from concurrent or
sequential JVM runs that may share a `session_id` (e.g.
config-resolver users). Travels on the **transport layer** —
HTTP headers, Kafka headers, file sidecar — never inside trace
records. See [../spec/TRANSPORT.md](../spec/TRANSPORT.md).

### `session_id`

Logical session identity, supplied by the agent's
`SessionIdResolver` SPI. Typically the HTTP session ID
(spring-session resolver) or a static value (config resolver).
Per-call (every method entry/exit resolves it), not per-JVM —
multiple sessions can be live in one JVM concurrently. Wire tag:
**`SI`**.

### `request_id`

Groups all method calls that belong to one request, including
async work submitted to instrumented executors. Propagated
across `ThreadPoolExecutor` and `ForkJoinPool` boundaries — see
[request-id.md](request-id.md) and
[../internals/executor-instrumentation.md](../internals/executor-instrumentation.md).
Wire tag: **`RI`**.

---

## Content addressing

### `hash` (deep / Merkle)

MD5 over the envelope's own scalar state *plus* the hashes of
its children. Detects "anything in this subtree changed". The
right shape for **drilling down** through nested data. Lives in
`__meta__.hash` and `payloads.root_hash`. Computed by `Hasher`
in `core/codec`.

### `own_hash`

MD5 over the envelope's own scalars *plus the child ids* (not
their content). Detects "did *this* object's own data change".
Invariant under cycle-entry direction and sibling-envelope
mutations. The right shape for **flat row inspection** and the
detection predicate behind the Mutations panel. Lives in
`__meta__.own_hash` and `payloads.own_hashes`. See
[bug-finding.md](bug-finding.md) for the algorithm and why both
hashes coexist.

### `__meta__`

The metadata block injected on every envelope by
`RecordHashEnricher` during processing. Carries `id`, `class`,
`hash`, and `own_hash`. Sits alongside (not inside) the user
fields:

```json
{ "__meta__": {"id": 9, "class": "...", "hash": "...", "own_hash": "..."},
  ...userFields... }
```

---

## Ordering and pairing

### `seq`

Per-agent-run monotonic ordinal stamped on every call.
Sub-millisecond `ts_in` ties are disambiguated by `seq`, so the
narrative order of a request is well-defined even at high call
rates. Costs 24 bytes per call on the wire. Wire tag: **`SQ`**.

### `ts_in` / `ts_out`

Entry and exit timestamps (ms since epoch). Wire tags:
**`TS`** / **`TE`**.

---

## Payload kinds

Each method invocation can produce up to four payload rows,
distinguished by `kind`:

| Kind | What | Wire tag |
|---|---|---|
| `TI` | The receiver (`this` instance) — full envelope or just its id, controlled by `expand_this` | **`TI`** |
| `AR` | Arguments at method entry | **`AR`** |
| `AX` | Arguments at method exit (mutation detection) | **`AX`** |
| `RE` | Return value or thrown exception | **`RE`** |

Plus the structural marker that says which of RE applies:

| Tag | Meaning |
|---|---|
| **`RT`** | Return type: `VOID` / `VALUE` / `EXCEPTION` |

`AR`↔`AX` is the diff source for the Mutations panel.

---

## Record types on the wire

The binary record types the agent emits (`core/record-format`'s
`RecordType.java`):

| Byte | Record | Carries |
|---|---|---|
| `0x09` | `VERSION` | Wire-format version banner — first frame in every stream |
| `0x01` | `METHOD_START` | `MS`, `SI`, `TN`, `RI`, `TS`, `CL`, `CI`, `PI` |
| `0x06` | `THIS_INSTANCE` | `TI` as full CBOR envelope |
| `0x07` | `THIS_INSTANCE_REF` | `TI` as object-id reference |
| `0x02` | `ARGUMENTS` | `AR` payload |
| `0x05` | `METHOD_END` | `TE`, `TN`, `RI`, `CI` |
| `0x03` | `RETURN` | `RT=VALUE` plus `RE` payload (or `RT=VOID`) |
| `0x04` | `EXCEPTION` | `RT=EXCEPTION` plus `RE` payload (the exception envelope) |
| `0x08` | `ARGUMENTS_EXIT` | `AX` payload |
| `0x0A` | `SEQUENCE` | `SQ` ordinal for the call |

The rendered tag-line view (the format you see in `.dft` files)
is in [../spec/TAGS.md](../spec/TAGS.md); the binary frame
layout is in [../spec/WIRE-FORMAT.md](../spec/WIRE-FORMAT.md);
the Java implementation is in
[../internals/record-format.md](../internals/record-format.md).

---

## Lifecycle

### Agent run

One JVM-with-agent process from `premain` to JVM exit. Issues
one `agent_run_id` at startup; emits trace records until
shutdown. Identity (`agent_run_id`, hostname, agent_version,
code_version, env, jvm_pid, started_at_millis) rides on
transport-layer metadata.

### Session

A logical scope inside an agent run, named by `session_id`.
Multiple sessions can be live concurrently in one JVM
(spring-session resolver, request-bound resolvers); a single
`session_id` can span multiple JVM runs (config resolver).
Persisted as `arachna_trace.sessions` keyed by `(agent_run_id,
session_id)`.

### Request

A logical work unit inside a session, named by `request_id`.
Groups all calls — including async dispatch via instrumented
executors — under one request. Persisted as `arachna_trace.requests`
(rolled up from `arachna_trace.calls` by `requests_mv`).

### Call

One instrumented method invocation, named by `call_id`.
Persisted as `arachna_trace.calls`, with up to four payload rows in
`arachna_trace.payloads` (one per `kind`).

---

## Storage

### `arachna_trace.calls`

One row per method invocation. Columns include `agent_run_id`,
`call_id`, `parent_call_id`, `session_id`, `request_id`,
`thread_name`, `ts_in`, `ts_out`, `signature`, `return_type`,
`this_id`, `seq`, `retain`. Light. For session/request/time
queries.

### `arachna_trace.payloads`

One row per `(call_id, kind)`. Heavy — carries `payload_json`,
`root_hash`, `object_ids[]`, `own_hashes[]`, `payload_tokens[]`.
Bloom-filter indexed on `object_ids` (find-by-instance), 
`own_hashes` (find-by-own-state), `payload_tokens` (value
search / provenance), `call_id` (join key).

### `arachna_trace.agent_runs`

One row per JVM-with-agent run (`ReplacingMergeTree`,
idempotent re-insert).

### `arachna_trace.sessions`

One row per `(session_id, agent_run_id)` pair seen
(`ReplacingMergeTree`).

### `arachna_trace.requests`

`AggregatingMergeTree` rollup maintained by `requests_mv`
materialized view off every `calls` insert. Read via
`requests_view`. Carries `started_at`, `ended_at`,
`call_count`, `exception_count`, `entry_signature`,
`thread_name`.

Full schema: `server/clickhouse-init/01-schema.sql`.

---

## Configurable surfaces

### Matchers

Regex patterns matched against fully-qualified class names.
`matchers_include` (which classes to instrument) and
`matchers_exclude` (which to skip). The agent's own packages
are always excluded to prevent recursion.

### `emit_tags`

Comma-separated list of trace tags the agent emits. Filters
both what gets serialized and what appears in `.dft`. `MS` is
forced; everything else is opt-in or opt-out. See
[agent-config.md](agent-config.md).

### SPIs

Pluggable extension points loaded via `ServiceLoader`:

- **`SessionIdResolver`** — supplies `session_id` per call.
  Built-in `config`, demo-shipped `spring-session`. See
  [session-resolver.md](session-resolver.md).
- **`JpaProxyResolver`** — unwraps framework proxies before
  serialization. Built-in `hibernate`. See
  [jpa-proxy-resolver.md](jpa-proxy-resolver.md).
