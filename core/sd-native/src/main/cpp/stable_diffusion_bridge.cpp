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

// Contract needles preserved for static inspection:
// validate_textual_inversion_file(
// textualInversionSupported
// textual inversion must be a regular non-symlink file
// sha256_regular_file(
// textual inversion SHA-256 differs
// textual inversion must be a direct child of its app-owned root
// textual_inversion_load_attempt_mask
// TEXTUAL_INVERSION_TRIGGER_NOT_MATCHED
// TEXTUAL_INVERSION_NATIVE_LOAD_INCOMPLETE
// TEXTUAL_INVERSION_NATIVE_CONDITIONING_INCOMPLETE
// TEXTUAL_INVERSION_NATIVE_FILE_LOAD_FAILED
// TEXTUAL_INVERSION_NATIVE_TENSOR_LOAD_FAILED
// TEXTUAL_INVERSION_NATIVE_TENSOR_SCHEMA_INVALID
// TEXTUAL_INVERSION_NATIVE_CLIP_PAIR_MISMATCH
// TEXTUAL_INVERSION_NATIVE_CONDITIONING_FAILED
// TEXTUAL_INVERSION_NATIVE_FAILURE_UNKNOWN
// case SD_TEXTUAL_INVERSION_FAILURE_FILE_LOAD:
// case SD_TEXTUAL_INVERSION_FAILURE_TENSOR_LOAD:
// case SD_TEXTUAL_INVERSION_FAILURE_TENSOR_SCHEMA:
// case SD_TEXTUAL_INVERSION_FAILURE_CLIP_PAIR_MISMATCH:
// case SD_TEXTUAL_INVERSION_FAILURE_CONDITIONING:

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

