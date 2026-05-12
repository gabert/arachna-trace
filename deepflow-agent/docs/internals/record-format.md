# Record Format and Collector

The `core/record-format` module defines the on-the-wire binary
frames that every DeepFlow trace consists of. The
`server/record-collector-server` module is the first network hop
that reads those frames — it accepts them over HTTP and relays
them onto Kafka.

These two are paired here because the collector's job is exactly
"forward the bytes record-format defines, unchanged." Anything
that decodes, renders, or transforms the frames lives downstream
in the processor (see [processor.md](processor.md)).

For the language-neutral wire-format *contract* (what a non-Java
agent would have to produce), see
[../spec/WIRE-FORMAT.md](../spec/WIRE-FORMAT.md). This doc is the
**Java implementation** of that contract.

## Binary frame layout

Every record on the wire has the same five-byte header:

```
+--------+----------------+-----------------------------+
| type:1 | payload len:4  | payload bytes (length N)    |
+--------+----------------+-----------------------------+
   1 B          4 B                    N B
```

- **type** — one of the ten record-type bytes (see below).
- **payload len** — big-endian int32, length of the payload bytes
  that follow.
- **payload** — the record-specific body.

`TraceRecord.toFrame()` produces this layout;
`RecordReader` consumes it the same way.

## Record types

Defined in `RecordType.java`:

| Byte   | Type                  | Class                      |
|--------|-----------------------|----------------------------|
| `0x01` | `METHOD_START`        | `MethodStartRecord`        |
| `0x02` | `ARGUMENTS`           | `ArgumentsRecord`          |
| `0x03` | `RETURN`              | `ReturnRecord`             |
| `0x04` | `EXCEPTION`           | `ExceptionRecord`          |
| `0x05` | `METHOD_END`          | `MethodEndRecord`          |
| `0x06` | `THIS_INSTANCE`       | `ThisInstanceRecord`       |
| `0x07` | `THIS_INSTANCE_REF`   | `ThisInstanceRefRecord`    |
| `0x08` | `ARGUMENTS_EXIT`      | `ArgumentsExitRecord`      |
| `0x09` | `VERSION`             | `VersionRecord`            |
| `0x0A` | `SEQUENCE`            | `SequenceRecord`           |

`RecordType.VERSION_MAJOR` / `VERSION_MINOR` define the current
wire-format version (currently `1.4`). The `VersionRecord` is the
first frame in every stream so consumers can dispatch on it.

## Why one class per record type

`TraceRecord` is a **sealed interface** with exactly ten permitted
implementations (one per record type). Each implementation owns:

- Its single-byte type discriminator (`typeByte()`).
- Its payload marshaling (`payloadBytes()`).
- Its parser (`static parse(byte[])`).

`TraceRecord.parse(byte typeByte, byte[] payload)` dispatches with
a sealed `switch`, so the compiler's exhaustiveness check
guarantees no rendering or dispatching site is silently missed
when a new record type is added. Adding a new type is two edits:
one entry to the `permits` clause and one case to `parse(...)`.

## Producer side (agent)

The agent module uses `RecordWriter` to build frames:

```java
recordWriter.methodStart(signature, threadName, requestId, ts, line, callId, parentId);
recordWriter.thisInstanceRef(thisId);
recordWriter.arguments(cborBytes);
// ... user code runs ...
recordWriter.methodEnd(threadName, requestId, te, callId);
recordWriter.returnValue(cborBytes);
recordWriter.sequence(callId, seq);
```

Each call returns `byte[]` — a complete framed record ready for a
destination. `RecordBuffer` (in `core/serializer`) queues them;
`RecordDrainer` polls and hands them to the configured
destination. See [serializer.md](serializer.md) for the agent-side
pipeline.

## Consumer side (collector)

`server/record-collector-server` is the first network reader of
the frames. It's deliberately minimal: a Netty HTTP endpoint that
accepts POSTed bytes and forwards them, **byte-for-byte
unchanged**, to a Kafka topic.

### Why a thin relay

Single responsibility. A slow processor or a stalled ClickHouse
cannot backpressure into the agent's hot path if the collector
itself does no parsing, decoding, or augmentation. The collector
exists so the network ingress stays fast and predictable.

### Components

```
RecordCollectorServer
  ├─ ServerBootstrap (Netty)
  │    ├─ HttpServerCodec        # HTTP parsing
  │    ├─ HttpObjectAggregator   # whole-request buffering
  │    └─ RecordHandler          # /records POST → forward
  └─ KafkaRecordForwarder        # ProducerRecord → Kafka topic
```

Four files total:

- **`RecordCollectorServer`** — entry point, Netty bootstrap,
  graceful shutdown hook.
- **`ServerConfig`** — port, Kafka bootstrap servers, topic name,
  max HTTP content length.
- **`RecordHandler`** — single endpoint handler. Routes `POST
  /records` to the forwarder; everything else returns 404 or 405.
- **`KafkaRecordForwarder`** — Kafka producer wrapper.
  `LINGER_MS=5`, `BATCH_SIZE=64 KB`. Best-effort `send` with
  stderr logging on failure.

### Agent-run identity travels on headers, not in the payload

Every POST carries seven `X-Deepflow-*` headers (defined in
`AgentRun.Headers`):

```
X-Deepflow-Agent-Run-Id
X-Deepflow-Hostname
X-Deepflow-Agent-Version
X-Deepflow-Code-Version
X-Deepflow-Env
X-Deepflow-Jvm-Pid
X-Deepflow-Started-At-Millis
```

`RecordHandler.extractAgentRunHeaders` reads them off the HTTP
request and `KafkaRecordForwarder.send` copies them verbatim onto
the Kafka `ProducerRecord` headers. The body itself stays
opaque — no parsing, no decoding.

This means agent-run attribution survives in-flight: a Kafka
batch always carries its producer identity on its own headers, so
the processor can attribute every record without depending on
any "agent header" frame inside the body. See
[../spec/TRANSPORT.md](../spec/TRANSPORT.md) for the normative
contract.

## File destination (alternative consumer)

The other consumer of `record-format` frames is
`FileDestination` (in `core/serializer`), which writes per-thread
`.dft` files locally. Same `byte[]` input, different output —
the file destination renders the frames to text via
`RecordRenderer` (so `.dft` files are human-readable), while the
HTTP destination forwards the binary bytes unchanged for the
collector to relay.

See [serializer.md](serializer.md) for the destination interface.

## Source files

- `core/record-format/src/main/java/com/github/gabert/deepflow/recorder/record/`
  - `TraceRecord.java` — sealed interface + parse dispatch
  - `RecordType.java` — type-byte constants and size constants
  - `RecordWriter.java` — frame builders (producer-side)
  - `RecordReader.java` — frame iterator (consumer-side)
  - `BinaryUtil.java` — big-endian int/long helpers
  - `VersionRecord.java`, `MethodStartRecord.java`,
    `MethodEndRecord.java`, `ArgumentsRecord.java`,
    `ArgumentsExitRecord.java`, `ReturnRecord.java`,
    `ExceptionRecord.java`, `ThisInstanceRecord.java`,
    `ThisInstanceRefRecord.java`, `SequenceRecord.java` — one
    class per record type
- `server/record-collector-server/src/main/java/com/github/gabert/deepflow/server/`
  - `RecordCollectorServer.java` — Netty bootstrap + main
  - `RecordHandler.java` — `POST /records` handler
  - `KafkaRecordForwarder.java` — Kafka producer + header copy
  - `ServerConfig.java` — port / Kafka config
