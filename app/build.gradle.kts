plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.muyuchat.mca"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.muyuchat.mca"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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

