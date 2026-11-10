import java.util.Properties
import org.gradle.api.artifacts.ExternalModuleDependency

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val metaWearablesVersion = "1.0.1"
val metaWearablesCoordinates = listOf(
    "com.meta.wearable:mwdat-core:$metaWearablesVersion",
    "com.meta.wearable:mwdat-camera:$metaWearablesVersion",
    "com.meta.wearable:mwdat-mockdevice:$metaWearablesVersion",
)

fun credential(name: String): String {
    return optionalCredential(name).ifEmpty {
        // Meta Developer Mode does not attest. The toolkit documents 0 until Wearables
        // Developer Center credentials are supplied through local.properties, Gradle, or the environment.
        "0"
    }
}

fun optionalCredential(name: String): String {
    val localFile = rootProject.file("local.properties")
    if (localFile.isFile) {
        val properties = Properties()
        localFile.inputStream().use { properties.load(it) }
        properties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    providers.gradleProperty(name).orNull?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    providers.environmentVariable(name).orNull?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return ""
}

fun javaStringLiteral(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "")
        .replace("\n", "")
    return "\"$escaped\""
}

fun nonTransitive(coordinate: String): ExternalModuleDependency {
    return (dependencies.create(coordinate) as ExternalModuleDependency).apply {
        isTransitive = false
    }
}

/**
 * Resolves the three DAT artifacts from Maven Central without their POM transitives.
 * mwdat-core 1.0.1 depends on AndroidX core 1.16 / activity 1.10 / lifecycle 2.9, which
 * require a compileSdk newer than 34. compileOnly + non-transitive keeps those off this
 * app's classpath so CameraX builds stay on compileSdk 34. If Maven cannot be reached,
 * the artifacts are omitted and call sites must use reflection.
 */
fun metaWearablesResolve(): Boolean {
    return try {
        val probe = configurations.detachedConfiguration(
            nonTransitive(metaWearablesCoordinates[0]),
            nonTransitive(metaWearablesCoordinates[1]),
            nonTransitive(metaWearablesCoordinates[2]),
        )
        probe.isTransitive = false
        probe.resolve()
        true
    } catch (error: Exception) {
        val message = error.message.orEmpty()
        if (message.contains("no repositories", ignoreCase = true)) {
            logger.warn(
                "Meta Wearables probe did not inherit a repository. " +
                    "The artifacts will be requested from google() and mavenCentral() on the compileOnly classpath.",
            )
            return true
        }
        val resolutionFailure = message.contains("Could not resolve", ignoreCase = true) ||
            message.contains("Could not find", ignoreCase = true) ||
            message.contains("offline", ignoreCase = true) ||
            error.javaClass.name.contains("Resolve")
        if (!resolutionFailure) {
            throw error
        }
        logger.warn(
            "Meta Wearables DAT SDK $metaWearablesVersion is not resolvable. " +
                "CameraX phone and emulator builds still compile; reach the SDK through reflection. " +
                message.lineSequence().firstOrNull().orEmpty(),
        )
        false
    }
}

val metaWearablesCompileOnly = metaWearablesResolve()

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.seeandsay.chronologer"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.seeandsay.chronologer"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        manifestPlaceholders["mwdat_application_id"] = credential("MWDAT_APPLICATION_ID")
        manifestPlaceholders["mwdat_client_token"] = credential("MWDAT_CLIENT_TOKEN")
        buildConfigField(
            "boolean",
            "META_WEARABLES_COMPILE_ONLY",
            metaWearablesCompileOnly.toString(),
        )
        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            javaStringLiteral(optionalCredential("GEMINI_API_KEY")),
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/INDEX.LIST"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    if (metaWearablesCompileOnly) {
        metaWearablesCoordinates.forEach { coordinate ->
            compileOnly(nonTransitive(coordinate))
        }
    }
}
