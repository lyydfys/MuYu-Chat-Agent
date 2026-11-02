#ifndef MCA_WAN_API_V2_H
#define MCA_WAN_API_V2_H

/*
 * Typed, versioned ABI for the MCA Wan 2.2 TI2V external video adapter (libmca_wan.so).
 *
 * The adapter is built out of tree against a stable-diffusion.cpp that exposes
 * generate_video_streaming()/sd_cancel_generation(); this header is deliberately free of any
 * engine or Android dependency so the app-side caller (sd_video_generate.cpp) can include it too.
 *
 * ABI RULES
 * - Both structs start with `struct_size` and `version`. Callers MUST fill both before the call.
 *   `struct_size` must equal the callee's sizeof exactly. A caller built against a different
 *   header is REJECTED with MCA_WAN_ERR_ABI_MISMATCH instead of having its fields read at the
 *   wrong offsets — a "smaller or equal" test is not enough, because a library built from an
 *   older header would pass it and then write the result at stale offsets.
 * - The ABI is append-only: new fields are only ever added at the end of the request struct and
 *   of the result struct. Field order of existing members never changes, and adding a field
 *   requires a fresh build of both the adapter and every caller.
 * - MCA_WAN_ABI_VERSION changes for any layout or semantic change. It is the second, independent
 *   check so a stale library cannot masquerade as compatible even if the sizes happen to match.
 *
 * OWNERSHIP
 * - Every pointer in the request is BORROWED for the duration of the mca_wan_generate_v2() call
 *   only. The adapter never stores, frees or takes ownership of any of them (paths, prompt strings,
 *   custom sigma array, LoRA array, out_dir). The caller must keep them alive and unchanged until
 *   the call returns; the adapter does not copy them into the result either.
 * - Nothing in the result is heap-allocated: every string lives in a fixed-size char buffer that
 *   the caller already owns. There is nothing to free after a call.
 *
 * UNSUPPORTED FIELDS
 * - A request field that carries a non-default value the adapter cannot honour is REJECTED with
 *   MCA_WAN_ERR_UNSUPPORTED_FIELD and `unsupported_field` names it. Silent dropping of a caller
 *   field is an ABI violation, not a fallback.
 */

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Bump only together with a breaking layout/semantic change. */
#define MCA_WAN_ABI_VERSION 2u

/* Overall outcome of one call. */
typedef enum mca_wan_status {
    MCA_WAN_STATUS_OK          = 0,
    MCA_WAN_STATUS_CANCELLED   = 1,
    MCA_WAN_STATUS_FAILED      = 2,
    MCA_WAN_STATUS_UNSUPPORTED = 3
} mca_wan_status_t;

/* Stable failure categories. Values are part of the ABI; append only. */
typedef enum mca_wan_error_code {
    MCA_WAN_ERR_NONE              = 0,
    MCA_WAN_ERR_INVALID_ARGUMENT  = 1, /* missing/out-of-range value, or contradictory fields */
    MCA_WAN_ERR_ABI_MISMATCH      = 2, /* struct_size/version mismatch, or missing symbol */
    MCA_WAN_ERR_UNSUPPORTED_FIELD = 3, /* a field is set but this adapter cannot honour it */
    MCA_WAN_ERR_CONTEXT_CREATE    = 4, /* model/context construction failed */
    MCA_WAN_ERR_SAMPLE            = 5, /* failure before the first decoded frame (sampling) */
    MCA_WAN_ERR_DECODE            = 6, /* failure after sampling started delivering frames */
    MCA_WAN_ERR_CANCELLED         = 7, /* caller cancel observed */
    MCA_WAN_ERR_IO                = 8  /* frame spool or image file could not be read/written */
} mca_wan_error_code_t;

/*
 * Sampler ids. These values are the MCA ABI's own and are mapped to the engine's
 * sample_method_t by name inside the adapter, so an engine enum reorder cannot silently change
 * the sampler a caller asked for. ENGINE_DEFAULT lets the engine pick for the loaded model.
 */
typedef enum mca_wan_sampler {
    MCA_WAN_SAMPLER_ENGINE_DEFAULT = 0,
    MCA_WAN_SAMPLER_EULER          = 1,
    MCA_WAN_SAMPLER_EULER_A        = 2,
    MCA_WAN_SAMPLER_HEUN           = 3,
    MCA_WAN_SAMPLER_DPM2           = 4,
    MCA_WAN_SAMPLER_DPMPP2S_A      = 5,
    MCA_WAN_SAMPLER_DPMPP2M        = 6,
    MCA_WAN_SAMPLER_DPMPP2MV2      = 7,
    MCA_WAN_SAMPLER_IPNDM          = 8,
    MCA_WAN_SAMPLER_IPNDM_V        = 9,
    MCA_WAN_SAMPLER_LCM            = 10,
    MCA_WAN_SAMPLER_DDIM_TRAILING  = 11,
    MCA_WAN_SAMPLER_TCD            = 12,
    MCA_WAN_SAMPLER_RES_MULTISTEP  = 13,
    MCA_WAN_SAMPLER_RES_2S         = 14,
    MCA_WAN_SAMPLER_ER_SDE         = 15,
    MCA_WAN_SAMPLER_EULER_CFG_PP   = 16,
    MCA_WAN_SAMPLER_EULER_A_CFG_PP = 17,
    MCA_WAN_SAMPLER_EULER_GE       = 18,
    MCA_WAN_SAMPLER_DPMPP2M_SDE    = 19,
    MCA_WAN_SAMPLER_DPMPP2M_SDE_BT = 20,
    MCA_WAN_SAMPLER_LMS            = 21
} mca_wan_sampler_t;

