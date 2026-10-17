// mca/qnn/qnn_bridge_payloads.cpp -- payload builders for the QNN bridge
// (T2B2-B1).  Relocated byte-exact from qnn_native_bridge.cpp.

#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cctype>
#include <cstdarg>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <fstream>
#include <iomanip>
#include <iterator>
#include <limits>
#include <mutex>
#include <new>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "diffusion_scheduler.hpp"
#include "image_conditioning.hpp"
#include "image_execution_math.hpp"
#include "jni_utf8_codec.hpp"
#include "mnn_qnn_prompt_handoff.hpp"
#include "native_prompt_language_contract.hpp"
#include "qnn_controlnet_execution.hpp"
#include "qnn_image_pixel_range.hpp"
#include "qnn_image_stage_trace.hpp"
#include "qnn_inpaint_contract.hpp"
#include "qnn_soc_htp_mapping.hpp"
#include "qnn_shared_preview.hpp"

#include "mca/qnn/qnn_system_symbol_loader.hpp"
#include "mca/qnn/qnn_capability_probe.hpp"
#include "mca/qnn/qnn_preview_manager.hpp"
#include "mca/qnn/qnn_bundle_files.hpp"
#include "mca/qnn/qnn_bundle_manifest.hpp"
#include "mca/qnn/qnn_sdxl_phase_support.hpp"
#include "mca/qnn/qnn_sdxl_unet_tiler.hpp"
#include "mca/qnn/qnn_tensor_codec.hpp"
#include "mca/qnn/qnn_device_support.hpp"
#include "mca/qnn/qnn_tensor_inspect.hpp"
#include "mca/qnn/qnn_ultrafix_handler.hpp"
#include "mca/qnn/qnn_image_writer.hpp"
#include "mca/qnn/qnn_inpaint_blend.hpp"
#include "mca/qnn/qnn_contract_parser.hpp"
#include "mca/qnn/qnn_executable_graph.hpp"
#include "mca/qnn/qnn_conditioning_pipeline.hpp"
#include "mca/qnn/qnn_model_execution_pipeline.hpp"
#include "mca/qnn/qnn_semantic_pipeline.hpp"
#include "mca/qnn/qnn_graph_executor_helper.hpp"
#include "mca/qnn/qnn_tensor_quantizer.hpp"
// The isolated-phase fragment header below defines its entry points unqualified, so the namespace
// import has to precede it.  (qnn_native_bridge.cpp previously listed this after every include,
// which left the fragment expanding without the mca::qnn names in scope.)
using namespace mca::qnn;

// The SDXL isolated-phase fragment headers below serialise real graph execution on the shared QNN
// context-execution mutex.  It is declared extern in mca/qnn/qnn_preview_manager.hpp (included
// above) and defined exactly once in mca/qnn/qnn_preview_manager.cpp, so mca_qnn_runtime and
// mca_qnn_native lock one and the same object.
//
// Do NOT re-add a global-scope `extern` here: that declares ::g_qnn_context_execution_mutex, a
// different symbol from ::mca::qnn::g_qnn_context_execution_mutex and one that nothing defines, so
// it would both fail to link and silently drop the serialisation of QNN context execution.

#if __has_include(<nlohmann/json.hpp>)
#include <nlohmann/json.hpp>
#elif __has_include("nlohmann/json.hpp")
#include "nlohmann/json.hpp"
#else
#include "../../../../../third_party/llama.cpp/vendor/nlohmann/json.hpp"
#endif

