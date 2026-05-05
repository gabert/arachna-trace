# Rendered Text View — Tag Lines

**Normative for any consumer that produces, persists, or accepts the
rendered text view.** Optional for agents — agents produce the binary
wire format; the text view is a downstream rendering used by file
output and processor pipelines.

## 1. Purpose

The rendered text view is a one-line-per-fact representation of a
binary record stream, used by:

- the file destination (`.dft` files written per thread)
- the reference processor (intermediate form before parsing into
  ClickHouse rows)
- humans reading traces directly

It is **lossless** with respect to the binary format insofar as the
configured `emit_tags` set permits. Filtering tags (e.g. dropping `TI`)
discards information from this view but not from the underlying wire
records.

## 2. Line format

Each line is:

```
TAG;value
```

- `TAG` — a 2-character ASCII tag from §3.
- `;` — single semicolon, no surrounding whitespace.
- `value` — UTF-8 text. May contain semicolons, newlines, JSON, or
  any other characters except `\n` (the line terminator). When a
  value contains a literal newline, the producer MUST escape it as
  the JSON string escape `\n` — the rendered text view is
  line-oriented and consumers split on `\n`.
- Lines are terminated by a single `\n` (LF, U+000A). Producers MUST
  NOT emit `\r\n`.

There is no comment syntax. There is no header line. There is no
escaping for `;` in tag values — consumers MUST split only on the
**first** `;` in each line.

## 3. Tag list

| Tag | Source record | Cardinality per call | Value |
|-----|---------------|----------------------|-------|
| `VR`| VERSION       | once per stream      | `<major>.<minor>` (e.g. `"1.4"`) |
| `MS`| METHOD_START  | once                 | method signature (UTF-8 string) |
| `SI`| METHOD_START  | 0..1                 | session id (omitted if empty) |
| `TN`| METHOD_START + METHOD_END | twice    | thread name |
| `RI`| METHOD_START + METHOD_END | twice    | request id (decimal int64 as string) |
| `TS`| METHOD_START  | once                 | entry timestamp (decimal int64 as string, ms since epoch) |
| `CL`| METHOD_START  | once                 | caller line (decimal int32) |
| `CI`| METHOD_START + METHOD_END | twice    | call id (UUID canonical text form) |
| `PI`| METHOD_START  | 0..1                 | parent call id (UUID; omitted if root) |
| `TI`| THIS_INSTANCE / THIS_INSTANCE_REF | 0..1 | hashed JSON envelope (full) **or** decimal int64 (ref-only) |
| `AR`| ARGUMENTS     | 0..1                 | hashed JSON of the args |
| `AX`| ARGUMENTS_EXIT| 0..1                 | hashed JSON of the args at exit |
| `RT`| RETURN / EXCEPTION | once            | one of `VOID`, `VALUE`, `EXCEPTION` |
| `RE`| RETURN / EXCEPTION | 0..1            | hashed JSON of return or exception (omitted if `RT;VOID`) |
| `TE`| METHOD_END    | once                 | exit timestamp (decimal int64) |
| `SQ`| SEQUENCE      | 0..1                 | `<callId>\|<seq>` — agent-observed monotonic ordinal (per agent run). Pair by `callId`, not by adjacency. |

The `MS` and `VR` tags are **always emitted**, regardless of the
configured `emit_tags` set — `MS` because every call must be
identifiable by signature, `VR` because consumers need the
wire-format version banner to dispatch parsing. Other tags MAY be
filtered by the consumer's configuration (the reference agent's
`emit_tags` config); filtered tags are absent from the rendered
output but the underlying binary record is unaffected.

## 4. Ordering

Within one call, lines appear in this order:

```
MS-record block:
  TS;<entry timestamp>
  [SI;<session>]
  MS;<signature>
  TN;<thread>
  RI;<requestId>
  CL;<callerLine>
  [CI;<callId>]
  [PI;<parentCallId>]

THIS block (if any):
  TI;<envelope-or-id>

ARGUMENTS (if any):
  AR;<json>

SEQUENCE (if any):
  SQ;<callId>|<seq>

(nested calls' MS blocks recurse here)

ME-record block:
  TE;<exit timestamp>
  TN;<thread>
  RI;<requestId>
  [CI;<callId>]

RETURN/EXCEPTION:
  RT;VOID                         (if void)
  RT;VALUE  + RE;<json>           (if value)
  RT;EXCEPTION + RE;<json>        (if exception)

ARGUMENTS_EXIT (if any):
  AX;<json>
```

`VR` appears once, before any call, at stream start.

A consumer parsing this view:

- MUST switch from "entry context" to "exit context" on `TE`.
- MUST handle `RT`, `RE`, `AX` as belonging to the call whose `CI`
  was just resolved on the preceding `TE` line.
- MUST pair calls by `CI` UUID, not by stack ordering.

The reference parser is in `RecordParser.java`; its docstring carries
the same state-machine summary.

## 5. Cross-batch / cross-stream behavior

The text view inherits the wire format's cross-batch behavior: an
`MS` block can land in one batch and the matching `ME` block in
another. A consumer MUST keep open-call state across batches and
pair by `CI`. The reference parser does this with a UUID-keyed map
and a TTL eviction sweep (see `RecordParser.evictStaleOpenCalls`).

## 6. JSON values inside `TI`/`AR`/`AX`/`RE`

The text view is produced in two separable steps:

1. **Render** — `RecordRenderer.render(bytes)` decodes the binary
   wire format and emits tag lines whose JSON values are
   *un-hashed* (humanized envelopes per [HASHING.md §3](HASHING.md),
   no `__meta__` block).
2. **Enrich** (optional) — `RecordHashEnricher.enrich(rendered)`
   walks the `TI`/`AR`/`AX`/`RE` lines and rewrites each JSON
   value to its **hashed** form (`__meta__` injected on every
   envelope).

Both the reference file destination and the reference processor's
Kafka pipeline call enrich. **Tag streams written to disk or
forwarded to ClickHouse therefore always carry hashed JSON.** A
consumer that operates on raw `RecordRenderer.render()` output
without enrichment will see un-hashed JSON; that is allowed but
unusual.

A consumer that wants only the hash (and not the values) can read
`<value>.__meta__.hash` for envelope roots, or recompute via the
spec in HASHING.md for non-envelope roots.

A `TI` line whose value is a bare integer (the `THIS_INSTANCE_REF`
case) is never hashed — there is no JSON content to walk. The
enricher passes such lines through unchanged.

## 7. File destination layout

When a producer writes the text view to disk, the layout is:

```
<session_dump_location>/SESSION-<yyyyMMdd-HHmmss>/
   run.json                          # transport-layer agent identity (see TRANSPORT.md §3)
   <yyyyMMdd-HHmmss>-<thread1>.dft   # per-thread tag stream
   <yyyyMMdd-HHmmss>-<thread2>.dft
   ...
```

Each `.dft` file MUST start with the `VR;<major>.<minor>` line. Tag
lines for that thread follow.

The session directory's `yyyyMMdd-HHmmss` MUST match the producer's
agent-run start time, formatted with that pattern in UTC.

A non-Java agent writing the file destination MUST follow this
layout exactly so the reference UI / file-replay tooling can find
its output.

## 8. What the text view is NOT

(Informative.)

- It is **not** an authoritative storage format. Round-tripping
  text → binary → text is not normatively guaranteed (the binary
  carries opaque CBOR; the text carries decoded-and-hashed JSON).
- It is **not** the contract for downstream storage. ClickHouse
  consumes hashed JSON via the parsed-call path; it does not store
  the rendered text lines verbatim.
- It is **not** a stable diff target. Hashes will change if any
  field of any envelope changes.

The binary wire format is the contract; the text view is one
rendering of it.
