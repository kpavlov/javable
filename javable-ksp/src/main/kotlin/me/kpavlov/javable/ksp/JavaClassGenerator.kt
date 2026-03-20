package me.kpavlov.javable.ksp

import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Modifier
import com.palantir.javapoet.*
import com.squareup.kotlinpoet.asClassName
import javax.lang.model.element.Modifier as JavaModifier

object JavaClassGenerator {

    private val FUTURE_KT = ClassName.get("kotlinx.coroutines.future", "FutureKt")
    private val SUPERVISION_KT = ClassName.get("kotlinx.coroutines", "SupervisorKt")
    private val COROUTINE_SCOPE_KT = ClassName.get("kotlinx.coroutines", "CoroutineScopeKt")
    private val COROUTINE_SCOPE = ClassName.get("kotlinx.coroutines", "CoroutineScope")
    private val DISPATCHERS = ClassName.get("kotlinx.coroutines", "Dispatchers")
    private val JOB = ClassName.get("kotlinx.coroutines", "Job")
    private val KOTLIN_UNIT = ClassName.get("kotlin", "Unit")
    private val EMPTY_COROUTINE_CONTEXT = ClassName.get("kotlin.coroutines", "EmptyCoroutineContext")
    private val COROUTINE_START = ClassName.get("kotlinx.coroutines", "CoroutineStart")
    private val EXECUTORS_KT = ClassName.get("kotlinx.coroutines", "ExecutorsKt")
    private val COMPLETABLE_FUTURE = ClassName.get("java.util.concurrent", "CompletableFuture")
    private val EXECUTOR = ClassName.get("java.util.concurrent", "Executor")
    private val AUTO_CLOSEABLE = ClassName.get("java.lang", "AutoCloseable")
    private val GENERATED = ClassName.get("javax.annotation.processing", "Generated")

    fun generateJavaClass(
        packageName: String,
        className: String,
        kotlinClassDeclaration: KSClassDeclaration,
    ): JavaFile.Builder {
        val kotlinClassName =
            ClassName.get(packageName, kotlinClassDeclaration.simpleName.asString())

        val classBuilder =
            TypeSpec
                .classBuilder(className)
                .addAnnotation(
                    AnnotationSpec
                        .builder(GENERATED)
                        .addMember("value", $$"$S", JavaClassGenerator::class.qualifiedName)
                        .build(),
                ).addModifiers(JavaModifier.PUBLIC, JavaModifier.FINAL)
                .addSuperinterface(AUTO_CLOSEABLE)
                .addField(
                    FieldSpec
                        .builder(kotlinClassName, "delegate", JavaModifier.PRIVATE, JavaModifier.FINAL)
                        .build(),
                ).addField(
                    FieldSpec
                        .builder(JOB, "scopeJob", JavaModifier.PRIVATE, JavaModifier.FINAL)
                        .build(),
                ).addField(
                    FieldSpec
                        .builder(COROUTINE_SCOPE, "scope", JavaModifier.PRIVATE, JavaModifier.FINAL)
                        .build(),
                ).addMethod(
                    MethodSpec
                        .constructorBuilder()
                        .addModifiers(JavaModifier.PUBLIC)
                        .addParameter(kotlinClassName, "delegate")
                        .addStatement("this.delegate = delegate")
                        .addStatement(
                            $$"this.scopeJob = $T.SupervisorJob(null)",
                            SUPERVISION_KT,
                        ).addStatement(
                            $$"this.scope = $T.CoroutineScope($T.getDefault().plus(this.scopeJob))",
                            COROUTINE_SCOPE_KT,
                            DISPATCHERS,
                        ).build(),
                ).addMethod(
                    MethodSpec
                        .constructorBuilder()
                        .addModifiers(JavaModifier.PUBLIC)
                        .addParameter(kotlinClassName, "delegate")
                        .addParameter(EXECUTOR, "executor")
                        .addStatement("this.delegate = delegate")
                        .addStatement(
                            $$"this.scopeJob = $T.SupervisorJob(null)",
                            SUPERVISION_KT,
                        ).addStatement(
                            $$"this.scope = $T.CoroutineScope($T.from(executor).plus(this.scopeJob))",
                            COROUTINE_SCOPE_KT,
                            EXECUTORS_KT,
                        ).build(),
                ).addMethod(
                    MethodSpec
                        .methodBuilder("close")
                        .addAnnotation(Override::class.java)
                        .addModifiers(JavaModifier.PUBLIC)
                        .addStatement(
                            $$"$T<Void> done = new $T<>()",
                            COMPLETABLE_FUTURE,
                            COMPLETABLE_FUTURE,
                        ).addStatement(
                            $$"this.scopeJob.invokeOnCompletion(cause -> { done.complete(null); return $T.INSTANCE; })",
                            KOTLIN_UNIT,
                        ).addStatement(
                            $$"$T.cancel(this.scope, \"closed\", null)",
                            COROUTINE_SCOPE_KT,
                        ).beginControlFlow("try")
                        .addStatement("done.get()")
                        .nextControlFlow(
                            $$"catch ($T e)",
                            ClassName.get("java.lang", "InterruptedException"),
                        ).addStatement(
                            $$"$T.currentThread().interrupt()",
                            ClassName.get("java.lang", "Thread"),
                        ).nextControlFlow(
                            $$"catch ($T ignored)",
                            ClassName.get("java.lang", "Exception"),
                        ).endControlFlow()
                        .build(),
                )

        for (function in kotlinClassDeclaration
            .getAllFunctions()
            .filterNot { it.isAbstract }
            .filterNot { it.isConstructor() }
            .filterNot { it.simpleName.asString() in listOf("equals", "hashCode", "toString") }
            .filter { it.isPublic() }
        ) {
            if (Modifier.SUSPEND in function.modifiers) {
                classBuilder.addMethod(generateSuspendMethod(function, withExecutor = false))
                classBuilder.addMethod(generateSuspendMethod(function, withExecutor = true))
            } else {
                classBuilder.addMethod(generateRegularMethod(function))
            }
        }

        return JavaFile.builder(packageName, classBuilder.build())
    }

