package me.kpavlov.javable.it

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.kpavlov.javable.annotations.AsyncJavaApi
import me.kpavlov.javable.annotations.JavaApi
import me.kpavlov.javable.annotations.JavaWrapperType

/**
 * Subject class for testing [JavaWrapperType.PUBLISHER] wrapper generation.
 *
 * - [words] — non-suspend function returning `Flow<String>`:
 *   generated wrappers expose it as `Publisher<String>` via `asPublisher()`.
 * - [numbers] — non-suspend function with a parameter returning `Flow<Int>`:
 *   verifies parameter forwarding in the generated Publisher method.
 * - [singleValue] — suspend function returning a scalar `Int`:
 *   generated wrappers expose it as `Publisher<Int>` via the `publish` builder.
 *
 * The generated wrappers do NOT require a coroutine scope and do NOT implement
 * `AutoCloseable`. Single-value suspend functions use `mono {}` from
 * `kotlinx-coroutines-reactor` (`Mono<T>` implements `Publisher<T>`).
 */
@JavaApi(javaWrapper = true, kotlinWrapper = true)
public class PublisherSubject {

    @AsyncJavaApi(wrapperType = JavaWrapperType.PUBLISHER)
    public fun words(): Flow<String> = flow {
        emit("alpha")
        delay(10)
        emit("beta")
        delay(10)
        emit("gamma")
    }

    @AsyncJavaApi(wrapperType = JavaWrapperType.PUBLISHER)
    public fun numbers(count: Int): Flow<Int> = flow {
        for (i in 1..count) emit(i)
    }

    @AsyncJavaApi(wrapperType = JavaWrapperType.PUBLISHER)
    public suspend fun singleValue(input: Int): Int {
        delay(10)
        return input * 3
    }
}
