// mca/mnn/mnn_diffusion_generate_entries.cpp -- NativeMnnDiffusionBridge
// generate/progress/cancel/stats JNI entries and MNN Diffusion ABI shims.
// Business execution lives in mnn_diffusion_generate.cpp.

#include "mca/mnn/mnn_diffusion_jni_prelude.hpp"
#include "mca/mnn/mnn_diffusion_generate_request.hpp"

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_generate(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring paramsJson,
        jstring outputPath) {
#if MCA_WITH_MNN_DIFFUSION
    const auto root = normalize_mnn_model_path(jstring_to_std(env, bundleRoot));
    const auto params_raw = jstring_to_std(env, paramsJson);
    const auto output_path = jstring_to_std(env, outputPath);
    return utf8_to_jstring(
            env,
            generate_mnn_diffusion_image(root, params_raw, output_path));
#else
    const auto out = json({
        {"ok", false},
        {"backend", "mnn_diffusion"},
        {"error", "MNN-Diffusion native runner is not linked in this APK."}
    }).dump();
    return utf8_to_jstring(env, out);
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_getProgress(JNIEnv* env, jobject) {
    return mnn_json_entrypoint(
            env,
            "getProgress",
            "{\"phase\":\"error\",\"message\":\"native exception\",\"step\":0,\"steps\":0,"
            "\"elapsedMs\":0,\"secondsPerStep\":0.0,\"cancelRequested\":false}",
            [env] {
#if MCA_WITH_MNN_DIFFUSION
                std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
                const auto out = mnn_diffusion_progress_json_locked().dump();
#else
                const auto out = json({
                    {"phase", "unavailable"},
                    {"message", "MNN-Diffusion native runner is not linked in this APK."},
                    {"step", 0},
                    {"steps", 0},
                    {"elapsedMs", 0},
                    {"secondsPerStep", 0.0},
                    {"cancelRequested", false}
                }).dump();
#endif
                return utf8_to_jstring(env, out);
            });
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_cancel(JNIEnv*, jobject) {
#if MCA_WITH_MNN_DIFFUSION
    std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
    g_mnn_diffusion_cancel_requested = true;
    if (g_mnn_diffusion_generating) {
        g_mnn_diffusion_phase = "cancelling";
        g_mnn_diffusion_message = "Cancelling MNN-Diffusion generation.";
    }
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeMnnDiffusionBridge_getRuntimeStatsJson(JNIEnv* env, jobject) {
    return mnn_json_entrypoint(
            env,
            "getRuntimeStatsJson",
            "{\"backend\":\"mnn_diffusion\",\"runnerReady\":false,\"loaded\":false,"
            "\"generating\":false,\"lastError\":\"native exception\"}",
            [env] {
#if MCA_WITH_MNN_DIFFUSION
                std::lock_guard<std::mutex> lock(g_mnn_diffusion_mutex);
                const auto out = json({
                    {"backend", "mnn_diffusion"},
                    {"runnerReady", true},
                    {"loaded", g_mnn_diffusion_loaded},
                    {"generating", g_mnn_diffusion_generating},
                    {"bundleRoot", g_mnn_diffusion_bundle_root},
                    {"outputPath", g_mnn_diffusion_output_path},
                    {"backendMode", g_mnn_diffusion_backend},
                    {"backendDevices", json::array({"cpu", "opencl-experimental"})},
                    {"progress", mnn_diffusion_progress_json_locked()},
                    {"lastError", g_mnn_diffusion_last_error}
                }).dump();
#else
                const auto out = json({
                    {"backend", "mnn_diffusion"},
                    {"runnerReady", false},
                    {"loaded", false},
                    {"lastError", "MNN-Diffusion native runner is not linked in this APK."}
                }).dump();
#endif
                return utf8_to_jstring(env, out);
            });
}

#if MCA_WITH_MNN_DIFFUSION
namespace MNN {
namespace DIFFUSION {

Diffusion::Diffusion(std::string modelPath, DiffusionModelType modelType, MNNForwardType backendType, int memoryMode)
        : mModelPath(std::move(modelPath)),
          mModelType(modelType),
          mMemoryMode(memoryMode),
          mBackendType(backendType) {
}

Diffusion::~Diffusion() {
    mModules.clear();
    runtime_manager_.reset();
}

bool Diffusion::runVideo(
        const std::string& prompt,
        const std::string& outputDir,
        int width,
        int height,
        int frames,
        int steps,
        int seed,
        float cfgScale,
        std::function<void(int)> progressCallback) {
    (void)prompt;
    (void)outputDir;
    (void)width;
    (void)height;
    (void)frames;
    (void)steps;
    (void)seed;
    (void)cfgScale;
    (void)progressCallback;
    return false;
}

Diffusion* Diffusion::createDiffusion(
        std::string modelPath,
        DiffusionModelType modelType,
        MNNForwardType backendType,
        int memoryMode) {
    if (modelType == SANA_DIFFUSION) {
        return new SanaDiffusion(std::move(modelPath), modelType, backendType, memoryMode);
    }
    return new StableDiffusion(std::move(modelPath), modelType, backendType, memoryMode);
}

}  // namespace DIFFUSION
}  // namespace MNN
#endif
