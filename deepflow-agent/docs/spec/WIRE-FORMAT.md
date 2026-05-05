# Wire Format — Binary Records

**Normative.** Encoded version: **`major=1`, `minor=4`**.

A DeepFlow stream is a sequence of self-delimited binary frames. Each
frame is one **record** of one of the ten types defined in §4.

This document defines the byte layout exactly. It does **not** define
the semantic order in which records appear; that is in §5.

## 1. Encoding ground rules

(Repeats SPEC.md §6 for self-containment.)

| | Encoding |
|---|---|
| Endianness | Big-endian (network byte order) for all multi-byte integers. |
| Strings | UTF-8 bytes, prefixed by a 16-bit unsigned big-endian length. Max 65535 bytes. |
| UUID | 16 bytes: 64-bit MSB then 64-bit LSB, both big-endian. All-zero UUID = "no UUID" sentinel. |
| Timestamp | Signed 64-bit milliseconds since Unix epoch (UTC). The reference Java agent emits `System.currentTimeMillis()` for METHOD_START / METHOD_END timestamps. |
| CBOR payloads | Standard CBOR (RFC 8949) wrapped per [CBOR-ENVELOPE.md](CBOR-ENVELOPE.md). |

## 2. Naming conventions used in this document

| Notation | Meaning |
|---|---|
| `int8` | signed 8-bit integer (1 byte) |
| `int16`, `int32`, `int64` | signed big-endian integers |
| `uint16` | unsigned big-endian 16-bit integer (used only for length prefixes) |
| `uuid` | 16-byte UUID per ground rules |
| `string` | `uint16 len; UTF-8 bytes[len]` |
| `bytes[N]` | exactly N opaque bytes |
| `cbor` | opaque CBOR-encoded blob; length is given by the enclosing record's length field |

## 3. Frame layout

Every record on the wire is a single frame:

```
+--------+--------------+--------------------+
| type   | payload_len  | payload            |
| int8   | int32        | bytes[payload_len] |
+--------+--------------+--------------------+
```

- **`type`** — one of the nine type bytes in §4.
- **`payload_len`** — number of bytes in the payload. Signed but MUST
  be non-negative; consumers MUST reject negative lengths.
- **`payload`** — record-specific (see §4 for each type).

Frame header is always 5 bytes. A consumer MUST be able to skip a
record of unknown `type` by reading the header and advancing
`5 + payload_len` bytes. This guarantees forward-compatibility for
new record types added in future minor versions.

## 4. Record types

| Hex  | Name                  | Payload structure | When emitted |
|------|-----------------------|-------------------|--------------|
| 0x01 | METHOD_START          | see §4.1 | At each traced method entry. |
| 0x02 | ARGUMENTS             | `cbor` | After METHOD_START, when args are captured. |
| 0x03 | RETURN                | `cbor` (may be empty) | At normal method exit. |
| 0x04 | EXCEPTION             | `cbor` | At exceptional method exit. |
| 0x05 | METHOD_END            | see §4.5 | At each traced method exit (any kind). |
| 0x06 | THIS_INSTANCE         | `cbor` | After METHOD_START on instance methods, when full `this` is captured. |
| 0x07 | THIS_INSTANCE_REF     | `int64` | After METHOD_START on instance methods, when only `this`'s ID is captured. |
| 0x08 | ARGUMENTS_EXIT        | `cbor` | After METHOD_END, when mutation tracking is enabled. |
| 0x09 | VERSION               | see §4.9 | Once at the start of a stream. |
| 0x0A | SEQUENCE              | see §4.10 | After METHOD_START, when sub-millisecond observation order is desired. |

Type bytes `0x00` and `0x0B`–`0xFF` are reserved. A producer MUST NOT
emit them. A consumer SHOULD log and skip them rather than fail
(§3, forward-compatibility).

