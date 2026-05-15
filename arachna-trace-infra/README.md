# Arachna Trace Infrastructure

Server-side pipeline that consumes binary records from any conformant
agent, renders + Merkle-hashes them, persists to ClickHouse, and
serves a read-only HTTP API for the UI.

The pipeline is **language-neutral by design**: it consumes the
[wire format](../spec/) defined under `spec/`, not Java types. Today's
implementation is Java + Netty + Kafka clients + ClickHouse HTTP
client. An alternative implementation in Go, Rust, or any other
language is welcome as long as it preserves the same wire-format
intake and the same HTTP query surface.

## Components

| Module | Role |
|---|---|
| `record-collector-server/` | Netty HTTP intake; relays binary records to Kafka |
| `record-processor-server/` | Kafka consumer; renders CBOR → JSON, computes hashes, inserts into ClickHouse |
| `record-query-server/` | Read-only HTTP API in front of ClickHouse, consumed by the UI |
| `clickhouse-init/` | Schema DDL mounted into the ClickHouse container on first start |
| `docker-compose.yml` | Kafka (KRaft) + ClickHouse for local development |
| `test-pipeline.sh` | End-to-end smoke test: agent → collector → Kafka → processor |

## Build

The infra reactor consumes shared libraries (`ArachnaTraceCodec`,
`ArachnaTraceRenderer`) from the local Maven repository. The
`arachna-trace-shared/` reactor must be built first so those
libraries are available:

```bash
cd ../arachna-trace-shared     && mvn install     # publishes codec + renderer + SPI APIs
cd ../arachna-trace-infra      && mvn install     # builds collector / processor / query
```

The infra build and the demos build are independent of each other —
once the agent libs are in local m2, infra and demos can build in any
order or in parallel.

## Docs

- [Deployment modes](docs/reference/deployment-modes.md) — local, embedded, distributed
- [Collector config](docs/reference/collector-config.md)
- [Processor config](docs/reference/processor-config.md)
- [Query-server config](docs/reference/query-server-config.md)
- [Record format / collector relay internals](docs/internals/record-format.md)
- [Processor internals](docs/internals/processor.md)
- [Query-server internals](docs/internals/query-server.md)

## Release / Docker

Production images for the four services are built from the repo root
via `release/release.sh`. See [`../release/README.md`](../release/README.md)
for the manual release flow.
