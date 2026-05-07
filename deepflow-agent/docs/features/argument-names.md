# Argument names

When the agent captures a call's arguments, AR (entry) and AX (exit)
payloads are emitted as a name-keyed map — `{ "isbn": "9780…", "year":
1937 }` — instead of a positional array. This makes traces readable
without having to cross-reference the source.

## Where the names come from

The agent tries three sources, in order:

1. **`MethodParameters` attribute** (javac `-parameters` flag).
   Surfaced via reflection (`Parameter.isNamePresent()`). Spring Boot's
   parent POM enables this by default; many other projects do not.

2. **`LocalVariableTable` debug attribute** (javac `-g` flag).
   On by default in `maven-compiler-plugin` and Gradle, so most plain
   Java / Kotlin projects already have it. Read by the agent from the
   class bytes.

3. **Positional fallback**: `arg0`, `arg1`, … `argN`. Used when both
   attributes are missing.

The first source that yields names wins. Resolution happens once per
method, cached for the lifetime of the declaring `Class`.

## When you'll see real names

| build | `-parameters` | `-g` (debug) | result |
|---|---|---|---|
| Spring Boot (parent POM default) | ✓ | ✓ | real names |
| Plain Maven / Gradle (defaults) | ✗ | ✓ | real names |
| Anything compiled with `-g:none` (production-stripped) | ✗ | ✗ | `arg0..argN` |
| ProGuard / R8 / similar minified jar | ✗ | ✗ | `arg0..argN` |
| Hidden classes (lambdas, dynamic proxies, generated `MethodHandle` classes) | varies | none on disk | `arg0..argN` |

If a trace shows `arg0..argN` for a class you control, add either flag
to your build:

- Maven (`pom.xml` under `maven-compiler-plugin` config):
  ```xml
  <parameters>true</parameters>
  ```
- Gradle (`build.gradle.kts`):
  ```kotlin
  tasks.withType<JavaCompile> {
      options.compilerArgs.add("-parameters")
  }
  ```
- Or simply ensure debug info is on (`<debug>true</debug>` for
  `maven-compiler-plugin`, the default; `compileJava { options.debug =
  true }` for Gradle, the default).

The fallback never fails — `arg0..argN` is identical to what older
agent versions emitted. The feature is purely additive.
