package com.github.gabert.arachna.trace.recorder;

import java.util.UUID;

/**
 * Immutable identity of one JVM run of the agent. Built once at agent startup
 * and handed to {@link com.github.gabert.arachna.trace.recorder.destination.Destination}
 * implementations via {@code setAgentRun}, which carry these fields as
 * transport metadata (HTTP headers, Kafka headers, file sidecar) on every
 * downstream message.
 *
 * <p>Carrying agent-run identity at the transport layer rather than inside
 * each method-start/end record means the consumer cannot miss it: a delayed,
 * out-of-order, restarted, or rewound consumer always sees the metadata
 * alongside the records it describes.</p>
 */
public record AgentRun(
        UUID agentRunId,
        String hostname,
        String agentVersion,
        String codeVersion,
        String env,
        long jvmPid,
        long startedAtMillis
) {
    /**
     * Transport header names used to carry {@link AgentRun} fields across
     * HTTP (agent → collector) and Kafka (collector → processor) hops.
     * Centralized here so producers and consumers cannot drift.
     *
     * <p>The same string is used as the HTTP header name and as the Kafka
     * record header key.</p>
     */
    public static final class Headers {
        public static final String AGENT_RUN_ID    = "X-Arachna-Trace-Agent-Run-Id";
        public static final String HOSTNAME        = "X-Arachna-Trace-Hostname";
        public static final String AGENT_VERSION   = "X-Arachna-Trace-Agent-Version";
        public static final String CODE_VERSION    = "X-Arachna-Trace-Code-Version";
        public static final String ENV             = "X-Arachna-Trace-Env";
        public static final String JVM_PID         = "X-Arachna-Trace-Jvm-Pid";
        public static final String STARTED_AT_MS   = "X-Arachna-Trace-Started-At-Millis";

        private Headers() {}
    }
}
