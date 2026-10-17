// mca/qnn/qnn_model_execution_unet.cpp -- UNet and inpaint-UNet single-step executors.
// (T2B17 / R7-C6 split).  Relocated byte-exact from mca/qnn/qnn_model_execution_pipeline.cpp (lines 227-249, 382-473).

#include "mca/qnn/qnn_model_execution_pipeline.hpp"
#include "mca/qnn/qnn_tensor_quantizer.hpp"

namespace mca {
namespace qnn {

#if MCA_QNN_SYSTEM_LOADER_HAS_SDK


bool qnn_run_unet_once(
        QnnExecutableGraph& unet,
        int sample_index,
        int timestep_index,
        int text_index,
        const std::vector<float>& latent,
        double timestep,
        const float* embedding,
        size_t embedding_elements,
        std::vector<float>* output,
        long long* execute_ms,
        std::string* error) {
    if (!qnn_write_float_tensor(&unet.inputs[sample_index], latent.data(), latent.size(), error) ||
        !qnn_write_timestep_tensor(&unet.inputs[timestep_index], timestep, error) ||
        !qnn_write_float_tensor(&unet.inputs[text_index], embedding, embedding_elements, error)) {
        return false;
    }
    if (!unet.execute(execute_ms, error)) {
        return false;
    }
    return qnn_read_float_tensor(unet.outputs[0], output, error);
}

// Generic inpaint UNet binder. A regular four-channel graph keeps its normal
// sample binding and receives the mask only through the post-step latent blend.
// Explicit mask-conditioned graphs bind the additional tensors declared by
// qnn::inpaint::inspect().
bool qnn_run_inpaint_unet_once(
        QnnExecutableGraph& unet,
        const mca::qnn::inpaint::Contract& inpaint_contract,
        int timestep_index,
        int text_index,
        const std::vector<float>& latent,
        const std::vector<float>& mask,
        const std::vector<float>& masked_latent,
        double timestep,
        const float* embedding,
        size_t embedding_elements,
        std::vector<float>* output,
        long long* execute_ms,
        std::string* error) {
    if (!inpaint_contract.supported() || inpaint_contract.sample_index < 0 ||
        static_cast<size_t>(inpaint_contract.sample_index) >= unet.inputs.size() ||
        timestep_index < 0 || static_cast<size_t>(timestep_index) >= unet.inputs.size() ||
        text_index < 0 || static_cast<size_t>(text_index) >= unet.inputs.size()) {
        if (error != nullptr) *error = "Mask-conditioned UNet contract is not bound to the loaded graph inputs.";
        return false;
    }
    std::vector<float> concatenated;
    if (inpaint_contract.topology == mca::qnn::inpaint::MaskTopology::LatentBlend4) {
        if (!qnn_write_float_tensor(
                &unet.inputs[static_cast<size_t>(inpaint_contract.sample_index)],
                latent.data(),
                latent.size(),
                error)) {
            return false;
        }
    } else if (inpaint_contract.topology == mca::qnn::inpaint::MaskTopology::ConcatenatedLatent9) {
        if (!mca::qnn::inpaint::build_concatenated_sample(
                latent,
                mask,
                masked_latent,
                inpaint_contract.width,
                inpaint_contract.height,
                inpaint_contract.layout,
                &concatenated,
                error)) {
            return false;
        }
        if (!qnn_write_float_tensor(
                &unet.inputs[static_cast<size_t>(inpaint_contract.sample_index)],
                concatenated.data(),
                concatenated.size(),
                error)) {
            return false;
        }
    } else if (inpaint_contract.topology == mca::qnn::inpaint::MaskTopology::SeparateMaskInput) {
        if (inpaint_contract.mask_index < 0 ||
            static_cast<size_t>(inpaint_contract.mask_index) >= unet.inputs.size() ||
            !qnn_write_float_tensor(
                &unet.inputs[static_cast<size_t>(inpaint_contract.sample_index)],
                latent.data(),
                latent.size(),
                error) ||
            !mca::qnn::inpaint::validate_mask_values(
                mask,
                inpaint_contract.width,
                inpaint_contract.height,
                error) ||
            !qnn_write_float_tensor(
                &unet.inputs[static_cast<size_t>(inpaint_contract.mask_index)],
                mask.data(),
                mask.size(),
                error)) {
            return false;
        }
    } else {
        if (error != nullptr) *error = "QNN inpaint topology is not supported by the native binder.";
        return false;
    }
    if (!qnn_write_timestep_tensor(
            &unet.inputs[static_cast<size_t>(timestep_index)],
            timestep,
            error) ||
        !qnn_write_float_tensor(
            &unet.inputs[static_cast<size_t>(text_index)],
            embedding,
            embedding_elements,
            error)) {
        return false;
    }
    if (!unet.execute(execute_ms, error)) return false;
    return qnn_read_float_tensor(unet.outputs[0], output, error);
}

#endif  // MCA_QNN_SYSTEM_LOADER_HAS_SDK

}  // namespace qnn
}  // namespace mca
