// T2B17 / R7-C6 (2026-10-14): split out of stable_diffusion_bridge.cpp (pre-split sha256
// c11dcdd8a564ad59573e40d4554db435c1251d7946c5b99c52a9230eee8865cd).  Body lines are byte-exact
// relocations; every delta is registered in b-SPLIT-PLAN.json.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <dirent.h>
#include <exception>
#include <fcntl.h>
#include <iomanip>
#include <limits.h>
#include <limits>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <unordered_set>
#include <vector>

#include "json.hpp"
#include "stable-diffusion.h"
#include "tokenizers/clip_tokenizer.h"
#include "util.h"
#include "image_execution_math.hpp"
#include "execution_asset_binding.hpp"
#include "native_prompt_language_contract.hpp"
#include "mca/sd/sd_output_transaction.hpp"
#include "mca/sd/sd_input_contract.hpp"
#include "mca/sd/sd_progress_reporter.hpp"
#include "mca/sd/sd_schedule_resolver.hpp"
#include "mca/sd/sd_lora_adapter.hpp"
#include "mca/sd/sd_image_io.hpp"
#include "mca/sd/sd_textual_inversion.hpp"
#include "mca/sd/sd_component_resolver.hpp"
#include "mca/sd/sd_runtime_diagnostics.hpp"
#include "mca/sd/sd_upscaler.hpp"
#include "mca/sd/sd_context_key.hpp"
#include "mca/sd/sd_effective_config.hpp"
#include "mca/sd/sd_controlnet_adapter.hpp"
#include "mca/sd/sd_inpaint_processor.hpp"
#include "mca/sd/sd_preview_encoder.hpp"
#include "mca/sd/sd_evidence_verifier.hpp"
#include "mca/sd/sd_response_builder.hpp"
using mca::sd::preview_mode_from_contract;
using mca::sd::PreviewPublicationContext;
using mca::sd::SdPreviewScope;
using mca::sd::verify_preview_publication;
using mca::sd::populate_preview_response_json;
using mca::sd::validate_controlnet_component;
using mca::sd::configure_controlnet_params;
using mca::sd::verify_controlnet_wiring;
using mca::sd::extract_controlnet_evidence;
using mca::sd::verify_controlnet_branch_classification;
using mca::sd::populate_controlnet_effective_json;
using mca::sd::populate_controlnet_response_json;
using mca::sd::load_and_validate_inpaint_mask;
using mca::sd::configure_inpaint_params;
using mca::sd::verify_inpaint_wiring;
using mca::sd::populate_inpaint_effective_json;
using mca::sd::populate_inpaint_response_json;
using mca::sd::progress_to_json;
using mca::sd::runtime_config_json;
using mca::sd::set_progress_from_sd_log;
using mca::sd::safe_sd_runtime_message;
using mca::sd::sd_log_callback;
using mca::sd::make_context_key;
using mca::sd::observed_prediction_mode;
using mca::sd::ComponentPaths;
using mca::sd::execution_assets_match_consumers;
using mca::sd::validate_lora_file;
using mca::sd::validate_textual_inversion_execution_evidence;
using mca::sd::component_selection_json;
using mca::sd::resolve_component_paths;
using mca::sd::is_sha256;
using mca::sd::LoadedInputImage;
using mca::sd::load_canonical_input_image;
using mca::sd::build_output_paths;
using mca::sd::free_generated_images;
using mca::sd::remove_output_files;
using mca::sd::ContractTextualInversion;
using mca::sd::regular_file_identity;
using mca::sd::same_regular_file_identity;
using mca::sd::sha256_hex;
using mca::sd::sha256_utf8;
using mca::sd::fixed_width_lower_hex_u64;
using mca::sd::textual_inversion_binding_fingerprint;
using mca::sd::textual_inversion_selection_fingerprint;
using mca::sd::image_prompt_execution_sha256;
using mca::sd::sha256_implementation_ready;
using mca::sd::sha256_regular_file;
using mca::sd::validate_textual_inversion_file;


#include "mca/sd/sd_contract_parser.hpp"
using mca::sd::ContractError;
using mca::sd::invalid_contract;
using mca::sd::unsupported_contract;
using mca::sd::execution_mismatch;
using mca::sd::contract_failure_json;
using mca::sd::optional_string;
using mca::sd::StableDiffusionExecutionContract;
using mca::sd::task_uses_init_image;
using mca::sd::task_uses_control_image;
using mca::sd::backend_denoising_strength;
using mca::sd::parse_execution_contract;
using mca::sd::observed_prediction_wire;
using mca::sd::runtime_failure;
using mca::sd::native_effective_json;

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"
#include "stable_diffusion_bridge_internal.hpp"

