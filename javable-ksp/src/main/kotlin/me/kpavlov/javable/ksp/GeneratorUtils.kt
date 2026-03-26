package me.kpavlov.javable.ksp

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.isProtected
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import me.kpavlov.javable.annotations.AsyncJavaApi

internal const val ASYNC_JAVA_API = "AsyncJavaApi"
internal const val BLOCKING_JAVA_API = "BlockingJavaApi"

/** Returns the public, non-synthetic, non-overridden functions eligible for wrapper generation. */
internal fun KSClassDeclaration.getWrapperFunctions(): List<KSFunctionDeclaration> =
    getAllFunctions()
        .filterNot { it.isAbstract }
        .filterNot { it.isConstructor() }
        .filterNot { it.simpleName.asString() in listOf("equals", "hashCode", "toString") }
        .filterNot { it.isPrivate() }
        .filterNot { it.isProtected() }
        .toList()

/** Finds the first annotation on this function with the given [shortName], or null. */
internal fun KSFunctionDeclaration.findAnnotationByName(shortName: String): KSAnnotation? =
    annotations.find { it.shortName.asString() == shortName }

/** Returns true if any function in this list carries an annotation named [shortName]. */
internal fun List<KSFunctionDeclaration>.hasAnnotation(shortName: String): Boolean =
    any { fn -> fn.annotations.any { it.shortName.asString() == shortName } }

/**
 * Resolves the `wrapperType` of an `@AsyncJavaApi` annotation using the type-safe KSP API.
 * This is more reliable than the low-level [KSAnnotation.arguments] approach in KSP2,
 * which may fail to resolve enum constants and fall through to the default.
 *
 * Returns `"COMPLETABLE_FUTURE"` when no annotation is present.
 */
@OptIn(KspExperimental::class)
internal fun KSFunctionDeclaration.resolveAsyncWrapperType(): String =
    getAnnotationsByType(AsyncJavaApi::class).firstOrNull()?.wrapperType?.name ?: "COMPLETABLE_FUTURE"

/**
 * Returns true if any function has `@AsyncJavaApi` with a Future/Stage wrapper type
 * (`COMPLETABLE_FUTURE` or `COMPLETION_STAGE`), excluding `STREAM` and `PUBLISHER`.
 * Used to decide whether a coroutine scope and `AutoCloseable` are needed.
 */
internal fun List<KSFunctionDeclaration>.hasFutureAnnotation(): Boolean =
    any { fn ->
        fn.findAnnotationByName(ASYNC_JAVA_API) != null &&
            fn.resolveAsyncWrapperType().let { it != "STREAM" && it != "PUBLISHER" }
    }

/** Returns true if any function has `@AsyncJavaApi(wrapperType = STREAM)`. */
internal fun List<KSFunctionDeclaration>.hasStreamAnnotation(): Boolean =
    any { fn ->
        fn.findAnnotationByName(ASYNC_JAVA_API) != null && fn.resolveAsyncWrapperType() == "STREAM"
    }

/** Returns true if any function has `@AsyncJavaApi(wrapperType = PUBLISHER)`. */
internal fun List<KSFunctionDeclaration>.hasPublisherAnnotation(): Boolean =
    any { fn ->
        fn.findAnnotationByName(ASYNC_JAVA_API) != null && fn.resolveAsyncWrapperType() == "PUBLISHER"
    }

/**
 * Returns true if any function has `@AsyncJavaApi(wrapperType = PUBLISHER)` and does NOT
 * return `Flow<T>` — i.e. it is a single-value suspend function that needs a Kotlin
 * helper for the Java wrapper (Java cannot chain two suspend calls in a `publish` lambda).
 */
internal fun List<KSFunctionDeclaration>.hasPublisherSingleValueAnnotation(): Boolean =
    any { fn ->
        fn.findAnnotationByName(ASYNC_JAVA_API) != null &&
            fn.resolveAsyncWrapperType() == "PUBLISHER" &&
            fn.returnType?.resolve()?.declaration?.qualifiedName?.asString() != "kotlinx.coroutines.flow.Flow"
    }

/**
 * Resolves the `wrapperType` argument of an `@AsyncJavaApi` annotation via the low-level KSP API.
 * Kept for reference; prefer [resolveAsyncWrapperType] for reliable enum resolution in KSP2.
 */
internal fun resolveWrapperType(anno: KSAnnotation): String {
    val value = anno.arguments.find { it.name?.asString() == "wrapperType" }?.value
    return when (value) {
        is KSType -> value.declaration.simpleName.asString()
        is String -> value
        else -> "COMPLETABLE_FUTURE"
    }
}
