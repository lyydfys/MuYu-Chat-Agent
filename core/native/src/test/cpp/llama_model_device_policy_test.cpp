#include "../../main/cpp/llama_model_device_policy.hpp"

#include <cassert>

int main() {
    // CPU-only backends have no accelerator entry at index zero. The native
    // load call must use llama.cpp's negative sentinel while preserving MCA's
    // configured main_gpu value for requested/effective stats.
    const int configuredMainGpu = 0;
    assert(mca::llama::modelMainGpuForLoad(false, 0, configuredMainGpu) == -1);
    assert(configuredMainGpu == 0);

    // Explicit CPU execution must also avoid selecting a GPU when an optional
    // accelerator backend is registered in the same APK.
    assert(mca::llama::modelMainGpuForLoad(true, 0, 0) == -1);

    // Actual offload paths retain the caller-selected device. This includes
    // fixed layer counts and llama.cpp's auto/all layer modes.
    assert(mca::llama::modelMainGpuForLoad(true, 1, 0) == 0);
    assert(mca::llama::modelMainGpuForLoad(true, 12, 2) == 2);
    assert(mca::llama::modelMainGpuForLoad(true, -1, 1) == 1);
    assert(mca::llama::modelMainGpuForLoad(true, -2, 3) == 3);

    return 0;
}
