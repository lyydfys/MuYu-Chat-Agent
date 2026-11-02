// Device-side probe for the MCA Wan external adapter ABI v2.
// Links libmca_wan.so directly (no app APK, no Gradle) so the typed parameter
// mapping can be exercised on a real device.
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <pthread.h>
#include <unistd.h>

#include "mca_wan_api_v2.h"

static int g_progress_calls = 0;
static int g_sampling_calls = 0;
static const char* g_mode = "plain";
static mca_wan_lora_v2 g_lora;

/* Phase accounting, so a cancel can be attributed to the phase it interrupted. */
static volatile int g_last_phase = -1;
static volatile int g_last_step  = -1;
static volatile int g_cancel_fired = 0;
static int g_cancel_at_phase = -1;
static int g_cancel_after_ms = 0;

/* Reported to the adapter, which polls it inside the engine progress callback. */
static int on_cancel(void* user) {
    (void)user;
    return g_sampling_calls >= 1 ? 1 : 0;
}
/* Time-based cancel: behaves like a user pressing stop at an arbitrary moment, so the
 * flag can land inside encoding or decoding rather than only after the first step. */
static int g_cancel_on_decode = 0;
static int on_cancel_timed(void* user) {
    (void)user;
    if (!g_cancel_fired && g_cancel_on_decode && g_last_phase == MCA_WAN_PHASE_DECODING) {
        g_cancel_fired    = 1;
        g_cancel_at_phase = g_last_phase;
        fprintf(stderr, "[probe] cancel fired on DECODING entry\n");
    }
    if (g_cancel_fired && g_cancel_at_phase < 0) g_cancel_at_phase = g_last_phase;
    return g_cancel_fired;
}
static void* cancel_watchdog(void* user) {
    (void)user;
    usleep((useconds_t)g_cancel_after_ms * 1000);
    g_cancel_fired   = 1;
    g_cancel_at_phase = g_last_phase;
    fprintf(stderr, "[probe] cancel fired after %d ms (phase=%d step=%d)\n",
            g_cancel_after_ms, g_last_phase, g_last_step);
    return NULL;
}
static long long g_push_frames = 0;
static long long g_push_bytes  = 0;
static unsigned long long g_push_hash = 1469598103934665603ULL;

/* MCA_PROBE_PUSH=1 exercises the push ABI: the adapter must hand every frame here and must not
 * create any PPM file. The hash only proves the payload is non-trivial. */
static int push_consumer(void* user, const uint8_t* rgb, int32_t width, int32_t height, int64_t index) {
    (void)user;
    if (rgb == NULL || width <= 0 || height <= 0) return 1;
    const long long size = (long long)width * height * 3;
    g_push_frames += 1;
    g_push_bytes += size;
    for (long long i = 0; i < size; i += 9973) {
        g_push_hash = (g_push_hash ^ (unsigned long long)rgb[i]) * 1099511628211ULL;
    }
    if (index + 1 != g_push_frames) {
        fprintf(stderr, "[probe] push out of order: index=%lld count=%lld\n", (long long)index, g_push_frames);
    }
    return 0;
}

static void on_progress(const mca_wan_progress_v2* p, void* user) {
    (void)user;
    ++g_progress_calls;
    if (p != NULL && p->phase == MCA_WAN_PHASE_SAMPLING && p->steps > 0) ++g_sampling_calls;
    if (p != NULL) {
        g_last_phase = p->phase;
        g_last_step  = p->step;
    }
    if (p != NULL && (p->step == 0 || (p->steps > 0 && p->step == p->steps))) {
        fprintf(stderr, "[probe] progress phase=%d step=%d/%d %.2f s/it\n",
                p->phase, p->step, p->steps, (double)p->seconds_per_step);
    }
    // Every forwarded sampling event, not only the endpoints: the per-step contract is exactly
    // the thing under test, so the raw stream must be visible.
    if (p != NULL && p->phase == MCA_WAN_PHASE_SAMPLING && p->steps > 0) {
        fprintf(stderr, "[probe] sample step=%d/%d %.2f s/it\n",
                p->step, p->steps, (double)p->seconds_per_step);
    }
}

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <out_dir> [steps] [frames] [profile: tae|vae]\n", argv[0]);
        return 2;
    }
    const char* out_dir = argv[1];
    const int steps  = argc > 2 ? atoi(argv[2]) : 2;
    const int frames = argc > 3 ? atoi(argv[3]) : 5;
    const int use_tae = !(argc > 4 && strcmp(argv[4], "vae") == 0);
    const int threads = argc > 5 ? atoi(argv[5]) : 4;
    const int fa      = argc > 6 ? atoi(argv[6]) : 1;
    const float vram  = argc > 7 ? (float)atof(argv[7]) : 2.0f;
    if (argc > 8) g_mode = argv[8];
    const int width  = argc > 9 ? atoi(argv[9]) : 256;
    const int height = argc > 10 ? atoi(argv[10]) : 256;

    fprintf(stderr, "[probe] abi=%u sizeof(request)=%zu sizeof(result)=%zu\n",
            mca_wan_abi_version(), sizeof(mca_wan_request_v2), sizeof(mca_wan_result_v2));

    mca_wan_request_v2 r;
    memset(&r, 0, sizeof(r));
    r.struct_size = (uint32_t)sizeof(r);
    r.version     = MCA_WAN_ABI_VERSION;

    r.width       = width;
    r.height      = height;
    r.frame_count = frames;
    r.fps         = 24;
    r.seed        = getenv("MCA_WAN_SEED") != NULL ? atoi(getenv("MCA_WAN_SEED")) : 42;

    r.prompt          = "a red ball on a blue table";
    r.negative_prompt = "blurry, low quality, distorted, watermark, text";
    r.init_image_path = NULL;
    r.end_image_path  = NULL;

    r.steps      = steps;
    r.guidance   = 1.0f;                        /* cfg-scale 1.0 (Turbo-style, no CFG) */
    r.sampler    = MCA_WAN_SAMPLER_EULER;
    r.scheduler  = MCA_WAN_SCHEDULER_ENGINE_DEFAULT;
    r.flow_shift = 3.0f;
    r.custom_sigmas       = NULL;
    r.custom_sigmas_count = 0;
    r.loras       = NULL;
    r.lora_count  = 0;

    r.dit_path = "/data/local/tmp/wan98/Wan2.2-TI2V-5B-Q4_0.gguf";
    r.te_path  = "/data/local/tmp/wan98/umt5-xxl-encoder-Q4_K_M.gguf";
    r.vae_path = "/data/local/tmp/wan98/Wan2.2_VAE.safetensors";
    /* tae_path is only valid on the TAE profile; the adapter rejects it otherwise. */
    r.tae_path = use_tae ? "/data/local/tmp/wan98/taew2_2.safetensors" : NULL;
    r.vae_profile = use_tae ? MCA_WAN_VAE_PROFILE_TAE : MCA_WAN_VAE_PROFILE_DEFAULT;

    r.diffusion_backend = getenv("MCA_WAN_DIFF_BACKEND") != NULL ? getenv("MCA_WAN_DIFF_BACKEND") : "HTP0";
    r.te_backend        = "cpu";
    r.vae_backend       = "cpu";
    r.params_backend    = "disk";
    r.max_vram_gib      = vram;
    r.offload_to_cpu    = 1;
    r.spatial_tiling    = 0;
    r.temporal_tiling   = 1;
    r.tile_size         = 0;
    r.tile_frames       = 0;
    r.flash_attn        = fa;
    r.n_threads         = threads;
    if (getenv("MCA_WAN_TILE_FRAMES") != NULL) {
        r.tile_frames = atoi(getenv("MCA_WAN_TILE_FRAMES"));
    }
    if (getenv("MCA_WAN_SPATIAL_TILING") != NULL) {
        r.spatial_tiling = atoi(getenv("MCA_WAN_SPATIAL_TILING"));
    }
    if (getenv("MCA_WAN_TILE_SIZE") != NULL) {
        r.tile_size = atoi(getenv("MCA_WAN_TILE_SIZE"));
    }

    r.progress      = on_progress;
    r.progress_user = NULL;
    if (strstr(g_mode, "cancel") != NULL && strstr(g_mode, "cancel-ms=") == NULL) r.is_cancelled = on_cancel;
    if (strstr(g_mode, "cancel-ms=") != NULL) {
        const char* v = strstr(g_mode, "cancel-ms=") + strlen("cancel-ms=");
        g_cancel_after_ms = atoi(v);
        if (g_cancel_after_ms > 0) r.is_cancelled = on_cancel_timed;
    }
    if (strstr(g_mode, "cancel-on-decode") != NULL) {
        g_cancel_on_decode = 1;
        r.is_cancelled      = on_cancel_timed;
    }
    if (strstr(g_mode, "lora") != NULL) {
        g_lora.path   = "/data/local/tmp/Wan22_TI2V_5B_Turbo_lora_rank_64_fp16.safetensors";
        g_lora.weight = 1.0f;
        r.loras       = &g_lora;
        r.lora_count  = 1;
    }
    if (strstr(g_mode, "i2v") != NULL) {
        r.init_image_path = "/data/local/tmp/hy98/output.png";
    }
    static float g_custom_sigmas[3] = {1.0f, 0.25f, 0.0f};
    if (strstr(g_mode, "end") != NULL) {
        r.end_image_path = "/data/local/tmp/hy98/output.png";
    }
    if (strstr(g_mode, "sigmas") != NULL) {
        r.custom_sigmas       = g_custom_sigmas;
        r.custom_sigmas_count = 2;
    }
    r.cancel_user   = NULL;
    r.out_dir       = out_dir;

    if (getenv("MCA_PROBE_PUSH") != NULL) {
        r.frame_consumer      = push_consumer;
        r.frame_consumer_user = NULL;
    }

    mca_wan_result_v2 res;
    memset(&res, 0, sizeof(res));
    res.struct_size = (uint32_t)sizeof(res);
    res.version     = MCA_WAN_ABI_VERSION;

    fprintf(stderr, "[probe] calling mca_wan_generate_v2 frames=%d steps=%d vae=%s mode=%s dit=%s seed=%lld\n",
            frames, steps, use_tae ? "tae" : "full", g_mode, r.diffusion_backend, (long long)r.seed);
    if (g_cancel_after_ms > 0) {
        pthread_t watchdog;
        if (pthread_create(&watchdog, NULL, cancel_watchdog, NULL) == 0) pthread_detach(watchdog);
    }
    const int rc = mca_wan_generate_v2(&r, &res);

    printf("rc=%d ok=%d status=%d error_code=%d fallback=%d unsupported=%s\n",
           rc, res.ok, res.status, res.error_code, res.fallback_applied,
           res.unsupported_field);
    printf("backends req[d=%s te=%s vae=%s] act[d=%s te=%s vae=%s] reason=%s\n",
           res.requested_diffusion_backend, res.requested_te_backend, res.requested_vae_backend,
           res.actual_diffusion_backend, res.actual_te_backend, res.actual_vae_backend,
           res.fallback_reason);
    printf("timing load=%lld prep=%lld sample=%lld decode=%lld total=%lld ms\n",
           (long long)res.load_ms, (long long)res.prep_ms, (long long)res.sample_ms,
           (long long)res.decode_ms, (long long)res.total_ms);
    printf("frames written=%d delivered=%d geometry=%dx%d@%d\n",
           res.frames_written, res.delivered_frames, res.width, res.height, res.fps);
    printf("push frames=%lld bytes=%lld hash=%016llx\n",
           g_push_frames, g_push_bytes, g_push_hash);
    printf("progress_calls=%d sampling_calls=%d error=%s\n", g_progress_calls, g_sampling_calls, res.error);
    printf("cancel fired=%d at_phase=%d last_phase=%d last_step=%d\n",
           g_cancel_fired, g_cancel_at_phase, g_last_phase, g_last_step);
    return (res.ok == 1 && res.status == MCA_WAN_STATUS_OK) ? 0 : 1;
}
