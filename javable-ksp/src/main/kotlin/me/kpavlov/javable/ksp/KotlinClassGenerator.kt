package me.kpavlov.javable.ksp

import com.google.devtools.ksp.isInternal
import com.google.devtools.ksp.isProtected
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toTypeName

internal object KotlinClassGenerator : KotlinGenerator {

    private val COMPLETABLE_FUTURE = ClassName("java.util.concurrent", "CompletableFuture")
    private val COMPLETION_STAGE = ClassName("java.util.concurrent", "CompletionStage")
    private val EXECUTOR = ClassName("java.util.concurrent", "Executor")
    private val COROUTINE_SCOPE = ClassName("kotlinx.coroutines", "CoroutineScope")
    private val DISPATCHERS = ClassName("kotlinx.coroutines", "Dispatchers")
    private val SUPERVISOR_JOB = MemberName("kotlinx.coroutines", "SupervisorJob")
    private val STREAM = ClassName("java.util.stream", "Stream")
    private val FLOW_TO_LIST = MemberName("kotlinx.coroutines.flow", "toList")
    private val PUBLISHER = ClassName("org.reactivestreams", "Publisher")
    private val AS_PUBLISHER = MemberName("kotlinx.coroutines.reactive", "asPublisher")
    private val MONO = MemberName("kotlinx.coroutines.reactor", "mono")
    private val FLOW_BUILDER = MemberName("kotlinx.coroutines.flow", "flow")
    private val EMIT_ALL = MemberName("kotlinx.coroutines.flow", "emitAll")

    override fun generateWrapper(
        packageName: String,
        className: String,
        kotlinClassDeclaration: KSClassDeclaration,
    ): FileSpec.Builder {
        val delegateClassName = ClassName(packageName, kotlinClassDeclaration.simpleName.asString())

        val functions = kotlinClassDeclaration.getWrapperFunctions()

        val hasAsyncMethods = functions.hasFutureAnnotation()
        val hasBlockingMethods = functions.hasAnnotation(BLOCKING_JAVA_API)
        val hasStreamMethods = functions.hasStreamAnnotation()
        val hasPublisherMethods = functions.hasPublisherAnnotation()

        val needsScope = hasAsyncMethods

        val constructor = if (needsScope) {
            val constructorBuilder = FunSpec.constructorBuilder()
            if (kotlinClassDeclaration.isPublic()) {
                constructorBuilder.addModifiers(KModifier.PUBLIC)
            } else if (kotlinClassDeclaration.isInternal()) {
                constructorBuilder.addModifiers(KModifier.INTERNAL)
            } else if (kotlinClassDeclaration.isProtected()) {
                constructorBuilder.addModifiers(KModifier.PROTECTED)
            }

            constructorBuilder
                .addAnnotation(AnnotationSpec.builder(JvmOverloads::class).build())
                .addParameter(ParameterSpec.builder("delegate", delegateClassName).build())
                .addParameter(
                    ParameterSpec.builder("scope", COROUTINE_SCOPE)
                        .defaultValue(
                            "%T(%M() + %T.Default)",
                            COROUTINE_SCOPE,
                            SUPERVISOR_JOB,
                            DISPATCHERS,
                        )
                        .build(),
                )
                .build()
        } else {
            val constructorBuilder = FunSpec.constructorBuilder()
            if (kotlinClassDeclaration.isPublic()) {
                constructorBuilder.addModifiers(KModifier.PUBLIC)
            } else if (kotlinClassDeclaration.isInternal()) {
                constructorBuilder.addModifiers(KModifier.INTERNAL)
            } else if (kotlinClassDeclaration.isProtected()) {
                constructorBuilder.addModifiers(KModifier.PROTECTED)
            }
            constructorBuilder
                .addParameter(ParameterSpec.builder("delegate", delegateClassName).build())
                .build()
        }

        val classBuilder = TypeSpec.classBuilder(className)
            .addModifiers(KModifier.PUBLIC)
            .primaryConstructor(constructor)
            .addProperty(
                PropertySpec.builder("delegate", delegateClassName, KModifier.PRIVATE)
                    .initializer("delegate")
                    .build(),
            )

        if (needsScope) {
            classBuilder
                .addSuperinterface(ClassName("java.lang", "AutoCloseable"))
                .addProperty(
                    PropertySpec.builder("scope", COROUTINE_SCOPE, KModifier.PRIVATE)
                        .initializer("scope")
                        .build(),
                )
        }

        for (function in functions) {
            val asyncAnno = function.findAnnotationByName(ASYNC_JAVA_API)
            val blockingAnno = function.findAnnotationByName(BLOCKING_JAVA_API)
            when {
                asyncAnno != null -> {
                    val wt = function.resolveAsyncWrapperType()
                    when (wt) {
                        "STREAM" -> classBuilder.addFunction(generateKotlinStreamFun(function))
                        "PUBLISHER" -> classBuilder.addFunction(generateKotlinPublisherFun(function))
                        else -> {
                            classBuilder.addFunction(generateKotlinAsyncFun(function, wt, withExecutor = false))
                            classBuilder.addFunction(generateKotlinAsyncFun(function, wt, withExecutor = true))
                        }
                    }
                }

                blockingAnno != null -> classBuilder.addFunction(generateKotlinBlockingFun(function))
                !function.modifiers.contains(Modifier.SUSPEND) -> classBuilder.addFunction(
                    generateKotlinRegularFun(
                        function
                    )
                )
                // suspend without annotation → skip
            }
        }

        if (needsScope) {
            val job = ClassName("kotlinx.coroutines", "Job")
            classBuilder.addFunction(
                FunSpec.builder("close")
                    .addModifiers(KModifier.OVERRIDE)
                    .addStatement("val job = scope.coroutineContext[%T]", job)
                    .addStatement("scope.cancel()")
                    .addStatement("runBlocking { job?.join() }")
                    .build(),
            )
        }

        val fileBuilder = FileSpec.builder(packageName, className)

        if (hasAsyncMethods) {
            fileBuilder
                .addImport("kotlinx.coroutines.future", "future")
                .addImport("kotlinx.coroutines", "asCoroutineDispatcher")
        }
        if (needsScope) {
            fileBuilder
                .addImport("kotlinx.coroutines", "cancel")
                .addImport("kotlinx.coroutines", "runBlocking")
        }
        if ((hasBlockingMethods || hasStreamMethods) && !needsScope) {
            fileBuilder.addImport("kotlinx.coroutines", "runBlocking")
        }
        if (hasStreamMethods) {
            fileBuilder.addImport("kotlinx.coroutines.flow", "toList")
        }
        if (hasPublisherMethods) {
            fileBuilder.addImport("kotlinx.coroutines.reactive", "asPublisher")
            fileBuilder.addImport("kotlinx.coroutines.reactor", "mono")
        }

        return fileBuilder
            .addType(classBuilder.build())
    }

