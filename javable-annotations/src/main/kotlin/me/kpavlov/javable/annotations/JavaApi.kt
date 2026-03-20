package me.kpavlov.javable.annotations

public enum class JavaWrapperType {
    COMPLETABLE_FUTURE,
    COMPLETION_STAGE,
}

@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
public annotation class JavaApi(
    val kotlinWrapper: Boolean = true,
    val javaWrapper: Boolean = false,
    val autoCloseable: Boolean = false,
)

@MustBeDocumented
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
public annotation class AsyncJavaApi(val wrapperType: JavaWrapperType = JavaWrapperType.COMPLETABLE_FUTURE)

@MustBeDocumented
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
public annotation class BlockingJavaApi


