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
using mca::sd::progress_to_json;
using mca::sd::runtime_config_json;
using mca::sd::set_progress_from_sd_log;
using mca::sd::safe_sd_runtime_message;
using mca::sd::sd_log_callback;
using mca::sd::make_context_key;
using mca::sd::observed_prediction_mode;
using mca::sd::ComponentPaths;
using mca::sd::textual_inversion_consumer_paths;
using mca::sd::execution_assets_match_consumers;
using mca::sd::likely_split_diffusion_family;
using mca::sd::infer_components;
using mca::sd::canonical_existing_file_within_root;
using mca::sd::validate_lora_file;
using mca::sd::validate_upscaler_file;
using mca::sd::mask_bit_count;
using mca::sd::validate_textual_inversion_execution_evidence;
using mca::sd::component_selection_json;
using mca::sd::resolve_component_paths;
using mca::sd::is_sha256;
using mca::sd::ProgressState;
using mca::sd::LoraAdapterSpec;
using mca::sd::ContractLoraAdapter;
using mca::sd::LoadedInputImage;
using mca::sd::load_canonical_input_image;
using mca::sd::validate_canonical_output_path;
using mca::sd::build_output_paths;
using mca::sd::free_generated_images;
using mca::sd::remove_output_files;
using mca::sd::jpeg_exif_orientation;
using mca::sd::orient_pixels;
using mca::sd::ContractTextualInversion;
using mca::sd::RegularFileIdentity;
using mca::sd::regular_file_identity;
using mca::sd::same_regular_file_identity;
using mca::sd::read_regular_file_identity;
using mca::sd::Sha256Accumulator;
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
using mca::sd::required_field;
using mca::sd::blank_text;
using mca::sd::required_string;
using mca::sd::required_integer;
using mca::sd::required_int32;
using mca::sd::required_number;
using mca::sd::required_boolean;
using mca::sd::optional_string;
using mca::sd::optional_boolean;
using mca::sd::optional_int32;
using mca::sd::optional_number;
using mca::sd::is_textual_inversion_trigger;
using mca::sd::StableDiffusionExecutionContract;
using mca::sd::task_uses_input_image;
using mca::sd::task_uses_init_image;
using mca::sd::task_uses_mask_image;
using mca::sd::task_uses_control_image;
using mca::sd::expected_denoising_step_count;
using mca::sd::backend_denoising_strength;
using mca::sd::resolve_qwen_turbo_schedule;
using mca::sd::resolve_sampler_contract;
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
std::atomic<uint64_t> g_preview_revision{0};

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

