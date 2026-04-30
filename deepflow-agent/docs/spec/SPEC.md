# DeepFlow Trace Format — Specification

**Status:** Draft. Wire format frozen at v1.3 (`major=1`, `minor=3`).
**Audience:** People writing a DeepFlow agent in a language other than
Java, or people writing a non-reference processor that consumes the
format.

---

## 1. What is being specified

DeepFlow is a runtime tracing protocol that captures, per method
invocation:

- structural metadata (signature, thread, timestamps, line)
- call-tree linkage (per-call UUIDs and parent UUIDs)
- argument values at entry and (optionally) at exit
- the receiver (`this`) instance, by full payload or by stable ID
- the return value or thrown exception
- per-process identity (agent-run UUID, hostname, version, env)
- per-call-tree identity (session ID, request ID)

Captured values are encoded with **CBOR** wrapped in an **identity
envelope** that pins each instance to a stable ID and a content hash.
Records are framed in a small **binary wire format**, delivered over
HTTP or written to files, optionally re-streamed via Kafka, and
typically landed in ClickHouse for query.

**This spec is what you implement. The Java agent in this repository is
one implementation of it; the reference processor is another.** Anyone
producing the same wire bytes with the same identity & hashing
semantics participates in the same ecosystem.

## 2. Document map

| Document | Normative? | Purpose |
|---|---|---|
| [WIRE-FORMAT.md](WIRE-FORMAT.md) | **Normative** | Binary frame layout, every record type, byte tables. |
| [CBOR-ENVELOPE.md](CBOR-ENVELOPE.md) | **Normative** | CBOR identity envelope: `OBJECT_ID`, `CLASS_NAME`, `VALUE`, cycle handling. |
| [HASHING.md](HASHING.md) | **Normative** | Merkle-style content hash construction over the rendered envelope. |
| [TAGS.md](TAGS.md) | **Normative** | Rendered text view (the `TAG;value` line format). |
| [TRANSPORT.md](TRANSPORT.md) | **Normative** | HTTP, Kafka and file-sidecar carriage of agent-run identity. |
| [IDENTITY-MODEL.md](IDENTITY-MODEL.md) | Normative + design | Cross-language object-identity contract; the hard part for porting. |
| [PORTING-GUIDE.md](PORTING-GUIDE.md) | Informative | Practical walkthrough for an agent-author in a new language. |

When the wire format changes, the change MUST be reflected in
WIRE-FORMAT.md and the version constants in §5 below.

## 3. Conformance language

This specification uses RFC 2119 / RFC 8174 keywords:

- **MUST**, **MUST NOT** — absolute requirement / prohibition.
  Non-conforming implementations are not DeepFlow agents/processors.
- **SHOULD**, **SHOULD NOT** — strong recommendation; deviation must
  be justified, since other implementations rely on it.
- **MAY** — true optional.

Unless explicitly marked **(Implementation-defined)**, any normative
sentence is part of the contract. Sections marked
**(Informative)** illustrate or explain but do not bind.

### 3.1 Conformance levels

An implementation conforms at one of two levels:

- **Producer (agent)** — generates wire records and transport metadata
  according to WIRE-FORMAT, CBOR-ENVELOPE, HASHING, TAGS and TRANSPORT
  (where applicable to the chosen destination).
- **Consumer (processor)** — accepts a wire byte stream conforming to
  the above and decodes it without loss of any normative field.

A producer MAY choose to emit a strict subset of records (e.g. omit
`ARGUMENTS_EXIT` when mutation tracking is disabled). A producer MUST
NOT emit a record type not defined in this spec.

A consumer MUST tolerate the appearance, in any order within the
batch, of records emitted by a conforming producer of the same major
version. A consumer SHOULD reject (or quarantine, with diagnostics) a
batch declaring a major version it does not implement.

## 4. Mental model (Informative)

Every traced method invocation produces a **call**. Each call has:

- a **`call_id`** (UUID v4, agent-assigned) that uniquely names it
- a **`parent_call_id`** (the enclosing call; null at request roots)
- entry and exit half-records, separated on the wire and paired by
  `call_id` at the consumer

Calls are grouped by:

- **`session_id`** — agent-defined (often a user/session/tenant string)
- **`request_id`** — agent-defined (numeric; one per logical request)
- **`agent_run_id`** — UUID v4 unique per JVM/process run

`call_id`, `parent_call_id`, `session_id`, and `request_id` live
**inside** the records (they are call attributes). `agent_run_id`
lives **outside** the records, on the transport envelope (HTTP/Kafka
headers, or a file sidecar) — see [TRANSPORT.md](TRANSPORT.md) for
why.

Captured values (arguments, this, return) are CBOR with an identity
envelope so the consumer can stably name each instance and detect
mutations across calls without recurring-string overhead. See
[CBOR-ENVELOPE.md](CBOR-ENVELOPE.md) and [HASHING.md](HASHING.md).

## 5. Versioning

The wire format carries a 32-bit version: `major:int16` followed by
`minor:int16`. Both are signed two's-complement.

- **major** changes are breaking. A consumer MUST NOT attempt to
  parse a major it does not implement.
- **minor** changes are backward-compatible additions (new optional
  records, new optional CBOR fields). A consumer MUST tolerate
  unknown record types in a stream of a known major by reading the
  5-byte frame header and skipping past `payload_len` bytes
  (see [WIRE-FORMAT §3](WIRE-FORMAT.md)).

