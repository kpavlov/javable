package me.kpavlov.javable.it

import kotlinx.coroutines.delay
import me.kpavlov.javable.annotations.AsyncJavaApi
import me.kpavlov.javable.annotations.JavaApi

@JavaApi(autoCloseable = true)
public class UserRepository(
    public val generator: (Int) -> User,
) {

    @AsyncJavaApi
    suspend fun fetchAll(): List<User> {
        delay(100)
        return (1..100).map { generator.invoke(it) }
    }
}

public data class User(val name: String)

