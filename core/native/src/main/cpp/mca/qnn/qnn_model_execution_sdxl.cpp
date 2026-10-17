// mca/qnn/qnn_model_execution_sdxl.cpp -- SDXL text-encoder/UNet single-step executors and Sdxl conditioning preparation.
// (T2B17 / R7-C6 split).  Relocated byte-exact from mca/qnn/qnn_model_execution_pipeline.cpp (lines 474-645).

#include "mca/qnn/qnn_model_execution_pipeline.hpp"
#include "mca/qnn/qnn_tensor_quantizer.hpp"

namespace mca {
namespace qnn {

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK


bool qnn_run_text_encoder_once(
        QnnExecutableGraph& text_encoder,
        int token_index,
        int embedding_index,
        const int32_t* tokens,
        size_t token_count,
        std::vector<float>* embeddings,
        long long* execute_ms,
        std::string* error) {
    if (!qnn_write_int32_vector_tensor(
            &text_encoder.inputs[static_cast<size_t>(token_index)],
            tokens,
            token_count,
            error)) {
        return false;
    }
    if (!text_encoder.execute(execute_ms, error)) {
        return false;
    }
    return qnn_read_float_tensor(
        text_encoder.outputs[static_cast<size_t>(embedding_index)],
        embeddings,
        error);
}

bool qnn_run_sdxl_unet_once(
        QnnExecutableGraph& unet,
        int sample_index,
        int timestep_index,
        int hidden_index,
        int time_ids_index,
        int pooled_index,
        const std::vector<float>& latent,
        double timestep,
        const float* hidden,
        size_t hidden_elements,
        const float* time_ids,
        size_t time_id_elements,
        const float* pooled,
        size_t pooled_elements,
        std::vector<float>* output,
        long long* execute_ms,
        std::string* error) {
    if (!qnn_write_float_tensor(&unet.inputs[sample_index], latent.data(), latent.size(), error) ||
        !qnn_write_timestep_tensor(&unet.inputs[timestep_index], timestep, error) ||
        !qnn_write_float_tensor(&unet.inputs[hidden_index], hidden, hidden_elements, error) ||
        !qnn_write_float_tensor(&unet.inputs[time_ids_index], time_ids, time_id_elements, error) ||
        !qnn_write_float_tensor(&unet.inputs[pooled_index], pooled, pooled_elements, error)) {
        return false;
    }
    if (!unet.execute(execute_ms, error)) {
        return false;
    }
    std::vector<float> raw_output;
    if (!qnn_read_float_tensor(unet.outputs[0], &raw_output, error)) {
        return false;
    }
    mca::image::SpatialTensorShape input_shape;
    mca::image::SpatialTensorShape output_shape;
    if (!mca::image::resolve_spatial_tensor_shape(
                unet.inputs[sample_index].dimensions,
                4U,
                &input_shape,
                error) ||
        !mca::image::resolve_spatial_tensor_shape(
                unet.outputs[0].dimensions,
                4U,
                &output_shape,
                error)) {
        return false;
    }
    if (input_shape.height != output_shape.height ||
        input_shape.width != output_shape.width) {
        *error = "SDXL UNet output spatial shape does not match its latent input.";
        return false;
    }
    return mca::image::copy_spatial_tile(
        raw_output,
        output_shape,
        0U,
        0U,
        input_shape,
        output,
        error);
}

// File-local: only qnn_prepare_sdxl_conditioning in this translation unit calls it. The
// same name in qnn_tensor_memory.cpp overloads on size_t with different semantics, so this
// uint32_t overload stays here as its own file-local definition.
namespace {

bool qnn_matches_vector_shape(
        const std::vector<uint32_t>& dimensions,
        uint32_t expected_elements) {
    return dimensions.size() == 2U && dimensions[0] == 1U &&
        dimensions[1] == expected_elements;
}

}  // namespace

bool qnn_prepare_sdxl_conditioning(
        const QnnExecutableGraph& unet,
        int hidden_index,
        int time_ids_index,
        int pooled_index,
        const std::vector<float>& payload,
        bool use_cfg,
        QnnSdxlConditioningBuffers* conditioning,
        std::string* error) {
    if (conditioning == nullptr || error == nullptr || hidden_index < 0 ||
        time_ids_index < 0 || pooled_index < 0) {
        return false;
    }
    mca::image::SequenceFeatureShape hidden_shape;
    if (!mca::image::resolve_sequence_feature_shape(
            unet.inputs[static_cast<size_t>(hidden_index)].dimensions,
            static_cast<uint32_t>(mca::image::kSdxlClipTokenCount),
            static_cast<uint32_t>(mca::image::kSdxlHiddenWidth),
            &hidden_shape,
            error)) {
        return false;
    }
    if (!qnn_matches_vector_shape(
            unet.inputs[static_cast<size_t>(pooled_index)].dimensions,
            static_cast<uint32_t>(mca::image::kSdxlPooledWidth))) {
        *error = "SDXL pooled conditioning tensor must be exactly [1,1280].";
        return false;
    }
    if (!qnn_matches_vector_shape(
            unet.inputs[static_cast<size_t>(time_ids_index)].dimensions,
            static_cast<uint32_t>(mca::image::kSdxlTimeIdCount))) {
        *error = "SDXL micro-conditioning tensor must be exactly [1,6].";
        return false;
    }
    const size_t branch_count = use_cfg ? 2U : 1U;
    if (!mca::image::validate_sdxl_conditioning_payload(
            payload.size(), branch_count, error)) {
        return false;
    }
    constexpr size_t kHiddenElements =
            mca::image::kSdxlClipTokenCount * mca::image::kSdxlHiddenWidth;
    conditioning->negative_hidden.clear();
    conditioning->negative_pooled = nullptr;
    const size_t positive_hidden_offset = use_cfg ? kHiddenElements : 0U;
    if (use_cfg && !mca::image::reorder_sequence_feature_tensor(
            payload.data(),
            kHiddenElements,
            hidden_shape,
            &conditioning->negative_hidden,
            error)) {
        return false;
    }
    if (!mca::image::reorder_sequence_feature_tensor(
            payload.data() + positive_hidden_offset,
            kHiddenElements,
            hidden_shape,
            &conditioning->positive_hidden,
            error)) {
        return false;
    }
    const size_t pooled_offset = branch_count * kHiddenElements;
    if (use_cfg) {
        conditioning->negative_pooled = payload.data() + pooled_offset;
    }
    conditioning->positive_pooled = payload.data() + pooled_offset +
        (use_cfg ? mca::image::kSdxlPooledWidth : 0U);
    conditioning->time_ids =
            conditioning->positive_pooled + mca::image::kSdxlPooledWidth;
    error->clear();
    return true;
}

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca
