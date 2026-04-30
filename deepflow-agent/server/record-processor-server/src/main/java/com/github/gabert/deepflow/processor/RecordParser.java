package com.github.gabert.deepflow.processor;

import com.github.gabert.deepflow.recorder.destination.RecordRenderer.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Walks a rendered (and hash-enriched) {@link Result} line stream and pairs
 * up {@code TS}/{@code TE} markers via the wire-carried {@code call_id} UUID
 * (tag {@code CI}) to emit one {@link ParsedCall} per method invocation.
 *
 * <h2>Why stateful and UUID-keyed (vs the old per-batch stack)</h2>
 *
 * <p>The previous implementation used a method-local stack to pair {@code TS}
 * with {@code TE} by ordering. That had a latent bug: a request whose root
 * {@code TS} arrived in poll N and root {@code TE} in poll N+1 was silently
 * dropped (the in-flight builder sat in the local stack and was discarded
 * when {@code parse()} returned). Multi-thread interleaving in one batch
 * also pretended to share one stack, mispairing across threads.</p>
 *
 * <p>This implementation instead keys open calls by their UUID
 * ({@link UUID}, on the wire as {@code CI}). State persists across
 * {@code parse()} calls in {@link #openCalls}, so a {@code TE} can
 * find its matching {@code TS} no matter which batch each lived in.
 * Multi-thread interleaving in one batch is also correct because every
 * call is uniquely addressable by id.</p>
 *
 * <h2>Wire ordering this code handles</h2>
 *
 * <p>For one method invocation, the renderer emits tags in this rough order:
 * <pre>
 *   MS record -> TS, [SI], MS, TN, RI, CL, [CI], [PI]
 *   TI/AR     -> TI, AR
 *   ME record -> TE, TN, RI, [CI]
 *   RT/RE     -> RT, [RE]
 *   AX (opt)  -> AX
 * </pre>
 * The agent emits the exit records in the order METHOD_END, RETURN,
 * ARGUMENTS_EXIT, so on the wire {@code TE} comes <em>before</em> the call's
 * own {@code RT}/{@code RE}/{@code AX}. After {@code TE}, the parser stays in
 * "exit context" until the next {@code TS} or {@code TE}.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>One instance per {@code ClickHouseSink} (or any owner). State accumulates
 * across {@code parse()} calls — open calls live in {@link #openCalls} until
 * their matching {@code TE} or until TTL eviction reaps them (an MS without
 * an ME means the agent crashed mid-call; the entry would otherwise leak
 * forever).</p>
 *
 * <h2>TTL eviction</h2>
 *
 * <p>At the end of every {@code parse()} call we sweep entries whose
 * processor-side admission time is older than {@link #OPEN_CALL_TTL_MS}.
 * The sweep itself is throttled to {@link #SWEEP_INTERVAL_MS} so the cost
 * is amortised across many batches. The clock is injectable for tests.
 * Both knobs are deliberately hard-coded — the TTL is loose enough
 * (10 minutes) to be safely above any plausible real-world method
 * duration, and the sweep cadence is decoupled from real-time pressure.</p>
 */
public final class RecordParser {

    /** Drop open-call entries whose processor-side admission age exceeds this. */
    private static final long OPEN_CALL_TTL_MS = 10 * 60 * 1000L;
    /** Skip sweeping more often than this — the eviction itself is O(n). */
    private static final long SWEEP_INTERVAL_MS = 60 * 1000L;

    private final Map<UUID, Builder> openCalls = new HashMap<>();
    private final LongSupplier clock;
    private long nextSweepAt;

    /** Builder currently accumulating tags from an MS record (entry context). */
    private Builder currentEntry;

    /** Set after {@code TE} until {@code CI} resolves which call exited. */
    private boolean awaitingExitCallId;
    private long pendingTsOut;

    /** Builder accumulating tags from an ME record (exit context, post-CI). */
    private Builder currentExit;

    public RecordParser() {
        this(System::currentTimeMillis);
    }

    /** Test-only: inject a clock so eviction can be exercised deterministically. */
    RecordParser(LongSupplier clock) {
        this.clock = clock;
    }

    public List<ParsedCall> parse(Result result) {
        List<ParsedCall> completed = new ArrayList<>();

        for (String line : result.lines()) {
            int sep = line.indexOf(';');
            if (sep < 0) continue;
            String tag = line.substring(0, sep);
            String value = line.substring(sep + 1);

            switch (tag) {
                case "VR" -> { /* wire-format version banner — not modeled */ }

                case "TS" -> {
                    flushExitIfAny(completed);
                    currentEntry = new Builder(clock.getAsLong());
                    currentEntry.tsIn = parseLongOrZero(value);
                }

                case "TE" -> {
                    flushExitIfAny(completed);
                    // We don't yet know which call this TE belongs to —
                    // its CI tag will follow in the same ME record.
                    awaitingExitCallId = true;
                    pendingTsOut = parseLongOrZero(value);
                    currentEntry = null;
                }

                case "CI" -> {
                    UUID callId = parseUuidOrNull(value);
                    if (callId == null) break;
                    if (currentEntry != null) {
                        // Entry's call_id — index the builder so we can find it on TE.
                        // Defensive: if a malformed MS block ever emits two CIs, ignore
                        // the second so we don't leak the first builder under a stale key.
                        if (currentEntry.callId != null) break;
                        currentEntry.callId = callId;
                        openCalls.put(callId, currentEntry);
                        // currentEntry stays so subsequent TI/AR still apply.
                    } else if (awaitingExitCallId) {
                        Builder b = openCalls.remove(callId);
                        awaitingExitCallId = false;
                        if (b != null) {
                            b.tsOut = pendingTsOut;
                            currentExit = b;
                        }
                        // Else orphan ME — entry never recorded (failed-entry contract
                        // in RequestRecorder). No matching MS exists, so nothing to pair.
                    }
                    // else: stray CI with no context — ignore.
                }

                case "PI" -> {
                    if (currentEntry != null) currentEntry.parentCallId = parseUuidOrNull(value);
                }

                case "TN", "RI" -> {
                    // Both MS and ME records emit these; route by current context.
                    if (currentEntry != null) {
                        apply(currentEntry, tag, value);
                    } else if (currentExit != null) {
                        apply(currentExit, tag, value);
                    }
                    // After TE before CI: would be a duplicate from ME — drop.
                }

                case "RT", "RE", "AX" -> {
                    // Belong to the call that just hit TE.
                    if (currentExit != null) apply(currentExit, tag, value);
                }

                default -> {
                    // SI, MS, CL, TI, AR — apply to entry context.
                    if (currentEntry != null) apply(currentEntry, tag, value);
                }
            }
        }

        // End of batch: flush any in-progress exit. A currentEntry that hasn't
        // seen CI yet stays in this.currentEntry for the next batch — its
        // remaining tags may arrive later.
        flushExitIfAny(completed);

        evictStaleOpenCalls();

        return completed;
    }

    private void flushExitIfAny(List<ParsedCall> completed) {
        if (currentExit != null) {
            completed.add(currentExit.build());
            currentExit = null;
        }
    }

    private void evictStaleOpenCalls() {
        long now = clock.getAsLong();
        if (now < nextSweepAt) return;
        nextSweepAt = now + SWEEP_INTERVAL_MS;
        long cutoff = now - OPEN_CALL_TTL_MS;
        openCalls.entrySet().removeIf(e -> e.getValue().openedAtMillis < cutoff);
    }

    /** Test-only: visibility on retained state for eviction assertions. */
    int openCallCount() {
        return openCalls.size();
    }

    private static void apply(Builder b, String tag, String value) {
        switch (tag) {
            case "MS" -> b.signature = value;
            case "SI" -> b.sessionId = value;
            case "TN" -> b.threadName = value;
            case "RI" -> b.requestId = parseLongOrZero(value);
            case "CL" -> b.callerLine = parseIntOrZero(value);
            case "TI" -> setThis(b, value);
            case "AR" -> b.argsJson = value;
            case "AX" -> b.argsExitJson = value;
            case "RT" -> b.returnType = value;
            case "RE" -> b.returnJson = value;
            // unknown tag — drop
        }
    }

    private static void setThis(Builder b, String value) {
        if (looksLikeJson(value)) {
            b.thisJson = value;
        } else {
            try {
                b.thisIdRef = Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                // unreadable — leave null
            }
        }
    }

    private static boolean looksLikeJson(String v) {
        if (v.isEmpty()) return false;
        char c = v.charAt(0);
        return c == '{' || c == '[';
    }

    private static long parseLongOrZero(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }

    private static int parseIntOrZero(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static UUID parseUuidOrNull(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static final class Builder {
        final long openedAtMillis;
        UUID callId;
        UUID parentCallId;
        String sessionId;
        long requestId;
        String threadName;
        long tsIn;
        long tsOut;
        String signature;
        int callerLine;
        String returnType = "VOID";
        Long thisIdRef;
        String thisJson;
        String argsJson;
        String argsExitJson;
        String returnJson;

        Builder(long openedAtMillis) {
            this.openedAtMillis = openedAtMillis;
        }

        ParsedCall build() {
            return new ParsedCall(
                    callId, parentCallId,
                    sessionId, requestId, threadName,
                    tsIn, tsOut, signature, callerLine,
                    returnType, thisIdRef, thisJson,
                    argsJson, argsExitJson, returnJson);
        }
    }
}
