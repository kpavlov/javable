package me.kpavlov.javable.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import com.palantir.javapoet.AnnotationSpec
import com.palantir.javapoet.ClassName
import com.palantir.javapoet.FieldSpec
import com.palantir.javapoet.JavaFile
import com.palantir.javapoet.MethodSpec
import com.palantir.javapoet.ParameterizedTypeName
import com.palantir.javapoet.TypeName
import com.palantir.javapoet.TypeSpec
import com.palantir.javapoet.WildcardTypeName
import javax.lang.model.element.Modifier as JavaModifier

internal object JavaClassGenerator : JavaGenerator {

    private val FUTURE_KT = ClassName.get("kotlinx.coroutines.future", "FutureKt")
    private val BUILDERS_KT = ClassName.get("kotlinx.coroutines", "BuildersKt")
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
    private val COMPLETION_STAGE = ClassName.get("java.util.concurrent", "CompletionStage")
    private val EXECUTOR = ClassName.get("java.util.concurrent", "Executor")
    private val AUTO_CLOSEABLE = ClassName.get("java.lang", "AutoCloseable")
    private val GENERATED = ClassName.get("javax.annotation.processing", "Generated")
    private val TIME_UNIT = ClassName.get("java.util.concurrent", "TimeUnit")
    private val EXECUTION_EXCEPTION = ClassName.get("java.util.concurrent", "ExecutionException")
    private val TIMEOUT_EXCEPTION = ClassName.get("java.util.concurrent", "TimeoutException")
    private val THROWABLE = ClassName.get("java.lang", "Throwable")
    private val RUNTIME_EXCEPTION = ClassName.get("java.lang", "RuntimeException")
    private val OBJECT = ClassName.get("java.lang", "Object")
    private val FLOW = ClassName.get("kotlinx.coroutines.flow", "Flow")
    private val FLOW_KT = ClassName.get("kotlinx.coroutines.flow", "FlowKt")
    private val STREAM = ClassName.get("java.util.stream", "Stream")
    private val LIST = ClassName.get("java.util", "List")
    private val ARRAY_LIST = ClassName.get("java.util", "ArrayList")

    override fun generateJavaClass(
        packageName: String,
        className: String,
        kotlinClassDeclaration: KSClassDeclaration,
        autoCloseable: Boolean,
    ): JavaFile.Builder {
        val kotlinClassName =
            ClassName.get(packageName, kotlinClassDeclaration.simpleName.asString())

        val functions = kotlinClassDeclaration.getWrapperFunctions()

        val hasAsyncMethods = functions.hasFutureAnnotation()
        val needsScope = autoCloseable || hasAsyncMethods

        val classBuilder =
            TypeSpec
                .classBuilder(className)
                .addAnnotation(
                    AnnotationSpec
                        .builder(GENERATED)
                        .addMember("value", $$"$S", JavaClassGenerator::class.qualifiedName)
                        .build(),
                ).addModifiers(JavaModifier.PUBLIC, JavaModifier.FINAL)
                .addField(
                    FieldSpec
                        .builder(kotlinClassName, "delegate", JavaModifier.PRIVATE, JavaModifier.FINAL)
                        .build(),
                )

        if (autoCloseable) {
            classBuilder.addSuperinterface(AUTO_CLOSEABLE)
        }

        if (needsScope) {
            classBuilder
                .addField(
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
                )
        } else {
            classBuilder.addMethod(
                MethodSpec
                    .constructorBuilder()
                    .addModifiers(JavaModifier.PUBLIC)
                    .addParameter(kotlinClassName, "delegate")
                    .addStatement("this.delegate = delegate")
                    .build(),
            )
        }

        if (autoCloseable) {

            classBuilder.addMethod(
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
                    .addStatement($$"done.get(5L, $T.SECONDS)", TIME_UNIT)
                    .nextControlFlow(
                        $$"catch ($T e)",
                        ClassName.get("java.lang", "InterruptedException"),
                    ).addStatement(
                        $$"$T.currentThread().interrupt()",
                        ClassName.get("java.lang", "Thread"),
                    ).addStatement(
                        $$"throw new $T(\"Close interrupted\", e)",
                        RUNTIME_EXCEPTION,
                    ).nextControlFlow($$"catch ($T e)", EXECUTION_EXCEPTION)
                    .addStatement($$"$T cause = e.getCause()", THROWABLE)
                    .beginControlFlow($$"if (cause instanceof $T)", RUNTIME_EXCEPTION)
                    .addStatement($$"throw ($T) cause", RUNTIME_EXCEPTION)
                    .endControlFlow()
                    .addStatement($$"throw new $T(cause)", RUNTIME_EXCEPTION)
                    .nextControlFlow($$"catch ($T e)", TIMEOUT_EXCEPTION)
                    .addStatement($$"throw new $T(\"Scope did not close within 5 seconds\", e)", RUNTIME_EXCEPTION)
                    .endControlFlow()
                    .build(),
            )
        }

        for (function in functions) {
            val asyncAnno = function.findAnnotationByName(ASYNC_JAVA_API)
            val blockingAnno = function.findAnnotationByName(BLOCKING_JAVA_API)
            when {
                asyncAnno != null -> {
                    val wt = function.resolveAsyncWrapperType()
                    if (wt == "STREAM") {
                        classBuilder.addMethod(generateStreamMethod(function))
                    } else {
                        classBuilder.addMethod(generateAsyncMethod(function, wt, withExecutor = false))
                        classBuilder.addMethod(generateAsyncMethod(function, wt, withExecutor = true))
                    }
                }

                blockingAnno != null -> classBuilder.addMethod(generateBlockingMethod(function))
                !function.modifiers.contains(Modifier.SUSPEND) -> classBuilder.addMethod(generateRegularMethod(function))
                // suspend without annotation → skip
            }
        }

        return JavaFile.builder(packageName, classBuilder.build())
    }

