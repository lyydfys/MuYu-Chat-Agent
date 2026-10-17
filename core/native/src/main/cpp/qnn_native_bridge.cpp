#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cctype>
#include <cstdarg>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <fstream>
#include <iomanip>
#include <iterator>
#include <limits>
#include <mutex>
#include <new>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "diffusion_scheduler.hpp"
#include "image_conditioning.hpp"
#include "image_execution_math.hpp"
#include "jni_utf8_codec.hpp"
#include "mnn_qnn_prompt_handoff.hpp"
#include "native_prompt_language_contract.hpp"
#include "qnn_controlnet_execution.hpp"
#include "qnn_image_pixel_range.hpp"
#include "qnn_image_stage_trace.hpp"
#include "qnn_inpaint_contract.hpp"
#include "qnn_soc_htp_mapping.hpp"
#include "qnn_shared_preview.hpp"

#include "mca/qnn/qnn_system_symbol_loader.hpp"
#include "mca/qnn/qnn_capability_probe.hpp"
#include "mca/qnn/qnn_preview_manager.hpp"
#include "mca/qnn/qnn_bundle_files.hpp"
#include "mca/qnn/qnn_bundle_manifest.hpp"
#include "mca/qnn/qnn_sdxl_phase_support.hpp"
#include "mca/qnn/qnn_sdxl_unet_tiler.hpp"
#include "mca/qnn/qnn_tensor_codec.hpp"
#include "mca/qnn/qnn_device_support.hpp"
#include "mca/qnn/qnn_tensor_inspect.hpp"
#include "mca/qnn/qnn_ultrafix_handler.hpp"
#include "mca/qnn/qnn_image_writer.hpp"
#include "mca/qnn/qnn_inpaint_blend.hpp"
#include "mca/qnn/qnn_contract_parser.hpp"
#include "mca/qnn/qnn_executable_graph.hpp"
#include "mca/qnn/qnn_conditioning_pipeline.hpp"
#include "mca/qnn/qnn_model_execution_pipeline.hpp"
#include "mca/qnn/qnn_semantic_pipeline.hpp"
#include "mca/qnn/qnn_graph_executor_helper.hpp"
#include "mca/qnn/qnn_tensor_quantizer.hpp"
#include "mca/qnn/qnn_bridge_payloads.hpp"
// The isolated-phase fragment header below defines its entry points unqualified, so the namespace
// import has to precede it.  (qnn_native_bridge.cpp previously listed this after every include,
// which left the fragment expanding without the mca::qnn names in scope.)
using namespace mca::qnn;

// The SDXL isolated-phase fragment headers below serialise real graph execution on the shared QNN
// context-execution mutex.  It is declared extern in mca/qnn/qnn_preview_manager.hpp (included
// above) and defined exactly once in mca/qnn/qnn_preview_manager.cpp, so mca_qnn_runtime and
// mca_qnn_native lock one and the same object.
//
// Do NOT re-add a global-scope `extern` here: that declares ::g_qnn_context_execution_mutex, a
// different symbol from ::mca::qnn::g_qnn_context_execution_mutex and one that nothing defines, so
// it would both fail to link and silently drop the serialisation of QNN context execution.

#include "qnn_sdxl_isolated_phases.hpp"

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

