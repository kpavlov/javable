package me.kpavlov.javable.ksp

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import me.kpavlov.javable.annotations.JavaApi

private const val PREFIX = "me.kpavlov.javable"

internal class JavaApiProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {

    internal companion object {
        private const val JAVA_API_ANNOTATION = "me.kpavlov.javable.annotations.JavaApi"

        const val OPTION_ENABLED = "$PREFIX.enabled"
    }

    override fun finish() {
        logger.info("[javable-ksp] ✅ Done!")
    }

    override fun onError() {
        logger.error(
            "[javable-ksp] 💥 Error! KSP Processor Options: ${
                options.entries.joinToString(
                    prefix = "[",
                    separator = ", ",
                    postfix = "]",
                ) { it.toString() }
            }",
        )
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val enabled = options[OPTION_ENABLED]?.trim()?.takeIf { it.isNotEmpty() } != "false"

        logger.info("[javable-ksp] Options: ${options.entries.joinToString()}")

        if (!enabled) {
            logger.info("[javable-ksp] Plugin is disabled")
            return emptyList()
        }

        val unprocessable = mutableListOf<KSAnnotated>()

        val symbols =
            resolver
                .getSymbolsWithAnnotation(JAVA_API_ANNOTATION)

        symbols.filterIsInstance<KSClassDeclaration>().forEach { classDeclaration ->
            if (!classDeclaration.validate()) {
                unprocessable.add(classDeclaration)
                return@forEach
            }

            processClassDeclaration(classDeclaration)
        }

        return unprocessable
    }

    @OptIn(KspExperimental::class)
    @Suppress("TooGenericExceptionCaught")
    private fun processClassDeclaration(classDeclaration: KSClassDeclaration) {
        val qualifiedName = classDeclaration.qualifiedName?.asString()
        logger.info("🪛 Processing $qualifiedName...")

        val javaApiAnnotation = classDeclaration.getAnnotationsByType(JavaApi::class).singleOrNull()
            ?: error("Expected exactly one @JavaApi annotation on $qualifiedName")

        val simpleName = classDeclaration.simpleName.asString()
        val packageName = classDeclaration.packageName.asString()

        if (javaApiAnnotation.javaWrapper) {
            try {
                generateJavaFile(
                    JavaClassGenerator,
                    simpleName,
                    classDeclaration,
                    packageName,
                    autoCloseable = javaApiAnnotation.autoCloseable,
                )

            } catch (e: Exception) {
                logger.error("Failed to generate Java wrapper for $qualifiedName: ${e.message}")
            }
        }

        if (javaApiAnnotation.kotlinWrapper) {
            try {
                generateKotlinFile(KotlinClassGenerator, simpleName, classDeclaration, packageName)
            } catch (e: Exception) {
                logger.error("Failed to generate Kotlin wrapper for $qualifiedName: ${e.message}")
            }
        }
    }

    private fun generateKotlinFile(
        generator: KotlinGenerator,
        simpleName: String,
        classDeclaration: KSClassDeclaration,
        packageName: String
    ) {
        val containingFile = classDeclaration.containingFile
            ?: error("Cannot determine containing file for ${classDeclaration.qualifiedName?.asString()}")
        val kotlinClassName = "${simpleName}Kotlin"
        val kotlinFile = generator.generateWrapper(
            kotlinClassDeclaration = classDeclaration,
            packageName = packageName,
            className = kotlinClassName,
        ).build()

        codeGenerator.createNewFile(
            Dependencies(aggregating = false, containingFile),
            packageName = packageName,
            fileName = kotlinClassName,
            extensionName = "kt",
        ).bufferedWriter().use { kotlinFile.writeTo(it) }
    }

    private fun generateJavaFile(
        generator: JavaGenerator,
        simpleName: String,
        classDeclaration: KSClassDeclaration,
        packageName: String,
        autoCloseable: Boolean = false,
    ) {
        val containingFile = classDeclaration.containingFile
            ?: error("Cannot determine containing file for ${classDeclaration.qualifiedName?.asString()}")
        val javaClassName = "${simpleName}Java"
        val javaFile = generator.generateJavaClass(
            kotlinClassDeclaration = classDeclaration,
            packageName = packageName,
            className = javaClassName,
            autoCloseable = autoCloseable,
        ).build()

        codeGenerator.createNewFile(
            Dependencies(aggregating = false, containingFile),
            packageName = packageName,
            fileName = javaClassName,
            extensionName = "java",
        ).bufferedWriter().use { javaFile.writeTo(it) }
    }
}
