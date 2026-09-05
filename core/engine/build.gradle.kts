import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val vendoredGenieXAar = rootProject.file("vendor/geniex/geniex-android-0.3.12-mca1.aar")
val expectedVendoredGenieXSha256 =
    "089F266569D1D9BAFBC7F5D5748FBDCE332FE0EB4077B9D49DBE3F1D50950401"
val vendoredLiteRtQualcommJniDir = rootProject.file("vendor/litert/qualcomm/jniLibs")
val vendoredLiteRtQualcommDispatch =
    vendoredLiteRtQualcommJniDir.resolve("arm64-v8a/libLiteRtDispatch_Qualcomm.so")
val expectedVendoredLiteRtQualcommDispatchSha256 =
    "C4ABFFF6C99EC218F545415A81A2A03A3EE3E21DF2EA911902D6B7BBFEDA80BF"
val vendoredLiteRtQualcommCompilerPlugin =
    vendoredLiteRtQualcommJniDir.resolve("arm64-v8a/libLiteRtCompilerPlugin_Qualcomm.so")
val expectedVendoredLiteRtQualcommCompilerPluginSha256 =
    "425E5CAF007F834748C6BF67AFF265D7E21512A01910F219FAB6B7749EF57732"

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02X".format(byte) }
}

check(vendoredGenieXAar.isFile) {
    "Missing patched GenieX AAR: ${vendoredGenieXAar.absolutePath}"
}
check(sha256(vendoredGenieXAar) == expectedVendoredGenieXSha256) {
    "Patched GenieX AAR failed SHA-256 verification: ${vendoredGenieXAar.absolutePath}"
}
check(vendoredLiteRtQualcommDispatch.isFile) {
    "Missing LiteRT Qualcomm dispatch runtime: ${vendoredLiteRtQualcommDispatch.absolutePath}"
}
check(sha256(vendoredLiteRtQualcommDispatch) == expectedVendoredLiteRtQualcommDispatchSha256) {
    "LiteRT Qualcomm dispatch runtime failed SHA-256 verification: " +
        vendoredLiteRtQualcommDispatch.absolutePath
}
check(vendoredLiteRtQualcommCompilerPlugin.isFile) {
    "Missing LiteRT Qualcomm compiler plugin: ${vendoredLiteRtQualcommCompilerPlugin.absolutePath}"
}
check(sha256(vendoredLiteRtQualcommCompilerPlugin) == expectedVendoredLiteRtQualcommCompilerPluginSha256) {
    "LiteRT Qualcomm compiler plugin failed SHA-256 verification: " +
        vendoredLiteRtQualcommCompilerPlugin.absolutePath
}

android {
    namespace = "com.muyuchat.core.engine"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    sourceSets {
        getByName("main") {
            // Official LiteRT Qualcomm V79 dispatch plugin (SM8750 family).
            jniLibs.srcDir(vendoredLiteRtQualcommJniDir)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:modelstore"))
    implementation(project(":core:native"))
    implementation(project(":core:telemetry"))
    implementation(libs.kotlinx.coroutines.android)
    // Official LiteRT-LM Kotlin/Android runtime. Keep the version pinned so
    // native ABI/API changes cannot enter a production build via
    // `latest.release`.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
    // Qualcomm GenieX 0.3.12 with the one-line JNI message-storage lifetime
    // fix documented in vendor/geniex. Keep the local AAR compile-only here:
    // AGP does not allow a library AAR to depend directly on another local AAR.
    // The application module adds the same verified artifact for final APK
    // packaging (and this module's tests add it below for their runtime).
    compileOnly(files(vendoredGenieXAar))
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(files(vendoredGenieXAar))
}
