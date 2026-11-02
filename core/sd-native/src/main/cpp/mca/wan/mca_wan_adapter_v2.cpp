// P1: typed v2 adapter between the MCA video request and stable-diffusion.cpp.
//
// The v1 shim forwarded only width/height/frames/fps/steps/seed/prompt and silently dropped
// negative prompt, guidance, sampler/scheduler, flow shift, custom sigmas, LoRA, TAE, init/end
// image, cancel and progress. This adapter maps every request field onto the engine's real
// sd_ctx_params_t / sd_vid_gen_params_t members and rejects (never ignores) anything it cannot
// honour.
//
// Engine facts this file relies on (see include/stable-diffusion.h of the external tree):
//   sd_ctx_params_t::taesd_path                  TAE decoder path
//   sd_ctx_params_t::flash_attn / diffusion_flash_attn
//   sd_ctx_params_t::backend / params_backend    "diffusion=x,te=y,vae=z" device assignment
//   sd_ctx_params_t::max_vram                    char* GiB budget (not a float)
//   sd_vid_gen_params_t::loras / lora_count      sd_lora_t array (const)
//   sd_vid_gen_params_t::init_image / end_image  sd_image_t, decoded here
//   sd_vid_gen_params_t::sample_params           guidance.txt_cfg, sample_method, scheduler,
//                                                sample_steps, flow_shift, custom_sigmas
//   sd_cancel_generation(ctx, SD_CANCEL_ALL)     engine-level cancel, polled per sampler step
//   generate_video_streaming(...)                incremental frame sink used for PPM output
//
// Output stays PPM (frame_%05d.ppm) because sd_video_generate.cpp still reads PPM frames; moving
// the transport is P4's task, not this one.

#include "mca_wan_api_v2.h"

#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "stable-diffusion.h"

// The engine library does not export an image loader (its decoder lives in examples/common, which
// is not part of the linked library), so the adapter carries its own copy of stb_image. Static
// linkage keeps the stbi_* symbols out of libmca_wan.so's export surface.
#define STB_IMAGE_IMPLEMENTATION
#define STB_IMAGE_STATIC
#include "stb_image.h"