namespace {

using json = nlohmann::json;

std::mutex g_mutex;
using mca_sd_bridge_internal::ensure_context;
using mca_sd_bridge_internal::file_exists;
using mca_sd_bridge_internal::g_ctx;
using mca_sd_bridge_internal::g_ctx_key;
using mca_sd_bridge_internal::g_last_error;
using mca_sd_bridge_internal::mark_generation_stage;
using mca_sd_bridge_internal::measure_clip_prompt_tokens;
using mca_sd_bridge_internal::set_progress;
using mca_sd_bridge_internal::set_progress_component_selection;
auto &g_last_sd_error = mca::sd::last_sd_error_storage();
auto &g_generation_active = mca::sd::generation_active();
auto &g_cancel_requested = mca::sd::cancel_requested();
std::atomic<uint64_t> g_generation_sequence{0};
auto &g_active_generation_sequence = mca::sd::active_generation_sequence();
auto &g_generation_stage_mask = mca::sd::generation_stage_mask();
auto &g_observed_prediction = mca::sd::observed_prediction();
auto &g_ui_progress_callback_count = mca::sd::ui_progress_callback_count();
auto &g_ui_progress_reported_steps = mca::sd::ui_progress_reported_steps();
auto &g_ui_progress_max_step = mca::sd::ui_progress_max_step();

using mca::sd::kStageContractValidated;
using mca::sd::kStageContextReady;
using mca::sd::kStageGenerationInvoked;
using mca::sd::kStageSamplingObserved;
using mca::sd::kStageImageReturned;
using mca::sd::kStageOutputWritten;
using mca::sd::kStageContextReleased;
using mca::sd::kStageInputImageDecoded;
using mca::sd::kStageMaskImageDecoded;
using mca::sd::kStageControlImageDecoded;
using mca::sd::kStageLoraValidated;
using mca::sd::kStageTextualInversionValidated;
using mca::sd::kStageQwenTextConditionEncoded;
using mca::sd::kStageQwenTextContextReleased;
using mca::sd::kStageQwenDiffusionContextReady;

constexpr size_t kMaxInputImageBytes = 32u * 1024u * 1024u;
constexpr uint32_t kMaxInputImageSide = 8192u;
constexpr uint64_t kMaxInputImagePixels = 64u * 1024u * 1024u;
constexpr int kMaxBatchCount = 8;
using mca::sd::kMaxLoraCount;
using mca::sd::kMaxLoraBytes;
constexpr size_t kMaxTextualInversionCount = 8u;
// stable-diffusion.cpp's custom-word loader allocates a bounded 100 MiB scratch context.
// Keep the Android import and native contract limits identical to avoid a late OOM.
constexpr uint64_t kMaxTextualInversionBytes = 100ull * 1024ull * 1024ull;
using mca::sd::kMaxUpscalerBytes;
using mca::sd::kMaxUpscaleInputSide;
using mca::sd::kMaxUpscaleInputPixels;
using mca::sd::kMaxUpscaleProductOutputSide;
using mca::sd::kMaxUpscaleProductOutputPixels;
using mca::sd::kMaxUpscaleNativeOutputSide;
using mca::sd::kMaxUpscaleNativeOutputPixels;

void reset_generation_evidence() {
    mca::sd::reset_generation_evidence(PREDICTION_COUNT);
}

const char *sd_runtime_backend_label() {
    return "cpu";
}

void reset_progress_state() {
    mca::sd::reset_progress_state();
}

long long now_ms() {
    return mca::sd::progress_now_ms();
}

void set_progress_stage(const std::string &phase, const std::string &message) {
    mca::sd::set_progress_stage(phase, message);
}

void append_utf8_code_point(std::string &target, uint32_t code_point) {
    if (code_point <= 0x7fu) {
        target.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7ffu) {
        target.push_back(static_cast<char>(0xc0u | (code_point >> 6u)));
        target.push_back(static_cast<char>(0x80u | (code_point & 0x3fu)));
    } else if (code_point <= 0xffffu) {
        target.push_back(static_cast<char>(0xe0u | (code_point >> 12u)));
        target.push_back(static_cast<char>(0x80u | ((code_point >> 6u) & 0x3fu)));
        target.push_back(static_cast<char>(0x80u | (code_point & 0x3fu)));
    } else {
        target.push_back(static_cast<char>(0xf0u | (code_point >> 18u)));
        target.push_back(static_cast<char>(0x80u | ((code_point >> 12u) & 0x3fu)));
        target.push_back(static_cast<char>(0x80u | ((code_point >> 6u) & 0x3fu)));
        target.push_back(static_cast<char>(0x80u | (code_point & 0x3fu)));
    }
}

std::string jstring_to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return "";
    const jsize length = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return "";
    std::string result;
    result.reserve(static_cast<size_t>(length) * 3u);
    for (jsize index = 0; index < length; ++index) {
        uint32_t code_point = chars[index];
        if (code_point >= 0xd800u && code_point <= 0xdbffu) {
            if (index + 1 < length && chars[index + 1] >= 0xdc00u && chars[index + 1] <= 0xdfffu) {
                code_point = 0x10000u + ((code_point - 0xd800u) << 10u) +
                             (static_cast<uint32_t>(chars[++index]) - 0xdc00u);
            } else {
                code_point = 0xfffdu;
            }
        } else if (code_point >= 0xdc00u && code_point <= 0xdfffu) {
            code_point = 0xfffdu;
        }
        append_utf8_code_point(result, code_point);
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring string_to_jstring(JNIEnv *env, const std::string &value) {
    std::vector<jchar> utf16;
    utf16.reserve(value.size());
    size_t index = 0;
    while (index < value.size()) {
        const uint8_t first = static_cast<uint8_t>(value[index]);
        uint32_t code_point = 0xfffdu;
        size_t width = 1;
        if (first <= 0x7fu) {
            code_point = first;
        } else if ((first & 0xe0u) == 0xc0u && index + 1 < value.size()) {
            const uint8_t second = static_cast<uint8_t>(value[index + 1]);
            if ((second & 0xc0u) == 0x80u) {
                const uint32_t decoded = ((first & 0x1fu) << 6u) | (second & 0x3fu);
                if (decoded >= 0x80u) {
                    code_point = decoded;
                    width = 2;
                }
            }
        } else if ((first & 0xf0u) == 0xe0u && index + 2 < value.size()) {
            const uint8_t second = static_cast<uint8_t>(value[index + 1]);
            const uint8_t third = static_cast<uint8_t>(value[index + 2]);
            if ((second & 0xc0u) == 0x80u && (third & 0xc0u) == 0x80u) {
                const uint32_t decoded = ((first & 0x0fu) << 12u) |
                                         ((second & 0x3fu) << 6u) |
                                         (third & 0x3fu);
                if (decoded >= 0x800u && !(decoded >= 0xd800u && decoded <= 0xdfffu)) {
                    code_point = decoded;
                    width = 3;
                }
            }
        } else if ((first & 0xf8u) == 0xf0u && index + 3 < value.size()) {
            const uint8_t second = static_cast<uint8_t>(value[index + 1]);
            const uint8_t third = static_cast<uint8_t>(value[index + 2]);
            const uint8_t fourth = static_cast<uint8_t>(value[index + 3]);
            if ((second & 0xc0u) == 0x80u && (third & 0xc0u) == 0x80u &&
                (fourth & 0xc0u) == 0x80u) {
                const uint32_t decoded = ((first & 0x07u) << 18u) |
                                         ((second & 0x3fu) << 12u) |
                                         ((third & 0x3fu) << 6u) |
                                         (fourth & 0x3fu);
                if (decoded >= 0x10000u && decoded <= 0x10ffffu) {
                    code_point = decoded;
                    width = 4;
                }
            }
        }
        index += width;
        if (code_point <= 0xffffu) {
            utf16.push_back(static_cast<jchar>(code_point));
        } else {
            code_point -= 0x10000u;
            utf16.push_back(static_cast<jchar>(0xd800u + (code_point >> 10u)));
            utf16.push_back(static_cast<jchar>(0xdc00u + (code_point & 0x3ffu)));
        }
    }
    return env->NewString(utf16.empty() ? nullptr : utf16.data(), static_cast<jsize>(utf16.size()));
}

void set_last_error(const std::string &message) {
    g_last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, "MCA-SD", "%s", message.c_str());
}

} // namespace

