plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.muyuchat.core.advisor"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:benchmark"))
    implementation(project(":core:deviceprofile"))
    implementation(project(":core:engine"))
    implementation(project(":core:modelstore"))
    implementation(project(":core:download"))
    implementation(project(":core:tuning"))
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
