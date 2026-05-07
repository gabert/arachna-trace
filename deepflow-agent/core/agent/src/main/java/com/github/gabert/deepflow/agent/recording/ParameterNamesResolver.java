package com.github.gabert.deepflow.agent.recording;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the AR/AX map keys for a {@link Method}. Output is an
 * {@code Object[]} where each element is one of:
 * <ul>
 *   <li>a {@link String} — a real parameter name resolved from
 *       {@code MethodParameters} ({@code -parameters}) or the
 *       {@code LocalVariableTable} debug attribute ({@code -g}, default
 *       in Maven/Gradle), or</li>
 *   <li>an {@link Integer} — the parameter index, used as positional
 *       fallback when no source of names is available, or when the
 *       {@code parameter_names} agent config is {@code false}.</li>
 * </ul>
 *
 * <p>{@link RequestRecorder} calls {@link #resolve(Method)}
 * unconditionally and feeds the result into a {@code LinkedHashMap}; it
 * does not branch on the config flag itself. The flag is read once at
 * startup via {@link #setEnabled(boolean)}.</p>
 *
 * <p><b>Cache discipline.</b> The {@link ClassValue} cache stores only
 * positively-resolved string-name arrays. Positional results — whether
 * because resolution is disabled or because the class was stripped of
 * both attributes — are never cached. They are trivially cheap to
 * recompute (an int loop with autoboxing), and skipping the cache write
 * keeps the agent's footprint flat under {@code parameter_names=false}.
 * Class-keyed storage is released automatically when its declaring
 * {@code Class} is unloaded, so hot-reloading containers (Spring Boot
 * DevTools, Tomcat redeploy, OSGi) cannot leak metaspace via a pinned
 * {@code Method} key.</p>
 */
public final class ParameterNamesResolver {

    private static final Object[] EMPTY = new Object[0];

    /**
     * When {@code false}, {@link #resolve(Method)} short-circuits to
     * positional integer keys without consulting the cache, reflection,
     * or the class bytes. Set once at agent startup from the
     * {@code parameter_names} agent config; not intended to flip mid-run.
     */
    private static volatile boolean enabled = true;

    /**
     * Per-class cache of resolved string-name arrays. Populated only
     * when MethodParameters or LocalVariableTable yielded names.
     * Positional results bypass this map entirely.
     */
    private static final ClassValue<Map<String, String[]>> CACHE_BY_CLASS =
            new ClassValue<>() {
                @Override
                protected Map<String, String[]> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };

    private ParameterNamesResolver() {}

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    public static Object[] resolve(Method method) {
        int count = method.getParameterCount();
        if (count == 0) return EMPTY;

        if (!enabled) return positional(count);

        Map<String, String[]> perClass = CACHE_BY_CLASS.get(method.getDeclaringClass());
        String key = method.getName() + Type.getMethodDescriptor(method);
        String[] cached = perClass.get(key);
        if (cached != null) return cached;

        String[] names = fromMethodParameters(method);
        if (names == null) names = fromLocalVariableTable(method);

        if (names != null) {
            perClass.putIfAbsent(key, names);
            return names;
        }

        // No real names available — return positional integer keys
        // without caching. Same shape as parameter_names=false, just
        // arrived at because the .class file lacks both attributes.
        return positional(count);
    }

    // --- Source 1: MethodParameters attribute --------------------------

    private static String[] fromMethodParameters(Method method) {
        Parameter[] params = method.getParameters();
        // isNamePresent() is reliable on the first parameter — javac
        // emits MethodParameters for all params or none.
        if (!params[0].isNamePresent()) return null;
        String[] out = new String[params.length];
        for (int i = 0; i < params.length; i++) {
            out[i] = params[i].getName();
        }
        return out;
    }

    // --- Source 2: LocalVariableTable debug attribute ------------------

    /**
     * Walks the declaring class's bytes with ASM, finds the matching
     * method by (name, descriptor), and reads its
     * {@code LocalVariableTable}. The first {@code paramSlots} slots
     * (skipping slot 0 for instance methods, which is {@code this})
     * carry parameter names; long/double parameters consume two slots.
     *
     * <p>Returns {@code null} if the class bytes can't be read or the
     * table is absent (i.e. {@code -g:none} was used at compile time).</p>
     */
    private static String[] fromLocalVariableTable(Method method) {
        Class<?> declaring = method.getDeclaringClass();
        ClassLoader loader = declaring.getClassLoader();
        if (loader == null) loader = ClassLoader.getSystemClassLoader();
        String resourceName = declaring.getName().replace('.', '/') + ".class";

        try (InputStream in = loader.getResourceAsStream(resourceName)) {
            if (in == null) return null;
            ClassReader reader = new ClassReader(in);

            String targetName = method.getName();
            String targetDesc = Type.getMethodDescriptor(method);
            int paramCount = method.getParameterCount();
            String[] result = new String[paramCount];
            int[] paramSlots = paramSlotIndices(method);
            boolean[] foundAny = new boolean[]{false};

            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    if (!name.equals(targetName) || !descriptor.equals(targetDesc)) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitLocalVariable(String varName, String varDesc, String varSig,
                                                        Label start, Label end, int index) {
                            for (int p = 0; p < paramCount; p++) {
                                if (paramSlots[p] == index && result[p] == null) {
                                    result[p] = varName;
                                    foundAny[0] = true;
                                    return;
                                }
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);

            if (!foundAny[0]) return null;
            // Backfill any slots the table missed (rare — keep output
            // stable rather than emitting null keys).
            for (int i = 0; i < paramCount; i++) {
                if (result[i] == null) result[i] = "arg" + i;
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Computes the LVT slot index for each parameter. Instance methods
     * occupy slot 0 with {@code this}; parameters start at slot 1.
     * Static methods start at slot 0. {@code long} and {@code double}
     * each consume two adjacent slots.
     */
    private static int[] paramSlotIndices(Method method) {
        int paramCount = method.getParameterCount();
        int[] slots = new int[paramCount];
        boolean isStatic = java.lang.reflect.Modifier.isStatic(method.getModifiers());
        int slot = isStatic ? 0 : 1;
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < paramCount; i++) {
            slots[i] = slot;
            slot += (types[i] == long.class || types[i] == double.class) ? 2 : 1;
        }
        return slots;
    }

    // --- Positional fallback (uncached) --------------------------------

    private static Object[] positional(int count) {
        Object[] out = new Object[count];
        for (int i = 0; i < count; i++) out[i] = i;
        return out;
    }
}
