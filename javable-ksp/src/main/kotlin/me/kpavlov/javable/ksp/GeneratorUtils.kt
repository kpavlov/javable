package me.kpavlov.javable.ksp

import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType

internal const val ASYNC_JAVA_API = "AsyncJavaApi"
internal const val BLOCKING_JAVA_API = "BlockingJavaApi"

/** Returns the public, non-synthetic, non-overridden functions eligible for wrapper generation. */
internal fun KSClassDeclaration.getWrapperFunctions(): List<KSFunctionDeclaration> =
    getAllFunctions()
        .filterNot { it.isAbstract }
        .filterNot { it.isConstructor() }
        .filterNot { it.simpleName.asString() in listOf("equals", "hashCode", "toString") }
        .filterNot { it.isPrivate() }
        .toList()

/** Finds the first annotation on this function with the given [shortName], or null. */
internal fun KSFunctionDeclaration.findAnnotationByName(shortName: String): KSAnnotation? =
    annotations.find { it.shortName.asString() == shortName }

/** Returns true if any function in this list carries an annotation named [shortName]. */
internal fun List<KSFunctionDeclaration>.hasAnnotation(shortName: String): Boolean =
    any { fn -> fn.annotations.any { it.shortName.asString() == shortName } }

/**
 * Resolves the `wrapperType` argument of an `@AsyncJavaApi` annotation.
 * Returns `"COMPLETABLE_FUTURE"` (the default) when the argument is absent or unresolvable.
 */
internal fun resolveWrapperType(anno: KSAnnotation): String {
    val value = anno.arguments.find { it.name?.asString() == "wrapperType" }?.value
    return when (value) {
        is KSType -> value.declaration.simpleName.asString()
        is String -> value
        else -> "COMPLETABLE_FUTURE"
    }
}
