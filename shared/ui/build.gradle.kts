plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "io.github.crockalet.haunt.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources { enable = true }
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.haze)
            implementation(libs.haze.blur)
        }
        androidMain.dependencies {
            implementation(libs.maplibre.compose)
            implementation(libs.maplibre.native.runtime.opengl)
            implementation(libs.androidx.activity.compose)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.crockalet.haunt.ui.generated"
}

// Screenshots rendered by jvmTest land in build/screenshots.
tasks.withType<Test>().configureEach {
    systemProperty("haunt.screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}
