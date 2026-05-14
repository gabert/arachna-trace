package com.github.gabert.arachna.trace.recorder.destination;

import com.github.gabert.arachna.trace.recorder.AgentRun;

import java.io.Closeable;
import java.io.IOException;

public interface Destination extends Closeable {
    void accept(byte[] record);
    void flush() throws IOException;

    /**
     * Hand the destination the immutable identity of this agent run. Called
     * exactly once during agent startup, before any record is dispatched and
     * before the drainer thread is started, so implementations can stash the
     * value without synchronization.
     *
     * <p>Default is no-op for destinations that do not need it (e.g. the
     * in-memory test destination). Network/file destinations override to
     * propagate the metadata as transport-layer information (HTTP headers,
     * Kafka headers, file sidecar).</p>
     */
    default void setAgentRun(AgentRun agentRun) {}
}
