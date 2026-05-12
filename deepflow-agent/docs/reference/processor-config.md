# Record Processor Server Configuration

The Kafka consumer that renders binary records to JSON, injects
Merkle hashes, pairs `MS`↔`ME` by `call_id`, and inserts into
ClickHouse.

Config file: `server/record-processor-server/deepprocessor.cfg`.
Passed at startup as `java -jar record-processor-server.jar config=deepprocessor.cfg`.
CLI overrides are inline `key=value` pairs that take precedence
over file values.

For component context, see
[../internals/processor.md](../internals/processor.md).

## Kafka consumer settings

### kafka_bootstrap_servers

Comma-separated `host:port` list for the Kafka cluster. Should
match the collector's value so producer and consumer talk to
the same cluster.

Default: `localhost:9092`.

```properties
kafka_bootstrap_servers=broker1:9092,broker2:9092
```

### kafka_topic

Topic to consume from. Should match the collector's
`kafka_topic`.

Default: `deepflow-records`.

```properties
kafka_topic=deepflow-records
```

### kafka_group_id

Kafka consumer-group id. Running multiple processor instances
with the same group id parallelises consumption (each partition
is owned by one consumer at a time); running them with different
group ids fans out (each gets a full copy of the stream — useful
for adding a second sink without disrupting the primary).

Default: `deepflow-processor`.

```properties
kafka_group_id=deepflow-processor
```

## Sink selection

### sink_type

Which sink the processor writes to.

| Value | Description |
|---|---|
| `clickhouse` | Buffered HTTP `INSERT JSONEachRow` into `deepflow.calls`, `deepflow.payloads`, `deepflow.agent_runs`. Default. |
| `logging` | Print rendered + hashed lines to stdout. Used for end-to-end debugging when ClickHouse isn't part of the loop. |

```properties
sink_type=clickhouse
```

## ClickHouse connection (when `sink_type=clickhouse`)

### clickhouse_url

Base URL of the ClickHouse HTTP interface.

Default: `http://localhost:8123`.

```properties
clickhouse_url=http://clickhouse:8123
```

### clickhouse_database

Database name. The schema in
`server/clickhouse-init/01-schema.sql` creates the tables under
this database.

Default: `deepflow`.

```properties
clickhouse_database=deepflow
```

### clickhouse_user

ClickHouse user the processor authenticates as. Must have
INSERT permission on `calls`, `payloads`, `agent_runs`,
`sessions`.

Default: `deepflow`.

```properties
clickhouse_user=deepflow
```

### clickhouse_password

Password for `clickhouse_user`. The reference docker-compose
sets up the matching `deepflow` user with this password; in
production, set both via secrets and rotate.

Default: `deepflow`.

```properties
clickhouse_password=<set-via-secrets-in-production>
```

## Implicit batching settings

`ClickHouseSink` flushes the in-memory buffer to ClickHouse on
two triggers, both hard-coded:

| Trigger | Value | Notes |
|---|---|---|
| Buffer rows | 500 | Forced flush when buffered rows reach this count. |
| Timer | 1 second | Periodic flush on a scheduled tick. |

Plus TTL eviction for in-memory state to prevent leaks:

| State | TTL | Sweep interval |
|---|---|---|
| `RecordParser.openCalls` (unpaired `MS`) | 10 minutes | 60 seconds |
| `ClickHouseSink.seenSessions` | 1 hour | 5 minutes |

These knobs aren't exposed as config today — they're load-bearing
operational defaults. Open them up if a use case appears.