> **Truncation marker.** When a producer is configured with a
> per-value size cap (`max_value_size > 0` in the reference Java
> agent), any one of `THIS_INSTANCE`, `ARGUMENTS`, `ARGUMENTS_EXIT`,
> `RETURN`, or `EXCEPTION` whose CBOR-encoded value would exceed the
> cap is replaced by a **bare CBOR map** of shape
> `{"__truncated": true, "original_size": <int>}`. The map uses
> string keys, NOT envelope field-IDs — there is no `OBJECT_ID` /
> `CLASS_NAME` wrapper. Consumers MUST recognize this shape and
> treat the value as opaque. See [CBOR-ENVELOPE.md §5b](CBOR-ENVELOPE.md)
> for the full spec.

### 4.1 METHOD_START (0x01)

Carries the entry-half metadata for one call.

```
+------+-------------+---------+----------+----------+--------------+----------+--------------+
| sid  | signature   | thread  |timestamp |callerLine| requestId    | callId   | parentCallId |
|string| string      | string  | int64    | int32    | int64        | uuid     | uuid         |
+------+-------------+---------+----------+----------+--------------+----------+--------------+
```

| Field | Type | Notes |
|---|---|---|
| `sid` (session_id) | string | MAY be empty (zero-length); empty means "no session attribution". |
| `signature` | string | Method signature in producer-defined form; consumers MUST treat it as opaque text. The reference Java agent emits `<pkg>::<Class>.<method>(<argTypes>) -> <returnType> [<modifiers>]` — note the `::` separator between the package name and the (possibly inner) class name. Example: `com.example::Foo.bar(java.lang::String, int) -> com.example::Bar [public]`. Other-language agents SHOULD pick a stable convention and document it in their `agent_version` string. |
| `threadName` | string | Producer's name for the executing thread / coroutine / fiber. SHOULD be unique enough to disambiguate concurrent activity within one process. |
| `timestamp` | int64 | Entry time, milliseconds since Unix epoch (UTC). The reference Java agent emits `System.currentTimeMillis()`. Producers MUST use the same domain so duration math (`tsOut - tsIn`) and wall-clock comparison both work directly. |
| `callerLine` | int32 | Source line in the **caller** code at which this call was invoked. `0` if unknown. |
| `requestId` | int64 | Producer-assigned. Groups all calls in one logical request. `0` is reserved for "not in a request". |
| `callId` | uuid | This call's UUID. SHOULD be a freshly-generated v4 UUID per call. The all-zero sentinel MAY appear if the producer fails to allocate one, but a consumer SHOULD treat an all-zero `callId` as a malformed record. |
| `parentCallId` | uuid | The enclosing call's UUID, or all-zero for a request root. |

Total fixed-size portion: `2+2+2+8+4+8+16+16 = 58` bytes plus the
three string bodies. (The reference golden test `methodStart_layoutWithKnownFields`
pins `0x3D = 61` bytes for one-byte sid/sig/tn — i.e. `58 + 3` —
which is the canonical example to validate against.)

### 4.2 ARGUMENTS (0x02)

```
+------------+
| cbor       |
+------------+
```

CBOR-encoded argument list, wrapped in an identity envelope (per
CBOR-ENVELOPE.md). The envelope's `VALUE` field is a CBOR array of
the method's arguments in declaration order.

A method with zero arguments MAY omit the ARGUMENTS record entirely,
or emit one whose envelope `VALUE` is an empty array.

### 4.3 RETURN (0x03)

```
+------------+
| cbor       |   (length may be 0)
+------------+
```

CBOR-encoded return value, in an identity envelope.

A `payload_len` of **zero** signals a void return (no value to
encode). Consumers MUST NOT treat zero-length as a parse error.

### 4.4 EXCEPTION (0x04)

```
+------------+
| cbor       |
+------------+
```

