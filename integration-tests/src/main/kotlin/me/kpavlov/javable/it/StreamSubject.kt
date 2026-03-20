package me.kpavlov.javable.it

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.kpavlov.javable.annotations.AsyncJavaApi
import me.kpavlov.javable.annotations.JavaApi
import me.kpavlov.javable.annotations.JavaWrapperType

/**
 * Subject class for testing [JavaWrapperType.STREAM] wrapper generation.
 *
 * - [words] — non-suspend function returning `Flow<String>`:
 *   generated wrappers expose it as `Stream<String>`.
 * - [numbers] — non-suspend function with a parameter returning `Flow<Int>`:
 *   verifies parameter forwarding in the generated Stream method.
 *
 * Neither method requires a coroutine scope, so the generated wrappers
 * must NOT implement `AutoCloseable` and must NOT have a scope field.
 */
@JavaApi(javaWrapper = true, kotlinWrapper = true)
public class StreamSubject {

    @AsyncJavaApi(wrapperType = JavaWrapperType.STREAM)
    public fun words(): Flow<String> = flow {
        emit("alpha")
        delay(10)
        emit("beta")
        delay(10)
        emit("gamma")
    }

    @AsyncJavaApi(wrapperType = JavaWrapperType.STREAM)
    public fun numbers(count: Int): Flow<Int> = flow {
        for (i in 1..count) emit(i)
    }

    @AsyncJavaApi(wrapperType = JavaWrapperType.STREAM)
    public fun delayedWords(): Flow<String> = flow {
        delay(10)
        emit("delayed")
    }
}
