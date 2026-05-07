package com.github.gabert.deepflow.agent.recording;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies that {@link ParameterNamesResolver} resolves real names
 * even when the {@code MethodParameters} attribute is absent — i.e.
 * when the source was compiled with {@code -g} (debug info) but
 * without {@code -parameters}. The agent reactor's parent POM does
 * not enable {@code -parameters}, so test classes here only carry
 * {@code LocalVariableTable}; correct names from this resolver prove
 * the LVT fallback is wired.
 *
 * <p>Also covers the {@link ParameterNamesResolver#setEnabled(boolean)}
 * disabled mode: positional integer keys, no cache writes.</p>
 */
class ParameterNamesResolverTest {

    static class Sample {
        public void instanceMethod(String name, int age) { }
        public static void staticMethod(String name, int age) { }
        public void widePrimitives(long offset, double weight, String tag) { }
        public void noArgs() { }
    }

    @AfterEach
    void resetEnabledFlag() {
        // setEnabled is a process-wide static; restore default so
        // subsequent tests see the resolver in its normal state.
        ParameterNamesResolver.setEnabled(true);
    }

    @Test
    void resolvesInstanceMethodNames() throws Exception {
        Method m = Sample.class.getMethod("instanceMethod", String.class, int.class);
        // Sanity: parent POM does not pass -parameters, so reflection
        // should NOT see names directly. If isNamePresent() is true
        // the test assumption is wrong.
        assertFalse(
                m.getParameters()[0].isNamePresent(),
                "test assumes -parameters is OFF for the agent reactor; "
                        + "reflection-based names should be unavailable");
        assertArrayEquals(new Object[]{"name", "age"}, ParameterNamesResolver.resolve(m));
    }

    @Test
    void resolvesStaticMethodNames() throws Exception {
        Method m = Sample.class.getMethod("staticMethod", String.class, int.class);
        assertArrayEquals(new Object[]{"name", "age"}, ParameterNamesResolver.resolve(m));
    }

    @Test
    void resolvesWidePrimitives() throws Exception {
        // long and double each occupy 2 LVT slots — verifies the
        // slot-to-parameter-index walk in paramSlotIndices().
        Method m = Sample.class.getMethod("widePrimitives", long.class, double.class, String.class);
        assertArrayEquals(new Object[]{"offset", "weight", "tag"}, ParameterNamesResolver.resolve(m));
    }

    @Test
    void emptyParameterListYieldsEmptyArray() throws Exception {
        Method m = Sample.class.getMethod("noArgs");
        assertArrayEquals(new Object[0], ParameterNamesResolver.resolve(m));
    }

    @Test
    void disabledModeReturnsIntegerPositionalKeys() throws Exception {
        Method m = Sample.class.getMethod("instanceMethod", String.class, int.class);
        ParameterNamesResolver.setEnabled(false);
        Object[] keys = ParameterNamesResolver.resolve(m);
        assertArrayEquals(new Object[]{0, 1}, keys);
        // Element types must be Integer, not String — downstream CBOR
        // encoding relies on this distinction.
        assertFalse(keys[0] instanceof String, "positional keys must be Integer");
    }
}