namespace mca_sd_bridge_internal {

sd_ctx_t *g_ctx = nullptr;
std::string g_ctx_key;
std::string g_last_error;
void mark_generation_stage(uint32_t stage) {
    mca::sd::mark_generation_stage(stage);
}

void set_progress(const std::string &phase,
                  const std::string &message,
                  int step,
                  int steps,
                  float seconds_per_step,
                  int width,
                  int height,
                  int threads) {
    mca::sd::set_progress(
            phase, message, step, steps, seconds_per_step, width, height, threads);
}

void set_progress_component_selection(const std::string &component_selection_json) {
    mca::sd::set_progress_component_selection(component_selection_json);
}

bool file_exists(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

sd_ctx_t *ensure_context(const ComponentPaths &paths,
                         const std::vector<ContractTextualInversion> &textual_inversions,
                         const std::string &ctx_key,
                         int threads,
                         bool unload_after_stages,
                         sd_qwen_image_21_phase_t qwen_phase) {
    if (g_ctx != nullptr && g_ctx_key == ctx_key) return g_ctx;
    g_last_sd_error.clear();
    if (g_ctx != nullptr) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_ctx_key.clear();
    }

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = paths.model.empty() ? nullptr : paths.model.c_str();
    params.diffusion_model_path = paths.diffusion.empty() ? nullptr : paths.diffusion.c_str();
    params.high_noise_diffusion_model_path = paths.high_noise_diffusion.empty() ? nullptr : paths.high_noise_diffusion.c_str();
    params.vae_path = paths.vae.empty() ? nullptr : paths.vae.c_str();
    params.clip_l_path = paths.clip_l.empty() ? nullptr : paths.clip_l.c_str();
    params.clip_g_path = paths.clip_g.empty() ? nullptr : paths.clip_g.c_str();
    params.clip_vision_path = paths.clip_vision.empty() ? nullptr : paths.clip_vision.c_str();
    params.t5xxl_path = paths.t5xxl.empty() ? nullptr : paths.t5xxl.c_str();
    params.llm_path = paths.llm.empty() ? nullptr : paths.llm.c_str();
    params.llm_vision_path = paths.llm_vision.empty() ? nullptr : paths.llm_vision.c_str();
    params.embeddings_connectors_path = paths.embeddings_connectors.empty() ? nullptr : paths.embeddings_connectors.c_str();
    params.control_net_path = paths.control_net.empty() ? nullptr : paths.control_net.c_str();
    // The staged Qwen diffusion phase is text-to-image only: it never encodes
    // an init/control image, so keep only the VAE decoder resident.
    params.vae_decode_only = qwen_phase == SD_QWEN_IMAGE_21_PHASE_DIFFUSION_VAE;
    params.free_params_immediately = unload_after_stages;
    params.qwen_image_21_phase = qwen_phase;
    params.n_threads = threads;
    params.enable_mmap = true;
    params.rng_type = CPU_RNG;
    params.sampler_rng_type = CPU_RNG;
    params.lora_apply_mode = LORA_APPLY_AT_RUNTIME;
    params.offload_params_to_cpu = false;
    params.keep_clip_on_cpu = true;
    params.keep_vae_on_cpu = true;
    params.diffusion_flash_attn = true;
    params.flash_attn = true;
    params.backend = "cpu";
    params.params_backend = "cpu";
    params.max_vram = 0.0f;
    std::vector<sd_embedding_t> native_embeddings;
    native_embeddings.reserve(textual_inversions.size());
    for (const auto &artifact: textual_inversions) {
        sd_embedding_t embedding{};
        embedding.name = artifact.trigger.c_str();
        embedding.path = artifact.path.c_str();
        native_embeddings.push_back(embedding);
    }
    params.embeddings = native_embeddings.empty() ? nullptr : native_embeddings.data();
    params.embedding_count = static_cast<uint32_t>(native_embeddings.size());

    g_ctx = new_sd_ctx(&params);
    if (g_ctx == nullptr) {
        set_last_error(g_last_sd_error.empty()
                       ? "stable-diffusion.cpp failed to create context"
                       : "stable-diffusion.cpp failed to create context: " + g_last_sd_error);
        return nullptr;
    }
    if (qwen_phase != SD_QWEN_IMAGE_21_PHASE_TEXT_ENCODER &&
        !sd_ctx_supports_image_generation(g_ctx)) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        set_last_error("selected local model does not support image generation");
        return nullptr;
    }
    if (qwen_phase == SD_QWEN_IMAGE_21_PHASE_TEXT_ENCODER &&
        !sd_ctx_is_qwen_image_2_1(g_ctx)) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        set_last_error("selected text encoder is incompatible with Qwen Image 2.1");
        return nullptr;
    }
    g_ctx_key = ctx_key;
    return g_ctx;
}
// R7-C6: the optimize-off region keeps the member destructors (and their baseline weak
// exports) emitted at -O3 instead of being folded away inside the inline destructor.
#pragma clang optimize off
SdGenerateContext::~SdGenerateContext() {
    if (qwen_condition != nullptr) {
        sd_free_qwen_image_21_condition(qwen_condition);
        qwen_condition = nullptr;
    }
    if (stage_context_release_enabled && g_ctx != nullptr) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_ctx_key.clear();
    }
}
#pragma clang optimize on
} // namespace

