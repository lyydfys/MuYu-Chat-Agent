// V1-03 (P0-C): real native video generation for the MCA video workflow.
//
// One fused call per attempt: the MCA flow graph lowers to a single
// sd_vid_gen_params_t/generate_video invocation (proposal 闂?.2/闂?0.4). The result is a frame
// spool owned by this attempt (PPM P6 files); MP4 encoding happens in Kotlin afterwards so the
// encoder never runs on the native sampling thread. Frames are written and freed one by one to
// bound post-decode retention, but the native decode peak itself is unchanged (the full float
// tensor + RGB array coexist inside generate_video 闂?never report constant memory).
//
// Cancellation uses the DSO-global sd_set_cancel_callback. This DSO is loaded in the dedicated
// :local_video process only, so the callback cannot overwrite an image request; calls are also
// serialized by g_video_mutex. The callback only flips an atomic flag and is cleared in RAII.
// The external engine path passes the same atomic as the typed v2 request's is_cancelled
// predicate, so libmca_wan.so observes the cancel inside the engine's own sampling loop.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <cstdlib>
#include <dirent.h>
#include <limits>
#include <limits.h>
#include <memory>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>
#include <vector>

#include "json.hpp"
#include "mca/wan/mca_wan_api_v2.h"
#include "stable-diffusion.h"
#include "stb_image.h"
#include "util.h"

using json = nlohmann::json;

// Declared at file scope so execute() (inside an anonymous namespace) can reference it.
int mca_jni_frame_consumer(void *, const uint8_t *, int32_t, int32_t, int64_t);

namespace {

constexpr const char *kTag = "McaVideoNative";
constexpr int64_t kMaxDimension = 8192;
constexpr int64_t kMaxPixels = 8192LL * 8192LL;
constexpr int64_t kMaxFrames = 512;
constexpr int64_t kMaxSpoolBytes = 1024LL * 1024LL * 1024LL;

constexpr int64_t kReserveBytes = 64LL * 1024LL * 1024LL;

std::mutex g_video_mutex;
std::mutex g_progress_mutex;
std::atomic<bool> g_video_cancel{false};
std::string g_video_progress = "{}";

bool cancel_callback(void *) {
    return g_video_cancel.load(std::memory_order_relaxed);
}

void progress_callback(int step, int steps, float, void *) {
    json progress = {
        {"phase", "sampling"},
        {"step", step},
        {"steps", steps},
    };
    std::lock_guard<std::mutex> lock(g_progress_mutex);
    g_video_progress = progress.dump();
}

void set_progress(const char *phase) {
    json progress = {{"phase", phase}};
    std::lock_guard<std::mutex> lock(g_progress_mutex);
    g_video_progress = progress.dump();
}

void log_line(const std::string &message) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", message.c_str());
}

// Pre-generation HTP canary. It runs BEFORE any model/weight creation and answers "can this
// process create a Hexagon backend and compute on it?" by doing exactly that: enumerate the ggml
// backends, initialise the HTP device, run a 64-element a+b graph and compare every value. A /dev
// node check cannot answer this (the app can see the node and still be unable to open a FastRPC
// session), so it is not used as the decision. A non-empty result means the external engine must
// start on cpu; failures after this point are ordinary run failures and are never retried.
namespace {

struct HtpCanaryApi {
    size_t (*dev_count)();
    void *(*dev_get)(size_t);
    const char *(*dev_name)(void *);
    void *(*dev_init)(void *, const char *);
    void (*backend_free)(void *);
    int (*alloc_ctx)(void *, void *);
    void (*tensor_set)(void *, const void *, size_t, size_t);
    void (*tensor_get)(const void *, void *, size_t, size_t);
    int (*graph_compute)(void *, void *);
    void *(*ctx_init)(size_t, void *, bool);
    void (*ctx_free)(void *);
    void *(*new_tensor_1d)(void *, int, int64_t);
    void *(*add)(void *, void *, void *);
    void *(*new_graph)(void *);
    void (*build_forward)(void *, void *);
};

// The DSP loader resolves the HTP skeleton through its own search path (/vendor/dsp, then the
// process working directory). An app process runs with cwd "/", so a skeleton that ships inside the
// APK is unreachable and the open fails with 0x80000406 even though the HTP device itself is
// visible. The loader also tries "./", so the working directory is moved to the deployment path.
void configure_htp_skeleton_environment() {
    static bool configured = false;
    if (configured) return;
    configured = true;
    // The DSP loader's search list ends with "./", and it resolves that against the calling
    // process's working directory. An app process runs with cwd "/", so a skeleton that is not in
    // /vendor/dsp can only be found by moving the working directory to where it was deployed. The
    // deployment path is the same one the on-device probe validates; when it is absent nothing is
    // changed and the engine keeps its original lookup.
    const char *const kSkeletonDir = "/data/local/tmp/hy98";
    struct stat st {};
    if (stat(kSkeletonDir, &st) == 0 && S_ISDIR(st.st_mode)) {
        if (chdir(kSkeletonDir) == 0) {
            log_line("htp skeleton cwd=" + std::string(kSkeletonDir));
        }
        if (getenv("ADSP_LIBRARY_PATH") == nullptr) {
            setenv("ADSP_LIBRARY_PATH", kSkeletonDir, 1);
        }
    }
}

// resolve once; the engine is the same DSO the external adapter links against
const HtpCanaryApi *htp_canary_api() {
    configure_htp_skeleton_environment();
    static HtpCanaryApi api;
    static bool attempted = false;
    static bool ok = false;
    if (attempted) return ok ? &api : nullptr;
    attempted = true;
    void *engine = dlopen("libstable-diffusion.so", RTLD_NOW | RTLD_LOCAL);
    if (engine == nullptr) return nullptr;
#define MCA_DLSYM(field, name)                                     \
    api.field = reinterpret_cast<decltype(api.field)>(dlsym(engine, name)); \
    if (api.field == nullptr) return nullptr
    MCA_DLSYM(dev_count, "ggml_backend_dev_count");
    MCA_DLSYM(dev_get, "ggml_backend_dev_get");
    MCA_DLSYM(dev_name, "ggml_backend_dev_name");
    MCA_DLSYM(dev_init, "ggml_backend_dev_init");
    MCA_DLSYM(backend_free, "ggml_backend_free");
    MCA_DLSYM(alloc_ctx, "ggml_backend_alloc_ctx_tensors");
    MCA_DLSYM(tensor_set, "ggml_backend_tensor_set");
    MCA_DLSYM(tensor_get, "ggml_backend_tensor_get");
    MCA_DLSYM(graph_compute, "ggml_backend_graph_compute");
    MCA_DLSYM(ctx_init, "ggml_init");
    MCA_DLSYM(ctx_free, "ggml_free");
    MCA_DLSYM(new_tensor_1d, "ggml_new_tensor_1d");
    MCA_DLSYM(add, "ggml_add");
    MCA_DLSYM(new_graph, "ggml_new_graph");
    MCA_DLSYM(build_forward, "ggml_build_forward_expand");
#undef MCA_DLSYM
    ok = true;
    return &api;
}

}  // namespace