namespace mca {
namespace qnn {

std::string qnn_pipeline_probe_json(
        const std::string& bundle_root,
        const std::string& runtime_dirs_json,
        const std::string& params_json,
        const std::string& output_path) {
    if (g_qnn_runtime_poisoned.load()) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"runtime_resource_release_failed\",\"errorCode\":\"NATIVE_RESOURCE_RELEASE_FAILED\",\"message\":\"This disposable QNN image worker rejected reuse after a native resource release failure.\"}";
    }
    const auto started = std::chrono::steady_clock::now();
    const auto dirs = parse_json_string_array(runtime_dirs_json);
    const auto runtime = inspect_runtime_internal(dirs, true, false, &::mca::qnn::g_qnn_runtime_poisoned);
    if (g_qnn_runtime_poisoned.load()) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"runtime_resource_release_failed\",\"errorCode\":\"NATIVE_RESOURCE_RELEASE_FAILED\",\"message\":") +
            quote(runtime.message.empty()
                ? "QNN runtime preflight resources did not release cleanly."
                : runtime.message) + "}";
    }
    const auto bundle = inspect_bundle_internal(bundle_root);
    if (!runtime.loadable || !runtime.qnn_system_interface_present) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"runtime_unavailable\",\"message\":") +
            quote(runtime.message.empty() ? "QNN runtime is unavailable." : runtime.message) + "}";
    }
    if (!bundle.root_present) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"bundle_missing\",\"message\":\"QNN bundle root is missing.\"}";
    }
    std::string error;
    mca::qnn::ImagePixelRange pixel_range =
        mca::qnn::ImagePixelRange::NegativeOneToOne;
    if (!parse_qnn_pixel_range_contract(params_json, &pixel_range, &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"execution_contract_invalid\",\"errorCode\":\"EXECUTION_CONTRACT_INVALID\",\"message\":") +
            quote(error) + "}";
    }

    const std::string unet_binary = string_field(params_json, "unetContextBinary").empty()
        ? "unet.bin"
        : string_field(params_json, "unetContextBinary");
    const std::string vae_binary = string_field(params_json, "vaeDecoderContextBinary").empty()
        ? "vae_decoder.bin"
        : string_field(params_json, "vaeDecoderContextBinary");
    const std::string graph_name = string_field(params_json, "graphName").empty()
        ? "model"
        : string_field(params_json, "graphName");
    const std::string unet_path = join_path(bundle_root, unet_binary);
    const std::string vae_path = join_path(bundle_root, vae_binary);
    if (!exists_file(unet_path) || !exists_file(vae_path)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"context_missing\",\"message\":") +
            quote("QNN pipeline requires unet.bin and vae_decoder.bin in the bundle root.") + "}";
    }

    QnnExecutableGraph unet;
    if (!unet.load(runtime, unet_path, graph_name)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"unet_load_failed\",\"message\":") +
            quote(unet.message) + "}";
    }
    QnnExecutableGraph vae;
    if (!vae.load(runtime, vae_path, graph_name)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"vae_load_failed\",\"message\":") +
            quote(vae.message) + "}";
    }

    const int sample_index = std::max(0, tensor_index_by_name(unet.inputs, {"sample", "latent"}));
    int timestep_index = tensor_index_by_name(unet.inputs, {"timestamp", "timestep", "time"});
    if (timestep_index < 0 && unet.inputs.size() > 1) timestep_index = 1;
    int text_index = tensor_index_by_name(unet.inputs, {"text_embedding", "encoder_hidden", "hidden"});
    if (text_index < 0 && unet.inputs.size() > 2) text_index = 2;
    if (unet.inputs.size() < 3 || sample_index >= static_cast<int>(unet.inputs.size()) ||
        timestep_index < 0 || text_index < 0 || unet.outputs.empty() ||
        vae.inputs.empty() || vae.outputs.empty()) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"tensor_layout_unsupported\",\"message\":\"QNN SD1.5 probe expects UNet(sample,timestep,text) and VAE(latent) tensors.\"}";
    }

    const uint64_t latent_elements = qnn_tensor_element_count(unet.inputs[sample_index].tensor);
    const uint64_t text_elements = qnn_tensor_element_count(unet.inputs[text_index].tensor);
    const uint64_t vae_input_elements = qnn_tensor_element_count(vae.inputs[0].tensor);
    if (latent_elements == 0 || text_elements == 0 || vae_input_elements != latent_elements) {
        return "{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"tensor_shape_unsupported\",\"message\":\"QNN UNet and VAE latent tensor shapes are not compatible.\"}";
    }

    const int steps = static_cast<int>(std::max<long long>(1, std::min<long long>(long_field(params_json, "steps"), 8)));
    const int seed = static_cast<int>(long_field(params_json, "seed") == 0 ? 42 : long_field(params_json, "seed"));
    const float update_scale = static_cast<float>(double_field(params_json, "probeUpdateScale", 0.08));
    const float latent_scale = static_cast<float>(double_field(params_json, "vaeLatentScale", 1.0));
    std::mt19937 rng(static_cast<uint32_t>(seed));
    std::normal_distribution<float> normal(0.0f, 1.0f);
    std::vector<float> latents(static_cast<size_t>(latent_elements), 0.0f);
    for (float& value : latents) value = normal(rng);
    std::vector<float> text(static_cast<size_t>(text_elements), 0.0f);
    std::vector<float> unet_output;
    long long unet_execute_ms_total = 0;
    for (int step = 0; step < steps; ++step) {
        if (!qnn_write_float_tensor(&unet.inputs[sample_index], latents.data(), latents.size(), &error) ||
            !qnn_write_int32_tensor(&unet.inputs[timestep_index], steps - step, &error) ||
            !qnn_write_float_tensor(&unet.inputs[text_index], text.data(), text.size(), &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"unet_input_bind_failed\",\"message\":") +
                quote(error) + "}";
        }
        long long execute_ms = 0;
        if (!unet.execute(&execute_ms, &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"unet_execute_failed\",\"message\":") +
                quote(error) + "}";
        }
        unet_execute_ms_total += execute_ms;
        if (!qnn_read_float_tensor(unet.outputs[0], &unet_output, &error)) {
            return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"unet_output_read_failed\",\"message\":") +
                quote(error) + "}";
        }
        const size_t count = std::min(latents.size(), unet_output.size());
        for (size_t i = 0; i < count; ++i) {
            latents[i] -= update_scale * unet_output[i];
        }
    }

    std::vector<float> vae_latents(latents.size());
    for (size_t i = 0; i < latents.size(); ++i) vae_latents[i] = latents[i] * latent_scale;
    if (!qnn_write_float_tensor(&vae.inputs[0], vae_latents.data(), vae_latents.size(), &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"vae_input_bind_failed\",\"message\":") +
            quote(error) + "}";
    }
    long long vae_execute_ms = 0;
    if (!vae.execute(&vae_execute_ms, &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"vae_execute_failed\",\"message\":") +
            quote(error) + "}";
    }
    std::vector<float> pixels;
    if (!qnn_read_float_tensor(vae.outputs[0], &pixels, &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"vae_output_read_failed\",\"message\":") +
            quote(error) + "}";
    }
    int width = 0;
    int height = 0;
    mca::qnn::ImagePixelRangeEvidence pixel_range_evidence;
    if (!write_vae_tensor_png(
            vae.outputs[0],
            pixels,
            output_path,
            pixel_range,
            &pixel_range_evidence,
            &width,
            &height,
            &error)) {
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"png_write_failed\",\"message\":") +
            quote(error) + "}";
    }
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started
    ).count();
    const long long output_bytes = file_size_or_zero(output_path);
    std::string output_sha256;
    if (!qnn_file_sha256(output_path, &output_sha256, &error)) {
        ::unlink(output_path.c_str());
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"png_sha256_failed\",\"message\":") +
            quote(error) + "}";
    }
    std::ostringstream out;
    out << "{"
        << "\"ok\":true,"
        << "\"backend\":\"qnn_htp\","
        << "\"pipelineProbe\":true,"
        << "\"semanticReady\":false,"
        << "\"npuActive\":true,"
        << "\"executionStage\":\"pipeline_probe_passed\","
        << "\"message\":\"QNN UNet and VAE decoder executed sequentially on Snapdragon NPU; prompt text encoder and production scheduler are still pending.\","
        << "\"steps\":" << steps << ","
        << "\"seed\":" << seed << ","
        << "\"width\":" << width << ","
        << "\"height\":" << height << ","
        << qnn_pixel_range_evidence_json(pixel_range, pixel_range_evidence) << ","
        << "\"elapsedMs\":" << elapsed << ","
        << "\"unetContextLoadMs\":" << unet.context_load_ms << ","
        << "\"vaeContextLoadMs\":" << vae.context_load_ms << ","
        << "\"unetExecuteMsTotal\":" << unet_execute_ms_total << ","
        << "\"unetExecuteMsAvg\":" << (steps > 0 ? (unet_execute_ms_total / steps) : 0) << ","
        << "\"vaeExecuteMs\":" << vae_execute_ms << ","
        << "\"latentChecksum\":" << checksum_float_vector(latents) << ","
        << "\"pixelChecksum\":" << checksum_float_vector(pixels) << ","
        << "\"outputPath\":" << quote(output_path) << ","
        << "\"outputBytes\":" << output_bytes << ","
        << "\"outputSha256\":" << quote(output_sha256) << ","
        << "\"unetGraph\":" << quote(unet.graph_name) << ","
        << "\"vaeGraph\":" << quote(vae.graph_name) << ","
        << "\"debug\":{"
        << "\"unetInputs\":" << qnn_tensor_list_debug_json(unet.inputs) << ","
        << "\"unetOutputs\":" << qnn_tensor_list_debug_json(unet.outputs) << ","
        << "\"vaeInputs\":" << qnn_tensor_list_debug_json(vae.inputs) << ","
        << "\"vaeOutputs\":" << qnn_tensor_list_debug_json(vae.outputs) << ","
        << "\"finalLatentStats\":" << float_vector_stats_json(latents) << ","
        << "\"unetOutputStats\":" << float_vector_stats_json(unet_output) << ","
        << "\"pixelStats\":" << float_vector_stats_json(pixels) << "},"
        << "\"runtime\":" << runtime_probe_json(runtime) << ","
        << "\"bundle\":" << bundle_probe_json(bundle)
        << "}";
    const std::string success_json = out.str();
    const auto release_failure_json = [&](const std::string& component,
                                          const std::string& detail) {
        ::unlink(output_path.c_str());
        return std::string("{\"ok\":false,\"backend\":\"qnn_htp\",\"pipelineProbe\":true,\"executionStage\":\"") +
            component + "_release_failed\",\"errorCode\":\"NATIVE_RESOURCE_RELEASE_FAILED\",\"message\":" +
            quote(detail.empty()
                ? "QNN pipeline probe resources did not release cleanly."
                : detail) + "}";
    };
    std::string release_error;
    if (!vae.close_checked(&release_error)) {
        return release_failure_json("vae", release_error);
    }
    if (!unet.close_checked(&release_error)) {
        return release_failure_json("unet", release_error);
    }
    return success_json;
}