    /**
     * Generates a blocking `Stream<T>` function for a function annotated with
     * `@AsyncJavaApi(wrapperType = STREAM)` that returns `Flow<T>`.
     *
     * Works for both `suspend` and non-`suspend` functions: inside `runBlocking`,
     * the delegate call is valid regardless of whether it is suspend.
     */
    private fun generateKotlinStreamFun(function: KSFunctionDeclaration): FunSpec {
        val methodName = function.simpleName.asString()

        val flowType = function.returnType?.resolve()
        val elementTypeRef = flowType?.arguments?.firstOrNull()?.type
        val elementTypeName = elementTypeRef?.toTypeName()
            ?: error("Cannot resolve Flow element type for function $methodName")

        val returnType = STREAM.parameterizedBy(elementTypeName)

        val paramNames = mutableListOf<String>()
        val funBuilder = FunSpec.builder(methodName)
            .addModifiers(KModifier.PUBLIC)
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin.jvm", "Throws"))
                    .addMember("%T::class", InterruptedException::class)
                    .build(),
            )
            .returns(returnType)

        for (param in function.parameters) {
            val name = param.name?.getShortName() ?: "arg"
            funBuilder.addParameter(name, param.type.toTypeName())
            paramNames.add(name)
        }

        val callArgs = paramNames.joinToString(", ")
        funBuilder.addStatement(
            "return runBlocking { delegate.%N(%L).%M() }.stream()",
            methodName, callArgs, FLOW_TO_LIST,
        )

