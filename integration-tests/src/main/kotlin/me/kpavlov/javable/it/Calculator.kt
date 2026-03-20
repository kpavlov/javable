package me.kpavlov.javable.it

import kotlinx.coroutines.delay
import me.kpavlov.javable.annotations.AsyncJavaApi
import me.kpavlov.javable.annotations.BlockingJavaApi
import me.kpavlov.javable.annotations.JavaApi

@JavaApi(javaWrapper = true, autoCloseable = true)
public class Calculator {

    @AsyncJavaApi
    suspend fun add(a: Int, b: Int): Int {
        delay(10L)
        return a + b
    }

    @BlockingJavaApi
    suspend fun multiply(a: Int, b: Int): Int {
        delay(10L)
        return a * b
    }
}
