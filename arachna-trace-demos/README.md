# Arachna Trace Demos

Sample applications with the Arachna Trace agent attached, organised
by source language.

| Language | Directory |
|---|---|
| JVM | [`jvm/`](jvm/) — `demo-plain` (no framework, ~30 LoC), `demo-spring-boot` (JPA + Spring HTTP sessions) |

Demos exercise the [agent contract](../spec/) end-to-end against the
[infra pipeline](../arachna-trace-infra/), so they double as living
integration tests: if a demo's `test-run.sh` produces a non-empty
trace, the agent is wiring up correctly.

For build / run instructions, see each demo's local README.