namespace {

constexpr int kDefaultThreads = 4;
constexpr int kDefaultTileSize = 32;
constexpr int kMaxCustomSigmas = 4096;
constexpr int kMaxLoras = 16;

using Clock = std::chrono::steady_clock;

int64_t ms_since(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}

void copy_string(char* destination, size_t size, const char* source) {
    if (destination == nullptr || size == 0) return;
    if (source == nullptr) source = "";
    std::snprintf(destination, size, "%s", source);
}

bool is_blank(const char* value) {
    return value == nullptr || value[0] == '\0';
}

std::string lower_trim(const char* value) {
    if (value == nullptr) return std::string();
    std::string text(value);
    size_t begin = 0;
    while (begin < text.size() && std::isspace(static_cast<unsigned char>(text[begin]))) ++begin;
    size_t end = text.size();
    while (end > begin && std::isspace(static_cast<unsigned char>(text[end - 1]))) --end;
    std::string out = text.substr(begin, end - begin);
    for (char& c : out) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return out;
}

// The HTP backend needs the skeleton directory before the first device is opened. Same probe as
// the v1 shim: an existing environment variable always wins.
void configure_htp_skel() {
    if (getenv("MCA_HTP_SKEL_DIR") != nullptr) return;
    const char* candidates[] = {
        "/data/user/0/com.muyuchat.mca/files/engine",
        "/data/local/tmp/hy98/mca_skel",
        nullptr,
    };
    for (int i = 0; candidates[i] != nullptr; ++i) {
        const std::string probe = std::string(candidates[i]) + "/libggml-htp-mca-v79.so";
        if (access(probe.c_str(), R_OK) == 0) {
            setenv("MCA_HTP_SKEL_DIR", candidates[i], 1);
            std::fprintf(stderr, "[mca_wan] MCA_HTP_SKEL_DIR=%s\n", candidates[i]);
            return;
        }
    }
    std::fprintf(stderr, "[mca_wan] no skeleton dir found; using default lookup\n");
}

void engine_log_callback(sd_log_level_t level, const char* text, void*) {
    (void)level;
    if (text == nullptr) return;
    // stderr from an app process is not captured by logcat, which left engine failures invisible
    // when a run reported no frames. Mirror to both so device diagnosis stays possible.
    std::fprintf(stderr, "[mca_wan]%s", text);
    __android_log_print(ANDROID_LOG_INFO, "McaWanEngine", "%s", text);
}

void engine_progress_callback(int step, int steps, float time, void* data);

struct ProgressState {
    const mca_wan_request_v2* request = nullptr;
    sd_ctx_t* context = nullptr;
    int64_t frames_written = 0;
    bool cancel_raised = false;
    bool saw_sampling = false;
    Clock::time_point first_sampling{};
};

// Progress and cancel share one engine callback: the engine samples on this thread, so this is
// the only place a caller cancel can be observed *during* sampling. Once observed we also raise
// the engine's own cancel flag; the sampler checks it at every denoise step.
void engine_progress_callback(int step, int steps, float time, void* data) {
    auto* state = static_cast<ProgressState*>(data);
    if (state == nullptr || state->request == nullptr) return;
    const mca_wan_request_v2* request = state->request;

    if (!state->cancel_raised && request->is_cancelled != nullptr &&
        request->is_cancelled(request->cancel_user) != 0) {
        state->cancel_raised = true;
        if (state->context != nullptr) {
            sd_cancel_generation(state->context, SD_CANCEL_ALL);
        }
    }

    // The engine's progress callback is process-global: weight loading and VAE decode report
    // their own (step, steps) pairs (64/64 while loading TAE tensors, for example). Only a
    // callback whose total equals the frozen sampling step count identifies sampling: the Wan
    // sampler reports the denoise schedule (`sigmas - 1`, i.e. 3 for a 4-step request) and a
    // small loading count can carry the same denominator, so a size-based heuristic cannot tell
    // them apart. Per-step progress therefore needs the engine to report the requested total —
    // see build/evidence/v1-27/REMAINING-GAPS-4-5-7-PLAN.md.
    const bool is_sampling = request->steps <= 0 || steps == request->steps;
    // Diagnostic only: shows what the engine actually reports, including callbacks this filter
    // drops. Needed because the Wan sampler may run fewer denoiser evaluations than requested steps.
    {
        static const bool debug_progress = std::getenv("MCA_WAN_DEBUG_PROGRESS") != nullptr;
        if (debug_progress && !is_sampling) {
            std::fprintf(stderr, "[mca_wan] raw progress step=%d steps=%d (not forwarded)\n", step, steps);
        }
    }
    if (is_sampling && !state->saw_sampling) {
        state->saw_sampling   = true;
        state->first_sampling = Clock::now();
    }
    if (request->progress != nullptr && is_sampling) {
        mca_wan_progress_v2 progress{};
        progress.struct_size = sizeof(progress);
        progress.version = MCA_WAN_ABI_VERSION;
        progress.phase = MCA_WAN_PHASE_SAMPLING;
        progress.step = step;
        progress.steps = steps;
        progress.seconds_per_step = time;
        progress.frames_written = static_cast<int32_t>(state->frames_written);
        request->progress(&progress, request->progress_user);
    }
}

void emit_progress(const mca_wan_request_v2* request, ProgressState* state, int32_t phase) {
    if (request->progress == nullptr) return;
    mca_wan_progress_v2 progress{};
    progress.struct_size = sizeof(progress);
    progress.version = MCA_WAN_ABI_VERSION;
    progress.phase = phase;
    progress.frames_written = static_cast<int32_t>(state->frames_written);
    request->progress(&progress, request->progress_user);
}

// Clears the engine-global callbacks on every exit path so a later call cannot reach a dead
// ProgressState.
struct CallbackGuard {
    ~CallbackGuard() {
        sd_set_progress_callback(nullptr, nullptr);
        sd_set_log_callback(nullptr, nullptr);
    }
};

struct ImageBuffer {
    int width = 0;
    int height = 0;
    uint8_t* data = nullptr;

    ~ImageBuffer() {
        if (data != nullptr) {
            stbi_image_free(data);
            data = nullptr;
        }
    }

