package com.github.gabert.deepflow.processor;

import java.util.UUID;

/**
 * Per-JVM-run identity emitted by the agent at startup as a single
 * {@code RH} wire record. Surfaced by {@link RecordParser} when an
 * {@code RH} block is parsed; consumed by {@link ClickHouseSink} to
 * populate the {@code agent_runs} table.
 *
 * <p>{@code codeVersion} and {@code env} are nullable on the wire
 * (zero-length strings encode as {@code null}); persisted as empty
 * strings in the table for query simplicity.</p>
 */
public record AgentRunMetadata(
        UUID agentRunId,
        String hostname,
        String agentVersion,
        String codeVersion,
        String env,
        long jvmPid,
        long startedAtMillis
) {
}
