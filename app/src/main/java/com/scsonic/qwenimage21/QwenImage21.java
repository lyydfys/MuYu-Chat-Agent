package com.scsonic.qwenimage21;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Minimal JNI facade for MCA's isolated Qwen-Image-2.1 worker process.
 *
 * <p>The Qwen MNN build has a private SONAME and must only be loaded from the
 * dedicated :qwen_image21 process. Runtime libraries are copied from APK
 * assets to app-private code cache and compared byte-for-byte before dlopen.
 */
public final class QwenImage21 {
    private static final String ASSET_ROOT = "qwen-image-2.1/";
    private static final String MANIFEST_NAME = "runtime-manifest.json";
    private static final String[] LIBRARIES = {
            "libc++_shared.so",
            "libmca_qwenimage21_mnn.so",
            "libqwenimage21_jni.so"
    };
    private static volatile boolean librariesLoaded;

    private QwenImage21() {}

    public interface ProgressListener {
        void onProgress(int percent);
    }

    /** Extracts and verifies the ABI-matched private runtime, then loads it by absolute path. */
    public static synchronized File loadRuntimeLibraries(Context context) throws IOException {
        if (librariesLoaded) return runtimeDirectory(context);
        String abi = selectedAbi(context);
        String assetDirectory = ASSET_ROOT + abi + "/";
        JSONObject runtimeManifest;
        try (InputStream input = context.getAssets().open(assetDirectory + MANIFEST_NAME)) {
            ByteArrayOutputStream manifestBytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                manifestBytes.write(buffer, 0, count);
                if (manifestBytes.size() > 16 * 1024) {
                    throw new IOException("Qwen runtime asset manifest is too large.");
                }
            }
            if (manifestBytes.size() == 0) throw new IOException("Qwen runtime asset manifest is empty.");
            runtimeManifest = new JSONObject(manifestBytes.toString("UTF-8"));
        } catch (Exception error) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Qwen runtime asset manifest is invalid.", error);
        }
        if (!"mca.qwen-image-2.1.runtime.v1".equals(runtimeManifest.optString("schema"))) {
            throw new IOException("Qwen runtime asset manifest has an unsupported schema.");
        }
        JSONArray manifestLibraries = runtimeManifest.optJSONArray("libraries");
        if (manifestLibraries == null || manifestLibraries.length() != LIBRARIES.length) {
            throw new IOException("Qwen runtime asset manifest has an incomplete library set.");
        }
        java.util.HashMap<String, LibraryPin> pins = new java.util.HashMap<>();
        for (int index = 0; index < manifestLibraries.length(); index++) {
            JSONObject library = manifestLibraries.optJSONObject(index);
            if (library == null) throw new IOException("Qwen runtime manifest library entry is invalid.");
            String name = library.optString("file");
            long size = library.optLong("sizeBytes", -1L);
            String digest = library.optString("sha256").toLowerCase(Locale.ROOT);
            if (name.contains("/") || name.contains("\\") || size <= 0
                    || !digest.matches("[0-9a-f]{64}") || pins.put(name, new LibraryPin(size, digest)) != null) {
                throw new IOException("Qwen runtime manifest contains an invalid library pin.");
            }
        }
        for (String name : LIBRARIES) {
            if (!pins.containsKey(name)) throw new IOException("Qwen runtime manifest is missing " + name + ".");
        }
        File targetDirectory = runtimeDirectory(context);
        if (!targetDirectory.isDirectory() && !targetDirectory.mkdirs()) {
            throw new IOException("Cannot create the private Qwen runtime directory.");
        }

        File[] verified = new File[LIBRARIES.length];
        for (int i = 0; i < LIBRARIES.length; i++) {
            String name = LIBRARIES[i];
            LibraryPin pin = pins.get(name);
            String assetPath = assetDirectory + name;
            long assetLength;
            String assetSha;
            try (InputStream input = context.getAssets().open(assetPath)) {
                MessageDigest digest = sha256();
                byte[] buffer = new byte[64 * 1024];
                long count = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    count += read;
                }
                assetLength = count;
                assetSha = hex(digest.digest());
            } catch (IOException error) {
                throw new IOException("Qwen runtime binary is missing for ABI " + abi + ": " + name, error);
            }
            if (assetLength <= 0 || assetLength != pin.sizeBytes || !assetSha.equals(pin.sha256)) {
                throw new IOException("Qwen runtime APK asset does not match its pinned size/SHA-256: " + name);
            }

            File destination = new File(targetDirectory, name);
            if (destination.isFile() && destination.length() == assetLength
                    && assetSha.equals(sha256(destination))) {
                verified[i] = destination;
                continue;
            }

            File temporary = new File(targetDirectory, name + ".tmp");
            if (temporary.exists() && !temporary.delete()) {
                throw new IOException("Cannot replace the temporary Qwen runtime binary: " + name);
            }
            try (InputStream input = context.getAssets().open(assetPath);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.getFD().sync();
            }
            if (temporary.length() != assetLength || !assetSha.equals(sha256(temporary))) {
                temporary.delete();
                throw new IOException("Qwen runtime copy failed its APK asset length/SHA-256 check: " + name);
            }
            if (destination.exists() && !destination.delete()) {
                temporary.delete();
                throw new IOException("Cannot replace the installed Qwen runtime binary: " + name);
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete();
                throw new IOException("Cannot atomically install the Qwen runtime binary: " + name);
            }
            verified[i] = destination;
        }

        // Explicit path loading avoids the host application's MNN SONAME and
        // ensures libc++ is resolved before the private MNN dependency.
        for (File library : verified) System.load(library.getAbsolutePath());
        librariesLoaded = true;
        return targetDirectory;
    }

    private static File runtimeDirectory(Context context) {
        return new File(context.getCodeCacheDir(), "qwen-image-2.1-runtime");
    }

    private static String selectedAbi(Context context) throws IOException {
        // ApplicationInfo.nativeLibraryDir identifies the ABI selected by PackageManager for
        // this installed APK even on devices that advertise multiple translation ABIs.
        String appNativeDir = context.getApplicationInfo().nativeLibraryDir.toLowerCase(Locale.ROOT);
        if (appNativeDir.contains("/arm64") || appNativeDir.endsWith("\\arm64")) return "arm64-v8a";
        if (appNativeDir.contains("/x86_64") || appNativeDir.endsWith("\\x86_64")) return "x86_64";
        if (appNativeDir.contains("/x86") || appNativeDir.endsWith("\\x86")) return "x86";
        String abi = Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0];
        if ("arm64-v8a".equals(abi)) return abi;
        throw new IOException("Qwen-Image-2.1 runtime is not packaged for this app ABI (" + abi + ").");
    }

    private static final class LibraryPin {
        final long sizeBytes;
        final String sha256;

        LibraryPin(long sizeBytes, String sha256) {
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception error) {
            throw new IOException("SHA-256 is unavailable.", error);
        }
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }

    public static long create(String modelDirectory, boolean useGpu, int threads) {
        return nativeCreate(modelDirectory, useGpu, true, true, 0, Math.max(1, threads));
    }

    public static int generate(long handle, String prompt, String inputImage, String outputPng,
                               int width, int height, int steps, int seed, ProgressListener listener) {
        return nativeGenerate(handle, prompt, inputImage, outputPng, steps, seed, width, height, listener);
    }

    public static String lastError(long handle) {
        return nativeLastError(handle);
    }

    /**
     * Returns native-only execution evidence for the isolated runtime.  The
     * payload distinguishes the requested backend from MNN's effective
     * backend after graph execution, so an OpenCL request that fell back to
     * CPU cannot be published as a GPU result.
     */
    public static String executionAudit(long handle) {
        return nativeExecutionAudit(handle);
    }

    public static int availableMemoryMB() {
        return nativeAvailableMemoryMB();
    }

    public static void release(long handle) {
        if (handle != 0) nativeRelease(handle);
    }

    private static native long nativeCreate(String modelDir, boolean useGpu, boolean textEncoderOnCpu,
                                             boolean vaeOnCpu, int memoryMode, int threads);
    private static native int nativeGenerate(long handle, String prompt, String inputImage, String outputPng,
                                             int steps, int seed, int width, int height, ProgressListener listener);
    private static native String nativeLastError(long handle);
    private static native String nativeExecutionAudit(long handle);
    private static native int nativeAvailableMemoryMB();
    private static native void nativeRelease(long handle);
}