    bool load(const char* path) {
        if (is_blank(path)) return true;
        // Probe first so a hostile/huge image is rejected before stb allocates the pixel buffer.
        int probe_width = 0, probe_height = 0, probe_channels = 0;
        if (stbi_info(path, &probe_width, &probe_height, &probe_channels) == 0) return false;
        if (probe_width <= 0 || probe_height <= 0 ||
            static_cast<int64_t>(probe_width) * probe_height > 8192LL * 8192LL) {
            return false;
        }
        int channels = 0;
        data = stbi_load(path, &width, &height, &channels, 3);
        return data != nullptr && width > 0 && height > 0;
    }
};

// P6 writer. Frames arrive as 1, 3 or 4 channels; PPM carries RGB only.
bool write_ppm(const char* out_dir, int64_t index, const sd_image_t* frame) {
    if (frame == nullptr || frame->data == nullptr || frame->width == 0 || frame->height == 0) {
        return false;
    }
    if (frame->channel != 1 && frame->channel != 3 && frame->channel != 4) return false;
    char path[4096];
    const int path_length = std::snprintf(path, sizeof(path), "%s/frame_%05lld.ppm", out_dir,
                                          static_cast<long long>(index));
    if (path_length <= 0 || static_cast<size_t>(path_length) >= sizeof(path)) return false;
    FILE* file = std::fopen(path, "wb");
    if (file == nullptr) return false;
    if (std::fprintf(file, "P6\n%u %u\n255\n", frame->width, frame->height) <= 0) {
        std::fclose(file);
        std::remove(path);
        return false;
    }
    const size_t row_bytes = static_cast<size_t>(frame->width) * 3u;
    std::vector<uint8_t> row(row_bytes);
    bool ok = true;
    for (uint32_t y = 0; ok && y < frame->height; ++y) {
        const uint8_t* source = frame->data + static_cast<size_t>(y) * frame->width * frame->channel;
        if (frame->channel == 3) {
            std::memcpy(row.data(), source, row_bytes);
        } else if (frame->channel == 4) {
            for (uint32_t x = 0; x < frame->width; ++x) {
                row[x * 3] = source[x * 4];
                row[x * 3 + 1] = source[x * 4 + 1];
                row[x * 3 + 2] = source[x * 4 + 2];
            }
        } else {
            for (uint32_t x = 0; x < frame->width; ++x) {
                row[x * 3] = source[x];
                row[x * 3 + 1] = source[x];
                row[x * 3 + 2] = source[x];
            }
        }
        ok = std::fwrite(row.data(), 1, row_bytes, file) == row_bytes;
    }
    ok = ok && std::fflush(file) == 0 && fsync(fileno(file)) == 0;
    ok = (std::fclose(file) == 0) && ok;
    if (!ok) std::remove(path);
    return ok;
}

struct SinkState {
    const mca_wan_request_v2* request = nullptr;
    ProgressState* progress = nullptr;
    const char* out_dir = "";
    int32_t written = 0;
    bool write_failed = false;
    bool cancelled = false;
    bool emitted_decoding = false;
    Clock::time_point first_frame;
    bool saw_first_frame = false;
};

bool frame_sink(void* user, const sd_image_t* frame, int64_t index) {
    auto* sink = static_cast<SinkState*>(user);
    if (sink == nullptr || sink->request == nullptr) return false;
    if (!sink->saw_first_frame) {
        sink->saw_first_frame = true;
        sink->first_frame = Clock::now();
    }
    if (sink->request->is_cancelled != nullptr &&
        sink->request->is_cancelled(sink->request->cancel_user) != 0) {
        sink->cancelled = true;
        return false;
    }
    if (!sink->emitted_decoding) {
        sink->emitted_decoding = true;
        emit_progress(sink->request, sink->progress, MCA_WAN_PHASE_DECODING);
    }
    if (sink->request->frame_consumer != nullptr) {
        // Push delivery: the caller encodes this frame now, so nothing is spooled to disk and the
        // native run blocks until the consumer is ready for the next one (natural backpressure).
        const uint32_t w = frame->width;
        const uint32_t h = frame->height;
        if (frame->data == nullptr || w == 0 || h == 0) {
            sink->write_failed = true;
            return false;
        }
        if (frame->channel != 1 && frame->channel != 3 && frame->channel != 4) {
            sink->write_failed = true;
            return false;
        }
        const size_t row_bytes = static_cast<size_t>(w) * 3u;
        std::vector<uint8_t> rgb(row_bytes * h);
        for (uint32_t y = 0; y < h; ++y) {
            const uint8_t* source = frame->data + static_cast<size_t>(y) * w * frame->channel;
            uint8_t* target       = rgb.data() + static_cast<size_t>(y) * row_bytes;
            if (frame->channel == 3) {
                std::memcpy(target, source, row_bytes);
            } else if (frame->channel == 4) {
                for (uint32_t x = 0; x < w; ++x) {
                    target[x * 3]     = source[x * 4];
                    target[x * 3 + 1] = source[x * 4 + 1];
                    target[x * 3 + 2] = source[x * 4 + 2];
                }
            } else {
                for (uint32_t x = 0; x < w; ++x) {
                    target[x * 3] = target[x * 3 + 1] = target[x * 3 + 2] = source[x];
                }
            }
        }
        const int consumer_rc = sink->request->frame_consumer(
            sink->request->frame_consumer_user, rgb.data(), static_cast<int32_t>(w),
            static_cast<int32_t>(h), index);
        if (consumer_rc != 0) {
            sink->cancelled = true;
            return false;
        }
        ++sink->written;
        if (sink->progress != nullptr) sink->progress->frames_written = sink->written;
        return true;
    }
    if (!write_ppm(sink->out_dir, index, frame)) {
        sink->write_failed = true;
        return false;
    }
    ++sink->written;
    if (sink->progress != nullptr) sink->progress->frames_written = sink->written;
    return true;
}

// --- enum mapping -----------------------------------------------------------------------------

bool map_sampler(int32_t value, sample_method_t* out) {
    switch (value) {
        case MCA_WAN_SAMPLER_ENGINE_DEFAULT: *out = SAMPLE_METHOD_COUNT; return true;
        case MCA_WAN_SAMPLER_EULER: *out = EULER_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_EULER_A: *out = EULER_A_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_HEUN: *out = HEUN_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_DPM2: *out = DPM2_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_DPMPP2S_A: *out = DPMPP2S_A_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_DPMPP2M: *out = DPMPP2M_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_DPMPP2MV2: *out = DPMPP2Mv2_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_IPNDM: *out = IPNDM_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_IPNDM_V: *out = IPNDM_V_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_LCM: *out = LCM_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_DDIM_TRAILING: *out = DDIM_TRAILING_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_TCD: *out = TCD_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_RES_MULTISTEP: *out = RES_MULTISTEP_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_RES_2S: *out = RES_2S_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_ER_SDE: *out = ER_SDE_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_EULER_CFG_PP: *out = EULER_CFG_PP_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_EULER_A_CFG_PP: *out = EULER_A_CFG_PP_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_EULER_GE: *out = EULER_GE_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_DPMPP2M_SDE: *out = DPMPP2M_SDE_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_DPMPP2M_SDE_BT: *out = DPMPP2M_SDE_BT_SAMPLE_METHOD; return true;
        case MCA_WAN_SAMPLER_LMS: *out = LMS_SAMPLE_METHOD; return true;
        default: return false;
    }
}

bool map_scheduler(int32_t value, scheduler_t* out) {
    switch (value) {
        case MCA_WAN_SCHEDULER_ENGINE_DEFAULT: *out = SCHEDULER_COUNT; return true;
        case MCA_WAN_SCHEDULER_DISCRETE: *out = DISCRETE_SCHEDULER; return true;
        case MCA_WAN_SCHEDULER_KARRAS: *out = KARRAS_SCHEDULER; return true;
        case MCA_WAN_SCHEDULER_EXPONENTIAL: *out = EXPONENTIAL_SCHEDULER; return true;
        case MCA_WAN_SCHEDULER_AYS: *out = AYS_SCHEDULER; return true;
        case MCA_WAN_SCHEDULER_GITS: *out = GITS_SCHEDULER; return true;
        case MCA_WAN_SCHEDULER_SGM_UNIFORM: *out = SGM_UNIFORM_SCHEDULER; return true;
        case MCA_WAN_SCHEDULER_SIMPLE: *out = SIMPLE_SCHEDULER; return true;
        case MCA_WAN_SCHEDULER_SMOOTHSTEP: *out = SMOOTHSTEP_SCHEDULER; return true;
        case MCA_WAN_SCHEDULER_KL_OPTIMAL: *out = KL_OPTIMAL_SCHEDULER; return true;
        case MCA_WAN_SCHEDULER_LCM: *out = LCM_SCHEDULER; return true;
        case MCA_WAN_SCHEDULER_BONG_TANGENT: *out = BONG_TANGENT_SCHEDULER; return true;
        case MCA_WAN_SCHEDULER_LTX2: *out = LTX2_SCHEDULER; return true;
        case MCA_WAN_SCHEDULER_LOGIT_NORMAL: *out = LOGIT_NORMAL_SCHEDULER; return true;
        case MCA_WAN_SCHEDULER_FLUX2: *out = FLUX2_SCHEDULER; return true;
        case MCA_WAN_SCHEDULER_FLUX: *out = FLUX_SCHEDULER; return true;
        case MCA_WAN_SCHEDULER_BETA: *out = BETA_SCHEDULER; return true;
        default: return false;
    }
}

// --- result helpers ---------------------------------------------------------------------------

void reject(mca_wan_result_v2* result, mca_wan_error_code_t code, mca_wan_status_t status,
            const char* field, const char* message) {
    result->ok = 0;
    result->status = status;
    result->error_code = code;
    if (field != nullptr) copy_string(result->unsupported_field, sizeof(result->unsupported_field), field);
    copy_string(result->error, sizeof(result->error), message);
    if (field != nullptr && field[0] != '\0') {
        std::fprintf(stderr, "[mca_wan] reject code=%d field=%s: %s\n", static_cast<int>(code), field, message);
    } else {
        std::fprintf(stderr, "[mca_wan] reject code=%d: %s\n", static_cast<int>(code), message);
    }
}

bool validate_request(const mca_wan_request_v2* request, mca_wan_result_v2* result) {
    /* Exact match, not <=: a caller built against a different header would otherwise get its
     * fields read at the wrong offsets and silently receive garbage (struct_size 0 means the
     * caller never filled it). */
    if (request->struct_size != sizeof(mca_wan_request_v2)) {
        char message[160];
        std::snprintf(message, sizeof(message),
                      "request struct_size %u does not match the compiled %zu",
                      static_cast<unsigned>(request->struct_size), sizeof(mca_wan_request_v2));
        reject(result, MCA_WAN_ERR_ABI_MISMATCH, MCA_WAN_STATUS_FAILED, "struct_size", message);
        return false;
    }
    if (request->version != MCA_WAN_ABI_VERSION) {
        reject(result, MCA_WAN_ERR_ABI_MISMATCH, MCA_WAN_STATUS_FAILED, "version",
               "request version does not match MCA_WAN_ABI_VERSION");
        return false;
    }
    if (request->width <= 0 || request->height <= 0) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "width",
               "width/height must be positive");
        return false;
    }
    if (request->frame_count <= 0 || request->fps <= 0) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "frame_count",
               "frame_count/fps must be positive");
        return false;
    }
    if (is_blank(request->prompt)) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "prompt", "prompt is empty");
        return false;
    }
    if (is_blank(request->dit_path) || is_blank(request->te_path)) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "dit_path",
               "dit_path and te_path are required");
        return false;
    }
    if (is_blank(request->vae_path)) {
        // A TAE-only decode is a real capability gap, not a malformed request.
        if (request->vae_profile == MCA_WAN_VAE_PROFILE_TAE) {
            reject(result, MCA_WAN_ERR_UNSUPPORTED_FIELD, MCA_WAN_STATUS_UNSUPPORTED, "vae_profile",
                   "TAE-only video decode is unsupported; vae_path is still required for the final decode");
        } else {
            reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "vae_path", "vae_path is required");
        }
        return false;
    }
    if (is_blank(request->out_dir) && request->frame_consumer == nullptr) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "out_dir", "out_dir is required");
        return false;
    }
    if (request->steps <= 0) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "steps", "steps must be positive");
        return false;
    }
    if (!std::isfinite(request->guidance) || request->guidance <= 0.0f) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "guidance",
               "guidance must be finite and positive");
        return false;
    }
    if (!std::isfinite(request->flow_shift)) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "flow_shift",
               "flow_shift must be finite (<= 0 selects the engine default)");
        return false;
    }
    sample_method_t sampler = SAMPLE_METHOD_COUNT;
    scheduler_t scheduler = SCHEDULER_COUNT;
    if (!map_sampler(request->sampler, &sampler)) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "sampler",
               "sampler id is not part of mca_wan_sampler_t");
        return false;
    }
    if (!map_scheduler(request->scheduler, &scheduler)) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "scheduler",
               "scheduler id is not part of mca_wan_scheduler_t");
        return false;
    }
    if (request->custom_sigmas_count < 0 || request->custom_sigmas_count > kMaxCustomSigmas) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "custom_sigmas_count",
               "custom_sigmas_count is out of range");
        return false;
    }
    if (request->custom_sigmas_count > 0) {
        if (request->custom_sigmas == nullptr) {
            reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "custom_sigmas",
                   "custom_sigmas is null but custom_sigmas_count > 0");
            return false;
        }
        if (request->custom_sigmas_count < 2) {
            reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "custom_sigmas_count",
                   "custom_sigmas needs at least 2 values (steps = count - 1)");
            return false;
        }
        for (int32_t i = 0; i < request->custom_sigmas_count; ++i) {
            if (!std::isfinite(request->custom_sigmas[i])) {
                reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "custom_sigmas",
                       "custom_sigmas contains a non-finite value");
                return false;
            }
        }
    }
    if (request->lora_count < 0 || request->lora_count > kMaxLoras) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "lora_count",
               "lora_count is out of range");
        return false;
    }
    if (request->lora_count > 0) {
        if (request->loras == nullptr) {
            reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "loras",
                   "loras is null but lora_count > 0");
            return false;
        }
        for (int32_t i = 0; i < request->lora_count; ++i) {
            if (is_blank(request->loras[i].path)) {
                reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "loras",
                       "a LoRA entry has an empty path");
                return false;
            }
            if (!std::isfinite(request->loras[i].weight)) {
                reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "loras",
                       "a LoRA entry has a non-finite weight");
                return false;
            }
        }
    }
    if (request->vae_profile != MCA_WAN_VAE_PROFILE_DEFAULT &&
        request->vae_profile != MCA_WAN_VAE_PROFILE_TAE) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "vae_profile",
               "vae_profile is not part of mca_wan_vae_profile_t");
        return false;
    }
    if (request->vae_profile == MCA_WAN_VAE_PROFILE_DEFAULT && !is_blank(request->tae_path)) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "tae_path",
               "tae_path requires vae_profile = MCA_WAN_VAE_PROFILE_TAE");
        return false;
    }
    if (request->vae_profile == MCA_WAN_VAE_PROFILE_TAE && is_blank(request->tae_path)) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "tae_path",
               "vae_profile TAE requires tae_path");
        return false;
    }
    if (!std::isfinite(request->max_vram_gib) || request->max_vram_gib < 0.0f) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "max_vram_gib",
               "max_vram_gib must be finite and >= 0");
        return false;
    }
    if (request->offload_to_cpu != 0 && request->offload_to_cpu != 1) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "offload_to_cpu",
               "offload_to_cpu must be 0 or 1");
        return false;
    }
    if (request->offload_to_cpu != 0) {
        // offload_to_cpu is expressed as te=cpu/vae=cpu in the engine's backend assignment; an
        // explicit non-cpu module string contradicts it.
        const std::string te = lower_trim(request->te_backend);
        const std::string vae = lower_trim(request->vae_backend);
        if ((!te.empty() && te != "cpu") || (!vae.empty() && vae != "cpu")) {
            reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "offload_to_cpu",
                   "offload_to_cpu conflicts with a non-cpu te_backend/vae_backend");
            return false;
        }
    }
    if (request->tile_size < 0 || request->tile_frames < 0) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "tile_size",
               "tile_size/tile_frames must be >= 0");
        return false;
    }
    if (request->flash_attn != 0 && request->flash_attn != 1) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "flash_attn",
               "flash_attn must be 0 or 1");
        return false;
    }
    if (request->n_threads < 0 || request->n_threads > 64) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "n_threads",
               "n_threads is out of range");
        return false;
    }
    return true;
}

}  // namespace