Current version: **`major = 1`, `minor = 3`**. The format is pre-1.0
in the sense that there are no external consumers; the major has
nonetheless been used as `1` from the start. A future change of
governance (e.g. opening the spec to outside implementors) MAY
re-baseline the version numbers — when that happens, the change
will land in this section and in WIRE-FORMAT.md.

A producer SHOULD emit a `VERSION` record (type `0x09`) as the first
record of every stream:

- **File destination** (one stream = one `.dft` file): MUST emit
  VERSION as the first record of every file.
- **HTTP destination** (one stream = one POST body): the reference
  agent emits VERSION once at agent startup, into a buffer that is
  flushed lazily. The first POST therefore begins with VERSION; **subsequent
  POSTs from the same agent run do not**. Consumers MUST NOT assume
  VERSION is present on every Kafka message.
- **Kafka topic** (one stream = one ProducerRecord value): inherits
  the same property as HTTP — first message of the run carries
  VERSION; later messages do not.

The wire-format version is **not** the same thing as the
`X-Deepflow-Agent-Version` transport header (which carries the
agent's build version, e.g. `"deepflow-agent/0.0.1-SNAPSHOT"`). They
are independent: a v1.3 agent and a v1.4 agent both emit
agent-version strings, but only the wire-format VERSION record tells
a parser how to read the bytes. When VERSION is missing from a
Kafka message, the consumer SHOULD assume the same wire version the
last seen VERSION declared, defaulting to v1.3 if none has been
seen. A future minor version SHOULD make VERSION mandatory on every
batch, so this defaulting can be removed.

## 6. Encoding ground rules

These apply globally; per-record specifics are in WIRE-FORMAT.md.

- **Endianness:** all multi-byte integers are **big-endian**
  (network byte order). MUST.
- **Strings:** UTF-8, length-prefixed by an unsigned 16-bit
  big-endian count (max **65535** bytes). MUST. Strings longer than
  this MUST be truncated by the producer; producers SHOULD avoid this
  case by emitting only short identifiers (signatures, thread names)
  and routing large data through CBOR payloads.
- **UUIDs:** 16 bytes, written as two big-endian unsigned 64-bit
  values: most-significant 64 bits first, then least-significant 64
  bits. The all-zero UUID
  (`00000000-0000-0000-0000-000000000000`) is a reserved sentinel
  meaning "no UUID present" wherever a nullable UUID is permitted.
  Producers MUST NOT emit a real UUID equal to all-zero.
- **Timestamps:** signed 64-bit integer with **producer-defined
  domain**. The reference Java agent emits `System.nanoTime()` for
  per-record timestamps (METHOD_START, METHOD_END) — JVM-relative
  monotonic nanoseconds, NOT epoch-ms. The wall-clock anchor lives
  on the transport-layer `AgentRun.startedAtMillis` field
  (`X-Deepflow-Started-At-Millis` header). See WIRE-FORMAT.md §4.1
  for the full discussion.
- **Booleans, when needed in CBOR:** standard CBOR major type 7
  (true/false). MUST.

## 7. Identity model summary

Object identity (for `this`, for argument graphs, for mutation
detection) is the most subtle part of the spec because not every
language has Java's primitives. The full design discussion lives in
[IDENTITY-MODEL.md](IDENTITY-MODEL.md). The minimum binding contract:

1. Every CBOR-encoded object MUST appear inside an identity envelope
   carrying an `OBJECT_ID` (a non-zero positive int64) and a
   `CLASS_NAME` (UTF-8 string).
2. Within one agent-run, the same logical instance MUST be emitted
   with the same `OBJECT_ID` everywhere it appears, for as long as
   the agent considers it alive.
3. `OBJECT_ID` semantics across agent-runs are undefined. Cross-run
   identity MUST be established via content hash, not by ID.

The Java reference uses live-instance pointer identity backed by weak
references. Languages without weak references (notably Go) need a
different strategy — see IDENTITY-MODEL.md §3.

## 8. What is deliberately NOT specified

- **How** the producer instruments code (bytecode rewriting, runtime
  hooks, monkey-patching, source generation — agent's choice).
- **What** classes/functions are traced (driven by per-agent
  configuration).
- **Sampling, throttling, backpressure** between the agent and its
  destination.
- **Storage schema** of any downstream sink. The reference processor
  writes to ClickHouse; any sink that accepts the wire format is
  conforming.
- **Authentication / authorization** at the transport layer. Operators
  MAY layer mTLS, signed headers, etc.
- **Time-source clock skew** between agents. Consumers MUST treat
  agent timestamps as authoritative for that agent run — but those
  timestamps are NOT necessarily wall-clock (see §6). For wall-clock
  correlation, use `AgentRun.startedAtMillis` as the per-run anchor.

## 9. Reference implementation

The Java agent at `core/agent/`, the codec at `core/codec/`, the
record format at `core/record-format/`, the serializer at
`core/serializer/`, and the processors under `server/` together form
the reference implementation. Behavior in code that contradicts this
spec is a defect in the code, not in the spec. File issues against
either as appropriate.

## 10. License & change control

(Placeholder — to be filled when DeepFlow chooses an OSS license and
a public RFC process. Until then: changes to this spec are accepted
via PR with maintainer review, and breaking changes require a major
version bump per §5.)