namespace {

constexpr const char* kTag = "MCA-QNN";

std::string jstring_to_std(JNIEnv* env, jstring value) {
    if (value == nullptr) return "";
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return "";
    static_assert(sizeof(jchar) == sizeof(uint16_t), "JNI jchar must be a UTF-16 code unit");
    // mca::utf8 is the codec namespace the rest of the tree uses (native_engine.cpp,
    // mnn_stats_collector.cpp, ...); the previous mca::jni::utf16_to_utf8 call named a namespace
    // that does not exist anywhere in the project.
    std::string result = mca::utf8::encode_from_utf16(
        reinterpret_cast<const uint16_t*>(chars),
        static_cast<size_t>(length));
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring std_to_jstring(JNIEnv* env, const std::string& value) {
    const auto decoded = mca::utf8::decode_to_utf16(value, false);
    return env->NewString(
        reinterpret_cast<const jchar*>(decoded.utf16.data()),
        static_cast<jsize>(decoded.utf16.size()));
}

jstring qnn_jni_error_json(
        JNIEnv* env,
        const char* operation,
        const char* message) noexcept {
    try {
        std::ostringstream out;
        out << "{\"ok\":false,\"backend\":\"qnn_htp\",\"operation\":"
            << quote(operation == nullptr ? "unknown" : operation)
            << ",\"message\":" << quote(message == nullptr ? "unknown error" : message)
            << "}";
        return std_to_jstring(env, out.str());
    } catch (...) {
        return env->NewStringUTF("{\"ok\":false,\"backend\":\"qnn_htp\",\"message\":\"fatal JNI error serialization failure\"}");
    }
}

template <typename Callback>
jstring qnn_jni_json_guard(JNIEnv* env, const char* operation, Callback&& callback) noexcept {
    try {
        return std_to_jstring(env, callback());
    } catch (const std::exception& e) {
        return qnn_jni_error_json(env, operation, e.what());
    } catch (...) {
        return qnn_jni_error_json(env, operation, "unknown native exception");
    }
}

template <typename Callback>
jboolean qnn_jni_boolean_guard(const char* operation, Callback&& callback) noexcept {
    try {
        return callback() ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "%s failed: %s", operation, e.what());
        return JNI_FALSE;
    } catch (...) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "%s failed with unknown exception", operation);
        return JNI_FALSE;
    }
}

} // namespace



#if MCA_WITH_QNN_SDK_HEADERS

// With the QNN SDK headers present the implementations of the six entry points below live in the
// mca/qnn/*.cpp modules (qnn_semantic_pipeline, qnn_model_execution_pipeline, ...), which are
// compiled into the mca_qnn_runtime target.  This translation unit therefore only carries the
// no-SDK fallbacks that follow the #else, plus the shared JNI layer below the #endif.

#else

GraphSmokeResult run_typed_qnn_graph_smoke(
        const RuntimeProbe&,
        const SmokeSpecProbe&,
        const std::string&,
        const std::string&) {
    GraphSmokeResult result;
    result.attempted = false;
    result.execution_stage = "sdk_headers_missing";
    result.message = "QNN SDK headers were not available at build time.";
    return result;
}

std::string qnn_pipeline_probe_json(
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&) {
    return "{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"sdk_headers_missing\",\"message\":\"QNN SDK headers were not available at build time.\"}";
}

std::string qnn_semantic_generate_json(
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&) {
    return "{\"ok\":false,\"backend\":\"qnn_htp\",\"semanticReady\":false,\"executionStage\":\"sdk_headers_missing\",\"message\":\"QNN SDK headers were not available at build time.\"}";
}

std::string qnn_sdxl_unet_phase_json(
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&) {
    return "{\"ok\":false,\"phase\":\"unet\",\"executionStage\":\"sdk_headers_missing\",\"message\":\"QNN SDK headers were not available at build time.\"}";
}

std::string qnn_sdxl_encoder_phase_json(
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&) {
    return "{\"ok\":false,\"phase\":\"encoder\",\"executionStage\":\"sdk_headers_missing\",\"message\":\"QNN SDK headers were not available at build time.\"}";
}

std::string qnn_sdxl_vae_phase_json(
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&,
        const std::string&) {
    return "{\"ok\":false,\"phase\":\"vae\",\"executionStage\":\"sdk_headers_missing\",\"message\":\"QNN SDK headers were not available at build time.\"}";
}

#endif


extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_isRunnerReady(JNIEnv*, jobject) noexcept {
    return qnn_jni_boolean_guard("isRunnerReady", []() {
#if MCA_WITH_QNN_SDK_HEADERS
        return true;
#else
        return false;
#endif
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_inspectRuntime(
        JNIEnv* env,
        jobject,
        jstring runtimeDirsJson) noexcept {
    return qnn_jni_json_guard(env, "inspectRuntime", [&]() {
        if (g_qnn_runtime_poisoned.load()) {
            RuntimeProbe poisoned;
            poisoned.message =
                "This QNN process rejected runtime probing after a native resource release failure.";
            return runtime_probe_json(poisoned);
        }
        const auto dirs = parse_json_string_array(jstring_to_std(env, runtimeDirsJson));
        return runtime_probe_json(inspect_runtime_internal(dirs, true, false, &::mca::qnn::g_qnn_runtime_poisoned));
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_inspectBundle(
        JNIEnv* env,
        jobject,
        jstring bundleRoot) noexcept {
    return qnn_jni_json_guard(env, "inspectBundle", [&]() {
        return bundle_probe_json(inspect_bundle_internal(jstring_to_std(env, bundleRoot)));
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_inspectContextMetadata(
        JNIEnv* env,
        jobject,
        jstring contextBinaryPath,
        jstring runtimeDirsJson) noexcept {
    return qnn_jni_json_guard(env, "inspectContextMetadata", [&]() {
        const std::string context_path = jstring_to_std(env, contextBinaryPath);
        const auto dirs = parse_json_string_array(jstring_to_std(env, runtimeDirsJson));
        const RuntimeProbe runtime = inspect_runtime_internal(dirs, true, false, &::mca::qnn::g_qnn_runtime_poisoned);
        std::string metadata_json = "{}";
#if MCA_WITH_QNN_SDK_HEADERS
        if (!context_path.empty() && exists_file(context_path)) {
            metadata_json = binary_metadata_json(
                inspect_qnn_context_binary_metadata(runtime, read_binary_file(context_path)));
        } else {
            QnnBinaryMetadata metadata;
            metadata.attempted = true;
            metadata.message = "QNN context binary is missing or empty.";
            metadata_json = binary_metadata_json(metadata);
        }
#else
        metadata_json = "{\"attempted\":true,\"parsed\":false,\"message\":\"QNN SDK headers were not available at build time.\"}";
#endif
        std::ostringstream out;
        out << "{\"contextPath\":" << quote(context_path)
            << ",\"runtime\":" << runtime_probe_json(runtime)
            << ",\"binaryMetadata\":" << metadata_json << "}";
        return out.str();
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_runImageSmoke(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring runtimeDirsJson,
        jstring smokeSpecJson) noexcept {
    return qnn_jni_json_guard(env, "runImageSmoke", [&]() {
        const std::string bundle_root = jstring_to_std(env, bundleRoot);
        const std::string runtime_dirs_json = jstring_to_std(env, runtimeDirsJson);
        const std::string smoke_spec_json = jstring_to_std(env, smokeSpecJson);
        std::lock_guard<std::timed_mutex> context_execution_lock(::mca::qnn::g_qnn_context_execution_mutex);
        return smoke_json(
            "image",
            bundle_root,
            runtime_dirs_json,
            smoke_spec_json
        );
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_runImagePipelineProbe(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring runtimeDirsJson,
        jstring paramsJson,
        jstring outputPath) noexcept {
    return qnn_jni_json_guard(env, "runImagePipelineProbe", [&]() {
        const std::string bundle_root = jstring_to_std(env, bundleRoot);
        const std::string runtime_dirs_json = jstring_to_std(env, runtimeDirsJson);
        const std::string params_json = jstring_to_std(env, paramsJson);
        const std::string output_path = jstring_to_std(env, outputPath);
        std::lock_guard<std::timed_mutex> context_execution_lock(::mca::qnn::g_qnn_context_execution_mutex);
        return qnn_pipeline_probe_json(
            bundle_root,
            runtime_dirs_json,
            params_json,
            output_path
        );
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_runImageSemanticGenerate(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring runtimeDirsJson,
        jstring paramsJson,
        jstring embeddingsPath,
        jstring outputPath) noexcept {
    return qnn_jni_json_guard(env, "runImageSemanticGenerate", [&]() {
        return qnn_semantic_generate_json(
            jstring_to_std(env, bundleRoot),
            jstring_to_std(env, runtimeDirsJson),
            jstring_to_std(env, paramsJson),
            jstring_to_std(env, embeddingsPath),
            jstring_to_std(env, outputPath)
        );
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_runSdxlUnetPhase(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring runtimeProfileJson,
        jstring paramsJson,
        jstring embeddingsPath,
        jstring initialLatentPath,
        jstring latentPath) noexcept {
    return qnn_jni_json_guard(env, "runSdxlUnetPhase", [&]() {
        return qnn_sdxl_unet_phase_json(
            jstring_to_std(env, bundleRoot),
            jstring_to_std(env, runtimeProfileJson),
            jstring_to_std(env, paramsJson),
            jstring_to_std(env, embeddingsPath),
            jstring_to_std(env, initialLatentPath),
            jstring_to_std(env, latentPath));
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_runSdxlEncoderPhase(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring runtimeProfileJson,
        jstring paramsJson,
        jstring inputTensorPath,
        jstring latentPath,
        jstring expectedVaeEncoderContextSha256) noexcept {
    return qnn_jni_json_guard(env, "runSdxlEncoderPhase", [&]() {
        return qnn_sdxl_encoder_phase_json(
            jstring_to_std(env, bundleRoot),
            jstring_to_std(env, runtimeProfileJson),
            jstring_to_std(env, paramsJson),
            jstring_to_std(env, inputTensorPath),
            jstring_to_std(env, latentPath),
            jstring_to_std(env, expectedVaeEncoderContextSha256));
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_runSdxlVaePhase(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring runtimeProfileJson,
        jstring paramsJson,
        jstring latentPath,
        jstring outputPath) noexcept {
    return qnn_jni_json_guard(env, "runSdxlVaePhase", [&]() {
        return qnn_sdxl_vae_phase_json(
            jstring_to_std(env, bundleRoot),
            jstring_to_std(env, runtimeProfileJson),
            jstring_to_std(env, paramsJson),
            jstring_to_std(env, latentPath),
            jstring_to_std(env, outputPath));
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_cancelImageGeneration(JNIEnv*, jobject) noexcept {
    return qnn_jni_boolean_guard("cancelImageGeneration", []() {
        if (!g_qnn_image_generation_active.load()) return false;
        {
            std::lock_guard<std::mutex> publish_lock(g_qnn_image_preview_publish_mutex);
            g_qnn_image_generation_cancel_requested.store(true);
            g_qnn_image_generation_phase.store(kQnnImageCancelling);
            persist_qnn_image_generation_journal();
        }
        return true;
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_getImageGenerationProgressJson(
        JNIEnv* env,
        jobject) noexcept {
    return qnn_jni_json_guard(env, "getImageGenerationProgressJson", []() {
        return qnn_image_generation_progress_json();
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_runVisionSmoke(
        JNIEnv* env,
        jobject,
        jstring bundleRoot,
        jstring runtimeDirsJson,
        jstring smokeSpecJson) noexcept {
    return qnn_jni_json_guard(env, "runVisionSmoke", [&]() {
        const std::string bundle_root = jstring_to_std(env, bundleRoot);
        const std::string runtime_dirs_json = jstring_to_std(env, runtimeDirsJson);
        const std::string smoke_spec_json = jstring_to_std(env, smokeSpecJson);
        std::lock_guard<std::timed_mutex> context_execution_lock(::mca::qnn::g_qnn_context_execution_mutex);
        return smoke_json(
            "vision",
            bundle_root,
            runtime_dirs_json,
            smoke_spec_json
        );
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_nativebridge_NativeQnnBridge_getRuntimeStatsJson(
        JNIEnv* env,
        jobject) noexcept {
    return qnn_jni_json_guard(env, "getRuntimeStatsJson", []() {
        std::ostringstream out;
        out << "{"
            << "\"backend\":\"qnn_htp\","
            << "\"bridgeReady\":true,"
            // The JNI bridge can load without the typed SDK headers, but no
            // graph runner or pipeline probe exists in that build. Keep the
            // diagnostic in sync with isRunnerReady() so callers do not claim
            // that an inactive QNN stub is packaged and usable.
            << "\"graphRunnerReady\":"
            << (MCA_WITH_QNN_SDK_HEADERS ? "true" : "false") << ","
            << "\"pipelineProbeReady\":"
            << (MCA_WITH_QNN_SDK_HEADERS ? "true" : "false") << ","
            << "\"npuActive\":false,"
            << "\"compile\":" << compile_capability_json();
#if !MCA_WITH_QNN_SDK_HEADERS
        out << ",\"lastError\":\"QNN typed graph bindings are unavailable in this APK; rebuild with the QAIRT/QNN SDK headers.\"";
#endif
        out << "}";
        return out.str();
    });
}
