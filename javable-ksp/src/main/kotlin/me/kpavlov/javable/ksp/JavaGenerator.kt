package me.kpavlov.javable.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.palantir.javapoet.JavaFile

internal interface JavaGenerator {
    fun generateJavaClass(
        packageName: String,
        className: String,
        kotlinClassDeclaration: KSClassDeclaration,
    ): JavaFile.Builder
}