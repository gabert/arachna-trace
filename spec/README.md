# Wire-format Spec

The language-neutral contract between agents (producers) and
infrastructure (consumers). Every Arachna Trace agent and every piece
of server-side infrastructure must conform to this spec.

Read these in order:

1. **[SPEC.md](SPEC.md)** — top-level overview. Start here.
2. **[WIRE-FORMAT.md](WIRE-FORMAT.md)** — binary record framing.
3. **[CBOR-ENVELOPE.md](CBOR-ENVELOPE.md)** — value serialization with
   identity envelopes.
4. **[HASHING.md](HASHING.md)** — Merkle content hashing.
5. **[TAGS.md](TAGS.md)** — record tag catalog (`MS`, `AR`, `RE`, …).
6. **[TRANSPORT.md](TRANSPORT.md)** — HTTP intake, Kafka headers,
   `run.json` sidecar, agent-run identity.
7. **[IDENTITY-MODEL.md](IDENTITY-MODEL.md)** — `object_id` semantics
   across runtimes.
8. **[PORTING-GUIDE.md](PORTING-GUIDE.md)** — checklist for writing
   an agent in a new language.

Reference implementations:

- **JVM agent (producer):** [`../arachna-trace-agents/jvm/`](../arachna-trace-agents/jvm/)
- **Server-side pipeline (consumer):** [`../arachna-trace-infra/`](../arachna-trace-infra/)

A new agent in any language is conformant if pointing it at the
existing collector and reading the resulting UI shows the expected
calls, arguments, and mutations.