    /**
     * Generates a blocking `Stream<T>` method for a function annotated with
     * `@AsyncJavaApi(wrapperType = STREAM)` that returns `Flow<T>`.
     *
     * - Non-suspend: the Flow is obtained with a direct call; elements are collected via
     *   `runBlocking { FlowKt.toList(flow, ...) }`.
     * - Suspend: the Flow is first obtained with a `runBlocking` call, then collected with
     *   a second `runBlocking`.
     */
    private fun generateStreamMethod(function: KSFunctionDeclaration): MethodSpec {
        val methodName = function.simpleName.asString()
        val isSuspend = function.modifiers.contains(Modifier.SUSPEND)

        val flowType = function.returnType?.resolve()
        val elementTypeRef = flowType?.arguments?.firstOrNull()?.type
        val elementType = resolveTypeName(elementTypeRef, boxed = true)
        val flowTypeName = ParameterizedTypeName.get(FLOW, elementType)
        val listTypeName = ParameterizedTypeName.get(LIST, elementType)
        val streamTypeName = ParameterizedTypeName.get(STREAM, elementType)

        val builder = MethodSpec
            .methodBuilder(methodName)
            .addModifiers(JavaModifier.PUBLIC)
            .addException(ClassName.get("java.lang", "InterruptedException"))
            .returns(streamTypeName)

        val paramNames = addParameters(builder, function)
        val callArgs = paramNames.joinToString(", ")

        if (isSuspend) {
            val callArgsWithCont = (paramNames + "continuation").joinToString(", ")
            builder
                .addStatement(
                    $$"$T flow = ($T) $T.runBlocking($T.INSTANCE, (s, continuation) -> delegate.$$methodName($$callArgsWithCont))",
                    flowTypeName, flowTypeName, BUILDERS_KT, EMPTY_COROUTINE_CONTEXT,
                )
        } else {
            builder.addStatement($$"$T flow = delegate.$$methodName($$callArgs)", flowTypeName)
        }

        builder.addStatement(
            $$"return (($T) $T.runBlocking($T.INSTANCE, (s, continuation) -> $T.toList(flow, new $T<>(), continuation))).stream()",
            listTypeName, BUILDERS_KT, EMPTY_COROUTINE_CONTEXT, FLOW_KT, ARRAY_LIST,
        )

        return builder.build()
    }

