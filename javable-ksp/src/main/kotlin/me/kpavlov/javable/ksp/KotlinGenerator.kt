package me.kpavlov.javable.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.FileSpec

interface KotlinGenerator {
    fun generateWrapper(
        packageName: String,
        className: String,
        kotlinClassDeclaration: KSClassDeclaration,
    ): FileSpec

}
