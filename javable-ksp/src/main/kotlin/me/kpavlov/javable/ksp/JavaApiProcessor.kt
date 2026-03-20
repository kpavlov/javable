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

            processClassDeclaration(classDeclaration, unprocessable)
        }

        return unprocessable
    }

    @OptIn(KspExperimental::class)
    @Suppress("TooGenericExceptionCaught")
    private fun processClassDeclaration(
        classDeclaration: KSClassDeclaration,
        unprocessable: MutableList<KSAnnotated>,
    ) {
        try {
            val qualifiedName = classDeclaration.qualifiedName?.asString()
            logger.warn("🪛 Processing $qualifiedName...")

            val javaApiAnnotation = classDeclaration.getAnnotationsByType(JavaApi::class).single()

            val simpleName = classDeclaration.simpleName.asString()
            val packageName = classDeclaration.packageName.asString()

            if (javaApiAnnotation.javaWrapper) {
                generateJavaFile(
                    JavaClassGenerator,
                    simpleName,
                    classDeclaration,
                    packageName,
                    autoCloseable = javaApiAnnotation.autoCloseable,
                )
            }

            if (javaApiAnnotation.kotlinWrapper) {
                val generator = KotlinClassGenerator
                generateKotlinFile(generator, simpleName, classDeclaration, packageName)
            }
        } catch (e: Exception) {
            unprocessable.add(classDeclaration)
            logger.error(
                "Failed to generate schema extension " +
                        "for ${classDeclaration.qualifiedName?.asString()}: ${e.message}",
            )
        }
    }

    private fun generateKotlinFile(
        generator: KotlinGenerator,
        simpleName: String,
        classDeclaration: KSClassDeclaration,
        packageName: String
    ) {
        val kotlinClassName = "${simpleName}Kotlin"
        val kotlinFile = generator.generateWrapper(
            kotlinClassDeclaration = classDeclaration,
            packageName = packageName,
            className = kotlinClassName,
        ).build()

        codeGenerator.createNewFile(
            Dependencies(aggregating = false, classDeclaration.containingFile!!),
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
        val javaClassName = "${simpleName}Java"
        val javaFile = generator.generateJavaClass(
            kotlinClassDeclaration = classDeclaration,
            packageName = packageName,
            className = javaClassName,
            autoCloseable = autoCloseable,
        ).build()

        codeGenerator.createNewFile(
            Dependencies(aggregating = false, classDeclaration.containingFile!!),
            packageName = packageName,
            fileName = javaClassName,
            extensionName = "java",
        ).bufferedWriter().use { javaFile.writeTo(it) }
    }
}