/* Scheduler ids, same rule as the sampler ids. */
typedef enum mca_wan_scheduler {
    MCA_WAN_SCHEDULER_ENGINE_DEFAULT = 0,
    MCA_WAN_SCHEDULER_DISCRETE       = 1,
    MCA_WAN_SCHEDULER_KARRAS         = 2,
    MCA_WAN_SCHEDULER_EXPONENTIAL    = 3,
    MCA_WAN_SCHEDULER_AYS            = 4,
    MCA_WAN_SCHEDULER_GITS           = 5,
    MCA_WAN_SCHEDULER_SGM_UNIFORM    = 6,
    MCA_WAN_SCHEDULER_SIMPLE         = 7,
    MCA_WAN_SCHEDULER_SMOOTHSTEP     = 8,
    MCA_WAN_SCHEDULER_KL_OPTIMAL     = 9,
    MCA_WAN_SCHEDULER_LCM            = 10,
    MCA_WAN_SCHEDULER_BONG_TANGENT   = 11,
    MCA_WAN_SCHEDULER_LTX2           = 12,
    MCA_WAN_SCHEDULER_LOGIT_NORMAL   = 13,
    MCA_WAN_SCHEDULER_FLUX2          = 14,
    MCA_WAN_SCHEDULER_FLUX           = 15,
    MCA_WAN_SCHEDULER_BETA           = 16
} mca_wan_scheduler_t;

/*
 * Which decoder the caller wants loaded.
 * - DEFAULT: only vae_path is used; tae_path must be empty.
 * - TAE:     the TAE decoder at tae_path is loaded in addition to vae_path. In this engine version
 *            TAE is the preview decoder (sd_ctx_params_t.taesd_path); the full VAE still performs
 *            the final video decode, so vae_path stays required. A TAE-only video decode is not
 *            supported by the engine and is rejected instead of being approximated.
 */
typedef enum mca_wan_vae_profile {
    MCA_WAN_VAE_PROFILE_DEFAULT = 0,
    MCA_WAN_VAE_PROFILE_TAE     = 1
} mca_wan_vae_profile_t;

/* Progress phases reported through mca_wan_progress_fn_v2. */
typedef enum mca_wan_phase {
    MCA_WAN_PHASE_LOADING  = 0,
    MCA_WAN_PHASE_SAMPLING = 1,
    MCA_WAN_PHASE_DECODING = 2,
    MCA_WAN_PHASE_DONE     = 3
} mca_wan_phase_t;

/* One LoRA to apply to the diffusion pipeline. `weight` is the engine's multiplier. */
typedef struct mca_wan_lora_v2 {
    const char* path;   /* borrowed; must stay alive for the whole call */
    float       weight; /* finite; 0 is accepted and means "loaded but neutral" */
} mca_wan_lora_v2;

/* Progress payload. The adapter fills struct_size/version before invoking the callback. */
typedef struct mca_wan_progress_v2 {
    uint32_t struct_size;
    uint32_t version;
    int32_t  phase;            /* mca_wan_phase_t */
    int32_t  step;             /* 0..steps while sampling; 0 elsewhere */
    int32_t  steps;            /* sampler total for this run; 0 when unknown */
    float    seconds_per_step; /* last step duration reported by the engine; 0 when unknown */
    int32_t  frames_written;   /* spool frames written so far */
} mca_wan_progress_v2;

/* Called from the engine's sampling thread. Must not throw and must be cheap. */
typedef void (*mca_wan_progress_fn_v2)(const mca_wan_progress_v2* progress, void* user);

/*
 * Cooperative cancel. Polled by the adapter from the engine progress callback and from the frame
 * sink; a non-zero return aborts the run (the adapter also raises the engine's own cancel flag).
 * Safe to call from any thread.
 */
typedef int (*mca_wan_cancel_fn_v2)(void* user);

/*
 * Push delivery of one decoded RGB frame (3 bytes per pixel, tightly packed, `width * height * 3`).
 * Called synchronously from the decoding thread; returning non-zero stops the run. When set, the
 * adapter does not write PPM files, so the caller owns the frame once this returns.
 */
typedef int (*mca_wan_frame_consumer_v2)(void* user,
                                         const uint8_t* rgb,
                                         int32_t        width,
                                         int32_t        height,
                                         int64_t        index);

