package com.github.gabert.deepflow.agent.bootstrap;

import java.util.UUID;

public class PropagatingRunnable implements Runnable {
    private final Runnable delegate;
    private final long parentRequestId;
    private final UUID parentCallId;

    public PropagatingRunnable(Runnable delegate, long parentRequestId, UUID parentCallId) {
        this.delegate = delegate;
        this.parentRequestId = parentRequestId;
        this.parentCallId = parentCallId;
    }

    @Override
    public void run() {
        RequestContext.runScoped(parentRequestId, parentCallId, delegate);
    }
}
