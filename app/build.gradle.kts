import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val releaseSigningProperties = Properties()
val releaseSigningPropertiesFile = rootProject.file("signing.properties")
if (releaseSigningPropertiesFile.isFile) {
    releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
}

fun releaseSigningProperty(name: String): String? =
    releaseSigningProperties.getProperty(name)
        ?: System.getenv(
            "MCA_RELEASE_" + name
                .replace(Regex("([a-z])([A-Z])"), "$1_$2")
                .uppercase()
        )

val releaseSigningEnabled = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !releaseSigningProperty(it).isNullOrBlank() }

val mcaAbiFilters = providers.gradleProperty("mca.abis")
    .orElse("arm64-v8a,x86_64")
    .get()
    .split(",")
    .map { it.trim() }
    .filter { it.isNotBlank() }

android {
    namespace = "com.muyuchat.mca"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.muyuchat.mca"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.1.0-alpha.2"

        ndk {
            abiFilters += mcaAbiFilters
        }
    }

    signingConfigs {
        if (releaseSigningEnabled) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseSigningProperty("storeFile")))
                storePassword = requireNotNull(releaseSigningProperty("storePassword"))
                keyAlias = requireNotNull(releaseSigningProperty("keyAlias"))
                keyPassword = requireNotNull(releaseSigningProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // These native libraries come from the local llama.cpp/CMake stack or
            // AndroidX native graphics dependency. Some NDK strip toolchains cannot
            // strip them cleanly, and AGP would package them as-is anyway, so make
            // that choice explicit and keep debug builds quiet.
            keepDebugSymbols += setOf(
                "**/libmca_native.so",
                "**/libmca_sd_native.so",
                "**/libggml*.so",
                "**/libllama*.so",
                "**/libmtmd*.so",
                "**/libkleidiai.so",
                "**/libomp.so",
                "**/libandroidx.graphics.path.so"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:engine"))
    implementation(project(":core:sd-native"))
    implementation(project(":core:modelstore"))
    implementation(project(":core:download"))
    implementation(project(":core:telemetry"))
    implementation(project(":core:deviceprofile"))
    implementation(project(":core:advisor"))
    implementation(project(":core:benchmark"))
    implementation(project(":core:tuning"))
    implementation(project(":api:local"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:agent"))
    implementation(project(":feature:modelhub"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.okhttp)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