        return funBuilder.build()
    }

    /**
     * Generates a `Publisher<T>` function for `@AsyncJavaApi(wrapperType = PUBLISHER)`.
     *
     * - Flow return (non-suspend): `delegate.method().asPublisher()`
     * - Flow return (suspend): `flow { emitAll(delegate.method()) }.asPublisher()`
     * - Single-value suspend: `mono { delegate.method() }` (Mono implements Publisher)
     */
    private fun generateKotlinPublisherFun(function: KSFunctionDeclaration): FunSpec {
        val methodName = function.simpleName.asString()
        val isSuspend = function.modifiers.contains(Modifier.SUSPEND)
        val returnQualified = function.returnType?.resolve()?.declaration?.qualifiedName?.asString()
        val isFlowReturn = returnQualified == "kotlinx.coroutines.flow.Flow"

        val paramNames = mutableListOf<String>()
        val funBuilder = FunSpec.builder(methodName)
            .addModifiers(KModifier.PUBLIC)

        for (param in function.parameters) {
            val name = param.name?.getShortName() ?: "arg"
            funBuilder.addParameter(name, param.type.toTypeName())
            paramNames.add(name)
        }

        val callArgs = paramNames.joinToString(", ")

        if (isFlowReturn) {
            val flowType = function.returnType?.resolve()
            val elementTypeRef = flowType?.arguments?.firstOrNull()?.type
            val elementTypeName = elementTypeRef?.toTypeName()
                ?: error("Cannot resolve Flow element type for function $methodName")
            val returnType = PUBLISHER.parameterizedBy(elementTypeName)
            funBuilder.returns(returnType)

            if (isSuspend) {
                funBuilder.addStatement(
                    "return %M { %M(delegate.%N(%L)) }.%M()",
                    FLOW_BUILDER, EMIT_ALL, methodName, callArgs, AS_PUBLISHER,
                )
            } else {
                funBuilder.addStatement(
                    "return delegate.%N(%L).%M()",
                    methodName, callArgs, AS_PUBLISHER,
                )
            }
        } else {
            // Single-value suspend — use mono { delegate.method() } (Mono implements Publisher)
            val rawReturnType = function.returnType?.toTypeName()
                ?: error("Function $methodName has no return type")
            val returnType = PUBLISHER.parameterizedBy(rawReturnType)
            funBuilder.returns(returnType)
            funBuilder.addStatement(
                "return %M { delegate.%N(%L) }",
                MONO, methodName, callArgs,
            )
        }

        return funBuilder.build()
    }

    private fun generateKotlinBlockingFun(function: KSFunctionDeclaration): FunSpec {
        val methodName = function.simpleName.asString()
        val returnType = function.returnType?.toTypeName()
            ?: error("Function $methodName has no return type")
        val isUnit = function.returnType?.resolve()?.declaration?.qualifiedName?.asString() == "kotlin.Unit"

        val paramNames = mutableListOf<String>()
        val funBuilder = FunSpec.builder(methodName)
            .addModifiers(KModifier.PUBLIC)
            .returns(returnType)

        for (param in function.parameters) {
            val name = param.name?.getShortName() ?: "arg"
            funBuilder.addParameter(name, param.type.toTypeName())
            paramNames.add(name)
        }

        val callArgs = paramNames.joinToString(", ")
        if (isUnit) {
            funBuilder.addStatement("runBlocking { delegate.%N(%L) }", methodName, callArgs)
        } else {
            funBuilder.addStatement("return runBlocking { delegate.%N(%L) }", methodName, callArgs)
        }

        return funBuilder.build()
    }

    private fun generateKotlinRegularFun(function: KSFunctionDeclaration): FunSpec {
        val methodName = function.simpleName.asString()
        val returnType = function.returnType?.toTypeName()
            ?: error("Function $methodName has no return type")
        val isUnit = function.returnType?.resolve()?.declaration?.qualifiedName?.asString() == "kotlin.Unit"

        val paramNames = mutableListOf<String>()
        val funBuilder = FunSpec.builder(methodName)
            .addModifiers(KModifier.PUBLIC)
            .returns(returnType)

        for (param in function.parameters) {
            val name = param.name?.getShortName() ?: "arg"
            funBuilder.addParameter(name, param.type.toTypeName())
            paramNames.add(name)
        }

        val callArgs = paramNames.joinToString(", ")
        if (isUnit) {
            funBuilder.addStatement("delegate.%N(%L)", methodName, callArgs)
        } else {
            funBuilder.addStatement("return delegate.%N(%L)", methodName, callArgs)
        }

        return funBuilder.build()
    }

    private fun generateKotlinAsyncFun(
        function: KSFunctionDeclaration,
        wrapperType: String,
        withExecutor: Boolean,
    ): FunSpec {
        val methodName = function.simpleName.asString()
        val returnType = function.returnType?.toTypeName()
            ?: error("Function $methodName has no return type")
        val futureClass = if (wrapperType == "COMPLETION_STAGE") COMPLETION_STAGE else COMPLETABLE_FUTURE
        val returnFuture = futureClass.parameterizedBy(returnType)

        val paramNames = mutableListOf<String>()
        val funBuilder = FunSpec.builder(methodName)
            .addModifiers(KModifier.PUBLIC)
            .returns(returnFuture)

        for (param in function.parameters) {
            val name = param.name?.getShortName() ?: "arg"
            funBuilder.addParameter(name, param.type.toTypeName())
            paramNames.add(name)
        }

        if (withExecutor) {
            funBuilder.addParameter("executor", EXECUTOR)
        }

        val callArgs = paramNames.joinToString(", ")
        if (withExecutor) {
            funBuilder.addStatement(
                "return scope.future(executor.asCoroutineDispatcher()) { delegate.%N(%L) }",
                methodName,
                callArgs,
            )
        } else {
            funBuilder.addStatement(
                "return scope.future { delegate.%N(%L) }",
                methodName,
                callArgs,
            )
        }

        return funBuilder.build()
    }
}
