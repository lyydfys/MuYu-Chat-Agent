#ifndef NOMINMAX
#define NOMINMAX
#endif

#include "mca/qnn/qnn_model_execution_pipeline.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <limits>
#include <random>
#include <sstream>
#include <string>
#include <vector>

#include "mca/qnn/qnn_bundle_files.hpp"
#include "mca/qnn/qnn_image_writer.hpp"
#include "mca/qnn/qnn_tensor_codec.hpp"
#include "mca/qnn/qnn_tensor_memory.hpp"
#include "mca/qnn/qnn_conditioning_pipeline.hpp"
#include "image_execution_math.hpp"
#include "mca/qnn/qnn_graph_executor_helper.hpp"
#include "mca/qnn/qnn_tensor_quantizer.hpp"
#include "mca/qnn/qnn_preview_manager.hpp"

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK

namespace mca {
namespace qnn {

// File-local: every call site lives in this translation unit (qnn_run_controlnet_once,
// qnn_run_unet_once, qnn_run_inpaint_unet_once, qnn_run_sdxl_unet_once), so the definition
// must not be an external strong definition competing with qnn_tensor_quantizer.cpp.
namespace {

}  // namespace

// File-local: only qnn_resolve_controlnet_layout in this translation unit calls it, so the
// definition must not be an external strong definition competing with qnn_tensor_quantizer.cpp.
namespace {

}  // namespace

bool qnn_resolve_controlnet_layout(
        const QnnExecutableGraph& controlnet,
        const QnnExecutableGraph& unet,
        int unet_sample_index,
        int unet_timestep_index,
        int unet_text_index,
        int expected_width,
        int expected_height,
        QnnControlNetLayout* layout,
        std::string* error) {
    if (layout == nullptr || error == nullptr || unet_sample_index < 0 ||
        unet_timestep_index < 0 || unet_text_index < 0) {
        return false;
    }
    if (controlnet.inputs.size() != 4U) {
        *error = "ControlNet graph must expose exactly latent, timestep, text_emb, and image_cond inputs. inputs=" +
            qnn_tensor_list_debug_json(controlnet.inputs);
        return false;
    }
    layout->latent_index = qnn_exact_tensor_index(
        controlnet.inputs, {"latent", "sample"}, error);
    layout->timestep_index = qnn_exact_tensor_index(
        controlnet.inputs, {"timestep", "timestamp"}, error);
    layout->text_index = qnn_exact_tensor_index(
        controlnet.inputs, {"text_emb", "text_embedding", "encoder_hidden_states"}, error);
    layout->image_index = qnn_exact_tensor_index(
        controlnet.inputs, {"image_cond", "control_image", "controlnet_cond"}, error);
    if (layout->latent_index < 0 || layout->timestep_index < 0 ||
        layout->text_index < 0 || layout->image_index < 0) {
        if (error->empty()) {
            *error = "ControlNet graph tensor names do not match the required four-input contract. inputs=" +
                qnn_tensor_list_debug_json(controlnet.inputs);
        }
        return false;
    }
    if (controlnet.inputs[static_cast<size_t>(layout->latent_index)].dimensions !=
            unet.inputs[static_cast<size_t>(unet_sample_index)].dimensions ||
        controlnet.inputs[static_cast<size_t>(layout->timestep_index)].dimensions !=
            unet.inputs[static_cast<size_t>(unet_timestep_index)].dimensions ||
        controlnet.inputs[static_cast<size_t>(layout->text_index)].dimensions !=
            unet.inputs[static_cast<size_t>(unet_text_index)].dimensions) {
        *error = "ControlNet latent, timestep, and text tensor shapes must exactly match the Control-UNet base inputs.";
        return false;
    }
    if (!mca::image::resolve_spatial_tensor_shape(
            controlnet.inputs[static_cast<size_t>(layout->image_index)].dimensions,
            3U,
            &layout->image_shape,
            error)) {
        return false;
    }
    if (layout->image_shape.batch != 1U ||
        layout->image_shape.width != static_cast<size_t>(expected_width) ||
        layout->image_shape.height != static_cast<size_t>(expected_height)) {
        *error = "ControlNet image_cond tensor does not match the resolved execution width and height.";
        return false;
    }

    std::vector<mca::qnn::controlnet::TensorDescriptor> control_outputs;
    std::vector<mca::qnn::controlnet::TensorDescriptor> unet_inputs;
    control_outputs.reserve(controlnet.outputs.size());
    unet_inputs.reserve(unet.inputs.size());
    for (const auto& tensor : controlnet.outputs) {
        control_outputs.push_back({tensor.name, tensor.dimensions});
    }
    for (const auto& tensor : unet.inputs) {
        unet_inputs.push_back({tensor.name, tensor.dimensions});
    }
    return mca::qnn::controlnet::build_residual_binding_plan(
        control_outputs,
        unet_inputs,
        &layout->residual_bindings,
        error);
}

bool qnn_control_image_tensor_for_layout(
        const mca::qnn::controlnet::PreparedControlImage& image,
        const mca::image::SpatialTensorShape& shape,
        std::vector<float>* tensor,
        std::string* error) {
    if (tensor == nullptr || error == nullptr || shape.batch != 1U || shape.channels != 3U ||
        shape.width != image.tensor_width || shape.height != image.tensor_height ||
        image.tensor_hwc.size() != shape.element_count()) {
        if (error != nullptr) {
            *error = "Prepared control image does not exactly match the QNN image_cond tensor.";
        }
        return false;
    }
    if (shape.layout == mca::image::SpatialTensorLayout::Nhwc) {
        *tensor = image.tensor_hwc;
        error->clear();
        return true;
    }
    tensor->assign(shape.element_count(), 0.0F);
    for (size_t y = 0U; y < shape.height; ++y) {
        for (size_t x = 0U; x < shape.width; ++x) {
            for (size_t channel = 0U; channel < 3U; ++channel) {
                const size_t source = (y * shape.width + x) * 3U + channel;
                const size_t target = (channel * shape.height + y) * shape.width + x;
                (*tensor)[target] = image.tensor_hwc[source];
            }
        }
    }
    error->clear();
    return true;
}

bool qnn_run_controlnet_once(
        QnnExecutableGraph& controlnet,
        const QnnControlNetLayout& layout,
        QnnExecutableGraph& unet,
        const std::vector<float>& latent,
        double timestep,
        const float* embedding,
        size_t embedding_elements,
        const std::vector<float>& control_image_tensor,
        double control_strength,
        size_t* residual_write_count,
        uint64_t* scaled_residual_checksum,
        std::string* input_buffer_sha256,
        long long* execute_ms,
        std::string* error) {
    if (embedding == nullptr || residual_write_count == nullptr ||
        scaled_residual_checksum == nullptr || input_buffer_sha256 == nullptr ||
        execute_ms == nullptr || error == nullptr) {
        return false;
    }
    if (!qnn_write_float_tensor(
            &controlnet.inputs[static_cast<size_t>(layout.latent_index)],
            latent.data(), latent.size(), error) ||
        !qnn_write_timestep_tensor(
            &controlnet.inputs[static_cast<size_t>(layout.timestep_index)],
            timestep, error) ||
        !qnn_write_float_tensor(
            &controlnet.inputs[static_cast<size_t>(layout.text_index)],
            embedding, embedding_elements, error) ||
        !qnn_write_float_tensor(
            &controlnet.inputs[static_cast<size_t>(layout.image_index)],
            control_image_tensor.data(), control_image_tensor.size(), error)) {
        return false;
    }
    const std::string current_input_buffer_sha256 =
        mca::qnn::controlnet::sha256_hex_bytes(
            controlnet.inputs[static_cast<size_t>(layout.image_index)].buffer);
    if (current_input_buffer_sha256.size() != 64U) {
        *error = "ControlNet image_cond graph buffer SHA-256 could not be derived.";
        return false;
    }
    if (input_buffer_sha256->empty()) {
        *input_buffer_sha256 = current_input_buffer_sha256;
    } else if (*input_buffer_sha256 != current_input_buffer_sha256) {
        *error = "ControlNet image_cond graph buffer changed between scheduler steps.";
        return false;
    }
    if (!controlnet.execute(execute_ms, error)) return false;

    for (const auto& binding : layout.residual_bindings) {
        std::vector<float> residual;
        if (!qnn_read_float_tensor(
                controlnet.outputs[binding.control_output_index],
                &residual,
                error) ||
            !mca::qnn::controlnet::scale_residual_in_place(
                &residual,
                control_strength,
                error) ||
            !qnn_write_float_tensor(
                &unet.inputs[binding.unet_input_index],
                residual.data(),
                residual.size(),
                error)) {
            return false;
        }
        *scaled_residual_checksum += checksum_float_vector(residual);
        ++(*residual_write_count);
    }
    return true;
}


}  // namespace qnn
}  // namespace mca

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK
