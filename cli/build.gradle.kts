plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared:protocol"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

application {
    applicationName = "haunt"
    mainClass.set("io.github.crockalet.haunt.cli.MainKt")
}
