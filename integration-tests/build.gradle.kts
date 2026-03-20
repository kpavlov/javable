plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.google.ksp)
}


//group = rootProject.group
//version = rootProject.version

kotlin {
//    explicitApi = ExplicitApiMode.
}

kotlin {
    dependencies {
        api(project(":javable-annotations"))
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.coroutines.jdk9)
        testImplementation(kotlin("test-junit5"))

        add("ksp", project(":javable-ksp"))
    }
}

tasks.test {
    useJUnitPlatform()
}
