// mca/qnn/qnn_sdxl_unet_phase.hpp
// T2B18 / R7-C7 split: this header is now a pure declaration surface.  The 1,249-line
// inline template body moved byte-exact into mca_qnn_runtime translation units
// (qnn_sdxl_unet_phase.cpp / qnn_sdxl_unet_tiling.cpp / qnn_sdxl_unet_execution.cpp)
// with one phase-handoff context (qnn_sdxl_unet_phase_internal.hpp).  Include sites are
// unchanged: qnn_sdxl_isolated_phases.hpp keeps calling the same two overloads.
#ifndef MCA_QNN_SDXL_UNET_PHASE_HPP
#define MCA_QNN_SDXL_UNET_PHASE_HPP

#include <string>
#include <utility>

// namespace mca::qnn removed to prevent anonymous namespace shadowing

template <typename GraphT, typename... Args>
inline std::string execute_sdxl_unet_phase(
        const std::string& bundle_root,
        const std::string& runtime_profile_json,
        const std::string& params_json,
        const std::string& embeddings_path,
        const std::string& initial_latent_path,
        const std::string& latent_path,
        Args&&... /*extra_args*/);
template <typename GraphT>
inline std::string execute_sdxl_unet_phase(
        const std::string& bundle_root,
        const std::string& runtime_profile_json,
        const std::string& params_json,
        const std::string& prompt_embeds_path,
        const std::string& pooled_embeds_path,
        const std::string& time_ids_path,
        const std::string& latent_path,
        const std::string& output_path,
        const std::string& expected_unet_context_sha256_raw) {
    const std::string effective_embeddings = prompt_embeds_path;
    const std::string effective_initial_latent =
        output_path.empty() ? "" : latent_path;
    const std::string effective_output_latent =
        output_path.empty() ? latent_path : output_path;
    return execute_sdxl_unet_phase<GraphT>(
        bundle_root,
        runtime_profile_json,
        params_json,
        effective_embeddings,
        effective_initial_latent,
        effective_output_latent);
}

#endif  // MCA_QNN_SDXL_UNET_PHASE_HPP
