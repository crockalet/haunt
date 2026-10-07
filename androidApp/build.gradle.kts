import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries.android)
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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Shared key so nightlies update in place; real release signing comes later (M5).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("foss") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
    }

    buildFeatures { compose = true }

    lint {
        // play-services-base drags in fragment 1.1.0, but MainActivity is a ComponentActivity, not a FragmentActivity.
        disable += "InvalidFragmentVersionForActivityResult"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
}

// Settings → Data & licences. Library list generated per variant into res/raw/aboutlibraries.json with no
// network access at build time; licence texts come from config/licenses. The BSD / MIT texts there carry
// one project's copyright lines each, so strict mode fails the build when another project brings them.
aboutLibraries {
    offlineMode = true
    collect {
        configPath = file("config")
    }
    export {
        excludeFields.addAll("developers", "funding", "description", "scm", "organization")
    }
    license {
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        allowedLicenses.addAll("Apache-2.0", "ASDKL")
        allowedLicensesMap = mapOf(
            "BSD-2-Clause" to listOf("org.maplibre.nativeffi"),
            "BSD-3-Clause" to listOf("org.maplibre.compose"),
            "MIT" to listOf("org.maplibre.spatialk"),
            // JavaCPP is also offered under Apache-2.0, which is what the app shows.
            "GNU General Public License (GPL) version 2, or any later version" to listOf("org.bytedeco"),
            "GPLv2 with Classpath exception" to listOf("org.bytedeco"),
        )
    }
}

/**
 * Copies the third-party notices that dependencies ship outside their classes (MapLibre Native's
 * `META-INF/licenses/`, Play services' `third_party_licenses`) into `assets/notices/`, deduplicated,
 * with an `index.txt`. AGP doesn't package those files itself.
 */
abstract class CollectNoticesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val archives: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun collect() {
        val out = outputDirectory.get().asFile.resolve("notices")
        out.deleteRecursively()
        out.mkdirs()
        val seen = mutableSetOf<String>()
        val index = mutableListOf<String>()
        val playNotices = sortedMapOf<String, String>()
        fun write(path: String, bytes: ByteArray) {
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (!seen.add(hash)) return
            out.resolve(path).apply { parentFile.mkdirs() }.writeBytes(bytes)
            index += path
        }
        archives.files.sortedBy { it.name }.forEach { archive ->
            ZipFile(archive).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("META-INF/licenses/") }
                    // libc++ is Apache-2.0 WITH LLVM-exception: no notice needed when embedded in object form.
                    .filter { !it.name.startsWith("META-INF/licenses/android-ndk/") }
                    .sortedBy { it.name }
                    .forEach { write(it.name.removePrefix("META-INF/licenses/"), zip.getInputStream(it).readBytes()) }
                val json = zip.getEntry("third_party_licenses.json")
                val txt = zip.getEntry("third_party_licenses.txt")
                if (json != null && txt != null) {
                    val text = zip.getInputStream(txt).readBytes()
                    @Suppress("UNCHECKED_CAST")
                    val entries = JsonSlurper().parse(zip.getInputStream(json)) as Map<String, Map<String, Int>>
                    entries.forEach { (name, span) ->
                        val start = span.getValue("start")
                        val notice = text.copyOfRange(start, start + span.getValue("length")).decodeToString()
                        if (notice !in playNotices.values) {
                            val key = generateSequence(1) { it + 1 }.map { if (it == 1) name else "$name ($it)" }.first { it !in playNotices }
                            playNotices[key] = notice
                        }
                    }
                }
            }
        }
        if (playNotices.isNotEmpty()) {
            val merged = playNotices.entries.joinToString("\n\n") { (name, text) -> "$name\n\n${text.trim()}" }
            write("google-play-services/third_party_licenses.txt", merged.encodeToByteArray())
        }
        out.resolve("index.txt").writeText(index.sorted().joinToString("\n", postfix = "\n"))
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val notices = tasks.register<CollectNoticesTask>("collect${name}Notices") {
            archives.from(
                variant.runtimeConfiguration.incoming.artifactView {
                    attributes { attribute(Attribute.of("artifactType", String::class.java), "aar") }
                    lenient(true)
                }.files,
            )
        }
        variant.sources.assets?.addGeneratedSourceDirectory(notices, CollectNoticesTask::outputDirectory)
    }
}

dependencies {
    implementation(project(":shared:core"))
    implementation(project(":shared:protocol"))
    implementation(project(":shared:ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.compose.foundation) // floating joystick window
    implementation(libs.kotlinx.coroutines.android)
    "playImplementation"(libs.play.services.location)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}