    private fun generateBlockingMethod(function: KSFunctionDeclaration): MethodSpec {
        val methodName = function.simpleName.asString()
        val returnType = resolveTypeName(function.returnType)

        val builder =
            MethodSpec
                .methodBuilder(methodName)
                .addModifiers(JavaModifier.PUBLIC)
                .addException(ClassName.get("java.lang", "InterruptedException"))
                .returns(returnType)

        val paramNames = addParameters(builder, function)
        val callArgs = (paramNames + "continuation").joinToString(", ")

        if (returnType == TypeName.VOID) {
            builder.addStatement(
                $$"$T.runBlocking($T.INSTANCE, (s, continuation) -> delegate.$$methodName($$callArgs))",
                BUILDERS_KT,
                EMPTY_COROUTINE_CONTEXT,
            )
        } else {
            val boxedReturn = resolveTypeName(function.returnType, boxed = true)
            builder.addStatement(
                $$"return ($T) $T.runBlocking($T.INSTANCE, (s, continuation) -> delegate.$$methodName($$callArgs))",
                boxedReturn,
                BUILDERS_KT,
                EMPTY_COROUTINE_CONTEXT,
            )
        }

        return builder.build()
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
     * Generates an async function wrapper returning `CompletableFuture<T>` or `CompletionStage<T>`.
     *
     * - Without executor: runs on the scope's default dispatcher.
     * - With executor: runs on the caller-supplied Executor.
     */
    private fun generateAsyncMethod(
        function: KSFunctionDeclaration,
        wrapperType: String,
        withExecutor: Boolean,
    ): MethodSpec {
        val methodName = function.simpleName.asString()
        val rawReturn = resolveTypeName(function.returnType, boxed = true)
        val futureClass = if (wrapperType == "COMPLETION_STAGE") COMPLETION_STAGE else COMPLETABLE_FUTURE
        val returnType = ParameterizedTypeName.get(futureClass, rawReturn)

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

    /** Maps Kotlin stdlib collection/declared type names to their Java equivalents. */
    private val KOTLIN_TO_JAVA_CLASSES: Map<String, ClassName> = mapOf(
        "kotlin.collections.List" to ClassName.get("java.util", "List"),
        "kotlin.collections.MutableList" to ClassName.get("java.util", "List"),
        "kotlin.collections.Set" to ClassName.get("java.util", "Set"),
        "kotlin.collections.MutableSet" to ClassName.get("java.util", "Set"),
        "kotlin.collections.Map" to ClassName.get("java.util", "Map"),
        "kotlin.collections.MutableMap" to ClassName.get("java.util", "Map"),
        "kotlin.collections.Collection" to ClassName.get("java.util", "Collection"),
        "kotlin.collections.MutableCollection" to ClassName.get("java.util", "Collection"),
        "kotlin.collections.Iterable" to ClassName.get("java.lang", "Iterable"),
        "kotlin.collections.MutableIterable" to ClassName.get("java.lang", "Iterable"),
        "kotlin.collections.Iterator" to ClassName.get("java.util", "Iterator"),
        "kotlin.collections.MutableIterator" to ClassName.get("java.util", "Iterator"),
    )

    /** Maps Kotlin primitive/standard qualified names to a lambda that returns
     *  the appropriate JavaPoet [TypeName] given whether boxing is required. */
    private val KOTLIN_TO_JAVA_PRIMITIVES: Map<String, (Boolean) -> TypeName> = mapOf(
        "kotlin.Unit" to { useBoxed -> if (useBoxed) ClassName.get("java.lang", "Void") else TypeName.VOID },
        "kotlin.Int" to { useBoxed -> if (useBoxed) TypeName.INT.box() else TypeName.INT },
        "kotlin.Long" to { useBoxed -> if (useBoxed) TypeName.LONG.box() else TypeName.LONG },
        "kotlin.Double" to { useBoxed -> if (useBoxed) TypeName.DOUBLE.box() else TypeName.DOUBLE },
        "kotlin.Float" to { useBoxed -> if (useBoxed) TypeName.FLOAT.box() else TypeName.FLOAT },
        "kotlin.Boolean" to { useBoxed -> if (useBoxed) TypeName.BOOLEAN.box() else TypeName.BOOLEAN },
        "kotlin.Char" to { useBoxed -> if (useBoxed) TypeName.CHAR.box() else TypeName.CHAR },
        "kotlin.Short" to { useBoxed -> if (useBoxed) TypeName.SHORT.box() else TypeName.SHORT },
        "kotlin.Byte" to { useBoxed -> if (useBoxed) TypeName.BYTE.box() else TypeName.BYTE },
        "kotlin.String" to { _ -> ClassName.get("java.lang", "String") },
        "kotlin.Any" to { _ -> ClassName.get("java.lang", "Object") },
    )

    fun resolveTypeName(
        typeReference: KSTypeReference?,
        boxed: Boolean = false,
    ): TypeName {
        if (typeReference == null) return TypeName.VOID

        val resolved = typeReference.resolve()
        val qualifiedName = resolved.declaration.qualifiedName?.asString()
            ?: return ClassName.get("java.lang", "Object")
        val useBoxed = boxed || resolved.isMarkedNullable

        // Scalar / primitive types — return immediately
        KOTLIN_TO_JAVA_PRIMITIVES[qualifiedName]?.invoke(useBoxed)?.let { return it }

        // Resolve raw ClassName, mapping Kotlin stdlib types to Java equivalents
        val rawType = KOTLIN_TO_JAVA_CLASSES[qualifiedName]
            ?: ClassName.get(resolved.declaration.packageName.asString(), resolved.declaration.simpleName.asString())

        // Attach type arguments to produce ParameterizedTypeName when present
        val args = resolved.arguments
        if (args.isEmpty()) return rawType

        val typeArgs: Array<TypeName> = args.map { arg ->

            when (arg.variance) {
                Variance.STAR -> WildcardTypeName.subtypeOf(OBJECT)
                Variance.COVARIANT -> arg.type?.let { WildcardTypeName.subtypeOf(resolveTypeName(it, boxed = true)) }
                    ?: WildcardTypeName.subtypeOf(OBJECT)

                Variance.CONTRAVARIANT -> arg.type?.let {
                    WildcardTypeName.supertypeOf(
                        resolveTypeName(
                            it,
                            boxed = true
                        )
                    )
                }
                    ?: OBJECT

                else -> arg.type?.let { resolveTypeName(it, boxed = true) } ?: OBJECT
            }
        }.toTypedArray()

        return ParameterizedTypeName.get(rawType, *typeArgs)
    }
}
