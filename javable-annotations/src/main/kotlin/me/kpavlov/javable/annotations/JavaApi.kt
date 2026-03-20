package me.kpavlov.javable.annotations

/**
 * Marks a class or API as accessible to Java consumers, providing an optional
 * hint regarding whether the resource is closeable.
 *
 * This annotation is used to indicate that a specific class or API is designed
 * to be compatible with Java usage. It can be applied to classes or public APIs
 * to improve maintainability and cross-language compatibility within mixed Kotlin
 * and Java projects.
 *
 * @property closeable Specifies whether the annotated class or API represents
 * a resource that should be explicitly closed by the consumer. Defaults to `false`.
 */
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
annotation class JavaApi(
    val kotlinWrapper: Boolean = true,
    val javaWrapper: Boolean = false,
    val autoCloseable: Boolean = false,
    val wrapperType: WrapperType = WrapperType.COMPLETABLE_FUTURE
) {
    /**
     * Represents the type of wrapper used for Kotlin-to-Java interop scenarios.
     *
     * This enum class is utilized to specify how Kotlin types or APIs should
     * interact with Java-based asynchronous constructs. It allows the
     * configuration of compatibility layers, ensuring proper usage and integration
     * of Kotlin APIs in Java environments.
     *
     * - `NONE`: No wrapper is applied.
     * - `COMPLETION_STAGE`: The type is wrapped as a `CompletionStage`.
     * - `COMPLETABLE_FUTURE`: The type is wrapped as a `CompletableFuture`.
     */
    enum class WrapperType {
        NONE,
        COMPLETION_STAGE,
        COMPLETABLE_FUTURE,
    }
}

