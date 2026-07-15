#pragma once

namespace mca::llama {

// llama.cpp treats a non-negative main_gpu as an index into its accelerator
// device list when split_mode is NONE. CPU-only builds have an empty list, so
// main_gpu=0 is not a valid load parameter even though 0 remains MCA's public
// configuration value. A negative load-only value tells llama.cpp to clear the
// accelerator list and use its CPU buffer path.
inline constexpr int modelMainGpuForLoad(
        bool gpuOffloadSupported,
        int nGpuLayers,
        int configuredMainGpu) noexcept {
    return !gpuOffloadSupported || nGpuLayers == 0 ? -1 : configuredMainGpu;
}

}  // namespace mca::llama
