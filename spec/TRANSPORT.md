# Transport — Agent-Run Identity Carriage

**Normative.**

This document defines how the agent-run identity (the
`AgentRun` record) travels from agent to consumer over each
supported transport. The wire records themselves carry no agent-run
identity; it is **always** at the transport layer.

## 1. Why transport-layer

If agent-run identity were inside each record, a single dropped or
delayed "agent header" record would orphan every subsequent call. By
putting identity on the transport (HTTP headers, Kafka headers, or a
file sidecar), every batch of records carries its own attribution
unambiguously, regardless of order, replay, or restart.

## 2. The AgentRun fields

| Field | Type | Required | Notes |
|---|---|---|---|
| `agent_run_id` | UUID v4 | yes | Unique per JVM/process run. |
| `hostname`     | string  | yes | The producer's hostname. |
| `agent_version`| string  | yes | The agent implementation version (e.g. `"arachna-trace-agent/0.4.1-java"`). |
| `code_version` | string  | no  | The traced application's version (git SHA, tag, build id). Empty when unset. |
| `env`          | string  | no  | Environment label (`prod` / `staging` / `dev` / etc.). Empty when unset. |
| `process_pid`  | uint64  | yes | OS process id (the JVM pid for Java; equivalent for other runtimes). |
| `started_at_millis` | int64 | yes | Producer's wall-clock at agent start, ms since Unix epoch UTC. |

`process_pid` is named `jvm_pid` in the reference Java agent (header
`X-Arachna-Trace-Jvm-Pid`); a non-Java agent SHOULD reuse the same header
name and treat the value as opaque from the consumer's standpoint —
the consumer does not interpret it as Java-specific.

## 3. HTTP transport (agent → collector)

### 3.1 Endpoint

The agent POSTs binary record streams to a configurable URL. The
reference collector listens at `POST /records`.

```
POST /records HTTP/1.1
Host: <collector>
Content-Type: application/octet-stream
Content-Length: <bytes>
X-Arachna-Trace-Agent-Run-Id:    <uuid>
X-Arachna-Trace-Hostname:        <string>
X-Arachna-Trace-Agent-Version:   <string>
X-Arachna-Trace-Code-Version:    <string>      ; if known
X-Arachna-Trace-Env:             <string>      ; if known
X-Arachna-Trace-Jvm-Pid:         <uint64 as decimal>
X-Arachna-Trace-Started-At-Millis: <int64 as decimal>

<binary record bytes>
```

The body is **one or more wire-format frames** as defined in
[WIRE-FORMAT.md](WIRE-FORMAT.md), concatenated. Producers MAY batch
multiple frames per POST to amortize HTTP overhead.

### 3.2 Required headers

The producer MUST send all required-column headers in §2 on every
POST. The collector MUST reject (or quarantine, with an error log) a
POST missing `X-Arachna-Trace-Agent-Run-Id` — it has no way to attribute
the records.

The reference collector accepts whichever headers are present and
forwards them onward. A non-reference collector MAY enforce stricter
policy.

### 3.3 Header name canonical form

Header names MUST be matched case-insensitively per RFC 9110, but
producers SHOULD use the exact canonical form
`X-Arachna-Trace-Capitalized-Words` for readability.

### 3.4 Response

```
200 OK         — body accepted (does not imply persisted)
404 / 405      — wrong path or wrong method; producer MUST NOT retry
5xx            — server error; producer MAY retry with backoff (see note)
```

A `200` response means the collector has acknowledged the bytes.
Whether they reach long-term storage is a function of the
downstream pipeline (Kafka durability, processor health) — that is
not part of this transport contract.

> **Reference collector behaviour.** The reference Netty collector
> (`server/record-collector-server/.../RecordHandler`) returns
> `200`, `404` (wrong path), or `405` (wrong method). It has no
> explicit 5xx path — uncaught exceptions in the handler close the
> channel without a response, which the producer observes as a
> network-level error. Operators of stricter collectors may add
> 5xx semantics; the producer SHOULD already handle "no response"
> as a transient error.

## 4. Kafka transport (collector → processor)

The collector forwards each accepted POST body to a Kafka topic.

### 4.1 Topic

The reference topic is named **`arachna-trace-records`**. The topic name
is configurable on both ends.

### 4.2 Record value

The Kafka record's **value** is the **unmodified POST body** — the
binary wire-format frames received from the agent. The collector
does not decode, re-frame, or augment the bytes.

### 4.3 Record headers