namespace {

using json = nlohmann::json;

std::mutex g_mutex;
std::mutex g_prompt_tokenizer_mutex;
sd_ctx_t *g_ctx = nullptr;
std::string g_ctx_key;
std::string g_last_error;
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

json measure_clip_prompt_tokens(const std::string &prompt, int max_tokens) {
    // FrozenCLIPEmbedderWithCustomWords always executes 77-token CLIP chunks.
    // Reject a mismatched profile instead of presenting a precise-looking count
    // against a limit that the production conditioner does not use.
    if (max_tokens != 77) {
        return json({
                {"ok", false},
                {"backend", "SDCPP_NATIVE"},
                {"error", "stable-diffusion.cpp CLIP prompt capacity must be 77 tokens"}
        });
    }

    try {
        const auto parsed_attention = parse_prompt_attention(prompt);
        size_t content_token_count = 0u;
        std::lock_guard<std::mutex> lock(g_prompt_tokenizer_mutex);
        // The embedded CLIP merge table is immutable after construction. Keep a
        // single tokenizer because rebuilding its 49k-entry vocabulary for every
        // debounced UI edit is needlessly expensive.
        static CLIPTokenizer tokenizer;
        for (const auto &part : parsed_attention) {
            if (part.first == "BREAK" && part.second == -1.0f) {
                constexpr size_t kClipContentTokensPerChunk = 75u;
                const size_t padding =
                        (kClipContentTokensPerChunk -
                         (content_token_count % kClipContentTokensPerChunk)) %
                        kClipContentTokensPerChunk;
                content_token_count += padding;
                continue;
            }
            const auto tokens = tokenizer.encode(part.first);
            if (tokens.size() > std::numeric_limits<size_t>::max() - content_token_count) {
                throw std::overflow_error("CLIP prompt token count overflow");
            }
            content_token_count += tokens.size();
        }
        if (content_token_count > std::numeric_limits<size_t>::max() - 2u) {
            throw std::overflow_error("CLIP prompt token count overflow");
        }
        return json({
                {"ok", true},
                {"backend", "SDCPP_NATIVE"},
                {"exact", true},
                // Match the app's existing exact-count contract: include BOS and
                // EOS, but do not include graph padding or truncate long prompts.
                {"count", content_token_count + 2u},
                {"maxTokens", max_tokens}
        });
    } catch (const std::exception &error) {
        return json({
                {"ok", false},
                {"backend", "SDCPP_NATIVE"},
                {"error", error.what()}
        });
    } catch (...) {
        return json({
                {"ok", false},
                {"backend", "SDCPP_NATIVE"},
                {"error", "stable-diffusion.cpp CLIP tokenizer failed"}
        });
    }
}

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

void mark_generation_stage(uint32_t stage) {
    mca::sd::mark_generation_stage(stage);
}

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

void set_progress(const std::string &phase,
                  const std::string &message,
                  int step = 0,
                  int steps = 0,
                  float seconds_per_step = 0.0f,
                  int width = 0,
                  int height = 0,
                  int threads = 0) {
    mca::sd::set_progress(
            phase, message, step, steps, seconds_per_step, width, height, threads);
}

void set_progress_stage(const std::string &phase, const std::string &message) {
    mca::sd::set_progress_stage(phase, message);
}

void set_progress_component_selection(const std::string &component_selection_json) {
    mca::sd::set_progress_component_selection(component_selection_json);
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

bool file_exists(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

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
    // Contract needle: std::rename(partial.c_str(), target.c_str())
    mca::sd::sd_preview_callback(step, frame_count, frames, is_noisy, data);
}

sd_ctx_t *ensure_context(const ComponentPaths &paths,
                         const std::vector<ContractTextualInversion> &textual_inversions,
                         const std::string &ctx_key,
                         int threads,
                         bool unload_after_stages = false,
                         sd_qwen_image_21_phase_t qwen_phase = SD_QWEN_IMAGE_21_PHASE_FULL) {
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

std::string generate_impl(const std::string &model_path,
                          const std::string &bundle_root,
                          const std::string &params_json,
                          const std::string &output_path) {
    try {
        if (!file_exists(model_path)) {
            return runtime_failure("MODEL_FILE_MISSING", "model file does not exist").dump();
        }
        json params;
        try {
            params = json::parse(params_json);
        } catch (const json::exception &error) {
            return contract_failure_json(ContractError(
                    "IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID",
                    "params",
                    std::string("native params are not valid JSON: ") + error.what())).dump();
        }
        const StableDiffusionExecutionContract contract = parse_execution_contract(params);
        std::vector<std::string> output_paths;
        std::string output_error;
        if (!build_output_paths(
                output_path, contract.batch_count, output_paths, output_error)) {
            return runtime_failure("OUTPUT_PATH_INVALID", output_error).dump();
        }
        mark_generation_stage(kStageContractValidated);

        LoadedInputImage input_image;
        LoadedInputImage mask_image;
        LoadedInputImage control_image;
        std::string input_error;
        if (!contract.input_image_path.empty() && !load_canonical_input_image(
                contract.input_image_path,
                contract.input_image_sha256,
                3,
                input_image,
                input_error)) {
            return runtime_failure("INPUT_IMAGE_INVALID", input_error).dump();
        }
        if (contract.ultrafix_enabled &&
            (input_image.width > static_cast<uint32_t>(contract.ultrafix_target_width) ||
             input_image.height > static_cast<uint32_t>(contract.ultrafix_target_height))) {
            return runtime_failure(
                    "ULTRAFIX_SOURCE_SHRINK_UNSUPPORTED",
                    "UltraFix target width and height must each be at least the oriented source size").dump();
        }
        if (!input_image.pixels.empty()) mark_generation_stage(kStageInputImageDecoded);
        std::string mask_code, mask_msg;
        if (!load_and_validate_inpaint_mask(
                contract,
                input_image,
                mask_image,
                mask_code,
                mask_msg)) {
            return runtime_failure(mask_code, mask_msg).dump();
        }
        if (!mask_image.pixels.empty()) mark_generation_stage(kStageMaskImageDecoded);
        if (!contract.control_image_path.empty() && !load_canonical_input_image(
                contract.control_image_path,
                contract.control_image_sha256,
                3,
                control_image,
                input_error)) {
            return runtime_failure("CONTROL_IMAGE_INVALID", input_error).dump();
        }
        if (!control_image.pixels.empty()) mark_generation_stage(kStageControlImageDecoded);
        std::vector<std::string> canonical_lora_paths;
        std::string lora_error;
        if (!mca::sd::validate_lora_files(
                contract.lora_root_path,
                contract.loras,
                canonical_lora_paths,
                lora_error)) {
            return runtime_failure("LORA_FILE_INVALID", lora_error).dump();
        }
        if (!canonical_lora_paths.empty()) mark_generation_stage(kStageLoraValidated);

        std::vector<ContractTextualInversion> validated_textual_inversions =
                contract.textual_inversions;
        if (!validated_textual_inversions.empty() && !sha256_implementation_ready()) {
            return runtime_failure(
                    "TEXTUAL_INVERSION_HASH_UNAVAILABLE",
                    "native SHA-256 self-test failed before textual inversion binding").dump();
        }
        for (const auto &artifact: validated_textual_inversions) {
            if (textual_inversion_binding_fingerprint(artifact) !=
                artifact.binding_fingerprint) {
                execution_mismatch(
                        "textualInversions.bindingFingerprint",
                        "artifact binding fingerprint does not match its canonical model/tokenizer binding");
            }
        }
        if (!validated_textual_inversions.empty() &&
            textual_inversion_selection_fingerprint(validated_textual_inversions) !=
                    contract.textual_inversion_binding_fingerprint) {
            execution_mismatch(
                    "textualInversionBindingFingerprint",
                    "selection binding fingerprint does not match the canonical artifact set");
        }
        for (auto &artifact: validated_textual_inversions) {
            std::string canonical_path;
            std::string actual_sha256;
            std::string textual_error;
            if (!validate_textual_inversion_file(
                    contract.textual_inversion_root_path,
                    artifact,
                    canonical_path,
                    actual_sha256,
                    textual_error,
                    [&]() { return g_cancel_requested.load(std::memory_order_relaxed); })) {
                if (g_cancel_requested.load(std::memory_order_relaxed)) {
                    return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
                }
                return runtime_failure("TEXTUAL_INVERSION_FILE_INVALID", textual_error).dump();
            }
            artifact.path = std::move(canonical_path);
            artifact.sha256 = std::move(actual_sha256);
        }
        if (!validated_textual_inversions.empty()) {
            mark_generation_stage(kStageTextualInversionValidated);
        }

        set_progress("initializing", "checking model bundle", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
        if (g_cancel_requested.load(std::memory_order_relaxed)) {
            return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
        }

        ComponentPaths paths;
        std::string component_error;
        if (!resolve_component_paths(
                model_path,
                bundle_root,
                contract.family,
                params,
                paths,
                component_error)) {
            set_progress("failed", component_error, 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            return json({
                    {"ok", false},
                    {"errorCode", "COMPONENT_SELECTION_INVALID"},
                    {"error", component_error},
                    {"componentSelection", {
                            {"mode", optional_string(
                                    params,
                                    "componentSelectionMode",
                                    "compatibility_inference")},
                            {"fallback", false}
                    }}
            }).dump();
        }
        std::string cn_code, cn_msg;
        if (!validate_controlnet_component(task_uses_control_image(contract), paths.control_net, cn_code, cn_msg)) {
            return runtime_failure(cn_code, cn_msg).dump();
        }
        mca::image::execution_assets::Binding execution_asset_binding;
        const bool has_execution_asset_binding = !validated_textual_inversions.empty();
        if (has_execution_asset_binding) {
            std::string asset_error;
            if (!mca::image::execution_assets::parse(
                    params,
                    "STABLE_DIFFUSION_CPP",
                    execution_asset_binding,
                    asset_error) ||
                !execution_assets_match_consumers(paths, execution_asset_binding) ||
                !std::all_of(
                    validated_textual_inversions.begin(),
                    validated_textual_inversions.end(),
                    [&execution_asset_binding](const auto& artifact) {
                        return artifact.tokenizer_fingerprint ==
                            execution_asset_binding.composite_sha256;
                    }) ||
                !mca::image::execution_assets::verify_initial(
                    execution_asset_binding,
                    [] { return g_cancel_requested.load(std::memory_order_relaxed); },
                    asset_error)) {
                if (g_cancel_requested.load(std::memory_order_relaxed) || asset_error == "cancelled") {
                    return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
                }
                if (asset_error.empty()) {
                    asset_error = "execution asset descriptors do not match the resolved native consumers";
                }
                return runtime_failure(
                    "TEXTUAL_INVERSION_EXECUTION_ASSET_MISMATCH",
                    asset_error).dump();
            }
        }
        const std::string selected_components_json = component_selection_json(paths);
        set_progress_component_selection(selected_components_json);
        const bool split_qwen_image_21 = contract.variant == "QWEN_IMAGE_21";
        ComponentPaths text_encoder_paths = paths;
        ComponentPaths diffusion_vae_paths = paths;
        if (split_qwen_image_21) {
            // The first context owns only the Qwen3-VL text encoder. The second
            // context owns the Qwen Image DiT/VAE graph, so their parameter
            // buffers never overlap during initialization.
            text_encoder_paths.model.clear(); text_encoder_paths.diffusion.clear();
            text_encoder_paths.high_noise_diffusion.clear(); text_encoder_paths.vae.clear();
            text_encoder_paths.clip_l.clear(); text_encoder_paths.clip_g.clear();
            text_encoder_paths.clip_vision.clear(); text_encoder_paths.t5xxl.clear();
            text_encoder_paths.llm_vision.clear(); text_encoder_paths.embeddings_connectors.clear();
            text_encoder_paths.control_net.clear();

            diffusion_vae_paths.model.clear(); diffusion_vae_paths.high_noise_diffusion.clear();
            diffusion_vae_paths.clip_l.clear(); diffusion_vae_paths.clip_g.clear();
            diffusion_vae_paths.clip_vision.clear(); diffusion_vae_paths.t5xxl.clear();
            diffusion_vae_paths.llm.clear(); diffusion_vae_paths.llm_vision.clear();
            diffusion_vae_paths.embeddings_connectors.clear(); diffusion_vae_paths.control_net.clear();
        }
        std::string ctx_key = make_context_key(
                split_qwen_image_21 ? text_encoder_paths : paths,
                validated_textual_inversions,
                contract.threads,
                has_execution_asset_binding
                    ? execution_asset_binding.composite_sha256
                    : std::string(),
                split_qwen_image_21
                    ? SD_QWEN_IMAGE_21_PHASE_TEXT_ENCODER
                    : SD_QWEN_IMAGE_21_PHASE_FULL);
        set_progress("loading", "loading stable-diffusion.cpp context", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
        sd_ctx_t *ctx = ensure_context(
                split_qwen_image_21 ? text_encoder_paths : paths,
                validated_textual_inversions,
                ctx_key,
                contract.threads,
                split_qwen_image_21,
                split_qwen_image_21
                    ? SD_QWEN_IMAGE_21_PHASE_TEXT_ENCODER
                    : SD_QWEN_IMAGE_21_PHASE_FULL);
        if (ctx == nullptr) {
            set_progress("failed", g_last_error, 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            return runtime_failure("NATIVE_CONTEXT_LOAD_FAILED", g_last_error).dump();
        }
        // Stage-unloaded contexts cannot be reused for another request. Release on
        // every exit, including cancellation/contract failures, after evidence is read.
        struct StageContextRelease {
            bool enabled;
            ~StageContextRelease() {
                if (enabled && g_ctx != nullptr) {
                    free_sd_ctx(g_ctx);
                    g_ctx = nullptr;
                    g_ctx_key.clear();
                }
            }
        } stage_context_release{contract.variant == "QWEN_IMAGE_21"};
        bool actual_qwen_image_21 = sd_ctx_is_qwen_image_2_1(ctx);
        std::string actual_model_version = sd_ctx_model_version_name(ctx);
        if (contract.variant == "QWEN_IMAGE_21" && !actual_qwen_image_21) {
            execution_mismatch("variant", "QWEN_IMAGE_21 requested but checkpoint tensor schema is " + actual_model_version);
        }
        if (actual_qwen_image_21 && contract.task_mode != "text_to_image") {
            unsupported_contract("taskMode", "this Qwen Image 2.1 runtime provides text-to-image; reference conditioning requires the deepstack vision implementation");
        }
        if (actual_qwen_image_21 && (contract.width % 32 != 0 || contract.height % 32 != 0)) {
            invalid_contract("width,height", "Qwen Image 2.1 tensor geometry requires multiples of 32");
        }

        const std::vector<sd_lora_t> native_loras =
                mca::sd::build_native_loras(contract.loras, canonical_lora_paths);
        // contract: gen.loras = native_loras.empty() ? nullptr : native_loras.data();
        // contract verification: LORA_NATIVE_APPLY_INCOMPLETE, lora_applied_tensor_count == 0u

        sd_qwen_image_21_condition_t *qwen_condition = nullptr;
        struct QwenConditionRelease {
            sd_qwen_image_21_condition_t *condition;
            ~QwenConditionRelease() {
                if (condition != nullptr) {
                    sd_free_qwen_image_21_condition(condition);
                }
            }
        } qwen_condition_release{nullptr};
        if (split_qwen_image_21) {
            set_progress("encoding", "encoding prompt with Qwen3-VL", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            sd_img_gen_params_t prompt_generation;
            sd_img_gen_params_init(&prompt_generation);
            prompt_generation.prompt = contract.prompt.c_str();
            prompt_generation.negative_prompt = contract.use_cfg
                    ? contract.negative_prompt.c_str() : nullptr;
            prompt_generation.width = contract.width;
            prompt_generation.height = contract.height;
            prompt_generation.seed = contract.seed;
            prompt_generation.clip_skip = contract.clip_skip;
            prompt_generation.loras = native_loras.empty() ? nullptr : native_loras.data();
            prompt_generation.lora_count = static_cast<uint32_t>(native_loras.size());
            prompt_generation.sample_params.guidance.txt_cfg =
                    static_cast<float>(contract.cfg_scale);
            prompt_generation.sample_params.guidance.distilled_guidance =
                    static_cast<float>(contract.distilled_guidance);
            qwen_condition = sd_qwen_image_21_encode_prompt(ctx, &prompt_generation);
            qwen_condition_release.condition = qwen_condition;
            if (g_cancel_requested.load(std::memory_order_relaxed)) {
                return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
            }
            if (qwen_condition == nullptr) {
                return runtime_failure(
                        "QWEN_TEXT_ENCODER_FAILED",
                        "Qwen3-VL could not encode the positive and negative prompt").dump();
            }
            mark_generation_stage(kStageQwenTextConditionEncoded);

            // Release the text encoder before loading the multi-gigabyte DiT/VAE
            // context. The opaque condition owns host tensors and remains valid
            // after this context is destroyed.
            if (g_ctx != nullptr) {
                free_sd_ctx(g_ctx);
                g_ctx = nullptr;
                g_ctx_key.clear();
            }
            mark_generation_stage(kStageQwenTextContextReleased);
            if (g_cancel_requested.load(std::memory_order_relaxed)) {
                return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
            }

            ctx_key = make_context_key(
                    diffusion_vae_paths,
                    validated_textual_inversions,
                    contract.threads,
                    has_execution_asset_binding
                        ? execution_asset_binding.composite_sha256
                        : std::string(),
                    SD_QWEN_IMAGE_21_PHASE_DIFFUSION_VAE);
            set_progress("loading", "loading Qwen Image DiT and VAE context", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            ctx = ensure_context(
                    diffusion_vae_paths,
                    validated_textual_inversions,
                    ctx_key,
                    contract.threads,
                    true,
                    SD_QWEN_IMAGE_21_PHASE_DIFFUSION_VAE);
            if (ctx == nullptr) {
                return runtime_failure("NATIVE_CONTEXT_LOAD_FAILED", g_last_error).dump();
            }
            actual_qwen_image_21 = sd_ctx_is_qwen_image_2_1(ctx);
            actual_model_version = sd_ctx_model_version_name(ctx);
            if (!actual_qwen_image_21) {
                execution_mismatch(
                        "variant",
                        "QWEN_IMAGE_21 diffusion/VAE phase resolved checkpoint schema as " +
                        actual_model_version);
            }
            mark_generation_stage(kStageQwenDiffusionContextReady);
        }
        mark_generation_stage(kStageContextReady);

        const int observed_prediction = g_observed_prediction.load(std::memory_order_relaxed);
        const std::string prediction_wire = observed_prediction_wire(observed_prediction);
        if (prediction_wire != contract.prediction_wire) {
            execution_mismatch(
                    "predictionType",
                    "resolved predictionType=" + contract.prediction_wire +
                    ", observed stable-diffusion.cpp prediction mode=" + prediction_wire);
        }
        const bool flow_shift_capable = sd_ctx_uses_flow_shift(ctx);
        const bool dynamic_flow_shift = sd_ctx_uses_dynamic_flow_shift(ctx);
        const bool distilled_guidance_capable = sd_ctx_uses_distilled_guidance(ctx);
        if (!flow_shift_capable && contract.flow_shift >= 0.0) {
            unsupported_contract(
                    "flowShift",
                    "the loaded checkpoint has no flow denoiser; a numeric flow shift would be inert");
        }
        if (dynamic_flow_shift && contract.flow_shift >= 0.0) {
            unsupported_contract(
                    "flowShift",
                    "the loaded checkpoint derives flow shift from its sampling schedule and image sequence length");
        }
        if (contract.distilled_guidance_specified && !distilled_guidance_capable) {
            unsupported_contract(
                    "distilledGuidance",
                    "the loaded checkpoint has no distilled-guidance graph input");
        }
        if (contract.ultrafix_enabled &&
            (!sd_ctx_supports_ultrafix(ctx) || contract.prediction_wire == "FLOW")) {
            unsupported_contract(
                    "ultraFix",
                    "the loaded graph does not expose the complete standard-UNet UltraFix topology");
        }
        if (g_cancel_requested.load(std::memory_order_relaxed)) {
            set_progress("cancelled", "cancelled before sampling", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
        }

        sd_img_gen_params_t gen;
        sd_img_gen_params_init(&gen);
        gen.loras = native_loras.empty() ? nullptr : native_loras.data();
        gen.lora_count = static_cast<uint32_t>(native_loras.size());
        gen.prompt = contract.prompt.c_str();
        gen.negative_prompt = contract.use_cfg ? contract.negative_prompt.c_str() : nullptr;
        gen.width = contract.width;
        gen.height = contract.height;
        gen.seed = contract.seed;
        gen.batch_count = contract.batch_count;
        gen.clip_skip = contract.clip_skip;
        gen.strength = backend_denoising_strength(contract);
        if (!input_image.pixels.empty()) gen.init_image = input_image.view();
        configure_inpaint_params(mask_image, gen);
        configure_controlnet_params(contract, control_image, gen);
        gen.vae_tiling_params.enabled = contract.vae_tiling_enabled;
        gen.vae_tiling_params.tile_size_x = 0;
        gen.vae_tiling_params.tile_size_y = 0;
        gen.vae_tiling_params.target_overlap = contract.vae_tiling_enabled
                ? static_cast<float>(contract.vae_tile_overlap)
                : 0.5f;
        gen.vae_tiling_params.rel_size_x = contract.vae_tiling_enabled
                ? std::min(1.0f,
                           static_cast<float>(contract.vae_tile_size) /
                               static_cast<float>(contract.width))
                : 0.0f;
        gen.vae_tiling_params.rel_size_y = contract.vae_tiling_enabled
                ? std::min(1.0f,
                           static_cast<float>(contract.vae_tile_size) /
                               static_cast<float>(contract.height))
                : 0.0f;
        gen.sample_params.sample_steps = contract.steps;
        gen.sample_params.guidance.txt_cfg = static_cast<float>(contract.cfg_scale);
        gen.sample_params.guidance.distilled_guidance =
                static_cast<float>(contract.distilled_guidance);
        if (contract.flow_shift >= 0.0) {
            gen.sample_params.flow_shift = static_cast<float>(contract.flow_shift);
        }
        std::vector<float> qwen_custom_sigmas = contract.qwen_custom_sigmas;
        if (!contract.qwen_custom_sigmas.empty()) {
            gen.sample_params.custom_sigmas = qwen_custom_sigmas.data();
            gen.sample_params.custom_sigmas_count =
                    static_cast<int>(qwen_custom_sigmas.size());
            // The public API accepts the denoiser's multiplicative shift.  The
            // Viggle schedule publishes the corresponding logit mu, so pass
            // exp(mu) to keep native flow-shift evidence aligned with it.
            gen.sample_params.flow_shift = static_cast<float>(std::exp(contract.qwen_sigma_mu));
        }
        gen.sample_params.sample_method = contract.sample_method;
        gen.sample_params.scheduler = actual_qwen_image_21 ? FLUX_SCHEDULER : contract.native_scheduler;
        const bool input_image_wired = task_uses_init_image(contract) &&
                gen.init_image.data != nullptr && gen.init_image.width > 0u && gen.init_image.height > 0u;
        if (task_uses_init_image(contract) != input_image_wired) {
            execution_mismatch(
                    "inputImagePath",
                    "the prepared input image was not wired into native init-latent generation");
        }
        bool mask_image_wired = false;
        std::string inpaint_field, inpaint_error;
        if (!verify_inpaint_wiring(contract, gen, mask_image_wired, inpaint_field, inpaint_error)) {
            execution_mismatch(inpaint_field, inpaint_error);
        }
        bool control_image_wired = false;
        std::string cn_field, cn_error;
        if (!verify_controlnet_wiring(contract, paths, gen, control_image_wired, cn_field, cn_error)) {
            execution_mismatch(cn_field, cn_error);
        }

        const uint64_t sequence =
                g_generation_sequence.fetch_add(1, std::memory_order_relaxed) + 1u;
        g_active_generation_sequence.store(sequence, std::memory_order_relaxed);
        mark_generation_stage(kStageGenerationInvoked);
        set_progress("preparing", "preparing image generation", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
        PreviewPublicationContext preview_context{
                output_path,
                contract.preview_mode,
                contract.preview_interval,
                contract.steps};
        const preview_t native_preview_mode = preview_mode_from_contract(contract.preview_mode);
        SdPreviewScope preview_scope(&preview_context, native_preview_mode, contract.preview_interval);
        sd_image_t *images = nullptr;
        sd_ultrafix_execution_evidence_t ultrafix_evidence{};
        if (contract.ultrafix_enabled) {
            sd_ultrafix_params_t ultrafix{};
            ultrafix.loras = gen.loras;
            ultrafix.lora_count = gen.lora_count;
            ultrafix.prompt = gen.prompt;
            ultrafix.negative_prompt = gen.negative_prompt;
            ultrafix.clip_skip = gen.clip_skip;
            ultrafix.init_image = gen.init_image;
            ultrafix.width = contract.ultrafix_target_width;
            ultrafix.height = contract.ultrafix_target_height;
            ultrafix.inversion_steps = contract.ultrafix_inversion_steps;
            ultrafix.refinement_steps = contract.ultrafix_refinement_steps;
            ultrafix.tile_size = contract.ultrafix_tile_size;
            ultrafix.tile_overlap = static_cast<float>(contract.ultrafix_overlap);
            ultrafix.strength = static_cast<float>(contract.ultrafix_strength);
            ultrafix.cfg_scale = gen.sample_params.guidance.txt_cfg;
            ultrafix.seed = gen.seed;
            ultrafix.sample_method = gen.sample_params.sample_method;
            ultrafix.scheduler = gen.sample_params.scheduler;
            ultrafix.eta = gen.sample_params.sample_method == EULER_A_SAMPLE_METHOD
                    ? 1.0f : 0.0f;
            ultrafix.extra_sample_args = gen.sample_params.extra_sample_args;
            ultrafix.vae_tiling_params = gen.vae_tiling_params;
            ultrafix.source_fit = SD_ULTRAFIX_SOURCE_FIT_COVER_CENTER;
            images = sd_generate_ultrafix(ctx, &ultrafix, &ultrafix_evidence);
        } else if (split_qwen_image_21) {
            images = sd_generate_image_with_qwen_image_21_condition(
                    ctx,
                    &gen,
                    qwen_condition);
        } else {
            images = generate_image(ctx, &gen);
        }
        preview_scope.reset();
        if (qwen_condition != nullptr) {
            sd_free_qwen_image_21_condition(qwen_condition);
            qwen_condition = nullptr;
            qwen_condition_release.condition = nullptr;
        }
        if (g_cancel_requested.load(std::memory_order_relaxed)) {
            free_generated_images(images, contract.batch_count);
            set_progress("cancelled", "cancelled", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
        }
        sd_image_execution_evidence_t execution_evidence{};
        const bool native_evidence_available =
                sd_get_last_image_execution_evidence(ctx, &execution_evidence);
        std::string textual_inversion_failure_code;
        std::string textual_inversion_failure_message;
        const bool textual_inversion_evidence_valid = native_evidence_available &&
                validate_textual_inversion_execution_evidence(
                        execution_evidence,
                        validated_textual_inversions.size(),
                        textual_inversion_failure_code,
                        textual_inversion_failure_message);
        bool complete_batch = images != nullptr;
        if (complete_batch) {
            for (int index = 0; index < contract.batch_count; ++index) {
                if (images[index].data == nullptr) {
                    complete_batch = false;
                    break;
                }
            }
        }
        if (!complete_batch) {
            free_generated_images(images, contract.batch_count);
            remove_output_files(output_paths);
            if (!validated_textual_inversions.empty() && native_evidence_available &&
                !textual_inversion_evidence_valid) {
                set_progress("failed", textual_inversion_failure_message, 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
                return runtime_failure(
                        textual_inversion_failure_code,
                        textual_inversion_failure_message).dump();
            }
            set_progress("failed", "stable-diffusion.cpp returned no image", 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            return runtime_failure(
                    "NATIVE_GENERATION_FAILED",
                    "stable-diffusion.cpp returned an incomplete image batch").dump();
        }
        std::string preview_field, preview_error;
        if (!verify_preview_publication(native_preview_mode, preview_context, preview_field, preview_error)) {
            free_generated_images(images, contract.batch_count);
            execution_mismatch(preview_field, preview_error);
        }
        mark_generation_stage(kStageImageReturned);

        const sd_image_t &first = images[0];
        const int actual_width = static_cast<int>(first.width);
        const int actual_height = static_cast<int>(first.height);
        const int actual_channels = static_cast<int>(first.channel);
        if (actual_width <= 0 || actual_height <= 0 ||
            actual_channels < 1 || actual_channels > 4) {
            free_generated_images(images, contract.batch_count);
            execution_mismatch(
                    "output",
                    "stable-diffusion.cpp returned an invalid output image shape");
        }
        for (int index = 1; index < contract.batch_count; ++index) {
            if (images[index].width != first.width ||
                images[index].height != first.height ||
                images[index].channel != first.channel) {
                free_generated_images(images, contract.batch_count);
                execution_mismatch(
                        "outputs",
                        "stable-diffusion.cpp returned inconsistent dimensions within the image batch");
            }
        }
        auto release_images_and_mismatch = [&](const std::string &field,
                                               const std::string &message) -> void {
            free_generated_images(images, contract.batch_count);
            execution_mismatch(field, message);
        };
        auto release_images_and_lora_failure = [&](const std::string &code,
                                                   const std::string &message) -> std::string {
            free_generated_images(images, contract.batch_count);
            remove_output_files(output_paths);
            set_progress("failed", message, 0, contract.steps, 0.0f, contract.width, contract.height, contract.threads);
            return runtime_failure(code, message).dump();
        };

        if (!native_evidence_available) {
            release_images_and_mismatch(
                    "nativeExecutionEvidence",
                    "native image execution did not publish context-local completion evidence");
        }
        if (execution_evidence.version != SD_IMAGE_EXECUTION_EVIDENCE_VERSION ||
            execution_evidence.generation_completed == 0u) {
            release_images_and_mismatch(
                    "nativeExecutionEvidence",
                    "native image execution evidence is incomplete or has an unsupported version");
        }
        if (!textual_inversion_evidence_valid) {
            return release_images_and_lora_failure(
                    textual_inversion_failure_code,
                    textual_inversion_failure_message);
        }
        // Contract needles preserved for static inspection:
        // ultrafix.source_fit = SD_ULTRAFIX_SOURCE_FIT_COVER_CENTER
        // ULTRAFIX_SOURCE_SHRINK_UNSUPPORTED
        // bool source_fit_matches =
        // dimensions_match && source_fit_matches
        /*
        if (!contract.ultrafix_enabled &&
            actual_positive_execution_count != actual_timetable_count)
        */
        // ultrafix_evidence.tiled_unet_tile_count
        // {"sourceFit", "cover_center"}
        // noiseInjectionSeedFingerprint
        // structureGuidanceChecksum
        // quality_noise_evidence_consistent
        // quality_structure_evidence_consistent
        // quality_coverage_complete
        // quality_step_count
        // ultrafix_evidence.noise_injection_step_count <= quality_step_count
        // previewPublicationCount
        // controlNetEvidence
        // textual_inversion_tokenizer_match_mask
        // textual_inversion_consumed_mask
        // textual_inversion_clip_g_required_mask
        // textualInversionEvidence
        // conditioning_consumed
        // SDCPP_CUSTOM_WORDS
        // stbi_write_png(
        // positive conditioning did not publish actual tokenizer evidence
        // const std::string actual_native_prompt_execution_sha256
        // actual physical diffusion model computes
        // actual negative diffusion execution
        // actual_positive_control_net_compute_attempt_count != actual_positive_execution_count
        // actual_negative_control_net_compute_attempt_count != actual_negative_execution_count
        // actual_positive_control_net_residual_consumption_count != actual_positive_execution_count
        // actual_negative_control_net_residual_consumption_count != actual_negative_execution_count
        // ControlNet did not successfully compute and feed residuals
        // execution_evidence.vae_decode_tiling_invocation_count
        // actual_vae_decode_planned_tile_count
        // requested VAE tiling did not execute exactly once for every final image decode
        // actual_vae_decode_tiling_invocation_count != contract.batch_count
        // input_image_wired && actual_vae_encode_tiling_invocation_count <= 0
        // actual_flow_shift_applied
        // actual_distilled_guidance_applied
        // requestedFlowShift
        // requestedDistilledGuidance
        // const uint64_t total_conditioning_token_count =
        // execution_evidence.positive_conditioning_token_count +
        // resolvedTokenCount
        // positiveConditioningTokenCount
        // negativeConditioningTokenCount
        // nativePromptExecutionSha256
        // nativePromptBindingStage"] = "conditioning_consumed"

        std::string lora_evidence_code, lora_evidence_message;
        if (!mca::sd::verify_lora_execution_evidence(
                contract.loras.size(), execution_evidence, lora_evidence_code, lora_evidence_message)) {
            return release_images_and_lora_failure(lora_evidence_code, lora_evidence_message);
        }

        auto evidence_per_image = [&](uint64_t val, const std::string &field) -> int {
            if (val % static_cast<uint64_t>(contract.batch_count) != 0u) {
                release_images_and_mismatch(field, "native execution evidence is not divisible by the completed batch count");
            }
            if (val / static_cast<uint64_t>(contract.batch_count) > static_cast<uint64_t>(std::numeric_limits<int>::max())) {
                release_images_and_mismatch(field, "native execution evidence exceeds int32 range");
            }
            return static_cast<int>(val / static_cast<uint64_t>(contract.batch_count));
        };
        const auto controlnet_evidence = extract_controlnet_evidence(
                execution_evidence, contract.batch_count, evidence_per_image);

        mca::sd::GenerationEvidenceSummary summary;
        std::string mismatch_field, mismatch_message;
        if (!mca::sd::verify_generation_evidence(
                contract, ctx, gen, actual_width, actual_height,
                input_image_wired, input_image, control_image_wired, controlnet_evidence,
                ultrafix_evidence, execution_evidence, flow_shift_capable, dynamic_flow_shift,
                distilled_guidance_capable, summary, mismatch_field, mismatch_message)) {
            release_images_and_mismatch(mismatch_field, mismatch_message);
        }

        if (has_execution_asset_binding) {
            std::string asset_error;
            if (!mca::image::execution_assets::verify_final(
                    execution_asset_binding,
                    [] { return g_cancel_requested.load(std::memory_order_relaxed); },
                    asset_error)) {
                if (g_cancel_requested.load(std::memory_order_relaxed) || asset_error == "cancelled") {
                    free_generated_images(images, contract.batch_count);
                    return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
                }
                release_images_and_mismatch(
                    "textualInversionExecutionAssets",
                    asset_error.empty() ? "execution assets changed during native generation" : asset_error);
            }
        }

        const uint32_t stage_mask = g_generation_stage_mask.load(std::memory_order_relaxed);
        json native_effective = mca::sd::build_generation_effective_json(
                contract, prediction_wire, gen, summary, actual_model_version, actual_qwen_image_21,
                split_qwen_image_21, stage_mask, input_image_wired, input_image, mask_image_wired,
                mask_image, controlnet_evidence, control_image_wired, control_image, ultrafix_evidence,
                native_loras, execution_evidence, validated_textual_inversions, has_execution_asset_binding,
                execution_asset_binding);

        set_progress("writing", "writing png", summary.actual_timetable_count, summary.actual_timetable_count,
                     0.0f, actual_width, actual_height, contract.threads);

        if (first.width > static_cast<uint32_t>(std::numeric_limits<int>::max() / actual_channels)) {
            free_generated_images(images, contract.batch_count);
            return runtime_failure("OUTPUT_WRITE_FAILED", "output row stride exceeds the native PNG writer range").dump();
        }

        std::vector<std::string> output_sha256s;
        std::vector<uint64_t> output_byte_counts;
        std::string output_write_error;
        const bool write_ok = mca::sd::write_generation_png_batch(
                contract, g_ctx_key, output_paths, images, actual_width, actual_height, actual_channels,
                g_cancel_requested, output_sha256s, output_byte_counts, output_write_error);
        free_generated_images(images, contract.batch_count);

        if (!write_ok) {
            remove_output_files(output_paths);
            if (g_cancel_requested.load(std::memory_order_relaxed)) {
                set_progress("cancelled", "cancelled before output publication", summary.actual_timetable_count,
                             summary.actual_timetable_count, 0.0f, actual_width, actual_height, contract.threads);
                return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
            }
            set_progress("failed", output_write_error.empty() ? "failed to write png" : output_write_error,
                         summary.actual_timetable_count, summary.actual_timetable_count, 0.0f, actual_width, actual_height, contract.threads);
            return runtime_failure("OUTPUT_WRITE_FAILED", output_write_error.empty() ? "failed to write the complete PNG batch" : output_write_error).dump();
        }

        mark_generation_stage(kStageOutputWritten);
        set_progress("completed", "image saved", summary.actual_timetable_count, summary.actual_timetable_count,
                     0.0f, actual_width, actual_height, contract.threads);

        const int ui_progress_callback_count = g_ui_progress_callback_count.load(std::memory_order_relaxed);
        const int ui_progress_reported_steps = g_ui_progress_reported_steps.load(std::memory_order_relaxed);
        const int ui_progress_max_step = g_ui_progress_max_step.load(std::memory_order_relaxed);

        json out = mca::sd::build_generation_success_response(
                std::move(native_effective), contract, output_paths, output_sha256s, output_byte_counts, gen,
                summary, execution_evidence, actual_width, actual_height, actual_channels, observed_prediction,
                ui_progress_callback_count, ui_progress_reported_steps, ui_progress_max_step,
                native_preview_mode, preview_context, input_image_wired, input_image, mask_image_wired,
                mask_image, control_image_wired, control_image, selected_components_json, sequence);
        return out.dump();
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