bool smoke_context_identity_matches(
        const SmokeSpecProbe& smoke_spec,
        const std::string& context_binary_path,
        std::string* error) {
    if (!smoke_spec.context_identity_required) return true;
    const long long actual_size = file_size_or_zero(context_binary_path);
    if (actual_size != smoke_spec.expected_context_size_bytes) {
        std::ostringstream message;
        message << "QNN smoke context size mismatch: expected "
                << smoke_spec.expected_context_size_bytes << ", got " << actual_size << ".";
        *error = message.str();
        return false;
    }
    std::string actual_sha256;
    if (!mca::qnn::controlnet::sha256_hex_file(
            context_binary_path, &actual_sha256, error)) {
        return false;
    }
    if (actual_sha256 != smoke_spec.expected_context_sha256) {
        *error = "QNN smoke context SHA-256 differs from the pinned package identity.";
        return false;
    }
    return true;
}

std::string smoke_execution_stage(
        const RuntimeProbe& runtime,
        const BundleProbe& bundle,
        const SmokeSpecProbe& smoke_spec,
        bool context_binary_present) {
    if (!runtime.loadable) return "runtime_load_failed";
    if (!runtime.qnn_system_interface_present) return "qnn_system_interface_missing";
    if (!bundle.root_present || !bundle.manifest_present || !bundle.has_graph_artifact) {
        return "bundle_incomplete";
    }
    if (!smoke_spec.complete) return "smoke_metadata_incomplete";
    if (!smoke_spec.tensor_buffer_plan_ready) return "tensor_buffer_plan_incomplete";
    if (!context_binary_present) return "context_binary_missing";
    if (smoke_spec.context_identity_required && !smoke_spec.context_identity_matched) {
        return "context_identity_mismatch";
    }
#if !MCA_WITH_QNN_SDK_HEADERS
    return "sdk_headers_missing";
#else
    return "graph_execution_ready";
#endif
}

