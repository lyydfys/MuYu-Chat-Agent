#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace mca::qnn {

// Stable, append-only diagnostic stages for one QNN image generation.  Keep
// each unload boundary distinct: a process killed inside a vendor call must
// leave enough evidence to identify the exact call that did not return.
enum class ImageStage : uint8_t {
    ContextLock = 0,
    UnetBinaryMmap,
    RuntimeLoadBefore,
    RuntimeLoadAfter,
    BackendCreateBefore,
    BackendCreateAfter,
    UnetContextCreateBefore,
    UnetContextCreateAfter,
    UnetGraphRetrieveBefore,
    UnetGraphRetrieveAfter,
    UnetTensorBindBefore,
    UnetTensorBindAfter,
    VaeBinaryMmap,
    VaeContextCreateBefore,
    VaeContextCreateAfter,
    VaeGraphRetrieveBefore,
    VaeGraphRetrieveAfter,
    VaeTensorBindBefore,
    VaeTensorBindAfter,
    UnetGraphExecute,
    VaeGraphExecute,
    PngWrite,
    VaeGraphReleaseBefore,
    VaeGraphReleaseAfter,
    VaeMetadataReleaseBefore,
    VaeMetadataReleaseAfter,
    VaeContextReleaseBefore,
    VaeContextReleaseAfter,
    UnetGraphReleaseBefore,
    UnetGraphReleaseAfter,
    UnetMetadataReleaseBefore,
    UnetMetadataReleaseAfter,
    UnetContextReleaseBefore,
    UnetContextReleaseAfter,
    DeviceReleaseBefore,
    DeviceReleaseAfter,
    BackendReleaseBefore,
    BackendReleaseAfter,
    LogReleaseBefore,
    LogReleaseAfter,
    RuntimeUnloadBefore,
    RuntimeUnloadAfter,
};

inline const char* image_stage_name(ImageStage stage) {
    switch (stage) {
        case ImageStage::ContextLock: return "context_lock";
        case ImageStage::UnetBinaryMmap: return "unet_context_binary_mmap";
        case ImageStage::RuntimeLoadBefore: return "runtime_load_before";
        case ImageStage::RuntimeLoadAfter: return "runtime_load_after";
        case ImageStage::BackendCreateBefore: return "backend_create_before";
        case ImageStage::BackendCreateAfter: return "backend_create_after";
        case ImageStage::UnetContextCreateBefore: return "unet_context_create_before";
        case ImageStage::UnetContextCreateAfter: return "unet_context_create_after";
        case ImageStage::UnetGraphRetrieveBefore: return "unet_graph_retrieve_before";
        case ImageStage::UnetGraphRetrieveAfter: return "unet_graph_retrieve_after";
        case ImageStage::UnetTensorBindBefore: return "unet_tensor_bind_before";
        case ImageStage::UnetTensorBindAfter: return "unet_tensor_bind_after";
        case ImageStage::VaeBinaryMmap: return "vae_context_binary_mmap";
        case ImageStage::VaeContextCreateBefore: return "vae_context_create_before";
        case ImageStage::VaeContextCreateAfter: return "vae_context_create_after";
        case ImageStage::VaeGraphRetrieveBefore: return "vae_graph_retrieve_before";
        case ImageStage::VaeGraphRetrieveAfter: return "vae_graph_retrieve_after";
        case ImageStage::VaeTensorBindBefore: return "vae_tensor_bind_before";
        case ImageStage::VaeTensorBindAfter: return "vae_tensor_bind_after";
        case ImageStage::UnetGraphExecute: return "unet_graph_execute";
        case ImageStage::VaeGraphExecute: return "vae_graph_execute";
        case ImageStage::PngWrite: return "png_write";
        case ImageStage::VaeGraphReleaseBefore: return "vae_graph_release_before";
        case ImageStage::VaeGraphReleaseAfter: return "vae_graph_release_after";
        case ImageStage::VaeMetadataReleaseBefore: return "vae_metadata_release_before";
        case ImageStage::VaeMetadataReleaseAfter: return "vae_metadata_release_after";
        case ImageStage::VaeContextReleaseBefore: return "vae_context_release_before";
        case ImageStage::VaeContextReleaseAfter: return "vae_context_release_after";
        case ImageStage::UnetGraphReleaseBefore: return "unet_graph_release_before";
        case ImageStage::UnetGraphReleaseAfter: return "unet_graph_release_after";
        case ImageStage::UnetMetadataReleaseBefore: return "unet_metadata_release_before";
        case ImageStage::UnetMetadataReleaseAfter: return "unet_metadata_release_after";
        case ImageStage::UnetContextReleaseBefore: return "unet_context_release_before";
        case ImageStage::UnetContextReleaseAfter: return "unet_context_release_after";
        case ImageStage::DeviceReleaseBefore: return "device_release_before";
        case ImageStage::DeviceReleaseAfter: return "device_release_after";
        case ImageStage::BackendReleaseBefore: return "backend_release_before";
        case ImageStage::BackendReleaseAfter: return "backend_release_after";
        case ImageStage::LogReleaseBefore: return "log_release_before";
        case ImageStage::LogReleaseAfter: return "log_release_after";
        case ImageStage::RuntimeUnloadBefore: return "runtime_unload_before";
        case ImageStage::RuntimeUnloadAfter: return "runtime_unload_after";
    }
    return "unknown";
}

inline uint64_t image_stage_bit(ImageStage stage) {
    return uint64_t{1} << static_cast<uint8_t>(stage);
}

inline std::vector<std::string> image_stage_names(uint64_t mask) {
    std::vector<std::string> names;
    const auto last = static_cast<uint8_t>(ImageStage::RuntimeUnloadAfter);
    for (uint8_t value = 0; value <= last; ++value) {
        const auto stage = static_cast<ImageStage>(value);
        if ((mask & image_stage_bit(stage)) != 0) names.emplace_back(image_stage_name(stage));
    }
    return names;
}

}  // namespace mca::qnn
