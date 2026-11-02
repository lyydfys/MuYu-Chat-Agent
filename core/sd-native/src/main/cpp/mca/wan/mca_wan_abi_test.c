/* ABI guard test: a caller built against a different header revision must be rejected
 * with MCA_WAN_ERR_ABI_MISMATCH instead of having its fields read at stale offsets.
 * Runs without loading a model: both checks happen before the engine context is created. */
#include <stdio.h>
#include <string.h>
#include <stdint.h>

#include "mca_wan_api_v2.h"

static int failures = 0;

static void check(const char* name, int condition, const char* detail) {
    printf("%s: %s%s%s\n", condition ? "PASS" : "FAIL", name,
           detail != NULL && detail[0] != '\0' ? " — " : "", detail != NULL ? detail : "");
    if (!condition) ++failures;
}

static mca_wan_request_v2 make_request(void) {
    mca_wan_request_v2 r;
    memset(&r, 0, sizeof(r));
    r.struct_size = (uint32_t)sizeof(r);
    r.version = MCA_WAN_ABI_VERSION;
    r.width = 64;
    r.height = 64;
    r.frame_count = 1;
    r.fps = 24;
    r.seed = 1;
    r.prompt = "abi guard";
    r.steps = 1;
    r.guidance = 1.0f;
    r.sampler = MCA_WAN_SAMPLER_EULER;
    r.scheduler = MCA_WAN_SCHEDULER_ENGINE_DEFAULT;
    r.flow_shift = 3.0f;
    r.dit_path = "/nonexistent/dit.gguf";
    r.te_path = "/nonexistent/te.gguf";
    r.vae_path = "/nonexistent/vae.safetensors";
    r.tae_path = "/nonexistent/tae.safetensors";
    r.vae_profile = MCA_WAN_VAE_PROFILE_TAE;
    r.diffusion_backend = "cpu";
    r.te_backend = "cpu";
    r.vae_backend = "cpu";
    r.params_backend = "disk";
    r.max_vram_gib = 1.0f;
    r.temporal_tiling = 1;
    r.flash_attn = 1;
    r.n_threads = 1;
    r.out_dir = "/data/local/tmp/hy98/abitest-out";
    return r;
}

static mca_wan_result_v2 make_result(void) {
    mca_wan_result_v2 res;
    memset(&res, 0, sizeof(res));
    res.struct_size = (uint32_t)sizeof(res);
    res.version = MCA_WAN_ABI_VERSION;
    return res;
}

int main(void) {
    printf("abi=%u sizeof(request)=%zu sizeof(result)=%zu\n",
           mca_wan_abi_version(), sizeof(mca_wan_request_v2), sizeof(mca_wan_result_v2));

    mca_wan_request_v2 r = make_request();
    mca_wan_result_v2 res = make_result();
    res.struct_size = (uint32_t)(sizeof(res) - 8); /* stale caller: older header */
    int rc = mca_wan_generate_v2(&r, &res);
    check("stale result buffer rejected", rc != 0, "expected non-zero return");

    r.struct_size = (uint32_t)(sizeof(r) - 8);
    res = make_result();
    rc = mca_wan_generate_v2(&r, &res);
    check("stale request buffer rejected", rc != 0 && res.error_code == MCA_WAN_ERR_ABI_MISMATCH,
          res.error);

    r = make_request();
    res = make_result();
    r.version = MCA_WAN_ABI_VERSION + 1;
    rc = mca_wan_generate_v2(&r, &res);
    check("wrong version rejected", rc != 0 && res.error_code == MCA_WAN_ERR_ABI_MISMATCH, res.error);

    printf("%s (%d failure%s)\n", failures == 0 ? "ABI-GUARD PASS" : "ABI-GUARD FAIL",
           failures, failures == 1 ? "" : "s");
    return failures == 0 ? 0 : 1;
}