namespace {

void sd_progress_callback(int step, int steps, float time, void *) {
    const bool generation_invoked =
            (g_generation_stage_mask.load(std::memory_order_relaxed) &
             kStageGenerationInvoked) != 0u;
    if (step > 0 && generation_invoked) {
        g_ui_progress_callback_count.fetch_add(1, std::memory_order_relaxed);
        g_ui_progress_reported_steps.store(steps, std::memory_order_relaxed);
        int observed = g_ui_progress_max_step.load(std::memory_order_relaxed);
        while (step > observed &&
               !g_ui_progress_max_step.compare_exchange_weak(
                       observed,
                       step,
                       std::memory_order_relaxed)) {
        }
        mark_generation_stage(kStageSamplingObserved);
    }
    set_progress(
            g_cancel_requested.load(std::memory_order_relaxed) ? "cancelling" : "sampling",
            g_cancel_requested.load(std::memory_order_relaxed)
            ? "cancel requested"
            : "sampling",
            step,
            steps,
            time);
    __android_log_print(ANDROID_LOG_INFO, "MCA-SD", "generation progress %d/%d %.2fs", step, steps, time);
}

bool sd_cancel_callback(void *) {
    return g_cancel_requested.load(std::memory_order_relaxed);
}

void sd_preview_callback(int step,
                         int frame_count,
                         sd_image_t *frames,
                         bool is_noisy,
                         void *data) {
    mca::sd::sd_preview_callback(step, frame_count, frames, is_noisy, data);
}

std::string generate_impl(const std::string &model_path,
                          const std::string &bundle_root,
                          const std::string &params_json,
                          const std::string &output_path) {
    try {
        mca_sd_bridge_internal::SdGenerateContext state{
                model_path,
                bundle_root,
                params_json,
                output_path,
                g_generation_sequence};
        if (auto phase_result = mca_sd_bridge_internal::sd_generate_request(state);
            !phase_result.empty()) {
            return phase_result;
        }
        if (auto phase_result = mca_sd_bridge_internal::sd_generate_context(state);
            !phase_result.empty()) {
            return phase_result;
        }
        if (auto phase_result = mca_sd_bridge_internal::sd_generate_execute(state);
            !phase_result.empty()) {
            return phase_result;
        }
        return mca_sd_bridge_internal::sd_generate_publish(state);
    } catch (const ContractError &error) {
        set_progress("failed", error.what());
        return contract_failure_json(error).dump();
    } catch (const json::exception &error) {
        set_progress("failed", error.what());
        return contract_failure_json(ContractError(
                "IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID",
                "params",
                error.what())).dump();
    } catch (const std::exception &error) {
        set_progress("failed", error.what());
        return runtime_failure("NATIVE_BRIDGE_FAILURE", error.what()).dump();
    }
}

std::string upscale_impl(const std::string &raw_upscaler_path,
                         const std::string &raw_upscaler_root,
                         const std::string &raw_input_path,
                         const std::string &raw_params,
                         const std::string &raw_output_path) {
    return mca::sd::execute_sd_upscale(
            raw_upscaler_path,
            raw_upscaler_root,
            raw_input_path,
            raw_params,
            raw_output_path,
            &g_generation_sequence);
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_generate(
        JNIEnv *env,
        jobject,
        jstring model_path,
        jstring bundle_root,
        jstring params_json,
        jstring output_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancel_requested.store(false, std::memory_order_relaxed);
    g_generation_active.store(true, std::memory_order_relaxed);
    reset_generation_evidence();
    reset_progress_state();
    set_progress("initializing", "starting local image generation");
    set_progress_component_selection("{}");
    sd_set_log_callback(sd_log_callback, nullptr);
    sd_set_progress_callback(sd_progress_callback, nullptr);
    sd_set_cancel_callback(sd_cancel_callback, nullptr);
    std::string result = generate_impl(
            jstring_to_string(env, model_path),
            jstring_to_string(env, bundle_root),
            jstring_to_string(env, params_json),
            jstring_to_string(env, output_path));
    if (g_ctx != nullptr) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_ctx_key.clear();
    }
    mark_generation_stage(kStageContextReleased);
    try {
        json result_json = json::parse(result);
        result_json["contextReleased"] = true;
        const uint64_t sequence =
                g_active_generation_sequence.load(std::memory_order_relaxed);
        if (sequence > 0) result_json["nativeGenerationSequence"] = sequence;
        result_json["nativeStageMask"] =
                g_generation_stage_mask.load(std::memory_order_relaxed);
        result_json["nativeDetailStageMask"] =
                static_cast<uint64_t>(g_generation_stage_mask.load(std::memory_order_relaxed));
        result = result_json.dump();
    } catch (const json::exception &) {
        if (!result.empty() && result.back() == '}') {
            result.insert(result.size() - 1, ",\"contextReleased\":true");
        }
    }
    g_generation_active.store(false, std::memory_order_relaxed);
    sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, false, false, nullptr);
    sd_set_cancel_callback(nullptr, nullptr);
    return string_to_jstring(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_upscale(
        JNIEnv *env,
        jobject,
        jstring upscaler_path,
        jstring upscaler_root,
        jstring input_path,
        jstring params_json,
        jstring output_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancel_requested.store(false, std::memory_order_relaxed);
    g_generation_active.store(true, std::memory_order_relaxed);
    reset_generation_evidence();
    reset_progress_state();
    set_progress("initializing", "starting local image upscale");
    set_progress_component_selection("{}");
    sd_set_log_callback(sd_log_callback, nullptr);
    sd_set_cancel_callback(sd_cancel_callback, nullptr);
    std::string result = upscale_impl(
            jstring_to_string(env, upscaler_path),
            jstring_to_string(env, upscaler_root),
            jstring_to_string(env, input_path),
            jstring_to_string(env, params_json),
            jstring_to_string(env, output_path));
    mark_generation_stage(kStageContextReleased);
    try {
        json result_json = json::parse(result);
        result_json["contextReleased"] = true;
        const uint64_t sequence =
                g_active_generation_sequence.load(std::memory_order_relaxed);
        if (sequence > 0u) result_json["nativeGenerationSequence"] = sequence;
        result_json["nativeStageMask"] =
                g_generation_stage_mask.load(std::memory_order_relaxed);
        result_json["nativeDetailStageMask"] =
                static_cast<uint64_t>(g_generation_stage_mask.load(std::memory_order_relaxed));
        result = result_json.dump();
    } catch (const json::exception &) {
        if (!result.empty() && result.back() == '}') {
            result.insert(result.size() - 1, ",\"contextReleased\":true");
        }
    }
    g_generation_active.store(false, std::memory_order_relaxed);
    sd_set_cancel_callback(nullptr, nullptr);
    return string_to_jstring(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_getSystemInfo(JNIEnv *env, jobject) {
    return string_to_jstring(env, sd_get_system_info());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_getProgress(JNIEnv *env, jobject) {
    return string_to_jstring(env, progress_to_json());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_getNativeConfig(JNIEnv *env, jobject) {
    return string_to_jstring(env, runtime_config_json());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_measurePromptTokens(
        JNIEnv *env,
        jobject,
        jstring tokenizer_kind,
        jstring prompt,
        jint max_tokens) {
    if (env == nullptr) return nullptr;
    const std::string kind = jstring_to_string(env, tokenizer_kind);
    if (kind != "CLIP") {
        return string_to_jstring(env, json({
                {"ok", false},
                {"backend", "SDCPP_NATIVE"},
                {"error", "The selected stable-diffusion.cpp tokenizer has no exact measurement adapter"}
        }).dump());
    }
    return string_to_jstring(
            env,
            measure_clip_prompt_tokens(
                    jstring_to_string(env, prompt),
                    static_cast<int>(max_tokens)).dump());
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_cancel(JNIEnv *, jobject) {
    g_cancel_requested.store(true, std::memory_order_relaxed);
    set_progress("cancelling", "cancel requested");
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_shutdown(JNIEnv *, jobject) {
    g_cancel_requested.store(true, std::memory_order_relaxed);
    sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, false, false, nullptr);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        g_ctx_key.clear();
    }
    mark_generation_stage(kStageContextReleased);
    g_generation_active.store(false, std::memory_order_relaxed);
    set_progress("idle", "native context released");
}
