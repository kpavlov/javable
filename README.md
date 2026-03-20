# javable

[![Java CI with Gradle](https://github.com/kpavlov/javable/actions/workflows/gradle.yml/badge.svg?branch=main)](https://github.com/kpavlov/javable/actions/workflows/gradle.yml)

Kotlin `suspend` functions are invisible to Java. Calling them from Java requires hand-written adapters that return
`CompletableFuture`, manage `CoroutineScope`, and implement `AutoCloseable` correctly — the same boilerplate every time.
The same problem exists for Kotlin `Flow<T>`: Java has no native way to consume a cold flow without coroutine plumbing.

**javable** eliminates that boilerplate. Annotate your Kotlin class once and KSP generates a ready-to-use Java wrapper (
and optionally a Kotlin one) with the right signatures, scope lifecycle, and resource cleanup. Async functions become
`CompletableFuture`; flows become `java.util.stream.Stream`; blocking wrappers are plain method calls.

---

## Setup

Add the KSP plugin and the javable dependencies to your `build.gradle.kts`:

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.3.6"
}

dependencies {
    implementation("me.kpavlov.javable:javable-annotations:0.1.0-SNAPSHOT")
    ksp("me.kpavlov.javable:javable-ksp:0.1.0-SNAPSHOT")

    // coroutines runtime — required in the consuming module
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk9:1.10.2")
}
```

> The library is not yet published to Maven Central. Build and publish it locally first:
> ```bash
> ./gradlew publishToMavenLocal
> ```
> Then add `mavenLocal()` to your repository list.

---

## Use Cases

### Expose a suspend function as `CompletableFuture`

This is the most common case: a Kotlin service with `suspend` functions that a Java caller needs to consume
asynchronously.

```kotlin
@JavaApi(javaWrapper = true, autoCloseable = true)
class Calculator {

    @AsyncJavaApi
    suspend fun add(a: Int, b: Int): Int {
        delay(10L)
        return a + b
    }
}
```

javable generates `CalculatorJava.java` with two overloads for each `@AsyncJavaApi` method — one using the wrapper's
built-in scope, one accepting a caller-supplied `Executor`:

```java
// Default scope — runs on Dispatchers.Default
CompletableFuture<Integer> add(int a, int b);

// Custom executor — useful with virtual threads (Java 21+)
CompletableFuture<Integer> add(int a, int b, Executor executor);
```

Use it from Java:

```java
void main() {
    try (var calc = new CalculatorJava(new Calculator())) {
        int result = calc.add(1, 2).get();
    }

    // With virtual threads (Java 21+):
    try (var exec = Executors.newVirtualThreadPerTaskExecutor();
         var calc = new CalculatorJava(new Calculator(), exec)) {
        calc.add(1, 2).thenAccept(System.out::println).get();
    }
}
```

The `autoCloseable = true` flag makes the Java wrapper implement `AutoCloseable`. Its `close()` cancels the coroutine
scope and waits up to 5 seconds for in-flight work to finish, so try-with-resources is safe.

---

### Expose a suspend function as a blocking call

When Java callers are on a thread that is safe to block (a dedicated worker or a virtual thread on Java 21+), use
`@BlockingJavaApi` instead:

```kotlin
@JavaApi(javaWrapper = true)
class Calculator {

    @BlockingJavaApi
    suspend fun multiply(a: Int, b: Int): Int {
        delay(10L)
        return a * b
    }
}
```

The generated method is a plain synchronous call — no `Future`, no executor overload:

```java
// Generated signature
int multiply(int a, int b) throws InterruptedException;
```

```java
// Usage
int product = calc.multiply(3, 4);   // blocks until complete
```

---

### Mix async and blocking on the same class

Both annotations can coexist on the same class. The generated wrapper handles each function independently:

```kotlin
@JavaApi(javaWrapper = true, autoCloseable = true)
class Calculator {

    @AsyncJavaApi
    suspend fun add(a: Int, b: Int): Int {
        delay(10); return a + b
    } // → CompletableFuture<Integer>

    @BlockingJavaApi
    suspend fun multiply(a: Int, b: Int): Int {
        delay(10); return a * b
    } // → int (blocking)
}
```

Suspend functions without either annotation are not exposed. Non-suspend public functions are always forwarded
unchanged.

---

### Return `CompletionStage` instead of `CompletableFuture`

If your API contract should depend on the `CompletionStage` interface rather than the concrete `CompletableFuture`
class, set `wrapperType`:

```kotlin
@JavaApi(javaWrapper = true)
class EventService {

    @AsyncJavaApi(wrapperType = JavaWrapperType.COMPLETION_STAGE)
    suspend fun publish(event: String) {
        ...
    }
}
```

Generated signatures:

```java
CompletionStage<Void> publish(String event);

CompletionStage<Void> publish(String event, Executor executor);
```

The underlying implementation is still a `CompletableFuture` — no runtime overhead.

---

### Expose a Kotlin `Flow` as a Java `Stream`

When a Kotlin function returns `Flow<T>`, Java callers have no clean way to consume it without coroutine knowledge.
Annotate the function with `@AsyncJavaApi(wrapperType = JavaWrapperType.STREAM)` — it works on both `suspend` and non-
`suspend` functions:

```kotlin
@JavaApi(javaWrapper = true, kotlinWrapper = true)
class StreamSubject {

    @AsyncJavaApi(wrapperType = JavaWrapperType.STREAM)
    fun words(): Flow<String> = flow {
        delay(500)
        emit("alpha")
        delay(100)
        emit("beta")
        delay(200)
        emit("gamma")
    }

    @AsyncJavaApi(wrapperType = JavaWrapperType.STREAM)
    fun numbers(count: Int): Flow<Int> = flow {
        for (i in 1..count) emit(i)
    }
}
```

javable generates a blocking `Stream<T>` method that collects the flow internally using `runBlocking`:

```java
// Generated signatures
Stream<String> words() throws InterruptedException;

Stream<Integer> numbers(int count) throws InterruptedException;
```

Use it from Java like any other `Stream`:

```java
var subject = new StreamSubjectJava(new StreamSubject());

List<String> words = subject.words().collect(Collectors.toList());
// ["alpha", "beta", "gamma"]

List<Integer> first4 = subject.numbers(4).collect(Collectors.toList());
// [1, 2, 3, 4]
```

Because no coroutine scope is needed — the flow is collected synchronously — the generated wrapper does **not**
implement `AutoCloseable` and has no scope field.

> **Note:** `STREAM` collects all flow elements into memory before returning the `Stream`. For very large or infinite
> flows, a reactive adapter (`Flux`, `Publisher`) is a better fit and is planned for a future release.

---

### Blocking-only class (no scope, no AutoCloseable)

If a class has only `@BlockingJavaApi` methods and no `@AsyncJavaApi` methods, javable generates a wrapper with no
`CoroutineScope` and no `AutoCloseable`:

```kotlin
@JavaApi(javaWrapper = true, kotlinWrapper = true)
class BlockingOnlySubject {

    @BlockingJavaApi
    suspend fun doubled(value: Int): Int {
        delay(5L)
        return value * 2
    }
}
```

```java
// No scope, no close() — just a plain wrapper
var result = new BlockingOnlySubjectJava(delegate).doubled(21); // 42
```

---

### Generate a Kotlin wrapper

By default, `@JavaApi` generates a Kotlin wrapper (`*Kotlin.kt`). This is useful when you want a clean, scope-managed
Kotlin API alongside the original coroutine implementation:

```kotlin
@JavaApi   // kotlinWrapper = true by default
class UserRepository(val generator: (Int) -> User) {

    @AsyncJavaApi
    suspend fun fetchAll(): List<User> {
        delay(100)
        return (1..100).map { generator(it) }
    }
}
```

The generated `UserRepositoryKotlin` class is fully usable from Java as well:

```java
void main() {
    try (var repo = new UserRepositoryKotlin(new UserRepository(i -> new User("User" + i)))) {
        List<User> users = repo.fetchAll().get();
    }
}
```

---

## Annotation Reference

### `@JavaApi` — class level

```kotlin
@JavaApi(
    kotlinWrapper = true,   // generate *Kotlin.kt
    javaWrapper = false,    // generate *Java.java
    autoCloseable = false,  // Java wrapper implements AutoCloseable
)
class Foo
```

### `@AsyncJavaApi` — function level

Wraps a `suspend` function as an async call, or a non-`suspend` function returning `Flow<T>` as a blocking stream call.
The generated output depends on `wrapperType`:

```kotlin
@AsyncJavaApi(wrapperType = JavaWrapperType.COMPLETABLE_FUTURE)
suspend fun search(query: String): String

@AsyncJavaApi(wrapperType = JavaWrapperType.STREAM)
fun askAgent(prompt: String): Flow<String>
```

| `wrapperType`                    | Applies to                                 | Generated return type          |
|----------------------------------|--------------------------------------------|--------------------------------|
| `COMPLETABLE_FUTURE` _(default)_ | `suspend` functions                        | `CompletableFuture<T>`         |
| `COMPLETION_STAGE`               | `suspend` functions                        | `CompletionStage<T>`           |
| `STREAM`                         | `fun` or `suspend fun` returning `Flow<T>` | `Stream<T>` (blocking collect) |

For `COMPLETABLE_FUTURE` and `COMPLETION_STAGE`, two overloads are always generated: one using the wrapper's built-in
scope, one accepting a caller-supplied `Executor`. For `STREAM`, a single blocking method is generated — no scope, no
executor overload.

### `@BlockingJavaApi` — function level

Wraps a `suspend` function as a plain synchronous call via `runBlocking`. No executor overload — always runs on the
calling thread. The generated method declares `throws InterruptedException`.

> If both `@AsyncJavaApi` and `@BlockingJavaApi` are present on the same function, `@AsyncJavaApi` takes precedence.

---

## Scope lifecycle

The generated wrappers manage a `CoroutineScope` backed by a `SupervisorJob`. Here's when a scope is created and when
`AutoCloseable` is implemented:

| Wrapper type | Condition                                                                            | Scope? | `AutoCloseable`? |
|--------------|--------------------------------------------------------------------------------------|--------|------------------|
| Java         | `autoCloseable = true`                                                               | yes    | yes              |
| Java         | `autoCloseable = false` + has `@AsyncJavaApi` (Future/Stage) methods                 | yes    | no               |
| Java         | `autoCloseable = false` + only `@AsyncJavaApi(STREAM)` or `@BlockingJavaApi` methods | no     | no               |
| Kotlin       | has `@AsyncJavaApi` (Future/Stage) methods                                           | yes    | yes              |
| Kotlin       | only `@AsyncJavaApi(STREAM)` or `@BlockingJavaApi` methods                           | no     | no               |

The **Kotlin wrapper's** `close()` cancels the scope and waits for all child coroutines to finish:

```kotlin
override fun close() {
    val job = scope.coroutineContext[Job]
    scope.cancel()
    runBlocking { job?.join() }
}
```

The **Java wrapper's** `close()` does the same with a 5-second timeout, and surfaces failures instead of swallowing
them:

```java

@Override
public void close() {
    CompletableFuture<Void> done = new CompletableFuture<>();
    this.scopeJob.invokeOnCompletion(cause -> {
        done.complete(null);
        return Unit.INSTANCE;
    });
    CoroutineScopeKt.cancel(this.scope, "closed", null);
    try {
        done.get(5L, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Close interrupted", e);
    } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
        throw new RuntimeException(cause);
    } catch (TimeoutException e) {
        throw new RuntimeException("Scope did not close within 5 seconds", e);
    }
}
```

---

## Modules

| Module                | Description                                                                                  |
|-----------------------|----------------------------------------------------------------------------------------------|
| `javable-annotations` | `@JavaApi`, `@AsyncJavaApi`, `@BlockingJavaApi`, `JavaWrapperType`                           |
| `javable-ksp`         | KSP processor + JavaPoet/KotlinPoet code generators                                          |
| `integration-tests`   | End-to-end examples (`Calculator`, `UserRepository`, `BlockingOnlySubject`, `StreamSubject`) |

---

## Build

```bash
./gradlew build    # compile, run KSP generation, and test
./gradlew check    # all checks including lint
```

**Tech stack:** Kotlin 2.3.20 · KSP 2.3.6 · Java
17+ · [Palantir JavaPoet](https://github.com/palantir/javapoet) · [Square KotlinPoet](https://square.github.io/kotlinpoet/) ·
kotlinx.coroutines 1.10.2

## Links

- https://kotlinlang.org/docs/coroutines-guide.html
- ["Calling Kotlin Suspending Functions from Java"](https://www.baeldung.com/kotlin/suspend-functions-from-java)
- https://github.com/palantir/javapoet
- https://square.github.io/kotlinpoet/
