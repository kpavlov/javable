package me.kpavlov.javable.ksp

import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
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

    override fun generateWrapper(
        packageName: String,
        className: String,
        kotlinClassDeclaration: KSClassDeclaration,
    ): FileSpec.Builder {
        val delegateClassName = ClassName(packageName, kotlinClassDeclaration.simpleName.asString())

        val functions = kotlinClassDeclaration
            .getAllFunctions()
            .filterNot { it.isAbstract }
            .filterNot { it.isConstructor() }
            .filterNot { it.simpleName.asString() in listOf("equals", "hashCode", "toString") }
            .filter { it.isPublic() }
            .toList()

        val hasAsyncMethods = functions.any { fn ->
            fn.annotations.any { it.shortName.asString() == "AsyncJavaApi" }
        }
        val hasBlockingMethods = functions.any { fn ->
            fn.annotations.any { it.shortName.asString() == "BlockingJavaApi" }
        }

        val constructor = if (hasAsyncMethods) {
            FunSpec.constructorBuilder()
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
            FunSpec.constructorBuilder()
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

        if (hasAsyncMethods) {
            classBuilder
                .addSuperinterface(ClassName("java.lang", "AutoCloseable"))
                .addProperty(
                    PropertySpec.builder("scope", COROUTINE_SCOPE, KModifier.PRIVATE)
                        .initializer("scope")
                        .build(),
                )
        }

        for (function in functions) {
            val asyncAnno = function.annotations.find { it.shortName.asString() == "AsyncJavaApi" }
            val blockingAnno = function.annotations.find { it.shortName.asString() == "BlockingJavaApi" }
            when {
                asyncAnno != null -> {
                    val wt = resolveWrapperType(asyncAnno)
                    classBuilder.addFunction(generateKotlinAsyncFun(function, wt, withExecutor = false))
                    classBuilder.addFunction(generateKotlinAsyncFun(function, wt, withExecutor = true))
                }
                blockingAnno != null -> classBuilder.addFunction(generateKotlinBlockingFun(function))
                !function.modifiers.contains(Modifier.SUSPEND) -> classBuilder.addFunction(generateKotlinRegularFun(function))
                // suspend without annotation → skip
            }
        }

        if (hasAsyncMethods) {
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
                .addImport("kotlinx.coroutines", "cancel")
                .addImport("kotlinx.coroutines", "asCoroutineDispatcher")
                .addImport("kotlinx.coroutines", "runBlocking")
        }
        if (hasBlockingMethods && !hasAsyncMethods) {
            fileBuilder.addImport("kotlinx.coroutines", "runBlocking")
        }

        return fileBuilder
            .addType(classBuilder.build())
    }

    private fun resolveWrapperType(anno: KSAnnotation): String {
        val value = anno.arguments.find { it.name?.asString() == "wrapperType" }?.value
        return when (value) {
            is KSType -> value.declaration.simpleName.asString()
            is String -> value
            else -> "COMPLETABLE_FUTURE"
        }
    }

    private fun generateKotlinBlockingFun(function: KSFunctionDeclaration): FunSpec {
        val methodName = function.simpleName.asString()
        val returnType = function.returnType!!.toTypeName()
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
        val returnType = function.returnType!!.toTypeName()
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
        val returnType = function.returnType!!.toTypeName()
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
