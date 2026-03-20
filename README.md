# javable

A KSP tool that generates Java-friendly wrapper classes from annotated Kotlin classes.

[![Java CI with Gradle](https://github.com/kpavlov/javable/actions/workflows/gradle.yml/badge.svg?branch=main)](https://github.com/kpavlov/javable/actions/workflows/gradle.yml)

## The Problem

Kotlin `suspend` functions cannot be called from Java.
Javable generates Kotlin/Java wrappers that expose suspend functions as `CompletableFuture<T>` (async)
or plain blocking calls — with an optional `Executor` overload to control which thread pool runs the
coroutine.

## Annotations

### `@JavaApi` — class level

Controls which wrapper(s) to generate for the annotated class.

```kotlin
@JavaApi(
    kotlinWrapper: Boolean = true,   // generate *Kotlin.kt
    javaWrapper:   Boolean = false,  // generate *Java.java
    autoCloseable: Boolean = false,  // implement AutoCloseable on the Java wrapper
)
```

### `@AsyncJavaApi` — function level

Wraps a `suspend` function as a `CompletableFuture<T>` (default) or `CompletionStage<T>`.
Two overloads are generated: one using the wrapper's default scope, one accepting an `Executor`.

```kotlin
@AsyncJavaApi(wrapperType: JavaWrapperType = JavaWrapperType.COMPLETABLE_FUTURE)
```

`JavaWrapperType` values:

| Value | Generated return type |
|---|---|
| `COMPLETABLE_FUTURE` _(default)_ | `CompletableFuture<T>` |
| `COMPLETION_STAGE` | `CompletionStage<T>` |

### `@BlockingJavaApi` — function level

Wraps a `suspend` function as a plain synchronous call via `runBlocking`.
No executor overload — blocking always runs on the calling thread.

---

## Usage

### Annotate your Kotlin class

```kotlin
import me.kpavlov.javable.annotations.*

@JavaApi(javaWrapper = true, autoCloseable = true)
class Calculator {

    @AsyncJavaApi                    // → CompletableFuture<Integer> add(int, int)
    suspend fun add(a: Int, b: Int): Int {
        delay(10L)
        return a + b
    }

    @BlockingJavaApi                 // → int multiply(int, int) throws InterruptedException
    suspend fun multiply(a: Int, b: Int): Int {
        delay(10L)
        return a * b
    }

    // suspend fun without annotation → skipped in generated wrappers
}
```

> **Suspend functions without `@AsyncJavaApi` or `@BlockingJavaApi` are not exposed.**
> Non-suspend public functions are always forwarded to the delegate unchanged.

---

### `@AsyncJavaApi` — async wrapper

#### Generated `CalculatorKotlin.kt`

```kotlin
public class CalculatorKotlin @JvmOverloads constructor(
    private val delegate: Calculator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    public fun add(a: Int, b: Int): CompletableFuture<Int> =
        scope.future { delegate.add(a, b) }

    public fun add(a: Int, b: Int, executor: Executor): CompletableFuture<Int> =
        scope.future(executor.asCoroutineDispatcher()) { delegate.add(a, b) }

    public fun multiply(a: Int, b: Int): Int =
        runBlocking { delegate.multiply(a, b) }

    override fun close() {
        val job = scope.coroutineContext[Job]
        scope.cancel()
        runBlocking { job?.join() }   // blocks until all coroutines complete
    }
}
```

#### Generated `CalculatorJava.java`

```java
@Generated("me.kpavlov.javable.ksp.JavaClassGenerator")
public final class CalculatorJava implements AutoCloseable {
    private final Calculator delegate;
    private final Job scopeJob;
    private final CoroutineScope scope;

    public CalculatorJava(Calculator delegate) { ... }
    public CalculatorJava(Calculator delegate, Executor executor) { ... }

    @Override
    public void close() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        this.scopeJob.invokeOnCompletion(cause -> { done.complete(null); return Unit.INSTANCE; });
        CoroutineScopeKt.cancel(this.scope, "closed", null);
        try {
            done.get(5L, TimeUnit.SECONDS);   // bounded wait
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        } catch (TimeoutException e) {
            throw new RuntimeException("Scope did not close within 5 seconds", e);
        }
    }

    // @AsyncJavaApi → two overloads
    public CompletableFuture<Integer> add(int a, int b) {
        return FutureKt.future(scope, EmptyCoroutineContext.INSTANCE, CoroutineStart.DEFAULT,
            (s, continuation) -> delegate.add(a, b, continuation));
    }

    public CompletableFuture<Integer> add(int a, int b, Executor executor) {
        return FutureKt.future(scope, ExecutorsKt.from(executor), CoroutineStart.DEFAULT,
            (s, continuation) -> delegate.add(a, b, continuation));
    }

    // @BlockingJavaApi → single blocking overload
    public int multiply(int a, int b) throws InterruptedException {
        return (Integer) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,
            (s, continuation) -> delegate.multiply(a, b, continuation));
    }
}
```

#### Using from Java

```java
// try-with-resources — close() cancels scope and waits up to 5 s
try (var calc = new CalculatorJava(new Calculator())) {
    int sum    = calc.add(1, 2).get();          // async
    int product = calc.multiply(3, 4);           // blocking
}

// With virtual threads (Java 21+):
try (var calc = new CalculatorJava(new Calculator(), Executors.newVirtualThreadPerTaskExecutor())) {
    calc.add(1, 2).thenAccept(System.out::println);
}
```

```java
// Kotlin wrapper — identical API
try (var calc = new CalculatorKotlin(new Calculator())) {
    int sum    = calc.add(1, 2).get();
    int product = calc.multiply(3, 4);
}
```

---

### `@AsyncJavaApi(wrapperType = COMPLETION_STAGE)` — `CompletionStage` return type

Use `COMPLETION_STAGE` when you want callers to depend on the `CompletionStage` interface
rather than the concrete `CompletableFuture` class:

```kotlin
@JavaApi(javaWrapper = true)
class EventService {

    @AsyncJavaApi(wrapperType = JavaWrapperType.COMPLETION_STAGE)
    suspend fun publish(event: String): Unit { ... }
}
```

Generated Java signature:

```java
public CompletionStage<Void> publish(String event) { ... }
public CompletionStage<Void> publish(String event, Executor executor) { ... }
```

> `scope.future { }` returns `CompletableFuture`, which is a `CompletionStage` subtype,
> so no runtime overhead is added.

---

### Scope lifecycle and `AutoCloseable`

| Condition | Scope generated? | `AutoCloseable`? |
|---|---|---|
| `autoCloseable = true` (Java wrapper) | always | yes |
| `autoCloseable = false`, has `@AsyncJavaApi` methods (Java wrapper) | yes | no |
| `autoCloseable = false`, no `@AsyncJavaApi` methods (Java wrapper) | no | no |
| has `@AsyncJavaApi` methods (Kotlin wrapper) | yes | yes |
| no `@AsyncJavaApi` methods (Kotlin wrapper) | no | no |

The Kotlin wrapper's `close()` cancels the scope and blocks until all child coroutines finish:

```kotlin
override fun close() {
    val job = scope.coroutineContext[Job]
    scope.cancel()
    runBlocking { job?.join() }
}
```

The Java wrapper's `close()` does the same with a 5-second timeout, surfacing errors
rather than swallowing them.

---

## Modules

| Module | Description |
|--------|-------------|
| `javable-annotations` | `@JavaApi`, `@AsyncJavaApi`, `@BlockingJavaApi`, `JavaWrapperType` |
| `javable-ksp` | KSP processor + JavaPoet/KotlinPoet code generators |
| `integration-tests` | End-to-end examples (`Calculator`, `UserRepository`) |

## Build

```bash
./gradlew build    # build + KSP generation + tests
./gradlew check    # all checks
```

## Tech Stack

- Kotlin 2.3.20 · KSP 2.3.6 · Java 17+
- [Palantir JavaPoet](https://github.com/palantir/javapoet) for Java source generation
- [Square KotlinPoet](https://square.github.io/kotlinpoet/) for Kotlin source generation
- kotlinx.coroutines 1.10.2 (`coroutines-core` + `coroutines-jdk9`)
