package me.kpavlov.javable.ksp

import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.symbol.KSClassDeclaration
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

object KotlinClassGenerator {

    private val COMPLETABLE_FUTURE = ClassName("java.util.concurrent", "CompletableFuture")
    private val EXECUTOR = ClassName("java.util.concurrent", "Executor")
    private val COROUTINE_SCOPE = ClassName("kotlinx.coroutines", "CoroutineScope")
    private val DISPATCHERS = ClassName("kotlinx.coroutines", "Dispatchers")
    private val SUPERVISOR_JOB = MemberName("kotlinx.coroutines", "SupervisorJob")

    fun generateWrapper(
        packageName: String,
        className: String,
        kotlinClassDeclaration: KSClassDeclaration,
    ): FileSpec {
        val delegateClassName = ClassName(packageName, kotlinClassDeclaration.simpleName.asString())

        val constructor = FunSpec.constructorBuilder()
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

        val classBuilder = TypeSpec.classBuilder(className)
            .addModifiers(KModifier.PUBLIC)
            .addSuperinterface(ClassName("java.lang", "AutoCloseable"))
            .primaryConstructor(constructor)
            .addProperty(
                PropertySpec.builder("delegate", delegateClassName, KModifier.PRIVATE)
                    .initializer("delegate")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("scope", COROUTINE_SCOPE, KModifier.PRIVATE)
                    .initializer("scope")
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
                val methodName = function.simpleName.asString()
                val returnType = function.returnType!!.toTypeName()
                val returnFuture = COMPLETABLE_FUTURE.parameterizedBy(returnType)

                val paramNames = mutableListOf<String>()
                val withoutExecutor = FunSpec.builder(methodName)
                    .addModifiers(KModifier.PUBLIC)
                    .returns(returnFuture)

                for (param in function.parameters) {
                    val name = param.name?.getShortName() ?: "arg"
                    withoutExecutor.addParameter(name, param.type.toTypeName())
                    paramNames.add(name)
                }

                val callArgs = paramNames.joinToString(", ")
                withoutExecutor.addStatement(
                    "return scope.future { delegate.%N(%L) }",
                    methodName,
                    callArgs,
                )
                classBuilder.addFunction(withoutExecutor.build())

                val withExecutor = FunSpec.builder(methodName)
                    .addModifiers(KModifier.PUBLIC)
                    .returns(returnFuture)

                for (param in function.parameters) {
                    val name = param.name?.getShortName() ?: "arg"
                    withExecutor.addParameter(name, param.type.toTypeName())
                }
                withExecutor.addParameter("executor", EXECUTOR)
                withExecutor.addStatement(
                    "return scope.future(executor.asCoroutineDispatcher()) { delegate.%N(%L) }",
                    methodName,
                    callArgs,
                )
                classBuilder.addFunction(withExecutor.build())
            }
        }

        classBuilder.addFunction(
            FunSpec.builder("close")
                .addModifiers(KModifier.OVERRIDE)
                .addStatement("scope.cancel()")
                .build(),
        )

        return FileSpec.builder(packageName, className)
            .addImport("kotlinx.coroutines.future", "future")
            .addImport("kotlinx.coroutines", "cancel")
            .addImport("kotlinx.coroutines", "asCoroutineDispatcher")
            .addType(classBuilder.build())
            .build()
    }
}
