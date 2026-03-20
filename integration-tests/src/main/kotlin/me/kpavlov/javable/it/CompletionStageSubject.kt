package me.kpavlov.javable.it

import kotlinx.coroutines.delay
import me.kpavlov.javable.annotations.AsyncJavaApi
import me.kpavlov.javable.annotations.JavaApi
import me.kpavlov.javable.annotations.JavaWrapperType

@JavaApi(javaWrapper = true, kotlinWrapper = true, autoCloseable = true)
public class CompletionStageSubject {

    @AsyncJavaApi(wrapperType = JavaWrapperType.COMPLETION_STAGE)
    suspend fun compute(input: Int): Int {
        delay(10L)
        return input * 2
    }
}