typedef struct mca_wan_request_v2 {
    uint32_t struct_size;
    uint32_t version;

    int32_t width;
    int32_t height;
    int32_t frame_count; /* 4k+1 video frames */
    int32_t fps;
    int64_t seed;

    const char* prompt;
    const char* negative_prompt;   /* nullptr/empty = no negative prompt */
    const char* init_image_path;   /* nullptr/empty = text-to-video; decoded to RGB888 */
    const char* end_image_path;    /* nullptr/empty = no end frame */

    int32_t steps;
    float   guidance;   /* guidance.txt_cfg; > 0 */
    int32_t sampler;    /* mca_wan_sampler_t */
    int32_t scheduler;  /* mca_wan_scheduler_t */
    float   flow_shift; /* <= 0 = engine default (Wan resolves its own shift) */
    const float* custom_sigmas; /* borrowed; replaces the scheduler when count >= 2 */
    int32_t      custom_sigmas_count;

    const mca_wan_lora_v2* loras; /* borrowed array */
    int32_t                lora_count;

    const char* dit_path;   /* diffusion model */
    const char* te_path;    /* text encoder (t5xxl) */
    const char* vae_path;
    const char* tae_path;   /* only valid with MCA_WAN_VAE_PROFILE_TAE */
    int32_t     vae_profile;

    const char* diffusion_backend; /* device assignment for the diffusion module, e.g. "HTP0" */
    const char* te_backend;
    const char* vae_backend;
    const char* params_backend;    /* weight residency backend, e.g. "disk" */
    float       max_vram_gib;      /* 0 = engine default; > 0 = per-device GiB budget */
    int32_t     offload_to_cpu;    /* 1 = text encoder and VAE stay on the cpu backend */

    int32_t spatial_tiling;  /* VAE spatial tiling */
    int32_t temporal_tiling; /* VAE temporal tiling (required for incremental frame delivery) */
    int32_t tile_size;       /* spatial tile edge in pixels; <= 0 = adapter default */
    int32_t tile_frames;     /* temporal tile length; <= 0 = engine default */

    int32_t flash_attn; /* 1 = flash attention for both the text encoder and the diffusion model */

    int32_t n_threads; /* >= 1 */

    mca_wan_progress_fn_v2 progress;
    void*                  progress_user;
    mca_wan_cancel_fn_v2   is_cancelled;
    void*                  cancel_user;

    const char* out_dir; /* decoded frames are spooled here as frame_%05d.ppm unless a consumer is set */

    /*
     * Optional push delivery. When set, every decoded frame is handed to the consumer instead of
     * being spooled, so the caller can encode as frames arrive and no frame ever reaches the disk.
     * `frame_consumer` returns 0 to continue and non-zero to stop the run (treated as a cancel).
     * Appended last so the struct stays append-only relative to `version`.
     */
    mca_wan_frame_consumer_v2 frame_consumer;
    void*                     frame_consumer_user;
} mca_wan_request_v2;

typedef struct mca_wan_result_v2 {
    uint32_t struct_size;
    uint32_t version;

    int32_t ok;         /* 1 = success, 0 = failure */
    int32_t status;     /* mca_wan_status_t */
    int32_t error_code; /* mca_wan_error_code_t */
    char    unsupported_field[64]; /* non-empty only for MCA_WAN_ERR_UNSUPPORTED_FIELD */

    char requested_diffusion_backend[64];
    char requested_te_backend[64];
    char requested_vae_backend[64];
    /*
     * The device assignment the engine context was constructed with. The engine does not report a
     * resolved per-module device back, so "actual" is the assignment that was applied, not a
     * post-hoc probe. Empty when the caller left the module on the engine default.
     */
    char actual_diffusion_backend[64];
    char actual_te_backend[64];
    char actual_vae_backend[64];

    /* Backend fallback is decided by the caller before the call; the adapter never retries. */
    int32_t fallback_applied;
    char    fallback_reason[128];

    /* Timings. load = context creation; prep = first sampling progress minus call start, i.e. the
     * lazy parameter load (params_backend=disk), conditioning and graph preparation that the engine
     * performs inside generate_video before the first denoise step; sample = first denoise step to
     * first delivered frame; decode = first delivered frame to return; total = whole call. The
     * engine decodes tiles while sampling finishes, so sample/decode are delivery windows, not
     * exclusive phases. */
    int64_t load_ms;
    int64_t sample_ms;
    int64_t decode_ms;
    int64_t total_ms;
    /* First sampling progress minus call start — the lazy parameter load (params_backend=disk),
     * conditioning and graph preparation the engine performs inside generate_video before the first
     * denoise step. sample_ms starts at that same point, so it no longer absorbs parameter loading. */
    int64_t prep_ms;

    int32_t frames_written;   /* frame_%05d.ppm files written */
    int32_t delivered_frames; /* frames handed to the sink by the engine */
    int32_t width;
    int32_t height;
    int32_t fps;

    char error[512];
} mca_wan_result_v2;

/*
 * Runs one video generation. Returns 0 when result->ok == 1, non-zero otherwise. `result` is
 * always written (unless the caller's result buffer fails its own struct_size/version check).
 */
int mca_wan_generate_v2(const mca_wan_request_v2* request, mca_wan_result_v2* result);

/* Returns MCA_WAN_ABI_VERSION of the loaded library. */
uint32_t mca_wan_abi_version(void);

#ifdef __cplusplus
}
#endif

#endif /* MCA_WAN_API_V2_H */
