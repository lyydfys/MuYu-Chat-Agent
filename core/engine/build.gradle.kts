import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val vendoredGenieXAar = rootProject.file("vendor/geniex/geniex-android-0.3.12-mca1.aar")
val expectedVendoredGenieXSha256 =
    "089F266569D1D9BAFBC7F5D5748FBDCE332FE0EB4077B9D49DBE3F1D50950401"

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

android {
    namespace = "com.muyuchat.core.engine"
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
    implementation(project(":core:modelstore"))
    implementation(project(":core:native"))
    implementation(project(":core:telemetry"))
    implementation(libs.kotlinx.coroutines.android)
    // Qualcomm GenieX 0.3.12 with the one-line JNI message-storage lifetime
    // fix documented in vendor/geniex. The exact AAR hash is verified above so
    // a build cannot silently fall back to the vulnerable upstream binary.
    implementation(files(vendoredGenieXAar))
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
