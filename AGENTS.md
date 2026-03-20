# AGENTS.md

Agent-specific reference for working in this codebase.

## Prime directives

- **Focus on code generation**: The primary goal is to generate high-quality Java code from Kotlin suspend functions.
- **Keep it simple**: Avoid unnecessary complexity and focus on the core functionality.
- **Test-driven development**: Write tests for both the generator and the generated code to ensure correctness. Start with simple cases and gradually add complexity.
- **Structured concurrency**: Use Java's `CompletableFuture` for asynchronous operations, Java 25 structured concurrency and virtual threads (for jvm 25+), and Kotlin's coroutines for structured concurrency.

## Key Files

| File | Role |
|------|------|
| `javable-annotations/src/main/kotlin/me/kpavlov/javable/annotations/JavaApi.kt` | `@JavaApi` annotation |
| `javable-ksp/src/main/kotlin/me/kpavlov/javable/ksp/JavaApiProcessor.kt` | KSP entry point — finds annotated classes, calls `JavaClassGenerator`, writes output |
| `javable-ksp/src/main/kotlin/me/kpavlov/javable/ksp/JavaClassGenerator.kt` | **Core generator** — `KSClassDeclaration` → `JavaFile.Builder` |
| `javable-ksp/src/main/kotlin/me/kpavlov/javable/ksp/SymbolFilter.kt` | Glob-based include/exclude filtering |
| `integration-tests/src/main/kotlin/me/kpavlov/javable/it/Calculator.kt` | Sample input: `suspend fun add(a: Int, b: Int): Int` |
| `integration-tests/src/test/java/me/kpavlov/javable/it/CalculatorTest.java` | Tests against the KSP-generated `CalculatorJava` class |
| `gradle/libs.versions.toml` | Version catalog |

Generated Java files land in:
`integration-tests/build/generated/ksp/main/java/`

Generated Kotlin files land in:
`integration-tests/build/generated/ksp/main/kotlin/`

## Code Generation Rules (`JavaClassGenerator`)

### Generated class shape
```
@Generated("me.kpavlov.javable.ksp.JavaClassGenerator")
public final class {Name}Java implements AutoCloseable {
    private final {Name} delegate;
    private final CoroutineScope scope;
    public {Name}Java({Name} delegate) { ... }
    public {Name}Java({Name} delegate, Executor executor) { ... }
    @Override public void close() { CoroutineScopeKt.cancel(this.scope, (CancellationException) null); }
    // methods below
}
```

### Function handling
- Skip: constructors, `equals`, `hashCode`, `toString`, non-public, abstract
- Regular functions → single delegating method
- Suspend functions (`Modifier.SUSPEND in function.modifiers`) → **two** `CompletableFuture<T>` methods:
  1. Default dispatcher (`EmptyCoroutineContext`)
  2. Caller-supplied `Executor` via `ExecutorsKt.from(executor)`

Both suspend variants are **non-blocking** — the second just controls which thread pool runs the coroutine. This scales with virtual threads and composes with async pipelines.

### Generated suspend method bodies

Default dispatcher:
```java
return FutureKt.future(scope, EmptyCoroutineContext.INSTANCE,
    CoroutineStart.DEFAULT,
    (s, continuation) -> delegate.method(params, continuation));
```

Executor dispatcher:
```java
return FutureKt.future(scope, ExecutorsKt.from(executor),
    CoroutineStart.DEFAULT,
    (s, continuation) -> delegate.method(params, continuation));
```

### Coroutine class references

| Usage | `ClassName.get(...)` |
|-------|----------------------|
| `FutureKt` | `"kotlinx.coroutines.future", "FutureKt"` |
| `SupervisionKt` (method `SupervisorJob`) | `"kotlinx.coroutines", "SupervisionKt"` |
| `CoroutineScopeKt` (methods `CoroutineScope`, `cancel`) | `"kotlinx.coroutines", "CoroutineScopeKt"` |
| `CoroutineScope` (field type) | `"kotlinx.coroutines", "CoroutineScope"` |
| `CancellationException` (cast in `close`) | `"kotlinx.coroutines", "CancellationException"` |
| `EmptyCoroutineContext` (field `INSTANCE`) | `"kotlin.coroutines", "EmptyCoroutineContext"` |
| `CoroutineStart` (field `DEFAULT`) | `"kotlinx.coroutines", "CoroutineStart"` |
| `ExecutorsKt` (method `from`) | `"kotlinx.coroutines", "ExecutorsKt"` |
| `Executor` parameter | `"java.util.concurrent", "Executor"` |
| `CompletableFuture` return | `"java.util.concurrent", "CompletableFuture"` |

### KSP → JavaPoet type mapping

| Kotlin qualified name | Unboxed | Boxed (for generics / nullable) |
|----------------------|---------|----------------------------------|
| `kotlin.Unit` | `TypeName.VOID` | `ClassName.get("java.lang", "Void")` |
| `kotlin.Int` | `TypeName.INT` | `TypeName.INT.box()` |
| `kotlin.Long` | `TypeName.LONG` | `TypeName.LONG.box()` |
| `kotlin.Double` | `TypeName.DOUBLE` | `TypeName.DOUBLE.box()` |
| `kotlin.Float` | `TypeName.FLOAT` | `TypeName.FLOAT.box()` |
| `kotlin.Boolean` | `TypeName.BOOLEAN` | `TypeName.BOOLEAN.box()` |
| `kotlin.Char` | `TypeName.CHAR` | `TypeName.CHAR.box()` |
| `kotlin.Short` | `TypeName.SHORT` | `TypeName.SHORT.box()` |
| `kotlin.Byte` | `TypeName.BYTE` | `TypeName.BYTE.box()` |
| `kotlin.String` | `ClassName.get("java.lang", "String")` | same |
| `kotlin.Any` | `ClassName.get("java.lang", "Object")` | same |
| nullable | always boxed | — |
| other | `ClassName.get(packageName, simpleName)` | same |

Suspend return types always use the boxed form (needed for `ParameterizedTypeName.get(COMPLETABLE_FUTURE, boxedType)`).

## JavaPoet + Kotlin 2.x String Syntax

Use `$$"..."` multi-dollar literals so `$T`, `$N`, `$S`, `$L` pass through as JavaPoet placeholders (not Kotlin interpolation). Use `$$variable` for Kotlin interpolation inside these strings:

```kotlin
// $T → JavaPoet type placeholder   $$methodName → Kotlin variable
builder.addStatement(
    $$"return $T.future(scope, $T.INSTANCE, $T.DEFAULT, (s, continuation) -> delegate.$$methodName($$callArgs))",
    FUTURE_KT, EMPTY_COROUTINE_CONTEXT, COROUTINE_START,
)
```

## Runtime Dependencies

`integration-tests/build.gradle.kts` needs `kotlinx-coroutines-jdk9` for `FutureKt`:
```kotlin
implementation(libs.kotlinx.coroutines.jdk9)
```

## Verify

```bash
./gradlew :integration-tests:build   # KSP runs, Java compiles, tests pass
./gradlew :javable-ksp:test          # unit tests for the processor
```