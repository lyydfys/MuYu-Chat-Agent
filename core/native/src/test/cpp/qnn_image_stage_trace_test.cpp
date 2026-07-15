#include "../../main/cpp/qnn_image_stage_trace.hpp"

#include <cassert>

int main() {
    using mca::qnn::ImageStage;
    uint64_t mask = 0;
    mask |= mca::qnn::image_stage_bit(ImageStage::UnetGraphRetrieveBefore);
    mask |= mca::qnn::image_stage_bit(ImageStage::UnetGraphRetrieveAfter);
    mask |= mca::qnn::image_stage_bit(ImageStage::VaeContextReleaseBefore);
    mask |= mca::qnn::image_stage_bit(ImageStage::RuntimeUnloadBefore);

    const auto names = mca::qnn::image_stage_names(mask);
    assert(names.size() == 4);
    assert(names[0] == "unet_graph_retrieve_before");
    assert(names[1] == "unet_graph_retrieve_after");
    assert(names[2] == "vae_context_release_before");
    assert(names[3] == "runtime_unload_before");

    uint64_t cleanup = 0;
    cleanup |= mca::qnn::image_stage_bit(ImageStage::VaeGraphReleaseBefore);
    cleanup |= mca::qnn::image_stage_bit(ImageStage::VaeContextReleaseBefore);
    cleanup |= mca::qnn::image_stage_bit(ImageStage::UnetGraphReleaseBefore);
    cleanup |= mca::qnn::image_stage_bit(ImageStage::UnetContextReleaseBefore);
    cleanup |= mca::qnn::image_stage_bit(ImageStage::BackendReleaseBefore);
    cleanup |= mca::qnn::image_stage_bit(ImageStage::RuntimeUnloadBefore);
    const auto cleanup_names = mca::qnn::image_stage_names(cleanup);
    assert(cleanup_names[0] == "vae_graph_release_before");
    assert(cleanup_names[1] == "vae_context_release_before");
    assert(cleanup_names[2] == "unet_graph_release_before");
    assert(cleanup_names[3] == "unet_context_release_before");
    assert(cleanup_names[4] == "backend_release_before");
    assert(cleanup_names[5] == "runtime_unload_before");
    return 0;
}
