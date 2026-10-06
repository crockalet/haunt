plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.crockalet.haunt.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.crockalet.haunt"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // CI passes -Phaunt.versionCode=<run number> so each nightly installs over the previous one.
        versionCode = providers.gradleProperty("haunt.versionCode").orNull?.toInt() ?: 1
        versionName = "0.1.0" + providers.gradleProperty("haunt.versionSuffix").getOrElse("")
    }

    signingConfigs {
        // Shared, public debug key (standard "android" passwords) so debug builds from any machine or CI
        // run update each other. Not for releases.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("foss") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared:core"))
    implementation(project(":shared:protocol"))
    implementation(project(":shared:ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation) // floating joystick window
    implementation(libs.kotlinx.coroutines.android)
    "playImplementation"(libs.play.services.location)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}
