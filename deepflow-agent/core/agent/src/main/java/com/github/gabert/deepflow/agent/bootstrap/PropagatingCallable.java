package com.github.gabert.deepflow.agent.bootstrap;

import java.util.UUID;
import java.util.concurrent.Callable;

public class PropagatingCallable<V> implements Callable<V> {
    private final Callable<V> delegate;
    private final long parentRequestId;
    private final UUID parentCallId;

    public PropagatingCallable(Callable<V> delegate, long parentRequestId, UUID parentCallId) {
        this.delegate = delegate;
        this.parentRequestId = parentRequestId;
        this.parentCallId = parentCallId;
    }

    @Override
    public V call() throws Exception {
        return RequestContext.callScoped(parentRequestId, parentCallId, delegate);
    }
}
