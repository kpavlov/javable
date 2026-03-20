# javable

A KSP tool that generates Java-friendly wrapper classes from Kotlin classes annotated with `@JavaApi`.

## The Problem

Kotlin `suspend` functions cannot be called from Java. 
Javable generates Kotint/Java wrappers that expose suspend functions as `CompletableFuture<T>` 
— with an optional `Executor` overload to control which thread pool runs the coroutine.

## Usage

Annotate a Kotlin class:

```kotlin
@JavaApi
class Calculator {
    suspend fun add(a: Int, b: Int): Int { ... }
}
```

KSP generates both a Kotlin wrapper (`CalculatorKotlin.kt`) and a Java wrapper (`CalculatorJava.java`).

### `CalculatorKotlin.kt` - Kotlin java-friendly wrapper

```kotlin
public class CalculatorKotlin @JvmOverloads constructor(
    private val delegate: Calculator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {
    public fun add(a: Int, b: Int): CompletableFuture<Int> = scope.future { delegate.add(a, b) }

    public fun add(a: Int, b: Int, executor: Executor): CompletableFuture<Int> =
        scope.future(executor.asCoroutineDispatcher()) { delegate.add(a, b) }

    override fun close() {
        scope.cancel()
    }
}
```

Use it from Java:

```java
// try-with-resources — close() cancels the scope
try (var calc = new CalculatorKotlin(new Calculator())) {
    int result = calc.add(1, 2).get();
}

// With virtual threads (Java 21+):
try (var calc = new CalculatorKotlin(new Calculator(), Executors.newVirtualThreadPerTaskExecutor())) {
    calc.add(1, 2).thenAccept(System.out::println);
}
```

### `CalculatorJava.java` - pure Java wrapper

```java
@Generated("me.kpavlov.javable.ksp.JavaClassGenerator")
public final class CalculatorJava implements AutoCloseable {
    private final Calculator delegate;
    private final Job scopeJob;
    private final CoroutineScope scope;

    public CalculatorJava(Calculator delegate) {
        this.delegate = delegate;
        this.scopeJob = SupervisorKt.SupervisorJob(null);
        this.scope = CoroutineScopeKt.CoroutineScope(Dispatchers.getDefault().plus(this.scopeJob));
    }

    public CalculatorJava(Calculator delegate, Executor executor) {
        this.delegate = delegate;
        this.scopeJob = SupervisorKt.SupervisorJob(null);
        this.scope = CoroutineScopeKt.CoroutineScope(ExecutorsKt.from(executor).plus(this.scopeJob));
    }

    @Override
    public void close() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        this.scopeJob.invokeOnCompletion(cause -> { done.complete(null); return Unit.INSTANCE; });
        CoroutineScopeKt.cancel(this.scope, "closed", null);
        try {
            done.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        }
    }

    public CompletableFuture<Integer> add(int a, int b) {
        return FutureKt.future(scope, EmptyCoroutineContext.INSTANCE, CoroutineStart.DEFAULT,
            (s, continuation) -> delegate.add(a, b, continuation));
    }

    public CompletableFuture<Integer> add(int a, int b, Executor executor) {
        return FutureKt.future(scope, ExecutorsKt.from(executor), CoroutineStart.DEFAULT,
            (s, continuation) -> delegate.add(a, b, continuation));
    }
}
```

Use it from Java:

```java
// try-with-resources — close() blocks until all coroutines finish
try (var calc = new CalculatorJava(new Calculator())) {
    int result = calc.add(1, 2).get();
}

// With virtual threads (Java 21+):
try (var calc = new CalculatorJava(new Calculator(), Executors.newVirtualThreadPerTaskExecutor())) {
    calc.add(1, 2).thenAccept(System.out::println);
}
```

## Modules

| Module | Description |
|--------|-------------|
| `javable-annotations` | `@JavaApi` annotation |
| `javable-ksp` | KSP processor + JavaPoet code generator |
| `integration-tests` | End-to-end example (Calculator) |

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