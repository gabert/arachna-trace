package com.github.gabert.arachna.trace.agent.recording;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the trace-line format consumed by the Python formatter and the
 * record renderer. The source file warns:
 * "Do not change without updating
 *  arachna-trace-formater/arachna-trace/agent_dump_processor.py".
 * These tests make accidental drift impossible — a single character
 * change in the format will fail loud.
 */
class MethodSignatureFormatterTest {

    static class Sample {
        public void simple(String name, int age) { }
        public static String staticMethod(long offset) { return null; }
        public Object[] arrayReturn(String[] words, int[][] matrix) { return null; }
    }

    @Test
    void regularInstanceMethod() throws Exception {
        Method m = Sample.class.getMethod("simple", String.class, int.class);
        assertEquals(
                "com.github.gabert.arachna.trace.agent.recording::MethodSignatureFormatterTest$Sample"
                        + ".simple(java.lang::String, int) -> void [public]",
                MethodSignatureFormatter.format(m));
    }

    @Test
    void staticMethodCarriesStaticModifier() throws Exception {
        Method m = Sample.class.getMethod("staticMethod", long.class);
        assertEquals(
                "com.github.gabert.arachna.trace.agent.recording::MethodSignatureFormatterTest$Sample"
                        + ".staticMethod(long) -> java.lang::String [public static]",
                MethodSignatureFormatter.format(m));
    }

    @Test
    void arrayParamFormatting() throws Exception {
        // The `::` substitution applies only to the class part, not to `[]`.
        // String[] → "java.lang::String[]", not "java.lang::String::[]".
        Method m = Sample.class.getMethod("arrayReturn", String[].class, int[][].class);
        String sig = MethodSignatureFormatter.format(m);
        assertEquals(
                "com.github.gabert.arachna.trace.agent.recording::MethodSignatureFormatterTest$Sample"
                        + ".arrayReturn(java.lang::String[], int[][]) -> java.lang::Object[] [public]",
                sig);
    }

    @Test
    void innerClassUsesDollarSeparator() throws Exception {
        // Outer$Inner — the formatter must NOT convert `$` to `::` and must
        // keep package boundary at the last dot (so Map.Entry → java.util::Map$Entry).
        Method m = Map.Entry.class.getMethod("getKey");
        String sig = MethodSignatureFormatter.format(m);
        assertEquals(
                "java.util::Map$Entry.getKey() -> java.lang::Object [public abstract]",
                sig);
    }
}