    private fun generateRegularMethod(function: KSFunctionDeclaration): MethodSpec {
        val methodName = function.simpleName.asString()
        val returnType = resolveTypeName(function.returnType)

        val builder =
            MethodSpec
                .methodBuilder(methodName)
                .addModifiers(JavaModifier.PUBLIC)
                .returns(returnType)

        val paramNames = addParameters(builder, function)
        val callArgs = paramNames.joinToString(", ")

        if (returnType == TypeName.VOID) {
            builder.addStatement($$"delegate.$$methodName($$callArgs)")
        } else {
            builder.addStatement($$"return delegate.$$methodName($$callArgs)")
        }

        return builder.build()
    }

    /**
     * Generates a suspend function wrapper that returns `CompletableFuture<T>`.
     *
     * - Without executor: runs on the scope's default dispatcher.
     * - With executor: runs on the caller-supplied Executor — non-blocking,
     *   works with virtual threads and any thread pool.
     */
    private fun generateSuspendMethod(
        function: KSFunctionDeclaration,
        withExecutor: Boolean,
    ): MethodSpec {
        val methodName = function.simpleName.asString()
        val rawReturn = resolveTypeName(function.returnType, boxed = true)
        val returnType = ParameterizedTypeName.get(COMPLETABLE_FUTURE, rawReturn)

        val builder =
            MethodSpec
                .methodBuilder(methodName)
                .addModifiers(JavaModifier.PUBLIC)
                .returns(returnType)

        val paramNames = addParameters(builder, function)
        val callArgs = (paramNames + "continuation").joinToString(", ")

        if (withExecutor) {
            builder.addParameter(EXECUTOR, "executor")
            builder.addStatement(
                $$"return $T.future(scope, $T.from(executor), $T.DEFAULT, (s, continuation) -> delegate.$$methodName($$callArgs))",
                FUTURE_KT,
                EXECUTORS_KT,
                COROUTINE_START,
            )
        } else {
            builder.addStatement(
                $$"return $T.future(scope, $T.INSTANCE, $T.DEFAULT, (s, continuation) -> delegate.$$methodName($$callArgs))",
                FUTURE_KT,
                EMPTY_COROUTINE_CONTEXT,
                COROUTINE_START,
            )
        }

        return builder.build()
    }

    private fun addParameters(
        builder: MethodSpec.Builder,
        function: KSFunctionDeclaration,
    ): List<String> {
        val names = mutableListOf<String>()
        var counter = 0
        for (param in function.parameters) {
            val typeName = resolveTypeName(param.type)
            val name = param.name?.getShortName() ?: "arg${counter++}"
            builder.addParameter(typeName, name)
            names.add(name)
        }
        return names
    }

    fun resolveTypeName(
        typeReference: KSTypeReference?,
        boxed: Boolean = false,
    ): TypeName {
        if (typeReference == null) return TypeName.VOID

        val resolved = typeReference.resolve()
        val qualifiedName = resolved.declaration.qualifiedName?.asString()
            ?: return ClassName.get("java.lang", "Object")
        val useBoxed = boxed || resolved.isMarkedNullable

        return when (qualifiedName) {
            "kotlin.Unit" -> if (useBoxed) ClassName.get("java.lang", "Void") else TypeName.VOID
            "kotlin.Int" -> if (useBoxed) TypeName.INT.box() else TypeName.INT
            "kotlin.Long" -> if (useBoxed) TypeName.LONG.box() else TypeName.LONG
            "kotlin.Double" -> if (useBoxed) TypeName.DOUBLE.box() else TypeName.DOUBLE
            "kotlin.Float" -> if (useBoxed) TypeName.FLOAT.box() else TypeName.FLOAT
            "kotlin.Boolean" -> if (useBoxed) TypeName.BOOLEAN.box() else TypeName.BOOLEAN
            "kotlin.Char" -> if (useBoxed) TypeName.CHAR.box() else TypeName.CHAR
            "kotlin.Short" -> if (useBoxed) TypeName.SHORT.box() else TypeName.SHORT
            "kotlin.Byte" -> if (useBoxed) TypeName.BYTE.box() else TypeName.BYTE
            "kotlin.String" -> ClassName.get("java.lang", "String")
            "kotlin.Any" -> ClassName.get("java.lang", "Object")
            else -> {
                val decl = resolved.declaration
                ClassName.get(decl.packageName.asString(), decl.simpleName.asString())
            }
        }
    }
}