std::string htp_preflight_unavailable_reason() {
    const HtpCanaryApi *api = htp_canary_api();
    if (api == nullptr) {
        return "htp_unavailable: engine_load_failed";
    }

    std::string inventory;
    void *target = nullptr;
    const size_t devices = api->dev_count();
    for (size_t i = 0; i < devices; ++i) {
        void *dev = api->dev_get(i);
        if (dev == nullptr) continue;
        const char *name = api->dev_name(dev);
        if (name == nullptr) continue;
        if (!inventory.empty()) inventory += ",";
        inventory += name;
        if (target == nullptr && std::strstr(name, "HTP") != nullptr) target = dev;
    }
    log_line("htp canary devices=" + std::to_string(devices) + " [" + inventory + "]");
    if (target == nullptr) {
        return "htp_unavailable: hexagon_device_missing";
    }

    void *backend = api->dev_init(target, nullptr);
    if (backend == nullptr) {
        return "htp_unavailable: session_init_failed";
    }
    std::string reason;
    // The arithmetic canary is opt-in. Running a graph inside an app process segfaulted this
    // process on 2026-10-31 (98a, fault addr 0x1000000) even though the HTP device had opened,
    // so a plain "device is reachable and initialises" check is what the preflight reports by
    // default. Set MCA_HTP_CANARY_COMPUTE=1 to exercise the graph as well.
    if (getenv("MCA_HTP_CANARY_COMPUTE") == nullptr) {
        api->backend_free(backend);
        return std::string();
    }
    void *ctx = api->ctx_init(16u * 1024u * 1024u, nullptr, /*no_alloc=*/true);
    void *graph = nullptr;
    if (ctx == nullptr) {
        reason = "htp_unavailable: canary_graph_failed";
    } else {
        // GGML_TYPE_F32 == 0 in this ggml revision
        void *a = api->new_tensor_1d(ctx, 0, 64);
        void *b = api->new_tensor_1d(ctx, 0, 64);
        void *c = api->add(ctx, a, b);
        graph = api->new_graph(ctx);
        api->build_forward(graph, c);
        if (!api->alloc_ctx(ctx, backend)) {
            reason = "htp_unavailable: canary_graph_failed";
        } else {
            float ha[64];
            float hb[64];
            for (int i = 0; i < 64; ++i) {
                ha[i] = 1.5f + static_cast<float>(i);
                hb[i] = 0.25f * static_cast<float>(i);
            }
            api->tensor_set(a, ha, 0, sizeof(ha));
            api->tensor_set(b, hb, 0, sizeof(hb));
            const int status = api->graph_compute(backend, graph);
            float out[64] = {0.0f};
            api->tensor_get(c, out, 0, sizeof(out));
            int mismatches = 0;
            for (int i = 0; i < 64; ++i) {
                const float d = out[i] - (ha[i] + hb[i]);
                if (d > 1e-4f || d < -1e-4f) ++mismatches;
            }
            if (status != 0) {
                reason = "htp_unavailable: canary_compute_failed";
            } else if (mismatches != 0) {
                reason = "htp_unavailable: canary_mismatch";
            } else {
                log_line("htp canary PASS on " + std::string(api->dev_name(target)));
            }
        }
        api->ctx_free(ctx);
    }
    api->backend_free(backend);
    return reason;
}

// ---------------------------------------------------------------------------
// Optional external stable-diffusion.cpp video backend (typed ABI v2).
//
// Loaded lazily through dlopen so the default MCA engine path keeps its current
// behaviour and link set. The V2 entry point is mandatory: a library that only
// exports the old mca_wan_generate symbol is rejected instead of silently
// dropping the fields the V1 shim never forwarded (negative prompt, guidance,
// sampler, scheduler, flow shift, custom sigmas, LoRA, TAE, init/end image,
// cancel and progress).
// ---------------------------------------------------------------------------
struct ExternalVideoInputs {
    std::string out_dir;
    // Non-null switches the external engine to push delivery: every decoded frame goes straight to
    // this consumer instead of being spooled, so the encoder runs while the model decodes.
    mca_wan_frame_consumer_v2 frame_consumer        = nullptr;
    void*                      frame_consumer_user   = nullptr;
    std::string prompt;
    std::string negative_prompt;
    std::string dit_path;
    std::string te_path;
    std::string vae_path;
    std::string init_image_path;
    std::string end_image_path;
    std::string tae_path;
    std::string diffusion_backend;
    std::string te_backend = "cpu";
    std::string vae_backend = "cpu";
    std::string params_backend = "disk";
    std::vector<std::string> lora_paths;
    std::vector<float> lora_weights;
    std::vector<float> custom_sigmas;
    int64_t width = 0, height = 0, frames = 0, fps = 0, steps = 0, seed = 0, threads = 4;
    float guidance = 0.0f;
    float flow_shift = 0.0f;
    float max_vram_gib = 2.0f;
    int32_t sampler = MCA_WAN_SAMPLER_EULER;
    int32_t scheduler = MCA_WAN_SCHEDULER_ENGINE_DEFAULT;
    int32_t vae_profile = MCA_WAN_VAE_PROFILE_DEFAULT;
    int32_t offload_to_cpu = 1;
    int32_t spatial_tiling = 0, temporal_tiling = 1, tile_size = 0, tile_frames = 1;
    int32_t flash_attn = 0;
};

enum class ExternalVideoOutcome { kOk, kCancelled, kEngineLoadFailed, kFailed };

typedef int (*mca_wan_generate_v2_fn)(const mca_wan_request_v2 *, mca_wan_result_v2 *);
typedef uint32_t (*mca_wan_abi_version_fn)();

// Checks a binary PPM (P6, maxval 255) header and payload size without loading the pixels.
bool verify_ppm_frame(const std::string &path, uint32_t expected_width, uint32_t expected_height);

// The v2 request borrows this predicate; it reads the same atomic the JNI cancel sets, so a
// cancel is observed inside the engine's sampling loop and not only after it returns.
int external_is_cancelled(void *) {
    return g_video_cancel.load(std::memory_order_relaxed) ? 1 : 0;
}

// Real per-step sampler progress (the V1 shim could only report the two boundaries). The worker
// projects only callbacks whose total equals the frozen step count, so a mismatched total is
// harmless here.
void external_progress(const mca_wan_progress_v2 *progress, void *) {
    if (progress == nullptr || progress->phase != MCA_WAN_PHASE_SAMPLING || progress->steps <= 0) return;
    progress_callback(progress->step, progress->steps, progress->seconds_per_step, nullptr);
}

const char *external_error_code_name(int32_t code) {
    switch (code) {
        case MCA_WAN_ERR_NONE: return "NONE";
        case MCA_WAN_ERR_INVALID_ARGUMENT: return "INVALID_ARGUMENT";
        case MCA_WAN_ERR_ABI_MISMATCH: return "ABI_MISMATCH";
        case MCA_WAN_ERR_UNSUPPORTED_FIELD: return "UNSUPPORTED_FIELD";
        case MCA_WAN_ERR_CONTEXT_CREATE: return "CONTEXT_CREATE";
        case MCA_WAN_ERR_SAMPLE: return "SAMPLE";
        case MCA_WAN_ERR_DECODE: return "DECODE";
        case MCA_WAN_ERR_CANCELLED: return "CANCELLED";
        case MCA_WAN_ERR_IO: return "IO";
        default: return "UNKNOWN";
    }
}

