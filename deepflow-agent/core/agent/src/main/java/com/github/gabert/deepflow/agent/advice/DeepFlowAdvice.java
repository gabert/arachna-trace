package com.github.gabert.deepflow.agent.advice;

import com.github.gabert.deepflow.agent.recording.RequestRecorder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;

/**
 * ByteBuddy advice that delegates entry/exit recording to the active
 * {@link RequestRecorder}. The {@code RECORDER} field is written once at
 * agent startup by {@code DeepFlowAgent.premain} and read by every traced
 * method invocation. Visibility relies on the happens-before from agent
 * startup completing before any instrumented class is loaded.
 *
 * <p>The {@link #onEnter} advice returns a boolean indicating whether the
 * entry was committed (UUID pushed onto {@code CALL_STACK} <em>and</em>
 * {@code MS} record queued). ByteBuddy passes this value as
 * {@link Advice.Enter} to {@link #onExit}, which only invokes
 * {@code recordExit} when entry succeeded. This guarantees push/pop
 * balance: a failed entry leaves both {@code DEPTH} and {@code CALL_STACK}
 * in their pre-entry state and suppresses the matching exit, so no later
 * call ever pairs against a wrong UUID.</p>
 */
public class DeepFlowAdvice {
    public static volatile RequestRecorder RECORDER;

    public static void setup(RequestRecorder recorder) {
        RECORDER = recorder;
    }

    @Advice.OnMethodEnter
    public static boolean onEnter(@Advice.Origin Method method,
                                  @Advice.This(optional = true) Object self,
                                  @Advice.AllArguments Object[] allArguments) {
        RequestRecorder recorder = RECORDER;
        if (recorder == null) return false;
        return recorder.recordEntry(method, self, allArguments);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter boolean entryRecorded,
                              @Advice.Origin Method method,
                              @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned,
                              @Advice.Thrown Throwable throwable,
                              @Advice.AllArguments Object[] allArguments) {
        if (!entryRecorded) return;
        RequestRecorder recorder = RECORDER;
        if (recorder != null) {
            recorder.recordExit(method, returned, throwable, allArguments);
        }
    }
}
