/* Device-side HTP canary: creates a real Hexagon backend session in the calling process and runs
 * one tiny graph on it. Unlike a /dev node probe this exercises FastRPC session creation, the
 * skeleton load and an actual DSP compute, and compares the result against the expected value.
 * Linked against libstable-diffusion.so so it can also run under `run-as <pkg>` to measure the
 * app UID/SELinux domain without building an APK. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ggml.h"
#include "ggml-backend.h"

static const char* type_name(enum ggml_backend_dev_type t) {
    switch (t) {
        case GGML_BACKEND_DEVICE_TYPE_CPU:   return "CPU";
        case GGML_BACKEND_DEVICE_TYPE_GPU:   return "GPU";
        case GGML_BACKEND_DEVICE_TYPE_ACCEL: return "ACCEL";
        default:                             return "OTHER";
    }
}

static int contains_ci(const char* haystack, const char* needle) {
    char lower[128];
    size_t n = strlen(haystack);
    if (n >= sizeof(lower)) n = sizeof(lower) - 1;
    for (size_t i = 0; i < n; ++i) {
        const char c = haystack[i];
        lower[i] = (c >= 'A' && c <= 'Z') ? (char)(c - 'A' + 'a') : c;
    }
    lower[n] = '\0';
    return strstr(lower, needle) != NULL;
}

int main(int argc, char** argv) {
    const char* want = argc > 1 ? argv[1] : "hexagon";

    printf("SKEL_DIR=%s SKEL_NAME=%s\n", getenv("MCA_HTP_SKEL_DIR") ? getenv("MCA_HTP_SKEL_DIR") : "(unset)",
           getenv("MCA_HTP_SKEL_NAME") ? getenv("MCA_HTP_SKEL_NAME") : "(unset)");
    const size_t count = ggml_backend_dev_count();
    printf("devices=%zu\n", count);
    ggml_backend_dev_t target = NULL;
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == NULL) continue;
        const char* name = ggml_backend_dev_name(dev);
        printf("  [%zu] %-28s %s\n", i, name ? name : "(null)", type_name(ggml_backend_dev_type(dev)));
        if (target == NULL && name != NULL && contains_ci(name, want)) target = dev;
    }
    if (target == NULL) {
        printf("CANARY FAIL no device matching '%s'\n", want);
        return 1;
    }

    ggml_backend_t backend = ggml_backend_dev_init(target, NULL);
    if (backend == NULL) {
        printf("CANARY FAIL ggml_backend_dev_init(%s) returned NULL\n", ggml_backend_dev_name(target));
        return 1;
    }
    printf("backend=%s\n", ggml_backend_name(backend));

    struct ggml_init_params params;
    params.mem_size = 16u * 1024u * 1024u;
    params.mem_buffer = NULL;
    params.no_alloc = true;  /* ggml_backend_alloc_ctx_tensors requires a no_alloc context */
    struct ggml_context* ctx = ggml_init(params);
    if (ctx == NULL) {
        printf("CANARY FAIL ggml_init\n");
        ggml_backend_free(backend);
        return 1;
    }
    const int n = 64;
    struct ggml_tensor* a = ggml_new_tensor_1d(ctx, GGML_TYPE_F32, n);
    struct ggml_tensor* b = ggml_new_tensor_1d(ctx, GGML_TYPE_F32, n);
    struct ggml_tensor* c = ggml_add(ctx, a, b);
    struct ggml_cgraph* graph = ggml_new_graph(ctx);
    ggml_build_forward_expand(graph, c);
    if (!ggml_backend_alloc_ctx_tensors(ctx, backend)) {
        printf("CANARY FAIL ggml_backend_alloc_ctx_tensors\n");
        ggml_free(ctx);
        ggml_backend_free(backend);
        return 1;
    }
    float ha[n];
    float hb[n];
    for (int i = 0; i < n; ++i) {
        ha[i] = 1.5f + (float)i;
        hb[i] = 0.25f * (float)i;
    }
    ggml_backend_tensor_set(a, ha, 0, sizeof(ha));
    ggml_backend_tensor_set(b, hb, 0, sizeof(hb));
    const enum ggml_status status = ggml_backend_graph_compute(backend, graph);
    float out[n];
    memset(out, 0, sizeof(out));
    ggml_backend_tensor_get(c, out, 0, sizeof(out));

    int mismatches = 0;
    for (int i = 0; i < n; ++i) {
        const float expect = ha[i] + hb[i];
        const float d = out[i] - expect;
        if (d > 1e-4f || d < -1e-4f) ++mismatches;
    }
    printf("status=%d mismatches=%d/%d sample=%.4f expect=%.4f\n", (int)status, mismatches, n, out[0],
           ha[0] + hb[0]);
    ggml_free(ctx);
    ggml_backend_free(backend);

    if (status != GGML_STATUS_SUCCESS || mismatches != 0) {
        printf("CANARY FAIL htp compute mismatch\n");
        return 1;
    }
    printf("CANARY PASS htp session + graph\n");
    return 0;
}
