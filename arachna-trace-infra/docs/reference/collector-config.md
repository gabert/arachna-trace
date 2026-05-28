# Record Collector Server Configuration

The Netty HTTP server that receives binary record batches from
agents via `POST /records` and forwards them to Kafka.

Config file: `server/record-collector-server/arachna-collector.cfg`.
Passed at startup as `java -jar record-collector-server.jar config=arachna-collector.cfg`.
CLI overrides are inline `key=value` pairs that take precedence
over file values.

For component context, see
[../internals/record-format.md](../internals/record-format.md).
For how this layer's caps must align with the agent and Kafka,
see the [size-limit alignment contract](deployment-modes.md#size-limits-across-the-pipeline--alignment-contract).

## Options

### server_port

HTTP listen port for the `/records` endpoint.

Default: `8099`.

```properties
server_port=8099
```

### max_content_length

Maximum size in bytes of a single POST body. Requests larger
than this are rejected by Netty's `HttpObjectAggregator` with a
413 / connection drop.

Default: `10485760` (10 MiB).

This default is **above** Kafka's per-record cap (1 MiB by
default). A POST between 1–10 MiB is accepted here but rejected
at the Kafka producer with a `RecordTooLargeException` in
stderr. See the ROADMAP entry **"Align the size-limit defaults
across the pipeline"** for the planned fix that brings this in
line with the Kafka cap.

```properties
max_content_length=10485760
```

### kafka_bootstrap_servers

Comma-separated `host:port` list for the Kafka cluster the
collector forwards records to.

Default: `localhost:9092`.

```properties
kafka_bootstrap_servers=broker1:9092,broker2:9092
```

### kafka_topic

Kafka topic name for raw trace records. Each HTTP POST becomes
one Kafka record on this topic; agent-run-identity headers from
the HTTP request are copied to the Kafka record's headers
verbatim. See [../spec/TRANSPORT.md](../../../spec/TRANSPORT.md).

Default: `arachna-trace-records`.

```properties
kafka_topic=arachna-trace-records
```

## Implicit producer settings

`KafkaRecordForwarder` sets two Kafka producer properties
explicitly:

| Property | Value | Why |
|---|---|---|
| `linger.ms` | 5 | Brief batching window — trades 5 ms tail latency for fewer Kafka round-trips. |
| `batch.size` | `65536` (64 KiB) | Producer-side batching cap before forced flush. |

Other producer properties (notably `max.request.size`, default
~1 MiB) use Kafka client defaults. The ROADMAP fix will pin
`max.request.size = max_content_length` so the producer caps
inherently track the HTTP body cap.
