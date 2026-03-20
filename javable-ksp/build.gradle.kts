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
        implementation("com.palantir.javapoet:javapoet:0.12.0")
        implementation("com.squareup:kotlinpoet-ksp:2.2.0")
        testImplementation(kotlin("test-junit5"))
        testImplementation(platform("org.junit:junit-bom:5.10.0"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")


    }
}

tasks.test {
    useJUnitPlatform()
}