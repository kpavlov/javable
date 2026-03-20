package me.kpavlov.javable.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

private const val PREFIX = "me.kpavlov.javable"

/**
 * KSP processor that generates extension properties for classes and functions,
 * annotated with `@Schema`.
 *
 * For a class annotated with @Schema, this processor generates an extension property:
 * ```kotlin
 * val MyClass.jsonSchemaString: String get() = "..."
 * ```
 */
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
        logger.info("[koog-ksp] ✅ Done!")
    }

    override fun onError() {
        logger.error(
            "[koog-ksp] 💥 Error! KSP Processor Options: ${
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

        logger.info("[koog-ksp] Options: ${options.entries.joinToString()}")

        if (!enabled) {
            logger.info("[koog-ksp] Plugin is disabled")
            return emptyList()
        }

        val unprocessable = mutableListOf<KSAnnotated>()

        val symbols =
            resolver
                .getSymbolsWithAnnotation(JAVA_API_ANNOTATION)

        processClassDeclarations(symbols.filterIsInstance<KSClassDeclaration>(), unprocessable)

        return unprocessable
    }

    private fun processClassDeclarations(
        classDeclarations: Sequence<KSClassDeclaration>,
        unprocessable: MutableList<KSAnnotated>,
    ) {
        classDeclarations.forEach { classDeclaration ->
            if (!classDeclaration.validate()) {
                unprocessable.add(classDeclaration)
                return@forEach
            }

            @Suppress("TooGenericExceptionCaught")
            try {
                val qualifiedName = classDeclaration.qualifiedName?.asString()
                logger.warn("🪛 Processing $qualifiedName...")

                val simpleName = classDeclaration.simpleName.asString()
                val packageName = classDeclaration.packageName.asString()
                val javaClassName = "${simpleName}Java"
                val kotlinClassName = "${simpleName}Kotlin"

                val javaFile = JavaClassGenerator.generateJavaClass(
                    kotlinClassDeclaration = classDeclaration,
                    packageName = packageName,
                    className = javaClassName,
                )

                codeGenerator.createNewFile(
                    Dependencies(aggregating = false, classDeclaration.containingFile!!),
                    packageName = packageName,
                    fileName = javaClassName,
                    extensionName = "java",
                ).bufferedWriter().use { javaFile.build().writeTo(it) }

                val kotlinFile = KotlinClassGenerator.generateWrapper(
                    kotlinClassDeclaration = classDeclaration,
                    packageName = packageName,
                    className = kotlinClassName,
                )

                codeGenerator.createNewFile(
                    Dependencies(aggregating = false, classDeclaration.containingFile!!),
                    packageName = packageName,
                    fileName = kotlinClassName,
                    extensionName = "kt",
                ).bufferedWriter().use { kotlinFile.writeTo(it) }
            } catch (e: Exception) {
                logger.error(
                    "Failed to generate schema extension " +
                            "for ${classDeclaration.qualifiedName?.asString()}: ${e.message}",
                )
            }
        }
    }
}
