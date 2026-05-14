package com.github.gabert.arachna.trace.agent.bootstrap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-local state for request ID and call-id propagation.
 *
 * <p>This class is injected into the bootstrap classloader so that advice
 * inlined into JDK classes (ThreadPoolExecutor, ForkJoinPool) can access
 * the same state as advice running in application classes.</p>
 */
public class RequestContext {
    public static final AtomicLong REQUEST_COUNTER = new AtomicLong(0);
    public static final ThreadLocal<long[]> CURRENT_REQUEST_ID =
            ThreadLocal.withInitial(() -> new long[]{0L});
    public static final ThreadLocal<int[]> DEPTH =
            ThreadLocal.withInitial(() -> new int[]{0});

    /**
     * Per-thread stack of UUIDs for currently-open traced calls. Pushed at
     * method entry, popped at method exit. The top is the current call;
     * the value below the top is the parent. Empty at the top of a request.
     *
     * <p>Used to express the call tree on the wire: each {@code MS} record
     * carries its own UUID and its parent's UUID (or null at the root), so
     * the processor can rebuild the tree without holding state across
     * batch boundaries.</p>
     */
    public static final ThreadLocal<Deque<UUID>> CALL_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    /** Returns the current call's parent UUID, or {@code null} if at the top of the stack. */
    public static UUID peekParentCallId() {
        return CALL_STACK.get().peek();
    }

    public static void pushCallId(UUID callId) {
        CALL_STACK.get().push(callId);
    }

    /** Pops and returns the current call's UUID; returns {@code null} if the stack is empty. */
    public static UUID popCallId() {
        Deque<UUID> stack = CALL_STACK.get();
        return stack.isEmpty() ? null : stack.pop();
    }

    /**
     * Begin a request: at depth 0 assign a fresh request ID, then increment depth.
     * Returns the active request ID.
     */
    public static long beginRequest() {
        int[] depthHolder = DEPTH.get();
        long[] requestIdHolder = CURRENT_REQUEST_ID.get();
        if (depthHolder[0] == 0) {
            requestIdHolder[0] = REQUEST_COUNTER.incrementAndGet();
        }
        depthHolder[0]++;
        return requestIdHolder[0];
    }

    /**
     * End a request: decrement depth (clamped at 0). Returns the request ID
     * that was active for this exit so callers can stamp it on records.
     */
    public static long endRequest() {
        int[] depthHolder = DEPTH.get();
        long requestId = CURRENT_REQUEST_ID.get()[0];
        if (depthHolder[0] > 0) {
            depthHolder[0]--;
        }
        return requestId;
    }

    /**
     * Run {@code body} with request state forced to (parentRequestId, depth=1)
     * and the call stack seeded with {@code parentCallId} (so the first traced
     * method on the worker thread sees the submitter's call as its parent),
     * restoring prior state on completion. Used by
     * Propagating{Runnable,Callable} to carry the request ID and call linkage
     * across thread boundaries.
     */
    public static void runScoped(long parentRequestId, UUID parentCallId, Runnable body) {
        long[] requestIdHolder = CURRENT_REQUEST_ID.get();
        int[] depthHolder = DEPTH.get();
        Deque<UUID> savedStack = CALL_STACK.get();

        long savedRequestId = requestIdHolder[0];
        int savedDepth = depthHolder[0];

        requestIdHolder[0] = parentRequestId;
        depthHolder[0] = 1;
        Deque<UUID> workerStack = new ArrayDeque<>();
        if (parentCallId != null) workerStack.push(parentCallId);
        CALL_STACK.set(workerStack);

        try {
            body.run();
        } finally {
            requestIdHolder[0] = savedRequestId;
            depthHolder[0] = savedDepth;
            CALL_STACK.set(savedStack);
        }
    }

    /**
     * Callable counterpart of {@link #runScoped(long, UUID, Runnable)}.
     */
    public static <V> V callScoped(long parentRequestId, UUID parentCallId, Callable<V> body) throws Exception {
        long[] requestIdHolder = CURRENT_REQUEST_ID.get();
        int[] depthHolder = DEPTH.get();
        Deque<UUID> savedStack = CALL_STACK.get();

        long savedRequestId = requestIdHolder[0];
        int savedDepth = depthHolder[0];

        requestIdHolder[0] = parentRequestId;
        depthHolder[0] = 1;
        Deque<UUID> workerStack = new ArrayDeque<>();
        if (parentCallId != null) workerStack.push(parentCallId);
        CALL_STACK.set(workerStack);

        try {
            return body.call();
        } finally {
            requestIdHolder[0] = savedRequestId;
            depthHolder[0] = savedDepth;
            CALL_STACK.set(savedStack);
        }
    }
}