std::string smoke_json(
        const char* kind,
        const std::string& bundle_root,
        const std::string& runtime_dirs_json,
        const std::string& smoke_spec_json) {
    if (g_qnn_runtime_poisoned.load()) {
        std::ostringstream poisoned;
        poisoned << "{"
            << "\"kind\":" << quote(kind) << ","
            << "\"backend\":\"qnn_htp\","
            << "\"ok\":false,"
            << "\"runnerReady\":false,"
            << "\"graphMetadataReady\":false,"
            << "\"graphRunnerReady\":false,"
            << "\"graphExecute\":false,"
            << "\"npuActive\":false,"
            << "\"smokePassed\":false,"
            << "\"executionStage\":\"runtime_resource_release_failed\","
            << "\"message\":\"This QNN process rejected reuse after a native resource release failure.\""
            << "}";
        return poisoned.str();
    }
    const auto start = std::chrono::steady_clock::now();
    const auto dirs = parse_json_string_array(runtime_dirs_json);
    const auto runtime = inspect_runtime_internal(dirs, true, false, &::mca::qnn::g_qnn_runtime_poisoned);
    if (g_qnn_runtime_poisoned.load()) {
        std::ostringstream poisoned;
        poisoned << "{"
            << "\"kind\":" << quote(kind) << ","
            << "\"backend\":\"qnn_htp\","
            << "\"ok\":false,"
            << "\"runnerReady\":false,"
            << "\"graphMetadataReady\":false,"
            << "\"graphRunnerReady\":false,"
            << "\"graphExecute\":false,"
            << "\"npuActive\":false,"
            << "\"smokePassed\":false,"
            << "\"executionStage\":\"runtime_resource_release_failed\","
            << "\"message\":" << quote(runtime.message.empty()
                ? "QNN runtime preflight resources did not release cleanly."
                : runtime.message)
            << "}";
        return poisoned.str();
    }
    const auto bundle = inspect_bundle_internal(bundle_root);
    const auto smoke_spec = inspect_smoke_spec(smoke_spec_json);
    const std::string context_binary_path = join_path(bundle_root, smoke_spec.context_binary);
    const bool context_binary_present = !smoke_spec.context_binary.empty() &&
        is_safe_bundle_relative_path(smoke_spec.context_binary) &&
        exists_file(context_binary_path) &&
        file_size_or_zero(context_binary_path) > 0;
    auto smoke_spec_with_file = smoke_spec;
    smoke_spec_with_file.context_binary_bytes = context_binary_present
        ? file_size_or_zero(context_binary_path)
        : 0;
    std::string context_identity_error;
    smoke_spec_with_file.context_identity_matched = context_binary_present &&
        smoke_context_identity_matches(
            smoke_spec_with_file,
            context_binary_path,
            &context_identity_error);
    const bool graph_metadata_ready = runtime.loadable &&
        runtime.qnn_system_interface_present &&
        bundle.root_present &&
        bundle.manifest_present &&
        bundle.has_graph_artifact &&
        context_binary_present &&
        smoke_spec_with_file.context_identity_matched &&
        smoke_spec_with_file.complete &&
        smoke_spec_with_file.tensor_buffer_plan_ready;
    GraphSmokeResult graph_smoke;
    if (graph_metadata_ready) {
        graph_smoke = run_typed_qnn_graph_smoke(
            runtime,
            smoke_spec_with_file,
            context_binary_path,
            smoke_spec_json
        );
    }
    smoke_spec_with_file.metadata_contract_matched = graph_smoke.metadata_contract_matched;
    smoke_spec_with_file.output_values_finite = graph_smoke.output_values_finite;
    smoke_spec_with_file.output_validation_passed = graph_smoke.output_validation_passed;
    smoke_spec_with_file.nonzero_output_elements = graph_smoke.nonzero_output_elements;
    std::string binary_metadata_json_value = "{}";
    bool binary_metadata_release_failed = false;
    std::string binary_metadata_release_message;
#if MCA_WITH_QNN_SDK_HEADERS
    if (context_binary_present && runtime.system_present &&
        !graph_smoke.resource_release_failed) {
        const QnnBinaryMetadata binary_metadata = inspect_qnn_context_binary_metadata(
            runtime, read_binary_file(context_binary_path));
        binary_metadata_release_failed = binary_metadata.release_failed;
        binary_metadata_release_message = binary_metadata.message;
        binary_metadata_json_value = binary_metadata_json(binary_metadata);
    }
#endif
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start
    ).count();

    std::string message;
    const bool graph_runner_ready = graph_smoke.graph_resolved && graph_smoke.tensors_bound;
    const bool graph_execute = graph_smoke.graph_executed;
    const bool smoke_passed = !binary_metadata_release_failed &&
        !graph_smoke.resource_release_failed && graph_smoke.graph_executed &&
        graph_smoke.metadata_contract_matched &&
        graph_smoke.output_validation_passed;
    const bool npu_active = graph_smoke.graph_executed;
    const std::string execution_stage = binary_metadata_release_failed
        ? "runtime_resource_release_failed"
        : (graph_smoke.resource_release_failed
            ? "runtime_resource_release_failed"
        : (graph_smoke.attempted
            ? graph_smoke.execution_stage
            : smoke_execution_stage(runtime, bundle, smoke_spec_with_file, context_binary_present)));
    if (binary_metadata_release_failed) {
        message = binary_metadata_release_message;
    } else if (graph_smoke.resource_release_failed) {
        message = graph_smoke.message.empty()
            ? "QNN graph smoke resources did not release cleanly."
            : graph_smoke.message;
    } else if (!runtime.loadable) {
        message = runtime.message;
    } else if (!runtime.qnn_system_interface_present) {
        message = "QNN System runtime loaded, but QnnSystemInterface_getProviders was not found.";
    } else if (!bundle.root_present || !bundle.manifest_present || !bundle.has_graph_artifact) {
        message = "QNN bundle is missing manifest or graph artifacts.";
    } else if (!smoke_spec_with_file.complete) {
        message = "QNN smoke metadata is incomplete; contextBinary, inputs, and outputs are required before graph execution.";
    } else if (!smoke_spec_with_file.tensor_buffer_plan_ready) {
        message = "QNN tensor buffer plan is incomplete; supported data types, shapes, and byte sizes are required before graph execution.";
    } else if (!context_binary_present) {
        message = "QNN smoke contextBinary is missing or empty inside this engine bundle.";
    } else if (smoke_spec_with_file.context_identity_required &&
               !smoke_spec_with_file.context_identity_matched) {
        message = context_identity_error.empty()
            ? "QNN smoke context differs from the pinned package identity."
            : context_identity_error;
#if !MCA_WITH_QNN_SDK_HEADERS
    } else {
        message = "QNN runtime, bundle, and smoke metadata are ready, but QNN SDK headers were not available at build time.";
#else
    } else {
        message = graph_smoke.message.empty()
            ? "QNN graph smoke execution returned no message."
            : graph_smoke.message;
#endif
    }

    std::ostringstream out;
    out << "{"
        << "\"kind\":" << quote(kind) << ","
        << "\"backend\":\"qnn_htp\","
        << "\"ok\":" << (smoke_passed ? "true" : "false") << ","
        << "\"runnerReady\":" << (MCA_WITH_QNN_SDK_HEADERS ? "true" : "false") << ","
        << "\"graphMetadataReady\":" << (graph_metadata_ready ? "true" : "false") << ","
        << "\"graphRunnerReady\":" << (graph_runner_ready ? "true" : "false") << ","
        << "\"compile\":" << compile_capability_json() << ","
        << "\"deviceCreated\":" << (graph_smoke.device_created ? "true" : "false") << ","
        << "\"contextDefaultDevice\":" << (graph_smoke.context_default_device ? "true" : "false") << ","
        << "\"deviceMessage\":" << quote(graph_smoke.device_message) << ","
        << "\"graphExecute\":" << (graph_execute ? "true" : "false") << ","
        << "\"npuActive\":" << (npu_active ? "true" : "false") << ","
        << "\"smokePassed\":" << (smoke_passed ? "true" : "false") << ","
        << "\"elapsedMs\":" << elapsed << ","
        << "\"contextLoadMs\":" << graph_smoke.context_load_ms << ","
        << "\"graphExecuteMs\":" << graph_smoke.graph_execute_ms << ","
        << "\"outputChecksum\":" << graph_smoke.output_checksum << ","
        << "\"resourceReleaseFailed\":"
        << (graph_smoke.resource_release_failed ? "true" : "false") << ","
        << "\"executionStage\":" << quote(execution_stage) << ","
        << "\"message\":" << quote(message) << ","
        << "\"runtime\":" << runtime_probe_json(runtime) << ","
        << "\"executionRuntime\":" << runtime_probe_json(graph_smoke.execution_runtime) << ","
        << "\"executionRuntimeSelected\":"
        << (graph_smoke.execution_runtime_selected ? "true" : "false") << ","
        << "\"bundle\":" << bundle_probe_json(bundle) << ","
        << "\"binaryMetadata\":" << binary_metadata_json_value << ","
        << "\"smokeSpec\":" << smoke_spec_probe_json(smoke_spec_with_file) << ","
        << "\"stages\":" << smoke_stage_json(
            runtime,
            bundle,
            smoke_spec_with_file,
            context_binary_present,
            graph_metadata_ready,
            graph_smoke.backend_created,
            graph_smoke.context_loaded,
            graph_smoke.graph_resolved,
            graph_smoke.tensors_bound,
            graph_smoke.graph_executed)
        << "}";
    return out.str();
}

}  // namespace qnn
}  // namespace mca