std::string lower_copy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool ends_with(const std::string &value, const std::string &suffix) {
    return value.size() >= suffix.size() &&
           value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool contains(const std::string &value, const std::string &needle) {
    return value.find(needle) != std::string::npos;
}

bool file_exists(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool dir_exists(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool is_model_file(const std::string &path) {
    const std::string lower = lower_copy(path);
    return ends_with(lower, ".gguf") ||
           ends_with(lower, ".safetensors") ||
           ends_with(lower, ".ckpt") ||
           ends_with(lower, ".pth") ||
           ends_with(lower, ".pt") ||
           ends_with(lower, ".sft");
}

void collect_model_files(const std::string &root, std::vector<std::string> &out) {
    DIR *dir = opendir(root.c_str());
    if (dir == nullptr) return;
    while (dirent *entry = readdir(dir)) {
        const std::string name(entry->d_name);
        if (name == "." || name == "..") continue;
        const std::string path = root + "/" + name;
        struct stat st {};
        if (stat(path.c_str(), &st) != 0) continue;
        if (S_ISDIR(st.st_mode)) {
            collect_model_files(path, out);
        } else if (S_ISREG(st.st_mode) && is_model_file(path)) {
            out.push_back(path);
        }
    }
    closedir(dir);
}


std::string escape_json(const std::string &value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (const char c: value) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out.push_back(c); break;
        }
    }
    return out;
}

const char *json_bool(bool value) {
    return value ? "true" : "false";
}

using mca::sd::progress_to_json;
using mca::sd::runtime_config_json;
using mca::sd::set_progress_from_sd_log;
using mca::sd::safe_sd_runtime_message;
using mca::sd::sd_log_callback;

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

preview_t preview_mode_from_contract(const std::string &mode) {
    if (mode == "projection") return PREVIEW_PROJ;
    if (mode == "tae") return PREVIEW_TAE;
    if (mode == "vae") return PREVIEW_VAE;
    return PREVIEW_NONE;
}

struct PreviewPublicationContext {
    std::string output_path;
    std::string mode;
    int interval = 0;
    int total_steps = 0;
    int publication_count = 0;
    int last_step = 0;
    uint64_t last_revision = 0;
};

void sd_preview_callback(int step,
                         int frame_count,
                         sd_image_t *frames,
                         bool is_noisy,
                         void *data) {
    auto *context = static_cast<PreviewPublicationContext *>(data);
    if (context == nullptr || context->interval <= 0 || frame_count <= 0 || frames == nullptr) {
        return;
    }
    const int normalized_step = step < 0 ? -step : step;
    if (normalized_step <= 0 ||
        (normalized_step % context->interval != 0 && normalized_step != context->total_steps)) {
        return;
    }
    const sd_image_t &frame = frames[0];
    if (frame.data == nullptr || frame.width == 0u || frame.height == 0u ||
        frame.channel == 0u || frame.channel > 4u ||
        frame.width > static_cast<uint32_t>(std::numeric_limits<int>::max()) ||
        frame.height > static_cast<uint32_t>(std::numeric_limits<int>::max()) ||
        frame.width > static_cast<uint32_t>(
            std::numeric_limits<int>::max() / static_cast<int>(frame.channel))) {
        return;
    }

    const uint64_t revision =
            g_preview_revision.fetch_add(1u, std::memory_order_relaxed) + 1u;
    const std::string target = context->output_path +
            ".preview-" + std::to_string(revision % 2u) + ".png";
    const std::string partial = target + ".part";
    std::remove(partial.c_str());
    const int stride = static_cast<int>(frame.width * frame.channel);
    if (stbi_write_png(
            partial.c_str(),
            static_cast<int>(frame.width),
            static_cast<int>(frame.height),
            static_cast<int>(frame.channel),
            frame.data,
            stride) == 0) {
        std::remove(partial.c_str());
        return;
    }
    if (std::rename(partial.c_str(), target.c_str()) != 0) {
        std::remove(partial.c_str());
        return;
    }

    context->publication_count += 1;
    context->last_step = normalized_step;
    context->last_revision = revision;
    mca::sd::set_progress_preview(
            target,
            "image/png",
            context->mode,
            normalized_step,
            revision,
            static_cast<int>(frame.width),
            static_cast<int>(frame.height),
            frame_count,
            is_noisy);
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
        if (!contract.mask_image_path.empty() && !load_canonical_input_image(
                contract.mask_image_path,
                contract.mask_image_sha256,
                1,
                mask_image,
                input_error)) {
            return runtime_failure("MASK_IMAGE_INVALID", input_error).dump();
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
        if (!input_image.pixels.empty() && !mask_image.pixels.empty() &&
            (input_image.width != mask_image.width || input_image.height != mask_image.height)) {
            return runtime_failure(
                    "MASK_IMAGE_SHAPE_MISMATCH",
                    "inpaint mask dimensions must match the input image dimensions").dump();
        }
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

        set_progress(
                "initializing",
                "checking model bundle",
                0,
                contract.steps,
                0.0f,
                contract.width,
                contract.height,
                contract.threads);
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
            set_progress(
                    "failed",
                    component_error,
                    0,
                    contract.steps,
                    0.0f,
                    contract.width,
                    contract.height,
                    contract.threads);
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
        if (task_uses_control_image(contract) && paths.control_net.empty()) {
            return runtime_failure(
                    "CONTROLNET_COMPONENT_MISSING",
                    "control generation requires a concrete ControlNet model component").dump();
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
            text_encoder_paths.model.clear();
            text_encoder_paths.diffusion.clear();
            text_encoder_paths.high_noise_diffusion.clear();
            text_encoder_paths.vae.clear();
            text_encoder_paths.clip_l.clear();
            text_encoder_paths.clip_g.clear();
            text_encoder_paths.clip_vision.clear();
            text_encoder_paths.t5xxl.clear();
            text_encoder_paths.llm_vision.clear();
            text_encoder_paths.embeddings_connectors.clear();
            text_encoder_paths.control_net.clear();

            diffusion_vae_paths.model.clear();
            diffusion_vae_paths.high_noise_diffusion.clear();
            diffusion_vae_paths.clip_l.clear();
            diffusion_vae_paths.clip_g.clear();
            diffusion_vae_paths.clip_vision.clear();
            diffusion_vae_paths.t5xxl.clear();
            diffusion_vae_paths.llm.clear();
            diffusion_vae_paths.llm_vision.clear();
            diffusion_vae_paths.embeddings_connectors.clear();
            diffusion_vae_paths.control_net.clear();
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
        set_progress(
                "loading",
                "loading stable-diffusion.cpp context",
                0,
                contract.steps,
                0.0f,
                contract.width,
                contract.height,
                contract.threads);
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
            set_progress(
                    "failed",
                    g_last_error,
                    0,
                    contract.steps,
                    0.0f,
                    contract.width,
                    contract.height,
                    contract.threads);
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
            set_progress(
                    "encoding",
                    "encoding prompt with Qwen3-VL",
                    0,
                    contract.steps,
                    0.0f,
                    contract.width,
                    contract.height,
                    contract.threads);
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
            set_progress(
                    "loading",
                    "loading Qwen Image DiT and VAE context",
                    0,
                    contract.steps,
                    0.0f,
                    contract.width,
                    contract.height,
                    contract.threads);
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
            set_progress(
                    "cancelled",
                    "cancelled before sampling",
                    0,
                    contract.steps,
                    0.0f,
                    contract.width,
                    contract.height,
                    contract.threads);
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
        gen.control_strength = static_cast<float>(contract.control_strength);
        if (!input_image.pixels.empty()) gen.init_image = input_image.view();
        if (!mask_image.pixels.empty()) gen.mask_image = mask_image.view();
        if (!control_image.pixels.empty()) gen.control_image = control_image.view();
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
        const bool mask_image_wired = task_uses_mask_image(contract) &&
                gen.mask_image.data != nullptr && gen.mask_image.width > 0u && gen.mask_image.height > 0u;
        const bool control_image_wired = task_uses_control_image(contract) &&
                gen.control_image.data != nullptr && gen.control_image.width > 0u &&
                gen.control_image.height > 0u && !paths.control_net.empty();
        if (task_uses_init_image(contract) != input_image_wired) {
            execution_mismatch(
                    "inputImagePath",
                    "the prepared input image was not wired into native init-latent generation");
        }
        if (task_uses_mask_image(contract) != mask_image_wired) {
            execution_mismatch(
                    "maskImagePath",
                    "the prepared mask image was not wired into native denoise-mask generation");
        }
        if (task_uses_control_image(contract) != control_image_wired) {
            execution_mismatch(
                    "controlImagePath",
                    "the prepared control image was not wired into a concrete ControlNet execution");
        }

        const uint64_t sequence =
                g_generation_sequence.fetch_add(1, std::memory_order_relaxed) + 1u;
        g_active_generation_sequence.store(sequence, std::memory_order_relaxed);
        mark_generation_stage(kStageGenerationInvoked);
        set_progress(
                "preparing",
                "preparing image generation",
                0,
                contract.steps,
                0.0f,
                contract.width,
                contract.height,
                contract.threads);
        PreviewPublicationContext preview_context{
                output_path,
                contract.preview_mode,
                contract.preview_interval,
                contract.steps};
        const preview_t native_preview_mode = preview_mode_from_contract(contract.preview_mode);
        sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, false, false, nullptr);
        if (native_preview_mode != PREVIEW_NONE) {
            sd_set_preview_callback(
                    sd_preview_callback,
                    native_preview_mode,
                    contract.preview_interval,
                    true,
                    false,
                    &preview_context);
        }
        sd_image_t *images = nullptr;
        sd_ultrafix_execution_evidence_t ultrafix_evidence{};
        try {
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
        } catch (...) {
            sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, false, false, nullptr);
            throw;
        }
        sd_set_preview_callback(nullptr, PREVIEW_NONE, 1, false, false, nullptr);
        if (qwen_condition != nullptr) {
            sd_free_qwen_image_21_condition(qwen_condition);
            qwen_condition = nullptr;
            qwen_condition_release.condition = nullptr;
        }
        if (g_cancel_requested.load(std::memory_order_relaxed)) {
            free_generated_images(images, contract.batch_count);
            set_progress(
                    "cancelled",
                    "cancelled",
                    0,
                    contract.steps,
                    0.0f,
                    contract.width,
                    contract.height,
                    contract.threads);
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
                set_progress(
                        "failed",
                        textual_inversion_failure_message,
                        0,
                        contract.steps,
                        0.0f,
                        contract.width,
                        contract.height,
                        contract.threads);
                return runtime_failure(
                        textual_inversion_failure_code,
                        textual_inversion_failure_message).dump();
            }
            set_progress(
                    "failed",
                    "stable-diffusion.cpp returned no image",
                    0,
                    contract.steps,
                    0.0f,
                    contract.width,
                    contract.height,
                    contract.threads);
            return runtime_failure(
                    "NATIVE_GENERATION_FAILED",
                    "stable-diffusion.cpp returned an incomplete image batch").dump();
        }
        if (native_preview_mode != PREVIEW_NONE && preview_context.publication_count <= 0) {
            free_generated_images(images, contract.batch_count);
            execution_mismatch(
                    "preview",
                    "stable-diffusion.cpp did not publish a real preview frame for the requested mode");
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
            set_progress(
                    "failed",
                    message,
                    0,
                    contract.steps,
                    0.0f,
                    contract.width,
                    contract.height,
                    contract.threads);
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
        if (contract.ultrafix_enabled) {
            const uint64_t expected_denoise_steps =
                    static_cast<uint64_t>(contract.ultrafix_inversion_steps);
            const uint64_t expected_refinement_branches = contract.use_cfg ? 2u : 1u;
            const bool dimensions_match =
                    ultrafix_evidence.source_width == input_image.width &&
                    ultrafix_evidence.source_height == input_image.height &&
                    ultrafix_evidence.target_width == static_cast<uint32_t>(contract.width) &&
                    ultrafix_evidence.target_height == static_cast<uint32_t>(contract.height);
            const uint64_t source_width = ultrafix_evidence.source_width;
            const uint64_t source_height = ultrafix_evidence.source_height;
            const uint64_t target_width = ultrafix_evidence.target_width;
            const uint64_t target_height = ultrafix_evidence.target_height;
            uint64_t expected_resized_width = target_width;
            uint64_t expected_resized_height = target_height;
            bool source_fit_matches =
                    source_width > 0u && source_height > 0u &&
                    source_width <= target_width && source_height <= target_height;
            if (source_fit_matches) {
                if (target_width * source_height >= target_height * source_width) {
                    expected_resized_height = source_height * target_width / source_width;
                } else {
                    expected_resized_width = source_width * target_height / source_height;
                }
                source_fit_matches =
                        ultrafix_evidence.source_fit ==
                                static_cast<uint32_t>(SD_ULTRAFIX_SOURCE_FIT_COVER_CENTER) &&
                        ultrafix_evidence.source_resized_width == expected_resized_width &&
                        ultrafix_evidence.source_resized_height == expected_resized_height &&
                        ultrafix_evidence.source_crop_left ==
                                (expected_resized_width - target_width) / 2u &&
                        ultrafix_evidence.source_crop_top ==
                                (expected_resized_height - target_height) / 2u;
            }
            const bool request_matches =
                    ultrafix_evidence.version == SD_ULTRAFIX_EXECUTION_EVIDENCE_VERSION &&
                    ultrafix_evidence.generation_completed == 1u &&
                    ultrafix_evidence.cancelled == 0u &&
                    ultrafix_evidence.preview_published == 0u &&
                    dimensions_match && source_fit_matches &&
                    ultrafix_evidence.tile_size ==
                            static_cast<uint32_t>(contract.ultrafix_tile_size) &&
                    std::fabs(static_cast<double>(ultrafix_evidence.tile_overlap) -
                              contract.ultrafix_overlap) <= 1.0e-6 &&
                    ultrafix_evidence.inversion_steps == expected_denoise_steps &&
                    ultrafix_evidence.refinement_steps ==
                            static_cast<uint32_t>(contract.ultrafix_refinement_steps) &&
                    ultrafix_evidence.denoise_step_count == expected_denoise_steps &&
                    ultrafix_evidence.sample_method ==
                            static_cast<uint32_t>(gen.sample_params.sample_method) &&
                    ultrafix_evidence.scheduler ==
                            static_cast<uint32_t>(gen.sample_params.scheduler);
            const bool vae_stages_complete =
                    ultrafix_evidence.vae_encode_invocation_count == 1u &&
                    ultrafix_evidence.vae_encode_success_count == 1u &&
                    ultrafix_evidence.vae_encode_tile_count ==
                            ultrafix_evidence.vae_encode_tile_success_count &&
                    ultrafix_evidence.tiled_vae_decode_invocation_count == 1u &&
                    ultrafix_evidence.tiled_vae_decode_success_count == 1u &&
                    ultrafix_evidence.tiled_vae_decode_tile_count ==
                            ultrafix_evidence.tiled_vae_decode_tile_success_count &&
                    ultrafix_evidence.vae_encode_tile_count ==
                            execution_evidence.vae_encode_tile_compute_attempt_count &&
                    ultrafix_evidence.vae_encode_tile_success_count ==
                            execution_evidence.vae_encode_tile_compute_success_count &&
                    ultrafix_evidence.tiled_vae_decode_tile_count ==
                            execution_evidence.vae_decode_tile_compute_attempt_count &&
                    ultrafix_evidence.tiled_vae_decode_tile_success_count ==
                            execution_evidence.vae_decode_tile_compute_success_count;
            const bool inversion_complete =
                    ultrafix_evidence.ddim_inversion_invocation_count ==
                            expected_denoise_steps &&
                    ultrafix_evidence.ddim_inversion_success_count ==
                            expected_denoise_steps &&
                    ultrafix_evidence.ddim_inversion_step_count ==
                            expected_denoise_steps &&
                    ultrafix_evidence.ddim_inversion_tile_count > 0u &&
                    ultrafix_evidence.ddim_inversion_tile_success_count ==
                            ultrafix_evidence.ddim_inversion_tile_count;
            const bool refinement_complete =
                    ultrafix_evidence.tiled_unet_invocation_count ==
                            expected_denoise_steps &&
                    ultrafix_evidence.tiled_unet_success_count ==
                            expected_denoise_steps &&
                    ultrafix_evidence.tiled_unet_step_count ==
                            expected_denoise_steps &&
                    ultrafix_evidence.tiled_unet_tile_count > 0u &&
                    ultrafix_evidence.tiled_unet_tile_success_count ==
                            ultrafix_evidence.tiled_unet_tile_count;
            const uint64_t expected_physical_diffusion_count =
                    ultrafix_evidence.ddim_inversion_tile_count +
                    ultrafix_evidence.tiled_unet_tile_count *
                            expected_refinement_branches;
            const bool physical_counts_match =
                    execution_evidence.diffusion_model_compute_count ==
                            expected_physical_diffusion_count &&
                    execution_evidence.positive_diffusion_model_compute_count ==
                            ultrafix_evidence.ddim_inversion_tile_count +
                            ultrafix_evidence.tiled_unet_tile_count &&
                    execution_evidence.negative_diffusion_model_compute_count ==
                            (contract.use_cfg
                                ? ultrafix_evidence.tiled_unet_tile_count
                                : 0u) &&
                    execution_evidence.auxiliary_diffusion_model_compute_count == 0u &&
                    execution_evidence.sampling_pass_count == 1u &&
                    execution_evidence.completed_sampling_step_count ==
                            expected_denoise_steps;
            const std::string quality_seed_descriptor =
                    mca::image::ultrafix_noise_seed_descriptor(
                            static_cast<uint64_t>(gen.seed),
                            expected_denoise_steps);
            const uint64_t quality_step_count =
                    ultrafix_evidence.quality_step_evaluation_count;
            const bool quality_noise_evidence_consistent =
                    ultrafix_evidence.noise_injection_step_count <= quality_step_count &&
                    ((ultrafix_evidence.noise_injection_step_count == 0u) ==
                     (ultrafix_evidence.noise_injection_checksum == 0u));
            const bool quality_structure_evidence_consistent =
                    ultrafix_evidence.structure_guidance_step_count <= quality_step_count &&
                    ((ultrafix_evidence.structure_guidance_step_count == 0u) ==
                     (ultrafix_evidence.structure_guidance_checksum == 0u));
            const bool quality_coverage_complete = quality_step_count == 0u
                    ? ultrafix_evidence.trajectory_noise_checksum == 0u
                    : ultrafix_evidence.trajectory_noise_checksum != 0u &&
                            ultrafix_evidence.noise_injection_step_count +
                                    ultrafix_evidence.structure_guidance_step_count >=
                                quality_step_count;
            const bool quality_execution_match =
                    ultrafix_evidence.noise_injection_seed ==
                            static_cast<uint64_t>(gen.seed) &&
                    ultrafix_evidence.quality_step_evaluation_count ==
                            (expected_denoise_steps > 0u
                                ? expected_denoise_steps - 1u : 0u) &&
                    quality_noise_evidence_consistent &&
                    quality_structure_evidence_consistent &&
                    quality_coverage_complete;
            if (!request_matches || !vae_stages_complete || !inversion_complete ||
                !refinement_complete || !physical_counts_match ||
                !quality_execution_match || quality_seed_descriptor.empty()) {
                release_images_and_mismatch(
                        "ultraFixEvidence",
                        "native UltraFix stage evidence is incomplete or conflicts with physical execution counts");
            }
        }
        std::string lora_evidence_code;
        std::string lora_evidence_message;
        if (!mca::sd::verify_lora_execution_evidence(
                contract.loras.size(),
                execution_evidence,
                lora_evidence_code,
                lora_evidence_message)) {
            return release_images_and_lora_failure(
                    lora_evidence_code,
                    lora_evidence_message);
        }
        const bool actual_flow_shift_applied =
                execution_evidence.flow_shift_applied != 0u;
        const bool actual_dynamic_flow_shift =
                execution_evidence.dynamic_flow_shift != 0u;
        const bool actual_distilled_guidance_applied =
                execution_evidence.distilled_guidance_applied != 0u;
        if (actual_flow_shift_applied != flow_shift_capable ||
            actual_dynamic_flow_shift != dynamic_flow_shift) {
            release_images_and_mismatch(
                    "flowShift",
                    "native flow-shift evidence does not match the loaded denoiser");
        }
        const double actual_flow_shift =
                static_cast<double>(execution_evidence.effective_flow_shift);
        if (actual_flow_shift_applied) {
            if (!std::isfinite(actual_flow_shift) || actual_flow_shift < 0.0) {
                release_images_and_mismatch(
                        "flowShift",
                        "native flow denoising did not publish a finite effective shift");
            }
            if (!dynamic_flow_shift && contract.flow_shift >= 0.0 &&
                std::fabs(actual_flow_shift - contract.flow_shift) > 1e-5) {
                release_images_and_mismatch(
                        "flowShift",
                        "native flow denoising did not execute the configured shift");
            }
        }
        if (actual_distilled_guidance_applied != distilled_guidance_capable) {
            release_images_and_mismatch(
                    "distilledGuidance",
                    "native distilled-guidance evidence does not match the loaded diffusion graph");
        }
        const double actual_distilled_guidance =
                static_cast<double>(execution_evidence.effective_distilled_guidance);
        if (actual_distilled_guidance_applied &&
            (!std::isfinite(actual_distilled_guidance) ||
             std::fabs(actual_distilled_guidance - contract.distilled_guidance) > 1e-5)) {
            release_images_and_mismatch(
                    "distilledGuidance",
                    "native diffusion execution did not consume the configured distilled guidance");
        }
        if (execution_evidence.conditioning_artifact_byte_count == 0u ||
            execution_evidence.conditioning_artifact_byte_count >
                    static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
            release_images_and_mismatch(
                    "promptWeightFingerprint",
                    "native conditioning evidence did not expose a bounded artifact");
        }
        std::vector<uint8_t> conditioning_artifact(
                static_cast<size_t>(execution_evidence.conditioning_artifact_byte_count));
        if (!sd_copy_last_image_conditioning_artifact(
                ctx,
                conditioning_artifact.data(),
                conditioning_artifact.size())) {
            release_images_and_mismatch(
                    "promptWeightFingerprint",
                    "native conditioning evidence artifact could not be copied exactly");
        }
        if (!sha256_implementation_ready()) {
            release_images_and_mismatch(
                    "promptWeightFingerprint",
                    "native SHA-256 self-check failed before conditioning evidence hashing");
        }
        const std::string actual_prompt_weight_fingerprint =
                sha256_hex(conditioning_artifact);

        auto evidence_int = [&](uint64_t value, const std::string &field) -> int {
            if (value > static_cast<uint64_t>(std::numeric_limits<int>::max())) {
                release_images_and_mismatch(field, "native execution evidence exceeds int32 range");
            }
            return static_cast<int>(value);
        };
        auto evidence_per_image = [&](uint64_t value, const std::string &field) -> int {
            if (value % static_cast<uint64_t>(contract.batch_count) != 0u) {
                release_images_and_mismatch(
                        field,
                        "native execution evidence is not divisible by the completed batch count");
            }
            return evidence_int(
                    value / static_cast<uint64_t>(contract.batch_count),
                    field);
        };

        const int actual_timetable_count = evidence_per_image(
                execution_evidence.completed_sampling_step_count,
                "timetableCount");
        const int actual_sampling_pass_count = evidence_int(
                execution_evidence.sampling_pass_count,
                "samplingPassCount");
        const int actual_unet_execution_count = evidence_per_image(
                execution_evidence.diffusion_model_compute_count,
                "unetExecutionCount");
        const int actual_positive_execution_count = evidence_per_image(
                execution_evidence.positive_diffusion_model_compute_count,
                "positiveDiffusionExecutionCount");
        const int actual_negative_execution_count = evidence_per_image(
                execution_evidence.negative_diffusion_model_compute_count,
                "negativeDiffusionExecutionCount");
        const int actual_auxiliary_execution_count = evidence_per_image(
                execution_evidence.auxiliary_diffusion_model_compute_count,
                "auxiliaryDiffusionExecutionCount");
        const int actual_control_net_compute_attempt_count = evidence_per_image(
                execution_evidence.control_net_compute_attempt_count,
                "controlNetComputeAttemptCount");
        const int actual_control_net_compute_success_count = evidence_per_image(
                execution_evidence.control_net_compute_success_count,
                "controlNetComputeSuccessCount");
        const int actual_positive_control_net_compute_attempt_count = evidence_per_image(
                execution_evidence.positive_control_net_compute_attempt_count,
                "positiveControlNetComputeAttemptCount");
        const int actual_positive_control_net_compute_success_count = evidence_per_image(
                execution_evidence.positive_control_net_compute_success_count,
                "positiveControlNetComputeSuccessCount");
        const int actual_negative_control_net_compute_attempt_count = evidence_per_image(
                execution_evidence.negative_control_net_compute_attempt_count,
                "negativeControlNetComputeAttemptCount");
        const int actual_negative_control_net_compute_success_count = evidence_per_image(
                execution_evidence.negative_control_net_compute_success_count,
                "negativeControlNetComputeSuccessCount");
        const int actual_control_net_residual_consumption_count = evidence_per_image(
                execution_evidence.control_net_residual_consumption_count,
                "controlNetResidualConsumptionCount");
        const int actual_positive_control_net_residual_consumption_count = evidence_per_image(
                execution_evidence.positive_control_net_residual_consumption_count,
                "positiveControlNetResidualConsumptionCount");
        const int actual_negative_control_net_residual_consumption_count = evidence_per_image(
                execution_evidence.negative_control_net_residual_consumption_count,
                "negativeControlNetResidualConsumptionCount");
        const int actual_auxiliary_control_net_residual_consumption_count = evidence_per_image(
                execution_evidence.auxiliary_control_net_residual_consumption_count,
                "auxiliaryControlNetResidualConsumptionCount");
        const int actual_vae_encode_tiling_invocation_count = evidence_int(
                execution_evidence.vae_encode_tiling_invocation_count,
                "vaeEncodeTilingInvocationCount");
        const int actual_vae_encode_tiling_success_count = evidence_int(
                execution_evidence.vae_encode_tiling_success_count,
                "vaeEncodeTilingSuccessCount");
        const int actual_vae_decode_tiling_invocation_count = evidence_int(
                execution_evidence.vae_decode_tiling_invocation_count,
                "vaeDecodeTilingInvocationCount");
        const int actual_vae_decode_tiling_success_count = evidence_int(
                execution_evidence.vae_decode_tiling_success_count,
                "vaeDecodeTilingSuccessCount");
        const int actual_vae_encode_planned_tile_count = evidence_int(
                execution_evidence.vae_encode_planned_tile_count,
                "vaeEncodePlannedTileCount");
        const int actual_vae_decode_planned_tile_count = evidence_int(
                execution_evidence.vae_decode_planned_tile_count,
                "vaeDecodePlannedTileCount");
        const int actual_vae_encode_tile_compute_attempt_count = evidence_int(
                execution_evidence.vae_encode_tile_compute_attempt_count,
                "vaeEncodeTileComputeAttemptCount");
        const int actual_vae_encode_tile_compute_success_count = evidence_int(
                execution_evidence.vae_encode_tile_compute_success_count,
                "vaeEncodeTileComputeSuccessCount");
        const int actual_vae_decode_tile_compute_attempt_count = evidence_int(
                execution_evidence.vae_decode_tile_compute_attempt_count,
                "vaeDecodeTileComputeAttemptCount");
        const int actual_vae_decode_tile_compute_success_count = evidence_int(
                execution_evidence.vae_decode_tile_compute_success_count,
                "vaeDecodeTileComputeSuccessCount");
        const int actual_vae_encode_tile_size_x = evidence_int(
                execution_evidence.vae_encode_tile_size_x,
                "vaeEncodeTileSizeX");
        const int actual_vae_encode_tile_size_y = evidence_int(
                execution_evidence.vae_encode_tile_size_y,
                "vaeEncodeTileSizeY");
        const int actual_vae_decode_tile_size_x = evidence_int(
                execution_evidence.vae_decode_tile_size_x,
                "vaeDecodeTileSizeX");
        const int actual_vae_decode_tile_size_y = evidence_int(
                execution_evidence.vae_decode_tile_size_y,
                "vaeDecodeTileSizeY");
        const double actual_vae_encode_tile_overlap_x =
                static_cast<double>(execution_evidence.vae_encode_tile_overlap_x);
        const double actual_vae_encode_tile_overlap_y =
                static_cast<double>(execution_evidence.vae_encode_tile_overlap_y);
        const double actual_vae_decode_tile_overlap_x =
                static_cast<double>(execution_evidence.vae_decode_tile_overlap_x);
        const double actual_vae_decode_tile_overlap_y =
                static_cast<double>(execution_evidence.vae_decode_tile_overlap_y);
        const uint64_t classified_compute_count =
                execution_evidence.positive_diffusion_model_compute_count +
                execution_evidence.negative_diffusion_model_compute_count +
                execution_evidence.auxiliary_diffusion_model_compute_count;
        if (classified_compute_count != execution_evidence.diffusion_model_compute_count) {
            release_images_and_mismatch(
                    "unetExecutionCount",
                    "physical diffusion computes were not completely classified by execution branch");
        }
        if (execution_evidence.control_net_compute_attempt_count !=
                    execution_evidence.positive_control_net_compute_attempt_count +
                    execution_evidence.negative_control_net_compute_attempt_count ||
            execution_evidence.control_net_compute_success_count !=
                    execution_evidence.positive_control_net_compute_success_count +
                    execution_evidence.negative_control_net_compute_success_count ||
            execution_evidence.control_net_residual_consumption_count !=
                    execution_evidence.positive_control_net_residual_consumption_count +
                    execution_evidence.negative_control_net_residual_consumption_count +
                    execution_evidence.auxiliary_control_net_residual_consumption_count) {
            release_images_and_mismatch(
                    "controlNetEvidence",
                    "ControlNet execution evidence was not completely classified by branch");
        }
        if (actual_sampling_pass_count != contract.batch_count) {
            release_images_and_mismatch(
                    "samplingPassCount",
                    "native image generation executed an unexpected number of sampling passes");
        }
        if (actual_auxiliary_execution_count != 0) {
            release_images_and_mismatch(
                    "unetExecutionCount",
                    "strict image execution does not support auxiliary diffusion branches");
        }
        if (!contract.ultrafix_enabled &&
            actual_positive_execution_count != actual_timetable_count) {
            release_images_and_mismatch(
                    "positiveDiffusionExecutionCount",
                    "positive conditioning did not physically execute once per completed sampling step");
        }

        const bool requested_use_cfg =
                std::fabs(static_cast<double>(gen.sample_params.guidance.txt_cfg) - 1.0) > 1e-6;
        const bool actual_use_cfg = actual_negative_execution_count > 0;
        const int actual_branches = actual_use_cfg ? 2 : 1;
        if (requested_use_cfg != actual_use_cfg) {
            release_images_and_mismatch(
                    "useCfg",
                    "actual negative diffusion execution does not match the configured CFG mode");
        }
        const bool actual_control_image_consumed =
                control_image_wired && actual_control_net_residual_consumption_count > 0;
        if (control_image_wired) {
            if (actual_control_net_compute_attempt_count != actual_unet_execution_count ||
                actual_control_net_compute_success_count != actual_control_net_compute_attempt_count ||
                actual_positive_control_net_compute_attempt_count != actual_positive_execution_count ||
                actual_positive_control_net_compute_success_count != actual_positive_execution_count ||
                actual_negative_control_net_compute_attempt_count != actual_negative_execution_count ||
                actual_negative_control_net_compute_success_count != actual_negative_execution_count ||
                actual_control_net_residual_consumption_count != actual_unet_execution_count ||
                actual_positive_control_net_residual_consumption_count != actual_positive_execution_count ||
                actual_negative_control_net_residual_consumption_count != actual_negative_execution_count ||
                actual_auxiliary_control_net_residual_consumption_count != 0) {
                release_images_and_mismatch(
                        "controlNetEvidence",
                        "ControlNet did not successfully compute and feed residuals into every physical diffusion branch");
            }
        } else if (actual_control_net_compute_attempt_count != 0 ||
                   actual_control_net_compute_success_count != 0 ||
                   actual_control_net_residual_consumption_count != 0 ||
                   actual_positive_control_net_compute_attempt_count != 0 ||
                   actual_positive_control_net_compute_success_count != 0 ||
                   actual_negative_control_net_compute_attempt_count != 0 ||
                   actual_negative_control_net_compute_success_count != 0 ||
                   actual_positive_control_net_residual_consumption_count != 0 ||
                   actual_negative_control_net_residual_consumption_count != 0 ||
                   actual_auxiliary_control_net_residual_consumption_count != 0) {
            release_images_and_mismatch(
                    "controlNetEvidence",
                    "native execution reported ControlNet work for a request without a control image");
        }
        const bool actual_vae_tiling_enabled =
                actual_vae_encode_tiling_invocation_count > 0 ||
                actual_vae_decode_tiling_invocation_count > 0;
        auto valid_vae_overlap = [](double value) {
            return std::isfinite(value) && value >= 0.0 && value <= 0.5;
        };
        auto validate_vae_tiling_phase = [&](const char *phase,
                                             int invocation_count,
                                             int success_count,
                                             int planned_tile_count,
                                             int tile_compute_attempt_count,
                                             int tile_compute_success_count,
                                             int tile_size_x,
                                             int tile_size_y,
                                             double overlap_x,
                                             double overlap_y) {
            if (invocation_count == 0) {
                if (success_count != 0 || planned_tile_count != 0 ||
                    tile_compute_attempt_count != 0 || tile_compute_success_count != 0 ||
                    tile_size_x != 0 || tile_size_y != 0 || overlap_x != 0.0 ||
                    overlap_y != 0.0) {
                    release_images_and_mismatch(
                            "vaeTiling",
                            std::string("native VAE ") + phase +
                                " evidence exists without a tiled invocation");
                }
                return;
            }
            if (success_count != invocation_count || planned_tile_count <= 0 ||
                tile_compute_attempt_count != planned_tile_count ||
                tile_compute_success_count != tile_compute_attempt_count ||
                tile_size_x <= 0 || tile_size_y <= 0 ||
                !valid_vae_overlap(overlap_x) || !valid_vae_overlap(overlap_y)) {
                release_images_and_mismatch(
                        "vaeTiling",
                        std::string("native VAE ") + phase +
                            " tiling did not complete its physical tile plan");
            }
        };
        validate_vae_tiling_phase(
                "encode",
                actual_vae_encode_tiling_invocation_count,
                actual_vae_encode_tiling_success_count,
                actual_vae_encode_planned_tile_count,
                actual_vae_encode_tile_compute_attempt_count,
                actual_vae_encode_tile_compute_success_count,
                actual_vae_encode_tile_size_x,
                actual_vae_encode_tile_size_y,
                actual_vae_encode_tile_overlap_x,
                actual_vae_encode_tile_overlap_y);
        validate_vae_tiling_phase(
                "decode",
                actual_vae_decode_tiling_invocation_count,
                actual_vae_decode_tiling_success_count,
                actual_vae_decode_planned_tile_count,
                actual_vae_decode_tile_compute_attempt_count,
                actual_vae_decode_tile_compute_success_count,
                actual_vae_decode_tile_size_x,
                actual_vae_decode_tile_size_y,
                actual_vae_decode_tile_overlap_x,
                actual_vae_decode_tile_overlap_y);
        if (contract.vae_tiling_enabled) {
            if (!actual_vae_tiling_enabled ||
                actual_vae_decode_tiling_invocation_count != contract.batch_count) {
                release_images_and_mismatch(
                        "vaeTiling",
                        "requested VAE tiling did not execute exactly once for every final image decode");
            }
            if (input_image_wired && actual_vae_encode_tiling_invocation_count <= 0) {
                release_images_and_mismatch(
                        "vaeTiling",
                        "requested VAE tiling did not execute while encoding the prepared input image");
            }
        } else if (actual_vae_tiling_enabled) {
            release_images_and_mismatch(
                    "vaeTiling",
                    "native VAE tiling executed even though the request disabled it");
        }
        if (execution_evidence.positive_conditioning_observed == 0u ||
            execution_evidence.positive_conditioning_token_count == 0u) {
            release_images_and_mismatch(
                    "tokenCount",
                    "positive conditioning did not publish actual tokenizer evidence");
        }
        if (actual_use_cfg) {
            if (execution_evidence.negative_conditioning_observed == 0u ||
                execution_evidence.negative_conditioning_token_count == 0u ||
                (!contract.ultrafix_enabled &&
                    actual_negative_execution_count != actual_timetable_count) ||
                (contract.ultrafix_enabled &&
                    static_cast<uint64_t>(actual_negative_execution_count) !=
                        ultrafix_evidence.tiled_unet_tile_count)) {
                release_images_and_mismatch(
                        "unconditionalBranch",
                        "CFG did not execute evidenced negative conditioning for every refinement evaluation");
            }
        } else if (execution_evidence.negative_conditioning_observed != 0u ||
                   execution_evidence.negative_conditioning_token_count != 0u ||
                   actual_negative_execution_count != 0) {
            release_images_and_mismatch(
                    "unconditionalBranch",
                    "non-CFG generation unexpectedly prepared or executed negative conditioning");
        }

        const uint64_t total_conditioning_token_count =
                execution_evidence.positive_conditioning_token_count +
                execution_evidence.negative_conditioning_token_count;
        const int actual_token_count = evidence_int(
                total_conditioning_token_count,
                "tokenCount");
        const int actual_positive_weighted_token_count = evidence_int(
                execution_evidence.positive_non_unity_weight_count,
                "positiveWeightedTokenCount");
        const int actual_negative_weighted_token_count = evidence_int(
                execution_evidence.negative_non_unity_weight_count,
                "negativeWeightedTokenCount");
        const bool actual_prompt_weighting_applied =
                execution_evidence.prompt_weighting_applied != 0u;
        const bool weighted_count_observed =
                actual_positive_weighted_token_count > 0 ||
                actual_negative_weighted_token_count > 0;
        if (actual_prompt_weighting_applied != weighted_count_observed) {
            release_images_and_mismatch(
                    "promptWeightingApplied",
                    "native weighting flag conflicts with actual non-unity token weights");
        }
        if (execution_evidence.positive_non_unity_weight_count >
                    execution_evidence.positive_conditioning_token_count ||
            execution_evidence.negative_non_unity_weight_count >
                    execution_evidence.negative_conditioning_token_count) {
            release_images_and_mismatch(
                    "promptWeightingApplied",
                    "native non-unity weight counts exceed actual conditioning tokens");
        }
        if (actual_prompt_weighting_applied && !contract.prompt_weighting_supported) {
            release_images_and_mismatch(
                    "promptWeightingSupported",
                    "native prompt syntax applied non-unity weights while the resolved profile disables weighting");
        }
        if (contract.prompt_weighting_supported &&
            (execution_evidence.positive_prompt_weighting_capable == 0u ||
             (actual_use_cfg && execution_evidence.negative_prompt_weighting_capable == 0u))) {
            release_images_and_mismatch(
                    "promptWeightingSupported",
                    "the executed conditioner cannot prove token-level weighting for every active branch");
        }
        // The resolved value is the active-branch capacity, while the native
        // tokenizer may consume more slots for long prompts or a model-native
        // conditioner such as T5. The exact executed count is reported above
        // and is intentionally not replaced with the request capacity.
        const std::string actual_native_prompt_execution_sha256 =
                image_prompt_execution_sha256(
                        gen.prompt == nullptr ? std::string() : std::string(gen.prompt),
                        gen.negative_prompt == nullptr
                                ? std::string()
                                : std::string(gen.negative_prompt));
        if (!is_sha256(actual_native_prompt_execution_sha256)) {
            release_images_and_mismatch(
                    "nativePromptExecutionSha256",
                    "native prompt framing could not be hashed after conditioning execution");
        }

        const int64_t total_unet_execution_count = evidence_int(
                execution_evidence.diffusion_model_compute_count,
                "totalUnetExecutionCount");
        const int ui_progress_callback_count =
                g_ui_progress_callback_count.load(std::memory_order_relaxed);
        const int ui_progress_reported_steps =
                g_ui_progress_reported_steps.load(std::memory_order_relaxed);
        const int ui_progress_max_step =
                g_ui_progress_max_step.load(std::memory_order_relaxed);
        if (actual_width != contract.width || actual_height != contract.height) {
            release_images_and_mismatch(
                    "width,height",
                    "stable-diffusion.cpp aligned the requested dimensions; strict execution rejects implicit alignment");
        }
        if (actual_timetable_count != contract.timetable_count) {
            release_images_and_mismatch(
                    "timetableCount",
                    "actual completed native sampling steps differ from the resolved timetableCount");
        }
        if (!contract.ultrafix_enabled &&
            actual_unet_execution_count != contract.unet_execution_count) {
            release_images_and_mismatch(
                    "unetExecutionCount",
                    "actual physical diffusion model computes differ from the resolved contract");
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
                    asset_error.empty()
                        ? "execution assets changed during native generation"
                        : asset_error);
            }
        }

        const double actual_cfg_scale =
                static_cast<double>(gen.sample_params.guidance.txt_cfg);
        const int semantic_unet_execution_count = contract.ultrafix_enabled
                ? contract.unet_execution_count
                : actual_unet_execution_count;
        json native_effective = native_effective_json(
                contract,
                prediction_wire,
                gen.sample_params.sample_steps,
                actual_timetable_count,
                semantic_unet_execution_count,
                actual_cfg_scale,
                actual_use_cfg,
                actual_token_count,
                actual_prompt_weighting_applied,
                actual_positive_weighted_token_count,
                actual_negative_weighted_token_count,
                actual_prompt_weight_fingerprint,
                actual_width,
                actual_height);
        native_effective["actualModelVersion"] = actual_model_version;
        native_effective["actualModelVariant"] = actual_qwen_image_21 ? "QWEN_IMAGE_21" : "";
        native_effective["nativeScheduler"] = sd_scheduler_name(gen.sample_params.scheduler);
        if (!contract.qwen_turbo_schedule.empty()) {
            native_effective["qwenTurboSchedule"] = contract.qwen_turbo_schedule;
            native_effective["qwenRawSigmas"] = contract.qwen_raw_sigmas;
            native_effective["qwenShiftedSigmas"] = contract.qwen_custom_sigmas;
            native_effective["qwenSigmaImageSeqLen"] = contract.qwen_sigma_image_seq_len;
            native_effective["qwenSigmaMu"] = contract.qwen_sigma_mu;
        }
        const uint32_t stage_mask =
                g_generation_stage_mask.load(std::memory_order_relaxed);
        const bool qwen_text_encoded =
                (stage_mask & kStageQwenTextConditionEncoded) != 0u;
        const bool qwen_text_released =
                (stage_mask & kStageQwenTextContextReleased) != 0u;
        const bool qwen_diffusion_ready =
                (stage_mask & kStageQwenDiffusionContextReady) != 0u;
        native_effective["stageParametersReleased"] =
                split_qwen_image_21 && qwen_text_released;
        native_effective["qwenTwoStage"] =
                split_qwen_image_21 && qwen_text_encoded &&
                qwen_text_released && qwen_diffusion_ready;
        native_effective["qwenTextEncoderPhase"] =
                split_qwen_image_21 && qwen_text_encoded;
        native_effective["qwenTextConditionEncoded"] =
                split_qwen_image_21 && qwen_text_encoded;
        native_effective["qwenTextEncoderReleasedBeforeDiffusion"] =
                split_qwen_image_21 && qwen_text_released && qwen_diffusion_ready;
        native_effective["qwenDiffusionVaePhase"] =
                split_qwen_image_21 && qwen_diffusion_ready;
        native_effective["inputImagePath"] = input_image_wired
                ? input_image.canonical_path : "";
        native_effective["maskImagePath"] = mask_image_wired
                ? mask_image.canonical_path : "";
        native_effective["controlImagePath"] = control_image_wired
                ? control_image.canonical_path : "";
        native_effective["inputImageExecutionCount"] = input_image_wired ? 1 : 0;
        native_effective["maskImageExecutionCount"] = mask_image_wired ? 1 : 0;
        native_effective["controlImageExecutionCount"] = actual_control_image_consumed ? 1 : 0;
        native_effective["inputImageSha256"] = input_image.sha256;
        native_effective["maskImageSha256"] = mask_image.sha256;
        native_effective["controlImageSha256"] = control_image.sha256;
        native_effective["strength"] = contract.strength;
        native_effective["backendStrength"] = input_image_wired
                ? (contract.ultrafix_enabled
                    ? contract.ultrafix_strength
                    : static_cast<double>(gen.strength))
                : contract.strength;
        native_effective["controlStrength"] = control_image_wired
                ? static_cast<double>(gen.control_strength) : contract.control_strength;
        native_effective["strengthApplied"] = input_image_wired;
        native_effective["controlStrengthApplied"] = actual_control_image_consumed;
        native_effective["clipSkip"] = gen.clip_skip;
        native_effective["batchCount"] = gen.batch_count;
        native_effective["vaeTiling"] = {
                {"enabled", actual_vae_tiling_enabled},
                {"requestedTileSize", contract.vae_tiling_enabled ? contract.vae_tile_size : 0},
                {"requestedOverlap", contract.vae_tiling_enabled ? contract.vae_tile_overlap : 0.0},
                {"encode", {
                        {"invocationCount", actual_vae_encode_tiling_invocation_count},
                        {"successCount", actual_vae_encode_tiling_success_count},
                        {"plannedTileCount", actual_vae_encode_planned_tile_count},
                        {"tileComputeAttemptCount", actual_vae_encode_tile_compute_attempt_count},
                        {"tileComputeSuccessCount", actual_vae_encode_tile_compute_success_count},
                        {"tileSizeX", actual_vae_encode_tile_size_x},
                        {"tileSizeY", actual_vae_encode_tile_size_y},
                        {"overlapX", actual_vae_encode_tile_overlap_x},
                        {"overlapY", actual_vae_encode_tile_overlap_y}
                }},
                {"decode", {
                        {"invocationCount", actual_vae_decode_tiling_invocation_count},
                        {"successCount", actual_vae_decode_tiling_success_count},
                        {"plannedTileCount", actual_vae_decode_planned_tile_count},
                        {"tileComputeAttemptCount", actual_vae_decode_tile_compute_attempt_count},
                        {"tileComputeSuccessCount", actual_vae_decode_tile_compute_success_count},
                        {"tileSizeX", actual_vae_decode_tile_size_x},
                        {"tileSizeY", actual_vae_decode_tile_size_y},
                        {"overlapX", actual_vae_decode_tile_overlap_x},
                        {"overlapY", actual_vae_decode_tile_overlap_y}
                }}
        };
        if (contract.ultrafix_enabled) {
            native_effective["ultraFix"] = {
                    {"version", ultrafix_evidence.version},
                    {"generationCompleted", ultrafix_evidence.generation_completed == 1u},
                    {"cancelled", ultrafix_evidence.cancelled == 1u},
                    {"previewPublished", ultrafix_evidence.preview_published == 1u},
                    {"sourceWidth", ultrafix_evidence.source_width},
                    {"sourceHeight", ultrafix_evidence.source_height},
                    {"targetWidth", ultrafix_evidence.target_width},
                    {"targetHeight", ultrafix_evidence.target_height},
                    {"sourceFit", "cover_center"},
                    {"sourceResizedWidth", ultrafix_evidence.source_resized_width},
                    {"sourceResizedHeight", ultrafix_evidence.source_resized_height},
                    {"sourceCropLeft", ultrafix_evidence.source_crop_left},
                    {"sourceCropTop", ultrafix_evidence.source_crop_top},
                    {"tileSize", ultrafix_evidence.tile_size},
                    {"overlap", ultrafix_evidence.tile_overlap},
                    {"inversionSteps", ultrafix_evidence.inversion_steps},
                    {"refinementSteps", ultrafix_evidence.refinement_steps},
                    {"denoiseStepCount", ultrafix_evidence.denoise_step_count},
                    {"sampleMethod", sd_sample_method_name(gen.sample_params.sample_method)},
                    {"nativeScheduler", sd_scheduler_name(gen.sample_params.scheduler)},
                    {"vaeEncode", {
                            {"invocationCount", ultrafix_evidence.vae_encode_invocation_count},
                            {"successCount", ultrafix_evidence.vae_encode_success_count},
                            {"tileInvocationCount", ultrafix_evidence.vae_encode_tile_count},
                            {"tileSuccessCount", ultrafix_evidence.vae_encode_tile_success_count},
                            {"stepCount", 1}
                    }},
                    {"ddimInversion", {
                            {"invocationCount", ultrafix_evidence.ddim_inversion_invocation_count},
                            {"successCount", ultrafix_evidence.ddim_inversion_success_count},
                            {"tileInvocationCount", ultrafix_evidence.ddim_inversion_tile_count},
                            {"tileSuccessCount", ultrafix_evidence.ddim_inversion_tile_success_count},
                            {"stepCount", ultrafix_evidence.ddim_inversion_step_count}
                    }},
                    {"tiledUnetRefinement", {
                            {"invocationCount", ultrafix_evidence.tiled_unet_invocation_count},
                            {"successCount", ultrafix_evidence.tiled_unet_success_count},
                            {"tileInvocationCount", ultrafix_evidence.tiled_unet_tile_count},
                            {"tileSuccessCount", ultrafix_evidence.tiled_unet_tile_success_count},
                            {"stepCount", ultrafix_evidence.tiled_unet_step_count}
                    }},
                    {"tiledVaeDecode", {
                            {"invocationCount", ultrafix_evidence.tiled_vae_decode_invocation_count},
                            {"successCount", ultrafix_evidence.tiled_vae_decode_success_count},
                            {"tileInvocationCount", ultrafix_evidence.tiled_vae_decode_tile_count},
                            {"tileSuccessCount", ultrafix_evidence.tiled_vae_decode_tile_success_count},
                            {"stepCount", 1}
                    }},
                    {"physicalDiffusionModelComputeCount",
                            execution_evidence.diffusion_model_compute_count},
                    {"qualityStepEvaluationCount",
                            ultrafix_evidence.quality_step_evaluation_count},
                    {"noiseInjectionStepCount",
                            ultrafix_evidence.noise_injection_step_count},
                    {"noiseInjectionSeedFingerprint",
                            sha256_utf8(mca::image::ultrafix_noise_seed_descriptor(
                                    ultrafix_evidence.noise_injection_seed,
                                    ultrafix_evidence.denoise_step_count))},
                    {"noiseInjectionChecksum",
                            fixed_width_lower_hex_u64(
                                    ultrafix_evidence.noise_injection_checksum)},
                    {"structureGuidanceStepCount",
                            ultrafix_evidence.structure_guidance_step_count},
                    {"structureGuidanceChecksum",
                            fixed_width_lower_hex_u64(
                                    ultrafix_evidence.structure_guidance_checksum)},
                    {"trajectoryNoiseChecksum",
                            fixed_width_lower_hex_u64(
                                    ultrafix_evidence.trajectory_noise_checksum)}
            };
            native_effective["strengthMechanism"] = "ddim_inversion";
        }
        native_effective["outputCount"] = contract.batch_count;
        native_effective["n"] = contract.batch_count;
        native_effective["totalUnetExecutionCount"] = total_unet_execution_count;
        native_effective["samplingPassCount"] = actual_sampling_pass_count;
        native_effective["positiveDiffusionModelComputeCount"] =
                actual_positive_execution_count;
        native_effective["negativeDiffusionModelComputeCount"] =
                actual_negative_execution_count;
        native_effective["auxiliaryDiffusionModelComputeCount"] =
                actual_auxiliary_execution_count;
        native_effective["positiveConditioningTokenCount"] =
                execution_evidence.positive_conditioning_token_count;
        native_effective["negativeConditioningTokenCount"] =
                execution_evidence.negative_conditioning_token_count;
        native_effective["conditioningArtifactSha256"] =
                actual_prompt_weight_fingerprint;
        native_effective["nativePromptExecutionSha256"] =
                actual_native_prompt_execution_sha256;
        native_effective["nativePromptBindingStage"] = "conditioning_consumed";
        native_effective["requestedDistilledGuidance"] = contract.distilled_guidance;
        native_effective["distilledGuidanceSpecified"] =
                contract.distilled_guidance_specified;
        native_effective["distilledGuidanceApplied"] =
                actual_distilled_guidance_applied;
        native_effective["distilledGuidance"] = actual_distilled_guidance_applied
                ? json(actual_distilled_guidance)
                : json(nullptr);
        native_effective["requestedFlowShift"] = contract.flow_shift;
        native_effective["flowShiftSpecified"] = contract.flow_shift_specified;
        native_effective["flowShiftApplied"] = actual_flow_shift_applied;
        native_effective["dynamicFlowShift"] = actual_dynamic_flow_shift;
        native_effective["flowShift"] = actual_flow_shift_applied
                ? json(actual_flow_shift)
                : json(nullptr);
        native_effective["imageInputConsumption"] = {
                {"input", input_image_wired ? "init_latent" : "none"},
                {"mask", mask_image_wired ? "denoise_mask" : "none"},
                {"control", actual_control_image_consumed ? "controlnet_residual" : "none"}
        };
        native_effective["controlNetEvidence"] = {
                {"computeAttemptCount", actual_control_net_compute_attempt_count},
                {"computeSuccessCount", actual_control_net_compute_success_count},
                {"positiveComputeAttemptCount", actual_positive_control_net_compute_attempt_count},
                {"positiveComputeSuccessCount", actual_positive_control_net_compute_success_count},
                {"negativeComputeAttemptCount", actual_negative_control_net_compute_attempt_count},
                {"negativeComputeSuccessCount", actual_negative_control_net_compute_success_count},
                {"residualConsumptionCount", actual_control_net_residual_consumption_count},
                {"positiveResidualConsumptionCount", actual_positive_control_net_residual_consumption_count},
                {"negativeResidualConsumptionCount", actual_negative_control_net_residual_consumption_count},
                {"auxiliaryResidualConsumptionCount", actual_auxiliary_control_net_residual_consumption_count}
        };
        native_effective["loras"] =
                mca::sd::format_lora_effective_json(contract.loras, native_loras);
        native_effective["loraEvidence"] =
                mca::sd::format_lora_evidence_json(execution_evidence);
        native_effective["textualInversions"] = json::array();
        for (const auto &artifact: validated_textual_inversions) {
            native_effective["textualInversions"].push_back({
                    {"id", artifact.id},
                    {"trigger", artifact.trigger},
                    {"sha256", artifact.sha256},
                    {"sizeBytes", artifact.size_bytes},
                    {"format", artifact.format},
                    {"runtime", artifact.runtime},
                    {"modelFingerprint", artifact.model_fingerprint},
                    {"tokenizerFingerprint", artifact.tokenizer_fingerprint},
                    {"profileId", artifact.profile_id},
                    {"profileRevision", artifact.profile_revision},
                    {"bindingFingerprint", artifact.binding_fingerprint}
            });
        }
        native_effective["textualInversionEvidence"] = {
                {"requestedCount", execution_evidence.textual_inversion_requested_count},
                {"validatedCount", validated_textual_inversions.size()},
                {"loadAttemptCount", execution_evidence.textual_inversion_load_attempt_count},
                {"loadedCount", execution_evidence.textual_inversion_loaded_count},
                {"tokenizerMatchCount", execution_evidence.textual_inversion_tokenizer_match_count},
                {"appliedCount", execution_evidence.textual_inversion_applied_count},
                {"appliedVectorCount", execution_evidence.textual_inversion_applied_vector_count},
                {"conditioningConsumptionCount",
                        execution_evidence.textual_inversion_conditioning_consumption_count},
                {"clipLAppliedCount", execution_evidence.textual_inversion_clip_l_applied_count},
                {"clipGAppliedCount", execution_evidence.textual_inversion_clip_g_applied_count},
                {"requestedMask", execution_evidence.textual_inversion_requested_mask},
                {"loadedMask", execution_evidence.textual_inversion_loaded_mask},
                {"tokenizerMatchMask", execution_evidence.textual_inversion_tokenizer_match_mask},
                {"appliedMask", execution_evidence.textual_inversion_applied_mask},
                {"consumedMask", execution_evidence.textual_inversion_consumed_mask},
                {"clipLMask", execution_evidence.textual_inversion_clip_l_mask},
                {"clipGMask", execution_evidence.textual_inversion_clip_g_mask},
                {"clipGRequiredMask",
                        execution_evidence.textual_inversion_clip_g_required_mask},
                {"failureCode", "none"},
                {"bindingFingerprint", contract.textual_inversion_binding_fingerprint},
                {"nativeMode", validated_textual_inversions.empty()
                        ? "none" : "SDCPP_CUSTOM_WORDS"},
                {"bindingStage", validated_textual_inversions.empty()
                        ? "none" : "conditioning_consumed"}
        };
        if (has_execution_asset_binding) {
            mca::image::execution_assets::append_evidence(
                    native_effective,
                    execution_asset_binding);
        }

        set_progress(
                "writing",
                "writing png",
                actual_timetable_count,
                actual_timetable_count,
                0.0f,
                actual_width,
                actual_height,
                contract.threads);
        if (first.width > static_cast<uint32_t>(
                std::numeric_limits<int>::max() / actual_channels)) {
            free_generated_images(images, contract.batch_count);
            return runtime_failure(
                    "OUTPUT_WRITE_FAILED",
                    "output row stride exceeds the native PNG writer range").dump();
        }
        const int stride = actual_width * actual_channels;
        bool write_ok = true;
        std::string output_write_error;
        std::vector<std::string> output_sha256s(
                static_cast<size_t>(contract.batch_count));
        std::vector<uint64_t> output_byte_counts(
                static_cast<size_t>(contract.batch_count), 0u);
        for (int index = 0; index < contract.batch_count; ++index) {
            bool wrote = false;
            if (contract.ultrafix_enabled) {
                mca::contract::RequestIdentity tx_identity;
                tx_identity.request_id = g_ctx_key;
                const auto tx_result = mca::sd::SdOutputTransaction::write_png_atomic(
                    output_paths[static_cast<size_t>(index)],
                    actual_width,
                    actual_height,
                    actual_channels,
                    images[index].data,
                    tx_identity,
                    [&]() { return g_cancel_requested.load(std::memory_order_relaxed); });
                if (tx_result.success) {
                    output_sha256s[static_cast<size_t>(index)] = tx_result.sha256_hex;
                    output_byte_counts[static_cast<size_t>(index)] = tx_result.bytes_written;
                    wrote = true;
                } else {
                    output_write_error = tx_result.error.has_value()
                            ? tx_result.error->public_message
                            : "atomic PNG publication failed";
                    wrote = false;
                }
            } else {
                wrote = stbi_write_png(
                    output_paths[static_cast<size_t>(index)].c_str(),
                    actual_width,
                    actual_height,
                    actual_channels,
                    images[index].data,
                    stride) != 0;
            }
            if (!wrote) {
                write_ok = false;
                break;
            }
        }
        free_generated_images(images, contract.batch_count);
        if (!write_ok) {
            remove_output_files(output_paths);
            if (g_cancel_requested.load(std::memory_order_relaxed)) {
                set_progress(
                        "cancelled",
                        "cancelled before output publication",
                        actual_timetable_count,
                        actual_timetable_count,
                        0.0f,
                        actual_width,
                        actual_height,
                        contract.threads);
                return json({{"ok", false}, {"cancelled", true}, {"error", "cancelled"}}).dump();
            }
            set_progress(
                    "failed",
                    output_write_error.empty()
                        ? "failed to write png"
                        : output_write_error,
                    actual_timetable_count,
                    actual_timetable_count,
                    0.0f,
                    actual_width,
                    actual_height,
                    contract.threads);
            return runtime_failure(
                    "OUTPUT_WRITE_FAILED",
                    output_write_error.empty()
                        ? "failed to write the complete PNG batch"
                        : output_write_error).dump();
        }
        mark_generation_stage(kStageOutputWritten);
        set_progress(
                "completed",
                "image saved",
                actual_timetable_count,
                actual_timetable_count,
                0.0f,
                actual_width,
                actual_height,
                contract.threads);

        json out = native_effective;
        out["ok"] = true;
        out["nativeExecution"] = true;
        out["executionStage"] = "semantic_generation_passed";
        out["path"] = output_paths.front();
        out["mimeType"] = "image/png";
        out["outputs"] = json::array();
        for (int index = 0; index < contract.batch_count; ++index) {
            json output_entry = {
                    {"index", index},
                    {"path", output_paths[static_cast<size_t>(index)]},
                    {"mimeType", "image/png"},
                    {"seed", contract.seed + index},
                    {"width", actual_width},
                    {"height", actual_height},
                    {"channels", actual_channels}
            };
            if (contract.ultrafix_enabled) {
                output_entry["sha256"] = output_sha256s[static_cast<size_t>(index)];
                output_entry["sizeBytes"] = output_byte_counts[static_cast<size_t>(index)];
                output_entry["atomicCommit"] = true;
            }
            out["outputs"].push_back(std::move(output_entry));
        }
        if (contract.ultrafix_enabled) {
            out["outputSha256"] = output_sha256s.front();
            out["outputSizeBytes"] = output_byte_counts.front();
            out["outputAtomicCommit"] = true;
            native_effective["outputSha256"] = output_sha256s.front();
            native_effective["outputSizeBytes"] = output_byte_counts.front();
            native_effective["outputAtomicCommit"] = true;
            out["nativeEffective"] = native_effective;
        }
        out["outputCount"] = contract.batch_count;
        out["n"] = contract.batch_count;
        out["threads"] = contract.threads;
        out["sampleMethod"] = sd_sample_method_name(gen.sample_params.sample_method);
        out["nativeScheduler"] = sd_scheduler_name(gen.sample_params.scheduler);
        out["nativePredictionMode"] = observed_prediction_mode(observed_prediction);
        out["actualDiffusionModelComputeCount"] =
                execution_evidence.diffusion_model_compute_count;
        out["actualPositiveDiffusionModelComputeCount"] =
                execution_evidence.positive_diffusion_model_compute_count;
        out["actualNegativeDiffusionModelComputeCount"] =
                execution_evidence.negative_diffusion_model_compute_count;
        out["actualAuxiliaryDiffusionModelComputeCount"] =
                execution_evidence.auxiliary_diffusion_model_compute_count;
        out["actualSamplingStepCount"] =
                execution_evidence.completed_sampling_step_count;
        out["actualSamplingPassCount"] = actual_sampling_pass_count;
        out["actualPositiveConditioningTokenCount"] =
                execution_evidence.positive_conditioning_token_count;
        out["actualNegativeConditioningTokenCount"] =
                execution_evidence.negative_conditioning_token_count;
        out["conditioningArtifactSha256"] = actual_prompt_weight_fingerprint;
        out["nativePromptExecutionSha256"] =
                actual_native_prompt_execution_sha256;
        out["nativePromptBindingStage"] = "conditioning_consumed";
        out["totalUnetExecutionCount"] = total_unet_execution_count;
        out["uiProgressCallbackCount"] = ui_progress_callback_count;
        out["uiProgressReportedSteps"] = ui_progress_reported_steps;
        out["uiProgressMaxStep"] = ui_progress_max_step;
        out["previewRequested"] = native_preview_mode != PREVIEW_NONE;
        out["previewMode"] = native_preview_mode == PREVIEW_NONE
                ? "none"
                : contract.preview_mode;
        out["previewInterval"] = contract.preview_interval;
        out["previewPublicationCount"] = preview_context.publication_count;
        out["previewLastStep"] = preview_context.last_step;
        out["previewLastRevision"] = preview_context.last_revision;
        out["configuredSampleSteps"] = gen.sample_params.sample_steps;
        out["actualDenoisingStepCount"] = actual_timetable_count;
        out["cfgBranchCount"] = actual_branches;
        out["negativePrompt"] = contract.negative_prompt;
        out["taskMode"] = contract.task_mode;
        out["inputImagePath"] = input_image_wired ? input_image.canonical_path : "";
        out["maskImagePath"] = mask_image_wired ? mask_image.canonical_path : "";
        out["controlImagePath"] = control_image_wired ? control_image.canonical_path : "";
        out["inputImageExecutionCount"] = input_image_wired ? 1 : 0;
        out["maskImageExecutionCount"] = mask_image_wired ? 1 : 0;
        out["controlImageExecutionCount"] = actual_control_image_consumed ? 1 : 0;
        out["inputImageSha256"] = input_image.sha256;
        out["maskImageSha256"] = mask_image.sha256;
        out["controlImageSha256"] = control_image.sha256;
        out["inputImageSourceWidth"] = input_image.source_width;
        out["inputImageSourceHeight"] = input_image.source_height;
        out["inputImageSourceChannels"] = input_image.source_channels;
        out["inputImageWidth"] = input_image.width;
        out["inputImageHeight"] = input_image.height;
        out["inputImageChannels"] = input_image.channels;
        out["inputImageExifOrientation"] = input_image.exif_orientation;
        out["maskImageSourceWidth"] = mask_image.source_width;
        out["maskImageSourceHeight"] = mask_image.source_height;
        out["maskImageSourceChannels"] = mask_image.source_channels;
        out["maskImageWidth"] = mask_image.width;
        out["maskImageHeight"] = mask_image.height;
        out["maskImageChannels"] = mask_image.channels;
        out["maskImageExifOrientation"] = mask_image.exif_orientation;
        out["controlImageSourceWidth"] = control_image.source_width;
        out["controlImageSourceHeight"] = control_image.source_height;
        out["controlImageSourceChannels"] = control_image.source_channels;
        out["controlImageWidth"] = control_image.width;
        out["controlImageHeight"] = control_image.height;
        out["controlImageChannels"] = control_image.channels;
        out["controlImageExifOrientation"] = control_image.exif_orientation;
        out["imageInputConsumption"] = native_effective["imageInputConsumption"];
        out["controlNetEvidence"] = native_effective["controlNetEvidence"];
        out["strength"] = native_effective["strength"];
        out["backendStrength"] = native_effective["backendStrength"];
        out["controlStrength"] = native_effective["controlStrength"];
        out["clipSkip"] = native_effective["clipSkip"];
        out["batchCount"] = native_effective["batchCount"];
        out["vaeTiling"] = native_effective["vaeTiling"];
        out["loras"] = native_effective["loras"];
        out["loraEvidence"] = native_effective["loraEvidence"];
        out["textualInversions"] = native_effective["textualInversions"];
        out["textualInversionEvidence"] = native_effective["textualInversionEvidence"];
        out["backendMode"] = "cpu";
        out["backend"] = "stable-diffusion.cpp";
        out["runtimeBackend"] = sd_runtime_backend_label();
        out["componentSelection"] = json::parse(selected_components_json);
        out["systemInfo"] = sd_get_system_info();
        out["nativeEffective"] = native_effective;
        out["nativeGenerationSequence"] = sequence;
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