// Runs one external-engine attempt. The caller keeps the DSO loaded across a possible htp->cpu
// retry: unloading a backend library that just failed during device/PD bring-up can race worker
// threads still alive inside it, which kills the disposable video process instead of returning a
// clean error. The process exits after the run, so the mapping is intentionally never unloaded.
ExternalVideoOutcome run_external_video_into_frames(const ExternalVideoInputs &inputs, sd_image_t **frames_out,
                                                    int *count_out, std::string *error_out,
                                                    std::string *actual_backend_out, bool *frames_spooled_out) {
    void *handle = dlopen("libmca_wan.so", RTLD_NOW);
    if (handle == nullptr) {
        *error_out = std::string("libmca_wan.so could not be loaded: ") + dlerror();
        return ExternalVideoOutcome::kEngineLoadFailed;
    }
    auto abi_version = reinterpret_cast<mca_wan_abi_version_fn>(dlsym(handle, "mca_wan_abi_version"));
    auto generate = reinterpret_cast<mca_wan_generate_v2_fn>(dlsym(handle, "mca_wan_generate_v2"));
    if (generate == nullptr) {
        // No silent fallback to the V1 entry point: it drops most of the request.
        *error_out = "libmca_wan.so is missing the expected symbol mca_wan_generate_v2 "
                     "(typed video ABI v2); the V1 mca_wan_generate entry point is not accepted";
        return ExternalVideoOutcome::kEngineLoadFailed;
    }
    if (abi_version == nullptr || abi_version() != MCA_WAN_ABI_VERSION) {
        *error_out = "libmca_wan.so does not export mca_wan_abi_version() == MCA_WAN_ABI_VERSION 2";
        return ExternalVideoOutcome::kEngineLoadFailed;
    }

    std::vector<mca_wan_lora_v2> loras;
    loras.reserve(inputs.lora_paths.size());
    for (size_t i = 0; i < inputs.lora_paths.size(); ++i) {
        mca_wan_lora_v2 lora{};
        lora.path = inputs.lora_paths[i].c_str();
        lora.weight = inputs.lora_weights[i];
        loras.push_back(lora);
    }

    mca_wan_request_v2 request{};
    request.struct_size = sizeof(request);
    request.version = MCA_WAN_ABI_VERSION;
    request.width = static_cast<int32_t>(inputs.width);
    request.height = static_cast<int32_t>(inputs.height);
    request.frame_count = static_cast<int32_t>(inputs.frames);
    request.fps = static_cast<int32_t>(inputs.fps);
    request.seed = inputs.seed;
    request.prompt = inputs.prompt.c_str();
    request.negative_prompt = inputs.negative_prompt.empty() ? nullptr : inputs.negative_prompt.c_str();
    request.init_image_path = inputs.init_image_path.empty() ? nullptr : inputs.init_image_path.c_str();
    request.end_image_path = inputs.end_image_path.empty() ? nullptr : inputs.end_image_path.c_str();
    request.steps = static_cast<int32_t>(inputs.steps);
    request.guidance = inputs.guidance;
    request.sampler = inputs.sampler;
    request.scheduler = inputs.scheduler;
    request.flow_shift = inputs.flow_shift;
    request.custom_sigmas = inputs.custom_sigmas.empty() ? nullptr : inputs.custom_sigmas.data();
    request.custom_sigmas_count = static_cast<int32_t>(inputs.custom_sigmas.size());
    request.loras = loras.empty() ? nullptr : loras.data();
    request.lora_count = static_cast<int32_t>(loras.size());
    request.dit_path = inputs.dit_path.c_str();
    request.te_path = inputs.te_path.c_str();
    request.vae_path = inputs.vae_path.c_str();
    request.tae_path = inputs.tae_path.empty() ? nullptr : inputs.tae_path.c_str();
    request.vae_profile = inputs.vae_profile;
    request.diffusion_backend = inputs.diffusion_backend.c_str();
    request.te_backend = inputs.te_backend.c_str();
    request.vae_backend = inputs.vae_backend.c_str();
    request.params_backend = inputs.params_backend.c_str();
    request.max_vram_gib = inputs.max_vram_gib;
    request.offload_to_cpu = inputs.offload_to_cpu;
    request.spatial_tiling = inputs.spatial_tiling;
    request.temporal_tiling = inputs.temporal_tiling;
    request.tile_size = inputs.tile_size;
    request.tile_frames = inputs.tile_frames;
    request.flash_attn = inputs.flash_attn;
    request.n_threads = static_cast<int32_t>(std::max<int64_t>(1, std::min<int64_t>(inputs.threads, 16)));
    request.progress = external_progress;
    request.progress_user = nullptr;
    request.is_cancelled = external_is_cancelled;
    request.cancel_user = nullptr;
    request.out_dir = inputs.out_dir.c_str();
    request.frame_consumer      = inputs.frame_consumer;
    request.frame_consumer_user = inputs.frame_consumer_user;

    mca_wan_result_v2 result{};
    result.struct_size = sizeof(result);
    result.version = MCA_WAN_ABI_VERSION;
    int rc = -1;
    try {
        rc = generate(&request, &result);
    } catch (const std::exception &error) {
        // Surface the real reason instead of a generic "uncaught native error". There is no
        // retry: once execution has been entered, failures are reported as-is (no fallback).
        *error_out = std::string("external video engine exception: ") + error.what();
        return ExternalVideoOutcome::kFailed;
    } catch (...) {
        *error_out = "external video engine threw a non-standard exception";
        return ExternalVideoOutcome::kFailed;
    }
    log_line(std::string("external video engine rc=") + std::to_string(rc) +
             " status=" + std::to_string(result.status) +
             " code=" + external_error_code_name(result.error_code) +
             " frames=" + std::to_string(result.frames_written) +
             " delivered=" + std::to_string(result.delivered_frames) +
             " requested=[" + result.requested_diffusion_backend + "/" + result.requested_te_backend + "/" +
             result.requested_vae_backend + "]" +
             " actual=[" + result.actual_diffusion_backend + "/" + result.actual_te_backend + "/" +
             result.actual_vae_backend + "]" +
             " loadMs=" + std::to_string(result.load_ms) + " sampleMs=" + std::to_string(result.sample_ms) +
             " decodeMs=" + std::to_string(result.decode_ms) + " totalMs=" + std::to_string(result.total_ms));
    if (result.status == MCA_WAN_STATUS_CANCELLED) {
        *error_out = result.error;
        return ExternalVideoOutcome::kCancelled;
    }
    // The response's external_backend field is sourced from the adapter's applied assignment.
    if (actual_backend_out != nullptr && result.actual_diffusion_backend[0] != '\0') {
        *actual_backend_out = result.actual_diffusion_backend;
    }
    if (rc != 0 || result.ok != 1 || result.frames_written <= 0) {
        *error_out = std::string("external video engine failed: ") + external_error_code_name(result.error_code);
        if (result.unsupported_field[0] != '\0') {
            *error_out += std::string(" field=") + result.unsupported_field;
        }
        *error_out += std::string(": ") + result.error;
        return ExternalVideoOutcome::kFailed;
    }

    const int count = result.frames_written;
    if (inputs.frame_consumer != nullptr) {
        // Push delivery: every frame already went straight to the consumer, so there is nothing on
        // disk to verify and nothing to publish in place.
        *frames_out = nullptr;
        *count_out = count;
        *frames_spooled_out = true;
        return ExternalVideoOutcome::kOk;
    }
    // The adapter's PPM writer and this file's write_frame() emit byte-identical P6 files at the
    // same paths. Reading every frame back into host memory only to rewrite the same bytes doubles
    // disk I/O and holds the whole clip (frames x w x h x 3) resident. Verify the files the engine
    // already produced instead, and publish them in place.
    for (int i = 0; i < count; ++i) {
        char name[64];
        snprintf(name, sizeof(name), "/frame_%05d.ppm", i);
        const std::string path = inputs.out_dir + name;
        if (!verify_ppm_frame(path, static_cast<uint32_t>(inputs.width), static_cast<uint32_t>(inputs.height))) {
            *error_out = std::string("external engine did not produce a valid decoded frame ") + name;
            return ExternalVideoOutcome::kFailed;
        }
    }
    *frames_out = nullptr;
    *count_out = count;
    *frames_spooled_out = true;
    return ExternalVideoOutcome::kOk;
}

// Header-and-size check only: the caller publishes the file, so the payload must be exactly the
// P6 body the frame rule promised, without allocating it.
bool verify_ppm_frame(const std::string &path, uint32_t expected_width, uint32_t expected_height) {
    FILE *f = fopen(path.c_str(), "rb");
    if (f == nullptr) return false;
    char magic[3] = {0, 0, 0};
    long w = 0, h = 0, maxval = 0;
    bool ok = fscanf(f, "%2s %ld %ld %ld", magic, &w, &h, &maxval) == 4 &&
              std::strcmp(magic, "P6") == 0 && maxval == 255 && w > 0 && h > 0;
    if (ok && fgetc(f) != EOF) {
        const long payload_start = ftell(f);
        ok = payload_start >= 0 && fseek(f, 0, SEEK_END) == 0;
        const long size = ok ? ftell(f) : -1;
        ok = ok && size >= 0 &&
             static_cast<uint64_t>(size - payload_start) ==
                 static_cast<uint64_t>(w) * static_cast<uint64_t>(h) * 3u;
    } else {
        ok = false;
    }
    fclose(f);
    return ok && static_cast<uint32_t>(w) == expected_width && static_cast<uint32_t>(h) == expected_height;
}

/**
 * sd.cpp's own diagnostics ("loading t5xxl", "No text encoders provided", tensor-name
 * mismatches, per-stage timings) were previously discarded because the video path installed a
 * null log callback. That silence is why a prompt-independent DiT could not be diagnosed from
 * device logs. Forward them to logcat under the same tag as the wrapper's own lines.
 */
void sd_log_callback(enum sd_log_level_t level, const char *text, void *) {
    if (text == nullptr) return;
    const int priority = level == SD_LOG_ERROR ? ANDROID_LOG_ERROR
        : (level == SD_LOG_WARN ? ANDROID_LOG_WARN
        : (level == SD_LOG_DEBUG ? ANDROID_LOG_DEBUG : ANDROID_LOG_INFO));
    __android_log_print(priority, kTag, "sd.cpp: %s", text);
}

/** Short fingerprint so logs can correlate a prompt without persisting its text. */
std::string prompt_fingerprint(const std::string &text) {
    uint64_t hash = 1469598103934665603ULL;
    for (unsigned char c : text) {
        hash ^= static_cast<uint64_t>(c);
        hash *= 1099511628211ULL;
    }
    char out[32];
    std::snprintf(out, sizeof(out), "%016llx-%zu", static_cast<unsigned long long>(hash), text.size());
    return std::string(out);
}

