package me.kpavlov.javable.annotations

/**
 * Selects the Java return type used when wrapping a `suspend` function with [AsyncJavaApi].
 */
public enum class JavaWrapperType {
    /**
     * The generated method returns `CompletableFuture<T>` (concrete class).
     * This is the default and gives callers access to the full `CompletableFuture` API
     * (e.g. `thenApply`, `exceptionally`, `get`).
     */
    COMPLETABLE_FUTURE,

    /**
     * The generated method returns `CompletionStage<T>` (interface).
     * Prefer this when callers should depend on the interface rather than the concrete class,
     * e.g. in library APIs or when using composition operators from `CompletionStage` only.
     *
     * The underlying implementation is still a `CompletableFuture`; no runtime overhead is added.
     */
    COMPLETION_STAGE,

    /**
     * The generated method returns `java.util.stream.Stream<T>` by collecting the `Flow<T>`
     * blocking via `runBlocking { flow.toList() }`.
     *
     * The annotated function must return `kotlinx.coroutines.flow.Flow<T>` and may be either
     * `suspend` or non-`suspend`. Use this to expose a reactive Kotlin Flow as a synchronous
     * Java Stream for callers that cannot consume coroutines or reactive streams directly.
     *
     * Note: this collects all elements into memory before returning the `Stream`. For very large
     * or infinite flows, prefer [PUBLISHER] instead.
     */
    STREAM,

    /**
     * The generated method returns `org.reactivestreams.Publisher<T>`.
     *
     * Two usage patterns are supported:
     *
     * 1. **Flow return** — the annotated function returns `kotlinx.coroutines.flow.Flow<T>`
     *    (suspend or non-suspend). The generated wrapper converts the Flow to a `Publisher`
     *    using `Flow.asPublisher()` from `kotlinx-coroutines-reactive`. The resulting Publisher
     *    is cold and fully reactive — elements are emitted lazily on subscription.
     *
     * 2. **Single-value suspend** — the annotated `suspend` function returns a scalar `T`.
     *    The generated wrapper emits a single element via the `publish` coroutine builder and
     *    completes. This requires a `CoroutineScope` (the wrapper will implement `AutoCloseable`).
     *
     * Requires `org.jetbrains.kotlinx:kotlinx-coroutines-reactor` on the runtime classpath
     * (which transitively provides `kotlinx-coroutines-reactive` for `asPublisher()`).
     */
    PUBLISHER,
}

/**
 * Marks a Kotlin class for Java-friendly wrapper generation via KSP.
 *
 * KSP reads this annotation and generates one or both of:
 * - A **Kotlin wrapper** (`*Kotlin.kt`) — always implements `AutoCloseable` when async methods are present.
 * - A **Java wrapper** (`*Java.java`) — optionally implements `AutoCloseable` when [autoCloseable] is `true`.
 *
 * Only functions annotated with [AsyncJavaApi] or [BlockingJavaApi] are exposed in the generated wrappers.
 * `suspend` functions without either annotation are silently skipped.
 * Non-`suspend` public functions are forwarded to the delegate unchanged.
 *
 * ### Example
 * ```kotlin
 * @JavaApi(javaWrapper = true, autoCloseable = true)
 * class Calculator {
 *
 *     @AsyncJavaApi
 *     suspend fun add(a: Int, b: Int): Int { ... }
 *
 *     @BlockingJavaApi
 *     suspend fun multiply(a: Int, b: Int): Int { ... }
 * }
 * ```
 *
 * @param kotlinWrapper When `true` (default), a Kotlin wrapper class (`CalculatorKotlin.kt`) is generated.
 * @param javaWrapper   When `true`, a pure-Java wrapper class (`CalculatorJava.java`) is generated.
 *                      Defaults to `false`.
 * @param autoCloseable When `true`, the generated Java wrapper implements `AutoCloseable` and its
 *                      `close()` method cancels the coroutine scope, then blocks (up to 5 seconds)
 *                      until all in-flight coroutines finish. Has no effect on the Kotlin wrapper
 *                      (which always adds `AutoCloseable` when async methods are present).
 *                      Defaults to `false`.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
public annotation class JavaApi(
    val kotlinWrapper: Boolean = true,
    val javaWrapper: Boolean = false,
    val autoCloseable: Boolean = false,
)

/**
 * Marks a `suspend` function for asynchronous wrapping in the generated Java/Kotlin wrapper.
 *
 * Two overloads are generated for each annotated function:
 * 1. A default-scope overload — runs the coroutine on the wrapper's internal `CoroutineScope`.
 * 2. An executor overload — runs the coroutine on the caller-supplied `Executor`
 *    (e.g. a virtual-thread executor on Java 21+).
 *
 * The return type is controlled by [wrapperType].
 *
 * ### Example — default `CompletableFuture`
 * ```kotlin
 * @JavaApi(javaWrapper = true)
 * class DataService {
 *
 *     @AsyncJavaApi
 *     suspend fun load(id: String): Data { ... }
 * }
 * ```
 * Generated Java:
 * ```java
 * CompletableFuture<Data> load(String id) { ... }
 * CompletableFuture<Data> load(String id, Executor executor) { ... }
 * ```
 *
 * ### Example — `CompletionStage` return type
 * ```kotlin
 * @AsyncJavaApi(wrapperType = JavaWrapperType.COMPLETION_STAGE)
 * suspend fun publish(event: String): Unit { ... }
 * ```
 * Generated Java:
 * ```java
 * CompletionStage<Void> publish(String event) { ... }
 * CompletionStage<Void> publish(String event, Executor executor) { ... }
 * ```
 *
 * @param wrapperType The Java return type to use. Defaults to [JavaWrapperType.COMPLETABLE_FUTURE].
 */
@MustBeDocumented
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
public annotation class AsyncJavaApi(val wrapperType: JavaWrapperType = JavaWrapperType.COMPLETABLE_FUTURE)

/**
 * Marks a `suspend` function for synchronous (blocking) wrapping in the generated Java/Kotlin wrapper.
 *
 * The generated method calls `runBlocking` internally and returns the result directly —
 * no `CompletableFuture`, no executor overload. The coroutine always runs on the calling thread.
 *
 * Use this annotation when the caller is already on a thread that is safe to block
 * (e.g. a dedicated worker thread or a virtual thread on Java 21+).
 *
 * **If both [AsyncJavaApi] and [BlockingJavaApi] are present on the same function,
 * [AsyncJavaApi] takes precedence and [BlockingJavaApi] is silently ignored.**
 *
 * **The generated Java method declares `throws InterruptedException`.**
 *
 * ### Example
 * ```kotlin
 * @JavaApi(javaWrapper = true)
 * class Calculator {
 *
 *     @BlockingJavaApi
 *     suspend fun multiply(a: Int, b: Int): Int { ... }
 * }
 * ```
 * Generated Java:
 * ```java
 * int multiply(int a, int b) throws InterruptedException { ... }
 * ```
 * Usage from Java:
 * ```java
 * int result = calculator.multiply(3, 4); // blocks until complete
 * ```
 */
@MustBeDocumented
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
public annotation class BlockingJavaApi