CBOR-encoded structured representation of the thrown exception.
**Note:** the exception's runtime class name is carried by the
identity envelope's `CLASS_NAME` field (see
[CBOR-ENVELOPE.md](CBOR-ENVELOPE.md)) — it is NOT inside the value
content. The value content is a map. The producer MUST include:

- a `message` field — string; the exception's message text, or
  the literal string `"null"` when the message is null
- a `stacktrace` field — array of strings; each string is one
  source-style frame (e.g. `"com.example.Foo.bar(Foo.java:42)"`)

The reference Java agent uses
`Map.of("message", String.valueOf(throwable.getMessage()), "stacktrace", List<String>)`,
where each list element is `StackTraceElement.toString()`. A
non-Java agent SHOULD emit the same two fields with the same types
so cross-language consumers can render exceptions uniformly.

A future minor version MAY add a structured-frame option
(`stacktrace_frames: [{class, method, file, line}, …]`) alongside
the string array; consumers MUST tolerate either or both.

### 4.5 METHOD_END (0x05)

Carries the exit-half metadata for one call. Pairs with its
METHOD_START via `callId`.

```
+------+---------+----------+----------+--------+
| sid  | thread  |timestamp |requestId | callId |
|string| string  | int64    | int64    | uuid   |
+------+---------+----------+----------+--------+
```

| Field | Type | Notes |
|---|---|---|
| `sid` | string | SHOULD equal the matching METHOD_START's `sid`. |
| `threadName` | string | SHOULD equal the matching METHOD_START's `threadName`. |
| `timestamp` | int64 | Exit time, milliseconds since Unix epoch (UTC). SHOULD be ≥ the matching METHOD_START's timestamp; small negative deltas are possible if the system clock is adjusted backwards mid-call (e.g. NTP correction), and consumers MUST tolerate them — clamp negative durations to zero rather than rejecting the record. |
| `requestId` | int64 | SHOULD equal the matching METHOD_START's `requestId`. |
| `callId` | uuid | The same `callId` as the matching METHOD_START. **This is the only field a conforming consumer uses to pair MS↔ME** — the `sid`/`threadName`/`requestId` echoes are convenience for tag rendering, not part of the matching contract. |

Total fixed-size portion: `2+2+8+8+16 = 36` bytes plus the two
string bodies. (Reference golden test `methodEnd_layoutWithKnownFields`
pins `0x26 = 38` bytes for one-byte sid/tn — i.e. `36 + 2`.)

### 4.6 THIS_INSTANCE (0x06)

```
+------------+
| cbor       |
+------------+
```

Full CBOR-encoded `this` object, in an identity envelope. Used when
the producer is configured to capture `this` by value
(`expand_this=true` in the reference agent).

### 4.7 THIS_INSTANCE_REF (0x07)

```
+--------+
| int64  |
+--------+
```

A bare 64-bit object ID, with no envelope and no class name. Used
when the producer is configured to capture only the identity of
`this` (`expand_this=false`). The ID has the same agent-run-scoped
identity semantics as the `OBJECT_ID` inside an envelope (see
[CBOR-ENVELOPE.md §3.1](CBOR-ENVELOPE.md) and
[IDENTITY-MODEL.md](IDENTITY-MODEL.md)).

