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
    const uint64_t preview = mca::qnn::image_stage_bit(ImageStage::PreviewVaeGraphExecute);
    const auto preview_names = mca::qnn::image_stage_names(preview);
    assert(preview_names.size() == 1);
    assert(preview_names[0] == "preview_vae_graph_execute");
    uint64_t encoder = 0;
    encoder |= mca::qnn::image_stage_bit(ImageStage::EncoderBinaryMmap);
    encoder |= mca::qnn::image_stage_bit(ImageStage::EncoderGraphExecute);
    const auto encoder_names = mca::qnn::image_stage_names(encoder);
    assert(encoder_names.size() == 2);
    assert(encoder_names[0] == "encoder_context_binary_mmap");
    assert(encoder_names[1] == "encoder_graph_execute");
    assert(mca::qnn::image_stage_bit(ImageStage::EncoderGraphExecute) ==
           (UINT64_C(1) << 63u));
    assert(mca::qnn::image_stage_mask_hex(
               mca::qnn::image_stage_bit(ImageStage::EncoderGraphExecute)) ==
           "8000000000000000");
    assert(mca::qnn::image_stage_mask_hex(UINT64_C(511)) == "00000000000001ff");
    assert(mca::qnn::image_stage_mask_hex(UINT64_C(1023)) == "00000000000003ff");
    return 0;
}