extern "C" uint32_t mca_wan_abi_version(void) { return MCA_WAN_ABI_VERSION; }

extern "C" int mca_wan_generate_v2(const mca_wan_request_v2* request, mca_wan_result_v2* result) {
    if (result == nullptr) return 1;
    if (result->struct_size != sizeof(mca_wan_result_v2) || result->version != MCA_WAN_ABI_VERSION) {
        // The caller's result buffer does not match this build; writing into it would land fields
        // at the wrong offsets, so report only through the return code.
        std::fprintf(stderr,
                     "[mca_wan] result ABI mismatch: struct_size=%u expected=%zu version=%u expected=%u\n",
                     static_cast<unsigned>(result->struct_size), sizeof(mca_wan_result_v2),
                     static_cast<unsigned>(result->version), MCA_WAN_ABI_VERSION);
        return 1;
    }
    result->ok = 0;
    result->status = MCA_WAN_STATUS_FAILED;
    result->error_code = MCA_WAN_ERR_NONE;
    result->unsupported_field[0] = '\0';
    result->error[0] = '\0';
    result->fallback_applied = 0;
    result->fallback_reason[0] = '\0';
    if (request == nullptr) {
        reject(result, MCA_WAN_ERR_INVALID_ARGUMENT, MCA_WAN_STATUS_FAILED, "request", "request is null");
        return 1;
    }
    if (!validate_request(request, result)) return 1;

    /* The sink writes frame_%05d.ppm into out_dir; create it here so a caller only has to name a
     * directory. Failing now is cheaper and clearer than decoding a whole video and then losing
     * every frame to a missing directory. Push consumers own the frames, so they need no directory. */
    if (request->frame_consumer == nullptr) {
        std::error_code dir_error;
        std::filesystem::create_directories(request->out_dir, dir_error);
        if (dir_error || !std::filesystem::is_directory(request->out_dir, dir_error)) {
            char message[320];
            std::snprintf(message, sizeof(message), "out_dir '%s' cannot be created: %s", request->out_dir,
                          dir_error ? dir_error.message().c_str() : "not a directory");
            reject(result, MCA_WAN_ERR_IO, MCA_WAN_STATUS_FAILED, "out_dir", message);
            return 1;
        }
    }

    copy_string(result->requested_diffusion_backend, sizeof(result->requested_diffusion_backend),
                request->diffusion_backend);
    copy_string(result->requested_te_backend, sizeof(result->requested_te_backend), request->te_backend);
    copy_string(result->requested_vae_backend, sizeof(result->requested_vae_backend), request->vae_backend);
    result->width = request->width;
    result->height = request->height;
    result->fps = request->fps;

    const Clock::time_point total_start = Clock::now();
    ProgressState progress_state;
    progress_state.request = request;

    if (request->is_cancelled != nullptr && request->is_cancelled(request->cancel_user) != 0) {
        reject(result, MCA_WAN_ERR_CANCELLED, MCA_WAN_STATUS_CANCELLED, nullptr,
               "cancelled before the engine was started");
        result->total_ms = ms_since(total_start);
        return 1;
    }

    configure_htp_skel();
    sd_set_log_callback(engine_log_callback, nullptr);
    CallbackGuard callback_guard;

    // Device assignment: the engine takes one "module=device" string. offload_to_cpu is honoured
    // here (te/vae stay on cpu) instead of being ignored.
    std::string backend_spec;
    auto append_backend = [&backend_spec](const char* module, const char* value) {
        if (is_blank(value)) return;
        if (!backend_spec.empty()) backend_spec += ',';
        backend_spec += module;
        backend_spec += '=';
        backend_spec += value;
    };
    append_backend("diffusion", request->diffusion_backend);
    append_backend("te", is_blank(request->te_backend) && request->offload_to_cpu != 0 ? "cpu" : request->te_backend);
    append_backend("vae", is_blank(request->vae_backend) && request->offload_to_cpu != 0 ? "cpu" : request->vae_backend);

    std::string max_vram_spec;
    if (request->max_vram_gib > 0.0f) {
        char buffer[32];
        std::snprintf(buffer, sizeof(buffer), "%.3f", static_cast<double>(request->max_vram_gib));
        max_vram_spec = buffer;
    }

    ImageBuffer init_image;
    ImageBuffer end_image;
    if (!init_image.load(request->init_image_path)) {
        reject(result, MCA_WAN_ERR_IO, MCA_WAN_STATUS_FAILED, "init_image_path",
               "init image could not be decoded to RGB888");
        result->total_ms = ms_since(total_start);
        return 1;
    }
    if (!end_image.load(request->end_image_path)) {
        reject(result, MCA_WAN_ERR_IO, MCA_WAN_STATUS_FAILED, "end_image_path",
               "end image could not be decoded to RGB888");
        result->total_ms = ms_since(total_start);
        return 1;
    }

    sample_method_t sample_method = SAMPLE_METHOD_COUNT;
    scheduler_t scheduler = SCHEDULER_COUNT;
    map_sampler(request->sampler, &sample_method);
    map_scheduler(request->scheduler, &scheduler);

    sd_ctx_params_t ctx_params;
    sd_ctx_params_init(&ctx_params);
    ctx_params.diffusion_model_path = request->dit_path;
    ctx_params.t5xxl_path = request->te_path;
    ctx_params.vae_path = request->vae_path;
    ctx_params.taesd_path = request->vae_profile == MCA_WAN_VAE_PROFILE_TAE ? request->tae_path : nullptr;
    ctx_params.backend = backend_spec.empty() ? nullptr : backend_spec.c_str();
    ctx_params.params_backend = is_blank(request->params_backend) ? nullptr : request->params_backend;
    ctx_params.max_vram = max_vram_spec.empty() ? nullptr : max_vram_spec.c_str();
    ctx_params.flash_attn = request->flash_attn != 0;
    ctx_params.diffusion_flash_attn = request->flash_attn != 0;
    ctx_params.n_threads = request->n_threads > 0 ? request->n_threads : kDefaultThreads;

    emit_progress(request, &progress_state, MCA_WAN_PHASE_LOADING);
    const Clock::time_point load_start = Clock::now();
    sd_ctx_t* ctx = new_sd_ctx(&ctx_params);
    result->load_ms = ms_since(load_start);
    if (ctx == nullptr) {
        reject(result, MCA_WAN_ERR_CONTEXT_CREATE, MCA_WAN_STATUS_FAILED, nullptr,
               "new_sd_ctx failed (model, backend assignment or memory budget)");
        result->total_ms = ms_since(total_start);
        return 1;
    }
    progress_state.context = ctx;

    // The engine's per-step progress is the only in-sampling hook; installing it here also makes
    // the caller's is_cancelled observable while sampling (see engine_progress_callback).
    sd_set_progress_callback(engine_progress_callback, &progress_state);

    /* The engine only conditions on an end image in its keyframe/FLF2V video paths (Wan FLF2V,
     * MiniMax-H3, LTXAV, Hunyuan). On the Wan 2.2 TI2V-5B path sd_vid_gen_params_t::end_image is
     * ignored, which was proven on device: an end-image run produced byte-identical frames to the
     * same T2V request. Reject it instead of promising conditioning the engine will not apply. */
    if (end_image.data != nullptr) {
        const char* model_name = sd_get_model_version_name(ctx);
        const bool engine_conditions_on_end =
            model_name != nullptr &&
            (std::strstr(model_name, "FLF2V") != nullptr || std::strstr(model_name, "MiniMax") != nullptr ||
             std::strstr(model_name, "LTX") != nullptr || std::strstr(model_name, "Hunyuan") != nullptr);
        if (!engine_conditions_on_end) {
            reject(result, MCA_WAN_ERR_UNSUPPORTED_FIELD, MCA_WAN_STATUS_UNSUPPORTED, "end_image_path",
                   "the loaded engine/model does not condition on an end image; the frame would be ignored");
            free_sd_ctx(ctx);
            progress_state.context = nullptr;
            result->total_ms = ms_since(total_start);
            return 1;
        }
    }

    sd_vid_gen_params_t params;
    sd_vid_gen_params_init(&params);
    params.prompt = request->prompt;
    params.negative_prompt = is_blank(request->negative_prompt) ? nullptr : request->negative_prompt;
    params.width = request->width;
    params.height = request->height;
    params.video_frames = request->frame_count;
    params.fps = request->fps;
    params.seed = request->seed;
    params.sample_params.sample_steps = request->steps;
    params.sample_params.sample_method = sample_method;
    params.sample_params.scheduler = scheduler;
    params.sample_params.guidance.txt_cfg = request->guidance;
    if (request->flow_shift > 0.0f) {
        params.sample_params.flow_shift = request->flow_shift;
    }

    // custom_sigmas replaces the scheduler; the engine derives steps from count - 1. A local copy
    // keeps the borrowed caller array untouched (the engine field is non-const).
    std::vector<float> custom_sigmas;
    if (request->custom_sigmas_count > 0) {
        custom_sigmas.assign(request->custom_sigmas, request->custom_sigmas + request->custom_sigmas_count);
        params.sample_params.custom_sigmas = custom_sigmas.data();
        params.sample_params.custom_sigmas_count = static_cast<int>(custom_sigmas.size());
    }

    std::vector<sd_lora_t> engine_loras;
    engine_loras.reserve(static_cast<size_t>(request->lora_count));
    for (int32_t i = 0; i < request->lora_count; ++i) {
        sd_lora_t lora{};
        lora.is_high_noise = false;  // Wan 2.2 TI2V-5B has a single diffusion model.
        lora.multiplier = request->loras[i].weight;
        lora.path = request->loras[i].path;
        engine_loras.push_back(lora);
    }
    if (!engine_loras.empty()) {
        params.loras = engine_loras.data();
        params.lora_count = static_cast<uint32_t>(engine_loras.size());
    }

    // TAEHV ignores the temporal tile sink (vae.hpp:59) and cannot honour temporal tiling: with
    // tile_frames=1 the tiler reports "left output frame 1 uncovered" and the decode produces no
    // frames at all. Temporal tiling is a property of the Wan VAE chunk decoder, so it is only
    // enabled for the full VAE profile.
    const bool tae_profile = request->vae_profile == MCA_WAN_VAE_PROFILE_TAE;
    params.vae_tiling_params.enabled = !tae_profile && request->spatial_tiling != 0;
    params.vae_tiling_params.temporal_tiling = !tae_profile && request->temporal_tiling != 0;
    params.vae_tiling_params.tile_size_x = request->tile_size > 0 ? request->tile_size : kDefaultTileSize;
    params.vae_tiling_params.tile_size_y = request->tile_size > 0 ? request->tile_size : kDefaultTileSize;
    params.vae_tiling_params.target_overlap = 0.5f;
    params.vae_tiling_params.rel_size_x = 0.0f;
    params.vae_tiling_params.rel_size_y = 0.0f;
    char tiling_args[64] = {};
    if (!tae_profile && request->tile_frames > 0) {
        std::snprintf(tiling_args, sizeof(tiling_args), "temporal_tile_frames=%d", request->tile_frames);
        params.vae_tiling_params.extra_tiling_args = tiling_args;
    }

    if (init_image.data != nullptr) {
        params.init_image = sd_image_t{static_cast<uint32_t>(init_image.width),
                                       static_cast<uint32_t>(init_image.height), 3, init_image.data};
    }
    if (end_image.data != nullptr) {
        params.end_image = sd_image_t{static_cast<uint32_t>(end_image.width),
                                      static_cast<uint32_t>(end_image.height), 3, end_image.data};
    }

    SinkState sink;
    sink.request = request;
    sink.progress = &progress_state;
    sink.out_dir = request->out_dir;

    const Clock::time_point sample_start = Clock::now();
    int64_t delivered = 0;
    sd_audio_t* audio = nullptr;
    const bool streamed = generate_video_streaming(ctx, &params, frame_sink, &sink, &delivered, &audio);
    if (audio != nullptr) free_sd_audio(audio);

    result->delivered_frames = static_cast<int32_t>(delivered);
    result->frames_written = sink.written;
    const Clock::time_point sampling_began =
        progress_state.saw_sampling ? progress_state.first_sampling : sample_start;
    result->prep_ms =
        std::chrono::duration_cast<std::chrono::milliseconds>(sampling_began - sample_start).count();
    result->sample_ms = sink.saw_first_frame
                            ? std::chrono::duration_cast<std::chrono::milliseconds>(sink.first_frame - sampling_began).count()
                            : ms_since(sampling_began);
    result->decode_ms = sink.saw_first_frame ? ms_since(sink.first_frame) : 0;
    result->total_ms = ms_since(total_start);

    copy_string(result->actual_diffusion_backend, sizeof(result->actual_diffusion_backend),
                request->diffusion_backend);
    copy_string(result->actual_te_backend, sizeof(result->actual_te_backend),
                is_blank(request->te_backend) && request->offload_to_cpu != 0 ? "cpu" : request->te_backend);
    copy_string(result->actual_vae_backend, sizeof(result->actual_vae_backend),
                is_blank(request->vae_backend) && request->offload_to_cpu != 0 ? "cpu" : request->vae_backend);

    const bool cancelled = sink.cancelled || progress_state.cancel_raised ||
                           (request->is_cancelled != nullptr && request->is_cancelled(request->cancel_user) != 0);
    free_sd_ctx(ctx);
    sd_set_progress_callback(nullptr, nullptr);

    if (cancelled) {
        reject(result, MCA_WAN_ERR_CANCELLED, MCA_WAN_STATUS_CANCELLED, nullptr,
               "cancelled while the engine was running");
        return 1;
    }
    if (sink.write_failed) {
        reject(result, MCA_WAN_ERR_IO, MCA_WAN_STATUS_FAILED, nullptr,
               "a decoded frame could not be written to out_dir");
        return 1;
    }
    // Success rule mirrors the engine's video_decode_succeeded(): at least one frame accepted.
    // `streamed` only says the VAE delivered tile by tile; a whole-video decode also forwards
    // through the sink, so it must not gate success.
    if (delivered <= 0 || sink.written <= 0) {
        // Before the first frame this is a sampling failure; afterwards it is a decode failure.
        const mca_wan_error_code_t code = sink.saw_first_frame ? MCA_WAN_ERR_DECODE : MCA_WAN_ERR_SAMPLE;
        char message[160];
        std::snprintf(message, sizeof(message),
                      "engine returned no frames (streamed=%d, delivered=%lld, written=%d)",
                      streamed ? 1 : 0, static_cast<long long>(delivered), sink.written);
        reject(result, code, MCA_WAN_STATUS_FAILED, nullptr, message);
        return 1;
    }

    emit_progress(request, &progress_state, MCA_WAN_PHASE_DONE);
    result->ok = 1;
    result->status = MCA_WAN_STATUS_OK;
    result->error_code = MCA_WAN_ERR_NONE;
    result->error[0] = '\0';
    std::fprintf(stderr,
                 "[mca_wan] ok frames=%d delivered=%lld load=%lldms sample=%lldms decode=%lldms total=%lldms\n",
                 sink.written, static_cast<long long>(delivered),
                 static_cast<long long>(result->load_ms), static_cast<long long>(result->sample_ms),
                 static_cast<long long>(result->decode_ms), static_cast<long long>(result->total_ms));
    return 0;
}