struct FrameArray {
    sd_image_t *frames = nullptr;
    int count = 0;

    ~FrameArray() {
        if (frames == nullptr) return;
        for (int i = 0; i < count; ++i) {
            std::free(frames[i].data);
            frames[i].data = nullptr;
        }
        std::free(frames);
        frames = nullptr;
    }
};

struct SdContext {
    sd_ctx_t *ctx = nullptr;

    ~SdContext() {
        if (ctx != nullptr) {
            free_sd_ctx(ctx);
            ctx = nullptr;
        }
    }
};

struct FrameImage {
    unsigned char *data = nullptr;

    ~FrameImage() {
        if (data != nullptr) {
            stbi_image_free(data);
            data = nullptr;
        }
    }
};

struct CallbackGuard {
    ~CallbackGuard() {
        sd_set_progress_callback(nullptr, nullptr);
        sd_set_cancel_callback(nullptr, nullptr);
    }
};

bool is_regular_file(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool is_directory(const std::string &path) {
    struct stat st {};
    return !path.empty() && stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool ends_with(const std::string &value, const std::string &suffix) {
    return value.size() >= suffix.size() &&
           value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool safe_child_path(const std::string &root, const std::string &candidate) {
    if (root.empty() || candidate.size() <= root.size()) return false;
    if (candidate.compare(0, root.size(), root) != 0) return false;
    return candidate[root.size()] == '/';
}

json failure(const char *code, const std::string &detail) {
    json result = {
        {"status", "failed"},
        {"code", code},
        {"detail", detail},
    };
    log_line(std::string(code) + ": " + detail);
    return result;
}

bool json_int64(const json &body, const char *name, int64_t &out) {
    auto it = body.find(name);
    if (it == body.end() || !it->is_number_integer()) return false;
    out = it->get<int64_t>();
    return true;
}

bool json_double(const json &body, const char *name, double &out) {
    auto it = body.find(name);
    if (it == body.end() || !it->is_number()) return false;
    out = it->get<double>();
    return std::isfinite(out);
}

std::string json_string(const json &body, const char *name) {
    auto it = body.find(name);
    if (it == body.end() || !it->is_string()) return std::string();
    return it->get<std::string>();
}

// Canonical scheduler names (the same table the external engine exposes through
// sd_scheduler_name). "default"/"" leaves the choice to the engine.
bool scheduler_from_name(const std::string &name, int32_t &out) {
    static const struct {
        const char *name;
        int32_t id;
    } kSchedulers[] = {
        {"discrete", MCA_WAN_SCHEDULER_DISCRETE},
        {"karras", MCA_WAN_SCHEDULER_KARRAS},
        {"exponential", MCA_WAN_SCHEDULER_EXPONENTIAL},
        {"ays", MCA_WAN_SCHEDULER_AYS},
        {"gits", MCA_WAN_SCHEDULER_GITS},
        {"sgm_uniform", MCA_WAN_SCHEDULER_SGM_UNIFORM},
        {"simple", MCA_WAN_SCHEDULER_SIMPLE},
        {"smoothstep", MCA_WAN_SCHEDULER_SMOOTHSTEP},
        {"kl_optimal", MCA_WAN_SCHEDULER_KL_OPTIMAL},
        {"lcm", MCA_WAN_SCHEDULER_LCM},
        {"bong_tangent", MCA_WAN_SCHEDULER_BONG_TANGENT},
        {"ltx2", MCA_WAN_SCHEDULER_LTX2},
        {"logit_normal", MCA_WAN_SCHEDULER_LOGIT_NORMAL},
        {"flux2", MCA_WAN_SCHEDULER_FLUX2},
        {"flux", MCA_WAN_SCHEDULER_FLUX},
        {"beta", MCA_WAN_SCHEDULER_BETA},
    };
    if (name.empty() || name == "default" || name == "engine_default") {
        out = MCA_WAN_SCHEDULER_ENGINE_DEFAULT;
        return true;
    }
    if (name == "normal") {  // the engine's alias for discrete
        out = MCA_WAN_SCHEDULER_DISCRETE;
        return true;
    }
    for (const auto &entry : kSchedulers) {
        if (name == entry.name) {
            out = entry.id;
            return true;
        }
    }
    return false;
}

bool ensure_empty_directory(const std::string &path, std::string &error) {
    if (!is_directory(path)) {
        if (mkdir(path.c_str(), 0700) != 0 && errno != EEXIST) {
            error = "cannot create frame spool directory";
            return false;
        }
        if (!is_directory(path)) {
            error = "frame spool path is not a directory";
            return false;
        }
    }
    DIR *dir = opendir(path.c_str());
    if (dir == nullptr) {
        error = "cannot inspect frame spool directory";
        return false;
    }
    bool empty = true;
    while (dirent *entry = readdir(dir)) {
        const char *name = entry->d_name;
        if (std::strcmp(name, ".") == 0 || std::strcmp(name, "..") == 0) continue;
        empty = false;
        break;
    }
    closedir(dir);
    if (!empty) {
        error = "frame spool directory is not empty; a fresh run directory is required";
        return false;
    }
    return true;
}

bool write_frame(const std::string &path, const sd_image_t &frame, std::string &error) {
    if (frame.data == nullptr || frame.width == 0 || frame.height == 0) {
        error = "native returned an empty frame";
        return false;
    }
    if (frame.channel != 1 && frame.channel != 3 && frame.channel != 4) {
        error = "unsupported native frame channel count";
        return false;
    }
    FILE *file = fopen(path.c_str(), "wb");
    if (file == nullptr) {
        error = "cannot create frame file";
        return false;
    }
    const bool header_ok = fprintf(file, "P6\n%u %u\n255\n", frame.width, frame.height) > 0;
    bool payload_ok = header_ok;
    const uint64_t pixels = static_cast<uint64_t>(frame.width) * frame.height;
    std::vector<unsigned char> row(static_cast<size_t>(frame.width) * 3u);
    for (uint64_t y = 0; payload_ok && y < frame.height; ++y) {
        const unsigned char *src = frame.data + y * static_cast<uint64_t>(frame.width) * frame.channel;
        if (frame.channel == 3) {
            std::memcpy(row.data(), src, row.size());
        } else if (frame.channel == 4) {
            for (uint64_t x = 0; x < frame.width; ++x) {
                row[x * 3] = src[x * 4];
                row[x * 3 + 1] = src[x * 4 + 1];
                row[x * 3 + 2] = src[x * 4 + 2];
            }
        } else {
            for (uint64_t x = 0; x < frame.width; ++x) {
                row[x * 3] = src[x];
                row[x * 3 + 1] = src[x];
                row[x * 3 + 2] = src[x];
            }
        }
        payload_ok = fwrite(row.data(), 1, row.size(), file) == row.size();
    }
    (void)pixels;
    const bool flushed = payload_ok && fflush(file) == 0 && fsync(fileno(file)) == 0;
    const bool closed = fclose(file) == 0;
    if (!header_ok || !payload_ok || !flushed || !closed) {
        unlink(path.c_str());
        error = "failed to write a complete frame file";
        return false;
    }
    return true;
}

json execute(const std::string &request_json, bool push_frames = false) {
    json body;
    try {
        body = json::parse(request_json);
    } catch (const json::exception &) {
        return failure("INPUT_INVALID", "request JSON is invalid");
    }
    if (!body.is_object()) return failure("INPUT_INVALID", "request JSON must be an object");

    const std::string run_dir = json_string(body, "runDir");
    const std::string staging_root = json_string(body, "stagingRoot");
    const std::string dit_path = json_string(body, "ditPath");
    const std::string encoder_path = json_string(body, "encoderPath");
    const std::string vae_path = json_string(body, "vaePath");
    const std::string prompt = json_string(body, "prompt");
    const std::string negative_prompt = json_string(body, "negativePrompt");
    std::string image_path = json_string(body, "imagePath");
    const std::string sampler = json_string(body, "sampler");
    // Typed ABI v2 fields. Absent keys leave every existing behaviour (and the V1 request
    // payload) untouched; initImagePath is an alias for imagePath so both engines see the same
    // staged start frame.
    const std::string init_image_path = json_string(body, "initImagePath");
    if (!init_image_path.empty()) image_path = init_image_path;
    const std::string end_image_path = json_string(body, "endImagePath");
    const std::string scheduler_name = json_string(body, "scheduler");
    const std::string tae_path = json_string(body, "taePath");
    const std::string vae_profile_name = json_string(body, "vaeProfile");

    int64_t width = 0, height = 0, frames = 0, fps = 0, steps = 0, seed = 0, threads = 4;
    double guidance = 0.0, flow_shift = 0.0;
    if (!json_int64(body, "width", width) || !json_int64(body, "height", height) ||
        !json_int64(body, "frames", frames) || !json_int64(body, "fps", fps) ||
        !json_int64(body, "steps", steps) || !json_int64(body, "seed", seed) ||
        !json_double(body, "guidance", guidance) || !json_double(body, "flowShift", flow_shift)) {
        return failure("INPUT_INVALID", "missing or non-numeric video parameters");
    }
    json_int64(body, "threads", threads);
    // Diagnostic-only seam: the production adapter never sets this, so the default stays true.
    // The opt-in budget probe sets it false to test whether CPU flash attention neutralises the
    // cross-attention path (the leading hypothesis for prompt-independent output).
    const bool flash_attn = body.value("flashAttn", true);
    // Optional VAE tiling. Off by default so existing behaviour and outputs are unchanged; a
    // full-frame decode is what made 480x832x9 exceed the 20-minute no-progress watchdog.
    const bool vae_tiling = body.value("vaeTiling", false);
    const int tile_x = static_cast<int>(body.value("tileSizeX", 0));
    const int tile_y = static_cast<int>(body.value("tileSizeY", 0));
    const double tile_overlap = body.value("tileOverlap", 0.5);
    // Optional 4-step distillation LoRA (lightx2v). Production never sends it, so behaviour is
    // unchanged unless a caller passes both loraPath and a positive loraWeight.
    const std::string lora_path = body.value("loraPath", std::string());
    const float lora_weight = static_cast<float>(body.value("loraWeight", 0.0));
    const bool lora_active = !lora_path.empty() && lora_weight > 0.0f;
    // Optional alternate video backend. Empty (default) keeps the built-in MCA engine path.
    const std::string engine = body.value("engine", std::string());
    if (!engine.empty() && engine != "sdcpp_external") {
        return failure("INPUT_INVALID", "unsupported video engine");
    }
    // External-engine backend selection. "htp" is the production default; "cpu" is the
    // compatibility path used when the platform cannot load an unsigned HTP PD. The value only
    // applies to engine=sdcpp_external, and an explicit value without that engine is rejected
    // instead of silently ignored.
    const bool backend_specified = body.contains("backend");
    const std::string backend = body.value("backend", std::string("htp"));
    if (backend != "cpu" && backend != "htp") {
        return failure("INPUT_INVALID", "backend must be \"cpu\" or \"htp\"");
    }
    if (backend_specified && engine != "sdcpp_external") {
        return failure("INPUT_INVALID", "backend requires engine=sdcpp_external");
    }
    // Module-specific assignment for the external engine. These used to be hardcoded to cpu/disk;
    // they are forwarded verbatim now so a caller can pin text encoder / VAE / parameter residency
    // independently of the diffusion device. Empty keeps the previous default.
    std::string te_backend = body.value("teBackend", std::string("cpu"));
    std::string vae_backend = body.value("vaeBackend", std::string("cpu"));
    std::string params_backend = body.value("paramsBackend", std::string("disk"));
    const bool modules_specified = body.contains("teBackend") || body.contains("vaeBackend") ||
                                   body.contains("paramsBackend");
    if (modules_specified && engine != "sdcpp_external") {
        return failure("INPUT_INVALID", "teBackend/vaeBackend/paramsBackend require engine=sdcpp_external");
    }
    // Compute modules accept a device assignment; paramsBackend names where the parameter blocks
    // live, which includes the disk spill the video pipeline relies on. Validating both with the
    // same device-only whitelist rejected every request that kept the "disk" default.
    for (const std::string* value : {&te_backend, &vae_backend}) {
        if (!value->empty() && *value != "cpu" && *value != "htp") {
            return failure("INPUT_INVALID", "teBackend/vaeBackend must be \"cpu\", \"htp\" or empty");
        }
    }
    if (!params_backend.empty() && params_backend != "cpu" && params_backend != "htp" &&
        params_backend != "disk" && params_backend != "ram") {
        return failure("INPUT_INVALID", "paramsBackend must be \"disk\", \"ram\", \"cpu\", \"htp\" or empty");
    }

    if (prompt.empty()) return failure("INPUT_INVALID", "prompt is empty");
    if (width <= 0 || height <= 0 || width > kMaxDimension || height > kMaxDimension) {
        return failure("INPUT_INVALID", "video dimensions are out of range");
    }
    if (width % 32 != 0 || height % 32 != 0) {
        return failure("INPUT_INVALID", "video dimensions must be multiples of 32");
    }
    if (width * height > kMaxPixels) return failure("INPUT_INVALID", "video resolution exceeds the spool budget");

    if (frames <= 0 || frames > kMaxFrames || (frames - 1) % 4 != 0) {
        return failure("INPUT_INVALID", "frame count must be 4k+1 within the supported bound");
    }
    if (fps <= 0 || fps > 120) return failure("INPUT_INVALID", "fps is out of range");
    if (steps <= 0 || steps > 200) return failure("INPUT_INVALID", "steps are out of range");
    if (sampler != "euler") return failure("INPUT_INVALID", "only the pinned euler sampler is supported");
    if (!std::isfinite(guidance) || guidance <= 0.0 || guidance > 100.0) {
        return failure("INPUT_INVALID", "guidance is out of range");
    }
    if (!std::isfinite(flow_shift) || flow_shift <= 0.0 || flow_shift > 100.0) {
        return failure("INPUT_INVALID", "flow shift is out of range");
    }

    // --- typed ABI v2 request fields -------------------------------------------------------
    // Parsing mirrors the existing explicit failure() style: a malformed value is INPUT_INVALID,
    // a valid value the selected engine cannot honour is also INPUT_INVALID (never dropped).
    int32_t scheduler_id = MCA_WAN_SCHEDULER_ENGINE_DEFAULT;
    if (!scheduler_name.empty() && !scheduler_from_name(scheduler_name, scheduler_id)) {
        return failure("INPUT_INVALID", "unsupported scheduler");
    }
    int32_t vae_profile = MCA_WAN_VAE_PROFILE_DEFAULT;
    if (!vae_profile_name.empty()) {
        if (vae_profile_name == "tae") {
            vae_profile = MCA_WAN_VAE_PROFILE_TAE;
        } else if (vae_profile_name != "default") {
            return failure("INPUT_INVALID", "vaeProfile must be \"default\" or \"tae\"");
        }
    }
    std::vector<float> custom_sigmas;
    auto custom_sigmas_it = body.find("customSigmas");
    if (custom_sigmas_it != body.end()) {
        if (!custom_sigmas_it->is_array() || custom_sigmas_it->size() < 2 || custom_sigmas_it->size() > 4096) {
            return failure("INPUT_INVALID", "customSigmas must be an array of 2..4096 sigmas");
        }
        for (const auto &value : *custom_sigmas_it) {
            if (!value.is_number() || !std::isfinite(value.get<double>())) {
                return failure("INPUT_INVALID", "customSigmas must contain finite numbers");
            }
            custom_sigmas.push_back(static_cast<float>(value.get<double>()));
        }
    }
    std::vector<std::string> external_lora_paths;
    std::vector<float> external_lora_weights;
    auto external_loras_it = body.find("loras");
    if (external_loras_it != body.end()) {
        if (!external_loras_it->is_array() || external_loras_it->size() > 16) {
            return failure("INPUT_INVALID", "loras must be an array of at most 16 entries");
        }
        for (const auto &entry : *external_loras_it) {
            if (!entry.is_object()) return failure("INPUT_INVALID", "each loras entry must be an object");
            const std::string path = entry.value("path", std::string());
            if (path.empty()) return failure("INPUT_INVALID", "a loras entry has no path");
            const auto weight_it = entry.find("weight");
            if (weight_it == entry.end() || !weight_it->is_number()) {
                return failure("INPUT_INVALID", "a loras entry has no numeric weight");
            }
            const double weight = weight_it->get<double>();
            if (!std::isfinite(weight) || weight < 0.0 || weight > 8.0) {
                return failure("INPUT_INVALID", "a loras entry weight is out of range");
            }
            external_lora_paths.push_back(path);
            external_lora_weights.push_back(static_cast<float>(weight));
        }
    }
    // The legacy single-LoRA field below only reflects `loraPath`; the array is the product path.
    // Printing the count keeps evidence honest instead of `lora=off` while weights are applied.
    log_line("external loras=" + std::to_string(external_lora_paths.size()));
    int32_t spatial_tiling = 0, temporal_tiling = 1, tile_size = 0, tile_frames = 1;
    auto tiling_it = body.find("tiling");
    if (tiling_it != body.end()) {
        if (!tiling_it->is_object()) return failure("INPUT_INVALID", "tiling must be an object");
        for (const char *key : {"spatial", "temporal"}) {
            auto it = tiling_it->find(key);
            if (it != tiling_it->end() && !it->is_boolean()) {
                return failure("INPUT_INVALID", "tiling flags must be booleans");
            }
        }
        for (const char *key : {"tileSize", "tileFrames"}) {
            auto it = tiling_it->find(key);
            if (it != tiling_it->end() && !it->is_number_integer()) {
                return failure("INPUT_INVALID", "tiling sizes must be integers");
            }
        }
        spatial_tiling = tiling_it->value("spatial", false) ? 1 : 0;
        temporal_tiling = tiling_it->value("temporal", true) ? 1 : 0;
        tile_size = static_cast<int32_t>(tiling_it->value("tileSize", 0));
        tile_frames = static_cast<int32_t>(tiling_it->value("tileFrames", 1));
        if (tile_size < 0 || tile_size > 4096 || tile_frames < 0 || tile_frames > 64) {
            return failure("INPUT_INVALID", "tiling values are out of range");
        }
    }
    int32_t offload_to_cpu = 1;
    auto offload_it = body.find("offloadToCpu");
    if (offload_it != body.end()) {
        if (!offload_it->is_boolean() && !offload_it->is_number_integer()) {
            return failure("INPUT_INVALID", "offloadToCpu must be a boolean or an integer");
        }
        const bool offload = offload_it->is_boolean() ? offload_it->get<bool>() : offload_it->get<int64_t>() != 0;
        offload_to_cpu = offload ? 1 : 0;
    }
    double max_vram_gib = 2.0;
    if (body.contains("maxVram")) {
        if (!json_double(body, "maxVram", max_vram_gib)) {
            return failure("INPUT_INVALID", "maxVram must be a finite number");
        }
        if (max_vram_gib < 0.0 || max_vram_gib > 64.0) {
            return failure("INPUT_INVALID", "maxVram is out of range");
        }
    }
    // These fields are only honoured by the external engine. Rejecting them for the built-in
    // engine is deliberate: parsing and then ignoring a caller field is what V1 did wrong.
    if (engine != "sdcpp_external") {
        if (!scheduler_name.empty() && scheduler_name != "discrete") {
            return failure("INPUT_INVALID", "scheduler requires engine=sdcpp_external (built-in is pinned to discrete)");
        }
        if (!custom_sigmas.empty()) return failure("INPUT_INVALID", "customSigmas requires engine=sdcpp_external");
        if (!end_image_path.empty()) return failure("INPUT_INVALID", "endImagePath requires engine=sdcpp_external");
        if (!external_lora_paths.empty()) {
            return failure("INPUT_INVALID", "loras requires engine=sdcpp_external (use loraPath/loraWeight)");
        }
        if (!tae_path.empty() || vae_profile != MCA_WAN_VAE_PROFILE_DEFAULT) {
            return failure("INPUT_INVALID", "taePath/vaeProfile require engine=sdcpp_external");
        }
        if (body.contains("tiling")) {
            return failure("INPUT_INVALID", "tiling requires engine=sdcpp_external (use vaeTiling/tileSizeX/tileSizeY)");
        }
        if (body.contains("offloadToCpu")) return failure("INPUT_INVALID", "offloadToCpu requires engine=sdcpp_external");
        if (body.contains("maxVram")) return failure("INPUT_INVALID", "maxVram requires engine=sdcpp_external");
    }
    if (!is_directory(staging_root) || !is_directory(run_dir)) {
        return failure("INPUT_INVALID", "run directory must be a child of the staging root");
    }
    char canonical_run[PATH_MAX] = {};
    char canonical_root[PATH_MAX] = {};
    if (realpath(run_dir.c_str(), canonical_run) == nullptr || realpath(staging_root.c_str(), canonical_root) == nullptr ||
        !safe_child_path(canonical_root, canonical_run)) {
        return failure("INPUT_INVALID", "run directory must resolve inside the staging root");
    }
    if (!is_regular_file(dit_path) || !is_regular_file(encoder_path) || !is_regular_file(vae_path)) {
        return failure("MODEL_MISSING", "one or more pinned model components are missing");
    }
    if (!image_path.empty()) {
        if (!is_regular_file(image_path) || !safe_child_path(canonical_root, image_path)) {
            return failure("INPUT_INVALID", "init image must be a staged regular file");
        }
    }
    if (!end_image_path.empty()) {
        if (!is_regular_file(end_image_path) || !safe_child_path(canonical_root, end_image_path)) {
            return failure("INPUT_INVALID", "end image must be a staged regular file");
        }
    }
    if (!tae_path.empty()) {
        if (!is_regular_file(tae_path) || !safe_child_path(canonical_root, tae_path)) {
            return failure("INPUT_INVALID", "TAE decoder must be a staged regular file");
        }
    }
    for (const auto &lora_file : external_lora_paths) {
        // Both sides are resolved: /data/data and /data/user/0 name the same directory, and a caller
        // cannot know which form the app's staging root resolved to. Resolving also rejects a symlink
        // that escapes the staging root (the app process cannot read outside it anyway).
        char canonical_lora[PATH_MAX] = {};
        if (!is_regular_file(lora_file) || realpath(lora_file.c_str(), canonical_lora) == nullptr ||
            !safe_child_path(canonical_root, canonical_lora)) {
            return failure("INPUT_INVALID", std::string("LoRA weights must be a staged regular file under ") + canonical_root + ": " + lora_file);
        }
    }

    const std::string frame_dir = run_dir + "/frames";
    std::string directory_error;
    if (!ensure_empty_directory(frame_dir, directory_error)) {
        return failure("MEDIA_VERIFY_FAILED", directory_error);
    }

    const int64_t frame_bytes = width * height * 3;
    const int64_t required = frame_bytes * frames + kReserveBytes;
    struct statvfs space {};
    if (statvfs(run_dir.c_str(), &space) != 0) {
        return failure("DISK_FULL", "cannot query the staging filesystem");
    }
    const uint64_t available = static_cast<uint64_t>(space.f_bavail) * space.f_frsize;
    if (required <= 0 || required > kMaxSpoolBytes || available < static_cast<uint64_t>(required)) {
        return failure("DISK_FULL", "insufficient staging space for the decoded frame spool");
    }

    FrameImage init_image;
    int init_width = 0, init_height = 0, init_channels = 0;
    if (!image_path.empty()) {
        init_image.data = stbi_load(image_path.c_str(), &init_width, &init_height, &init_channels, 3);
        if (init_image.data == nullptr || init_width <= 0 || init_height <= 0) {
            return failure("INPUT_INVALID", "init image could not be decoded to RGB");
        }
        if (static_cast<int64_t>(init_width) * init_height > kMaxPixels) {
            return failure("INPUT_INVALID", "init image resolution exceeds the bound");
        }
    }

    g_video_cancel.store(false, std::memory_order_relaxed);
    set_progress("loading");
    sd_set_log_callback(sd_log_callback, nullptr);
    sd_set_progress_callback(progress_callback, nullptr);
    sd_set_cancel_callback(cancel_callback, nullptr);
    CallbackGuard callback_guard;

    SdContext context;
    sd_ctx_params_t ctx_params;
    sd_ctx_params_init(&ctx_params);
    ctx_params.diffusion_model_path = dit_path.c_str();
    ctx_params.t5xxl_path = encoder_path.c_str();
    ctx_params.vae_path = vae_path.c_str();
    ctx_params.vae_decode_only = false;
    ctx_params.free_params_immediately = false;
    ctx_params.n_threads = static_cast<int>(std::max<int64_t>(1, std::min<int64_t>(threads, 16)));
    ctx_params.enable_mmap = true;
    ctx_params.keep_clip_on_cpu = true;
    ctx_params.keep_vae_on_cpu = true;
    ctx_params.flash_attn = flash_attn;
    ctx_params.diffusion_flash_attn = flash_attn;
    ctx_params.backend = "cpu";
    ctx_params.params_backend = "cpu";
    ctx_params.max_vram = 0.0f;
    ctx_params.rng_type = CPU_RNG;
    ctx_params.sampler_rng_type = CPU_RNG;
    if (engine.empty()) {
    context.ctx = new_sd_ctx(&ctx_params);
    if (context.ctx == nullptr) {
        return failure("MODEL_LOAD_FAILED", "stable-diffusion.cpp could not create the video context");
    }
    }

    // Evidence lines for conditioning/geometry diagnosis. They deliberately log a prompt
    // fingerprint (never the prompt text) so two runs can be compared without persisting input.
    if (context.ctx != nullptr) log_line(std::string("context model=") +
             (sd_ctx_model_version_name(context.ctx) != nullptr ? sd_ctx_model_version_name(context.ctx) : "unknown") +
             " supports_video=" + (sd_ctx_supports_video_generation(context.ctx) ? "true" : "false") +
             " distilled_guidance=" + (sd_ctx_uses_distilled_guidance(context.ctx) ? "true" : "false"));
    log_line("request geometry=" + std::to_string(width) + "x" + std::to_string(height) +
             " frames=" + std::to_string(frames) + " steps=" + std::to_string(steps) +
             " seed=" + std::to_string(seed) +
             " guidance=" + std::to_string(guidance) +
             " flow_shift=" + std::to_string(flow_shift) +
             " flash_attn=" + (flash_attn ? "true" : "false") + " vae_tiling=" + (vae_tiling ? std::to_string(tile_x) + "x" + std::to_string(tile_y) + "@" + std::to_string(tile_overlap) : std::string("off")) +
             " lora=" + (!lora_active ? std::string("off") : lora_path + "@" + std::to_string(lora_weight)) + " prompt_fp=" + prompt_fingerprint(prompt) +
             " negative_fp=" + (negative_prompt.empty() ? std::string("none") : prompt_fingerprint(negative_prompt)));

    sd_vid_gen_params_t params;
    sd_vid_gen_params_init(&params);
    params.prompt = prompt.c_str();
    params.negative_prompt = negative_prompt.empty() ? nullptr : negative_prompt.c_str();
    params.width = static_cast<int>(width);
    params.height = static_cast<int>(height);
    params.video_frames = static_cast<int>(frames);
    params.fps = static_cast<int>(fps);
    params.seed = seed;
    params.sample_params.sample_steps = static_cast<int>(steps);
    params.sample_params.sample_method = EULER_SAMPLE_METHOD;
    params.sample_params.scheduler = DISCRETE_SCHEDULER;
    params.vae_tiling_params.enabled = vae_tiling;
    params.vae_tiling_params.temporal_tiling = vae_tiling;
    params.vae_tiling_params.tile_size_x = tile_x;
    params.vae_tiling_params.tile_size_y = tile_y;
    params.vae_tiling_params.target_overlap = static_cast<float>(tile_overlap);
    params.vae_tiling_params.rel_size_x = 0.0f;
    params.vae_tiling_params.rel_size_y = 0.0f;
    params.vae_tiling_params.extra_tiling_args = nullptr;
    sd_lora_t lora{};
    lora.is_high_noise = false;
    lora.multiplier = lora_weight;
    lora.path = lora_path.c_str();
        if (lora_active) {
        params.loras = &lora;
        params.lora_count = 1;
    }
    params.sample_params.eta = 0.0f;
    params.sample_params.guidance.txt_cfg = static_cast<float>(guidance);
    params.sample_params.flow_shift = static_cast<float>(flow_shift);
    params.sample_params.shifted_timestep = 0;
    if (!image_path.empty()) {
        params.init_image = sd_image_t{
            static_cast<uint32_t>(init_width),
            static_cast<uint32_t>(init_height),
            3,
            init_image.data,
        };
    }

    set_progress("conditioning");
    const int64_t started_ms = static_cast<int64_t>(ggml_time_ms());
    sd_image_t *raw_frames = nullptr;
    int raw_count = 0;
    // True when the external adapter already published the exact PPM files this run returns.
    bool raw_frames_spooled = false;
    bool ok = false;
    // Actual external backend that runs (empty when the built-in engine ran).
    std::string effective_external_backend;
    // Non-empty only when the pre-flight HTP availability check switched this run to cpu.
    std::string external_backend_fallback;
    std::string external_backend_reason;
    if (engine == "sdcpp_external") {
        std::string engine_error;
        // Decision point: BEFORE any context/weight creation. Only an unavailable HTP (FastRPC
        // device/permission) switches this run to cpu. Once execution starts, every failure 闂?        // execution error, OOM, timeout, cancel, thermal 闂?is reported as-is and never retried on
        // another backend.
        std::string actual_backend = backend;
        if (actual_backend == "htp") {
            const std::string htp_reason = htp_preflight_unavailable_reason();
            if (!htp_reason.empty()) {
                actual_backend = "cpu";
                external_backend_fallback = "htp->cpu";
                external_backend_reason = htp_reason;
                log_line("external video engine fallback=htp->cpu reason=" + htp_reason);
            }
        }
        effective_external_backend = actual_backend;
        // Sampling start/finish boundaries as before; the v2 adapter additionally reports real
        // per-step progress through the same channel, so waiters see actual sampler progress.
        progress_callback(0, static_cast<int>(steps), 0.0f, nullptr);
        ExternalVideoInputs external;
        external.out_dir = frame_dir;
        if (push_frames) {
            external.frame_consumer      = mca_jni_frame_consumer;
            external.frame_consumer_user = nullptr;
        }
        external.prompt = prompt;
        external.negative_prompt = negative_prompt;
        external.dit_path = dit_path;
        external.te_path = encoder_path;
        external.vae_path = vae_path;
        external.init_image_path = image_path;
        external.end_image_path = end_image_path;
        external.tae_path = tae_path;
        external.diffusion_backend = actual_backend;
        external.te_backend = te_backend;
        external.vae_backend = vae_backend;
        external.params_backend = params_backend;
        external.lora_paths = external_lora_paths;
        external.lora_weights = external_lora_weights;
        external.custom_sigmas = custom_sigmas;
        external.width = width;
        external.height = height;
        external.frames = frames;
        external.fps = fps;
        external.steps = steps;
        external.seed = seed;
        external.threads = threads;
        external.guidance = static_cast<float>(guidance);
        external.flow_shift = static_cast<float>(flow_shift);
        external.max_vram_gib = static_cast<float>(max_vram_gib);
        external.sampler = MCA_WAN_SAMPLER_EULER;
        external.scheduler = scheduler_id;
        external.vae_profile = vae_profile;
        external.offload_to_cpu = offload_to_cpu;
        external.spatial_tiling = spatial_tiling;
        external.temporal_tiling = temporal_tiling;
        external.tile_size = tile_size;
        external.tile_frames = tile_frames;
        // flashAttn is a diagnostic seam for the built-in engine (default true). The external
        // engine historically ran without flash attention, so only an explicit key changes it.
        external.flash_attn = body.contains("flashAttn") && flash_attn ? 1 : 0;
        const ExternalVideoOutcome outcome =
            run_external_video_into_frames(external, &raw_frames, &raw_count, &engine_error,
                                           &effective_external_backend, &raw_frames_spooled);
        if (outcome == ExternalVideoOutcome::kCancelled) {
            set_progress("cancelled");
            return json{
                {"status", "cancelled"},
                {"detail", "external video engine observed the cancel request"},
                {"elapsedMs", static_cast<int64_t>(ggml_time_ms()) - started_ms},
            };
        }
        if (outcome == ExternalVideoOutcome::kEngineLoadFailed) {
            // Deliberately not NATIVE_EXECUTION_FAILED: the engine never ran, and the detail names
            // the symbol that is missing.
            return failure("ENGINE_LOAD_FAILED", engine_error);
        }
        if (outcome != ExternalVideoOutcome::kOk) {
            return failure("NATIVE_EXECUTION_FAILED", engine_error);
        }
        ok = true;
        progress_callback(static_cast<int>(steps), static_cast<int>(steps), 0.0f, nullptr);
        set_progress("decoding");
        set_progress("encoding");
    } else {
        ok = generate_video(context.ctx, &params, &raw_frames, &raw_count, nullptr);
    }
    FrameArray decoded{raw_frames, raw_count};
    const int64_t elapsed_ms = static_cast<int64_t>(ggml_time_ms()) - started_ms;

    if (g_video_cancel.load(std::memory_order_relaxed)) {
        set_progress("cancelled");
        return json{
            {"status", "cancelled"},
            {"detail", "native sampling stopped after the cancel callback"},
            {"elapsedMs", elapsed_ms},
        };
    }
    if (!ok) {
        return failure("NATIVE_EXECUTION_FAILED", "generate_video returned false");
    }
    if (decoded.count != static_cast<int>(frames)) {
        // The frame rule was validated above; a mismatch is a real backend defect, not a value
        // to silently round. The run must fail instead of shipping a shorter clip.
        return failure("NATIVE_EXECUTION_FAILED", "native returned " + std::to_string(decoded.count) +
                                                        " frames, expected " + std::to_string(frames));
    }

    set_progress("spooling");
    const bool pushed = engine == "sdcpp_external" && raw_frames_spooled && push_frames;
    json names = json::array();
    std::vector<std::string> written;
    written.reserve(static_cast<size_t>(decoded.count));
    int64_t total_bytes = 0;
    // Push delivery already handed every frame to the consumer: there is no frame array and no
    // file to name. Indexing decoded.frames here would dereference a null array.
    for (int i = 0; i < decoded.count; ++i) {
        if (g_video_cancel.load(std::memory_order_relaxed)) {
            for (const auto &path : written) unlink(path.c_str());
            set_progress("cancelled");
            return json{
                {"status", "cancelled"},
                {"detail", "cancel requested while spooling decoded frames"},
                {"elapsedMs", elapsed_ms},
            };
        }
        char name[64] = {};
        snprintf(name, sizeof(name), "frame_%05d.ppm", i);
        if (pushed) {
            // Frames went to the consumer, but the response contract still names every frame and
            // reports its payload size so the worker's frame-count rule stays identical.
            names.push_back(name);
            total_bytes += frame_bytes;
            continue;
        }
        const std::string path = frame_dir + "/" + name;
        if (raw_frames_spooled) {
            // Already on disk in the published format; only record it, never rewrite the bytes.
            written.push_back(path);
            names.push_back(name);
            total_bytes += frame_bytes;
            continue;
        }
        const sd_image_t &frame = decoded.frames[i];
        if (frame.width != static_cast<uint32_t>(width) || frame.height != static_cast<uint32_t>(height)) {
            for (const auto &path : written) unlink(path.c_str());
            return failure("NATIVE_EXECUTION_FAILED", "native frame geometry differs from the request");
        }
        std::string write_error;
        if (!write_frame(path, frame, write_error)) {
            for (const auto &written_path : written) unlink(written_path.c_str());
            return failure("MEDIA_VERIFY_FAILED", write_error);
        }
        written.push_back(path);
        names.push_back(name);
        total_bytes += frame_bytes;
        std::free(decoded.frames[i].data);
        decoded.frames[i].data = nullptr;
    }

    set_progress("done");
    log_line("video generation completed: " + std::to_string(decoded.count) + " frames in " +
             std::to_string(elapsed_ms) + " ms");
    // Built-in runs stay on the pinned discrete scheduler; external runs echo the typed id they
    // actually passed (or "engine_default" when the caller left the choice to the engine).
    const std::string effective_scheduler =
        engine != "sdcpp_external"
            ? std::string("discrete")
            : (scheduler_id == MCA_WAN_SCHEDULER_ENGINE_DEFAULT ? std::string("engine_default")
                                                                : scheduler_name);
    return json{
        {"status", "succeeded"},
        {"frameCount", decoded.count},
        {"width", width},
        {"height", height},
        {"fps", fps},
        {"frames", names},
        {"frameDir", frame_dir},
        {"frameBytes", total_bytes},
        {"elapsedMs", elapsed_ms},
        {"effective",
         {
             {"steps", steps},
             {"sampler", sampler},
             {"guidance", guidance},
             {"flowShift", flow_shift},
             {"seed", seed},
             {"scheduler", effective_scheduler},
             {"width", width},
             {"height", height},
             {"frames", frames},
             {"fps", fps},
             // Actual external backend for this run (cpu after a successful htp fallback), and
             // the non-empty marker that proves a fallback happened. Empty for the built-in engine.
             {"externalBackend", effective_external_backend},
             {"externalBackendFallback", external_backend_fallback},
             {"externalBackendReason", external_backend_reason},
         }},
    };
}

} // namespace

// Push delivery for generateVideoStreaming. The consumer runs on the thread that called into JNI
// (generate_video_streaming is synchronous), so the entry's env stays valid for the whole run and
// no attached-thread bookkeeping is needed. Both are only readable while g_video_mutex is held.
static JNIEnv *g_frame_env          = nullptr;
static jobject g_frame_listener     = nullptr;
static jmethodID g_frame_on_frame   = nullptr;

int mca_jni_frame_consumer(void *, const uint8_t *rgb, int32_t width, int32_t height, int64_t index) {
    JNIEnv *env = g_frame_env;
    if (env == nullptr || g_frame_listener == nullptr || rgb == nullptr || width <= 0 || height <= 0) {
        return 1;
    }
    const jsize bytes = static_cast<jsize>(static_cast<int64_t>(width) * height * 3);
    jbyteArray array = env->NewByteArray(bytes);
    if (array == nullptr) return 1;
    env->SetByteArrayRegion(array, 0, bytes, reinterpret_cast<const jbyte *>(rgb));
    const jboolean keep_going = env->CallBooleanMethod(g_frame_listener, g_frame_on_frame,
                                                       static_cast<jint>(index), array);
    env->DeleteLocalRef(array);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return 1;
    }
    return keep_going == JNI_TRUE ? 0 : 1;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_generateVideoStreaming(
        JNIEnv *env,
        jobject,
        jstring request_json,
        jobject frame_listener) {
    if (env == nullptr || request_json == nullptr || frame_listener == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(g_video_mutex);
    jclass listener_class = env->GetObjectClass(frame_listener);
    jmethodID on_frame = listener_class == nullptr
                             ? nullptr
                             : env->GetMethodID(listener_class, "onFrame", "(I[B)Z");
    if (on_frame == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }
    g_frame_env        = env;
    g_frame_listener   = frame_listener;
    g_frame_on_frame   = on_frame;
    const char *chars = env->GetStringUTFChars(request_json, nullptr);
    if (chars == nullptr) {
        g_frame_listener = nullptr;
        g_frame_env      = nullptr;
        return nullptr;
    }
    std::string request(chars);
    env->ReleaseStringUTFChars(request_json, chars);
    json result;
    try {
        result = execute(request, true);
    } catch (const std::exception &error) {
        result = failure("NATIVE_EXECUTION_FAILED", std::string("uncaught native error: ") + error.what());
    } catch (...) {
        result = failure("NATIVE_EXECUTION_FAILED", "uncaught native error");
    }
    g_frame_listener = nullptr;
    g_frame_env      = nullptr;
    const std::string payload = result.dump();
    return env->NewStringUTF(payload.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_generateVideo(
        JNIEnv *env,
        jobject,
        jstring request_json) {
    if (env == nullptr || request_json == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(g_video_mutex);
    const char *chars = env->GetStringUTFChars(request_json, nullptr);
    if (chars == nullptr) return nullptr;
    std::string request(chars);
    env->ReleaseStringUTFChars(request_json, chars);
    json result;
    try {
        result = execute(request);
    } catch (const std::exception &error) {
        result = failure("NATIVE_EXECUTION_FAILED", std::string("uncaught native error: ") + error.what());
    } catch (...) {
        result = failure("NATIVE_EXECUTION_FAILED", "uncaught native error");
    }
    const std::string payload = result.dump();
    return env->NewStringUTF(payload.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_getVideoProgress(
        JNIEnv *env,
        jobject) {
    if (env == nullptr) return nullptr;
    std::string progress;
    {
        std::lock_guard<std::mutex> lock(g_progress_mutex);
        progress = g_video_progress;
    }
    return env->NewStringUTF(progress.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_muyuchat_core_sdnative_NativeStableDiffusionBridge_cancelVideo(
        JNIEnv *,
        jobject) {
    g_video_cancel.store(true, std::memory_order_relaxed);
    set_progress("cancelling");
}
