package me.kpavlov.javable.it

import kotlinx.coroutines.delay
import me.kpavlov.javable.annotations.BlockingJavaApi
import me.kpavlov.javable.annotations.JavaApi

/**
 * A class with only [@BlockingJavaApi] methods and no [@AsyncJavaApi] methods.
 * The generated wrappers must have no coroutine scope and must not implement AutoCloseable.
 */
@JavaApi(javaWrapper = true, kotlinWrapper = true)
public class BlockingOnlySubject {

    @BlockingJavaApi
    public suspend fun doubled(value: Int): Int {
        delay(5L)
        return value * 2
    }
}
