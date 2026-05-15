package com.github.gabert.arachna.trace.processor;

import com.github.gabert.arachna.trace.codec.AgentRun;
import com.github.gabert.arachna.trace.recorder.destination.RecordRenderer;

public interface RecordSink extends AutoCloseable {
    /**
     * Process one rendered Kafka batch.
     *
     * @param result          the rendered + hash-enriched record lines
     * @param headerMetadata  agent-run identity carried on the Kafka message
     *                        headers; {@code null} if the message did not carry
     *                        them (legacy / direct Kafka producers). When
     *                        non-null, sinks should treat this as the
     *                        authoritative source for the run identity and
     *                        ignore any in-payload {@code RH} record.
     */
    void accept(RecordRenderer.Result result, AgentRun headerMetadata);

    @Override
    void close();
}
