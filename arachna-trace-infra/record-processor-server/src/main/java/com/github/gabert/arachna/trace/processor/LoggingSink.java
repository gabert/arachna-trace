package com.github.gabert.arachna.trace.processor;

import com.github.gabert.arachna.trace.codec.AgentRun;
import com.github.gabert.arachna.trace.recorder.destination.RecordRenderer;

public class LoggingSink implements RecordSink {

    @Override
    public void accept(RecordRenderer.Result result, AgentRun headerMetadata) {
        if (headerMetadata != null) {
            System.out.println("[agent_run] " + headerMetadata.agentRunId()
                    + " host=" + headerMetadata.hostname()
                    + " env=" + headerMetadata.env());
        }
        for (String line : result.lines()) {
            System.out.println(line);
        }
    }

    @Override
    public void close() {}
}