The collector MUST copy the seven `X-Arachna-Trace-*` HTTP headers
defined in §2 onto the Kafka record's headers, using the **same
names** verbatim and the **same UTF-8 string values** (encoded as
bytes). The processor MUST look up the agent-run fields by these
header names.

The reference collector enumerates the seven header names
explicitly (`RecordHandler.AGENT_RUN_HEADER_NAMES`); arbitrary
extra `X-Arachna-Trace-*` headers added by a producer are NOT
propagated to Kafka in v1. A future minor version MAY widen this
to a generic prefix-match.

### 4.4 Record key

The Kafka record key is unspecified. The reference collector uses
`null` (round-robin). Operators MAY route by `agent_run_id` or by
hostname for partition-affinity, but consumers MUST NOT rely on any
particular keying.

### 4.5 Record offset / ordering

A consumer MUST be tolerant of out-of-order delivery between Kafka
partitions. Within a single partition, Kafka guarantees order.

## 5. File transport (agent → file)

When the agent writes locally instead of POSTing, agent-run identity
travels in a **sidecar file** rather than in headers.

### 5.1 Layout

```
<dump_location>/SESSION-<yyyyMMdd-HHmmss>/
   run.json
   <yyyyMMdd-HHmmss>-<threadName>.dft
   ...
```

The `yyyyMMdd-HHmmss` MUST be the agent's start time, formatted in
UTC. One session directory per agent run.

### 5.2 `run.json`

A pretty-printed JSON serialization of the AgentRun fields. Field
names use **camelCase** (the reference Java agent serializes the
`AgentRun` Java record with Jackson defaults — record component
names map directly to JSON keys, no naming-strategy override):

```json
{
  "agentRunId": "5e3f...",
  "hostname": "host-01",
  "agentVersion": "arachna-trace-agent/0.4.1-java",
  "codeVersion": "git-abc12345",
  "env": "prod",
  "jvmPid": 12345,
  "startedAtMillis": 1730412345678
}
```

A non-Java agent MUST use the **same key names** (camelCase) to
keep file-destination output cross-tool readable. The `jvmPid` key
holds the producer's process id; the value is treated as opaque
— it is not interpreted as Java-specific by any consumer.

> **Naming inconsistency between transports.** HTTP/Kafka headers
> use kebab-case with an `X-Arachna-Trace-` prefix (e.g.
> `X-Arachna-Trace-Agent-Run-Id`); `run.json` uses camelCase
> (`agentRunId`). This asymmetry is a wart of the v1 spec — it
> reflects the natural conventions of each carrier. A future
> major version MAY normalize one of them; until then, implement
> both verbatim.

### 5.3 `.dft` files

One per producer thread. Each starts with `VR;<major>.<minor>` and
continues with rendered tag lines per [TAGS.md](TAGS.md).

A reader of the file destination MUST:
1. Read `run.json` first to recover the agent-run identity.
2. For each `.dft` file, parse tag lines and attribute every call to
   the run identity from `run.json`.

## 6. Future transports (Informative)

The transport layer is intentionally simple so additional transports
can be added without changing the wire format:

- **gRPC** — value bytes as the protobuf payload, agent-run fields
  as gRPC metadata entries with the same names.
- **NATS / RabbitMQ / SQS** — value bytes as the message body,
  agent-run fields as message headers/attributes.
- **OTLP-compat** — possible if/when Arachna Trace defines a mapping to
  OpenTelemetry's binary protocol; not in v1.

A new transport binding MUST preserve the property that every
delivered batch of wire records carries its agent-run identity
alongside (or above) the bytes — never inside.

## 7. Error handling at the transport layer

The producer's transport implementation handles ordinary network
errors (connect refused, timeout, 5xx). It is **not normatively
required** to guarantee at-least-once delivery; in practice the
reference HttpDestination does NOT retry, and discarded batches are
visible operationally as gaps in the trace store. Operators concerned
about gap-free capture SHOULD deploy a queueing collector (Kafka,
NATS) between agent and processor and accept the operational cost.

## 8. Security considerations (Informative)

The transport carries:

- runtime values from the traced application — potentially including
  PII, credentials, tokens
- application code structure — class and method names

Operators SHOULD:

- treat the collector endpoint as sensitive; deploy behind mTLS or
  a private network
- enable agent-side filtering (`matchers_exclude`) to keep
  authentication / cryptography paths out of the trace
- consider value-redaction at the agent for known-sensitive fields
  (the reference agent does not yet provide a redaction hook;
  contributions welcome)

The format itself includes no normative security mechanism.