The `THIS_INSTANCE_REF` value is a stable handle: the same logical
instance presented twice on the same agent-run produces the same
ID. The full object MAY appear elsewhere on the stream (in some
other call's `THIS_INSTANCE`, `ARGUMENTS`, or `RETURN`) under the
same ID, but the producer is not required to emit a full envelope
for it. Consumers SHOULD index `this_id` for cross-call lookup
without assuming a content payload exists.

Static methods MUST NOT emit THIS_INSTANCE or THIS_INSTANCE_REF.

### 4.8 ARGUMENTS_EXIT (0x08)

```
+------------+
| cbor       |
+------------+
```

Same shape as ARGUMENTS (0x02): the method's arguments, captured
**again** at exit time. Allows the consumer to detect mutations
applied during the call by comparing entry vs exit hashes.

This record is OPTIONAL. Emit only when mutation tracking is
enabled.

### 4.9 VERSION (0x09)

```
+--------+--------+
| major  | minor  |
| int16  | int16  |
+--------+--------+
```

Wire-format version banner. SHOULD be the first record of every
producer-emitted stream (§5).

## 5. Stream-level ordering rules

A "stream" is one continuous bytes sequence as observed by a single
consumer. For HTTP and Kafka destinations, one stream = one POST body
or one Kafka record value. For the file destination, one stream = one
`.dft` file (one per producer thread).

Within a stream, records MUST appear in the following relative order
for each call:

1. **METHOD_START** (0x01)
2. *(optional, in any order)* THIS_INSTANCE (0x06) **or**
   THIS_INSTANCE_REF (0x07) — at most one of the two
3. *(optional)* ARGUMENTS (0x02)
4. *(after the call body's nested calls, if any)*
5. **METHOD_END** (0x05)
6. *(optional, at most one of)* RETURN (0x03) **or** EXCEPTION (0x04)
7. *(optional)* ARGUMENTS_EXIT (0x08)

Steps 2, 3, 6, and 7 are all optional from the wire's perspective —
the reference Java agent omits them all when configured with
`serialize_values=false` (a "structural-only" mode that records the
call tree but not values). A consumer MUST tolerate a METHOD_START
followed directly by a METHOD_END (or a METHOD_END followed directly
by the next call's METHOD_START) with no intervening value records.

When the producer captures values (the default), it emits one of
RETURN or EXCEPTION after every METHOD_END.

> **Note on the agent's emitted order at exit.** The reference agent
> writes `METHOD_END, RETURN/EXCEPTION, ARGUMENTS_EXIT` — i.e. the
> exit-time `TE` (rendered timestamp) appears on the wire **before**
> the call's own `RT`/`RE`/`AX`. This is intentional: it lets the
> consumer pair the call by `callId` immediately on METHOD_END and
> attach later records to the now-resolved call. A new producer SHOULD
> emit in this same order.

VERSION (0x09) MUST appear at most once per stream. When present, it
SHOULD be the first record.

### 4.10 SEQUENCE (0x0A)

```
+--------+--------+
| callId | seq    |
| uuid   | int64  |
+--------+--------+
```

| Field | Type | Notes |
|---|---|---|
| `callId` | uuid | The callId of the METHOD_START this seq belongs to. Pairs by UUID, not by adjacency, so the consumer can route correctly when records from different threads interleave on the wire. |
| `seq` | int64 | A strictly monotonic ordinal scoped to a single agent run. The reference Java agent increments a per-process `AtomicLong` on each successful method entry — i.e. the seq reflects the order in which the agent **observed** events, regardless of thread or request. |

Total fixed size: `16 + 8 = 24` bytes.

Per-call ordering: when emitted, SEQUENCE for a call MUST appear after
that call's METHOD_START and before its METHOD_END. Within those bounds
the relative position to ARGUMENTS / THIS_INSTANCE / nested calls is
not constrained — pairing is by `callId`.

This record is OPTIONAL. The reference Java agent emits it when
`emit_tags` includes `SQ`, which is on by default. Consumers that read
no SEQUENCE records SHOULD fall back to ordering by `timestamp` (with
the millisecond-resolution ambiguity that implies); consumers that read
SEQUENCE SHOULD use it as the canonical ordering primitive (sub-ms
ties on `timestamp` are disambiguated by `seq`).

The seq value is meaningful only relative to its agent run; comparing
seqs across different `agent_run_id`s is undefined.

For nested calls on the same thread, METHOD_START records are
strictly LIFO with their METHOD_END counterparts:
> If A's METHOD_END has been written, B's METHOD_END must already
> have been written for any B that is nested inside A.

The consumer pairs by `callId`, not by stack ordering, so a producer
that violates LIFO will not corrupt the consumer's data — but it
SHOULD emit LIFO anyway because some downstream tools assume it.

## 6. Cross-stream / cross-batch behaviour

Different producer threads MAY share or interleave streams as the
transport allows:

- **File destination:** one stream per thread. No interleaving.
- **HTTP / Kafka destinations:** one stream per network message.
  Records from different threads MAY be interleaved within one
  stream as long as each call's records appear in the order of §5.
  The consumer MUST NOT rely on per-thread contiguity within a
  network message.

A consumer MUST be prepared for a call's METHOD_START to land in one
network message and its METHOD_END to land in a later one. Pairing is
by `callId`, which is durable across messages.

## 7. Worked example

The byte sequence below mirrors the reference test
`WireFormatGoldenTest.methodStart_layoutWithKnownFields` /
`methodEnd_layoutWithKnownFields` — pinned hex you can replay
verbatim and check against your own implementation.

Inputs:

- session id: `"S"` (1 byte)
- signature: `"M"` (1 byte)
- thread name: `"T"` (1 byte)
- timestamp (entry): `0x0102030405060708` (epoch ms)
- callerLine: `0x0A0B0C0D`
- requestId: `0x1112131415161718`
- callId: `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa`
- parentCallId: `bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb`
- timestamp (exit): same `0x0102030405060708` (same byte width)
- void return

Frames as `type | length | payload-bytes`:

```
METHOD_START   01 | 00 00 00 3D | 00 01 53                              ; sid "S"
                                  00 01 4D                              ; signature "M"
                                  00 01 54                              ; thread "T"
                                  01 02 03 04 05 06 07 08               ; timestamp
                                  0A 0B 0C 0D                           ; callerLine
                                  11 12 13 14 15 16 17 18               ; requestId
                                  AA AA AA AA AA AA AA AA AA AA AA AA AA AA AA AA   ; callId
                                  BB BB BB BB BB BB BB BB BB BB BB BB BB BB BB BB   ; parentCallId

METHOD_END     05 | 00 00 00 26 | 00 01 53                              ; sid "S"
                                  00 01 54                              ; thread "T"
                                  01 02 03 04 05 06 07 08               ; timestamp
                                  11 12 13 14 15 16 17 18               ; requestId
                                  AA AA AA AA AA AA AA AA AA AA AA AA AA AA AA AA   ; callId

RETURN (void)  03 | 00 00 00 00 |
```

Decoded payload sizes: METHOD_START = 61 bytes (`0x3D` = `58 + 3`),
METHOD_END = 38 bytes (`0x26` = `36 + 2`), RETURN = 0 bytes (void).

The reference test corpus at
`core/record-format/src/test/java/.../recorder/WireFormatGoldenTest.java`
carries the same and additional pinned encodings (THIS_INSTANCE,
THIS_INSTANCE_REF, ARGUMENTS, ARGUMENTS_EXIT, EXCEPTION, RETURN
value, VERSION, plus null-sid variants). New-language implementors
SHOULD validate their writers against these goldens before
shipping.

## 8. Limits & known constraints

- Each string field is bounded by 65535 UTF-8 bytes (the uint16
  length prefix). In practice, signatures and thread names fit well
  under this. Producers SHOULD NOT attempt to ship large blobs
  (e.g. SQL statements, JSON documents) through string fields —
  route those through a CBOR payload instead.
- Each record's `payload_len` is signed int32. The maximum payload
  size is therefore 2 GiB − 1 byte. In practice, transport buffers
  (HTTP request size, Kafka `max.message.bytes`) impose a tighter
  limit; producers SHOULD chunk large CBOR payloads at a level the
  CBOR wrapper supports if needed.
- The all-zero UUID is a reserved sentinel. The probability of
  `UUID.randomUUID()` (or equivalent) producing it is `2⁻¹²⁸` and
  treated as cosmic-ray-level.
