plugins {
    id("buildsrc.convention.kotlin-jvm")
}

kotlin {
    dependencies {
        // KSP2 API for programmatic invocation
        implementation(libs.ksp.symbol.processing.api)
        implementation(libs.ksp.symbol.processing.aa.embeddable)
        implementation(libs.ksp.symbol.processing.common.deps)

        implementation(libs.ksp.api)
        implementation(project(":javable-annotations"))
        implementation(libs.javapoet)
        implementation(libs.kotlinpoet)
        testImplementation(kotlin("test-junit5"))
        testImplementation(platform(libs.junit.bom))
    }
}

tasks.test {
    useJUnitPlatform()
}