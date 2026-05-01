# DeepFlow Agent — Documentation

The Java agent that captures method-level runtime data flow via
bytecode instrumentation. For solution-level context (what DeepFlow
is, what it's for, how it compares), see
[../../doc/](../../doc/).

## Using the agent

- [Getting Started](getting-started.md) -- build, attach, configure, read traces
- [Configuration Reference](configuration.md) -- every `deepagent.cfg` option
- [Architecture](architecture.md) -- agent data flow, modules, design decisions

## Features

- [Mutation Detection](features/mutation-detection.md) -- detecting argument changes via AX
- [Request ID](features/request-id.md) -- request correlation and cross-thread propagation
- [Serialization Modes](features/serialize-modes.md) -- full vs structural-only
- [Truncation](features/truncation.md) -- capping serialized value size

## Internals

- [Agent](internals/agent.md) -- bytecode instrumentation, recording flow
- [Codec](internals/codec.md) -- Java implementation of the CBOR envelope (defers to spec)
- [Executor Instrumentation](internals/executor-instrumentation.md) -- bootstrap injection, JPMS, request-ID propagation
- [Serializer](internals/serializer.md) -- recording pipeline (buffer, drainer, destinations)

## SPI

- [Session Resolver](spi/session-resolver.md) -- pluggable session ID source
- [JPA Proxy Resolver](spi/jpa-proxy-resolver.md) -- Hibernate proxy/collection unwrapping

## Wire-format spec

The language-neutral protocol contract that any DeepFlow agent or
processor must implement. The Java agent in this repository is one
reference implementation of it.

- [SPEC.md](spec/SPEC.md) -- conformance language and document map
- [WIRE-FORMAT.md](spec/WIRE-FORMAT.md) -- binary frame layout
- [CBOR-ENVELOPE.md](spec/CBOR-ENVELOPE.md) -- envelope shape, field IDs, cycle refs, truncation
- [HASHING.md](spec/HASHING.md) -- Merkle content hash construction
- [TAGS.md](spec/TAGS.md) -- rendered text view (`.dft` line format)
- [TRANSPORT.md](spec/TRANSPORT.md) -- HTTP / Kafka / file carriage of agent-run identity
- [IDENTITY-MODEL.md](spec/IDENTITY-MODEL.md) -- cross-language identity contract
- [PORTING-GUIDE.md](spec/PORTING-GUIDE.md) -- writing an agent in another language

## Session-scoped design notes

In-flight design history and the current punch list. These reflect the
state of work at a point in time, not the stable public contract.

- [SCHEMA_DESIGN.md](temporal/SCHEMA_DESIGN.md)
- [KNOWN_BUGS.md](temporal/KNOWN_BUGS.md)
- [WHATS_LEFT.md](temporal/WHATS_LEFT.md)
