#include <cassert>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace {

std::string read_text(const char* path) {
    std::ifstream input(path, std::ios::binary);
    assert(input.good());
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

std::string function_body(const std::string& source, const std::string& signature) {
    const auto signature_position = source.find(signature);
    const auto opening_brace = signature_position == std::string::npos
        ? std::string::npos
        : source.find('{', signature_position + signature.size());
    assert(signature_position != std::string::npos);
    assert(opening_brace != std::string::npos);
    int depth = 0;
    for (std::size_t position = opening_brace; position < source.size(); ++position) {
        if (source[position] == '{') ++depth;
        if (source[position] == '}' && --depth == 0) {
            return source.substr(opening_brace, position - opening_brace + 1);
        }
    }
    assert(false);
    return {};
}

void require_contains(const std::string& source, const std::string& needle) {
    if (source.find(needle) == std::string::npos) {
        std::fprintf(stderr, "missing MNN diffusion batch contract: %s\n", needle.c_str());
        assert(false);
    }
}

void require_sequence(
        const std::string& source,
        const std::vector<std::string>& needles) {
    std::size_t position = 0;
    for (const auto& needle : needles) {
        position = source.find(needle, position);
        if (position == std::string::npos) {
            std::fprintf(stderr, "out-of-order MNN diffusion batch contract: %s\n", needle.c_str());
            assert(false);
        }
        position += needle.size();
    }
}

}  // namespace

int main(int argc, char** argv) {
    assert(argc == 2);
    const auto source = read_text(argv[1]);

    const auto smoke = function_body(source, "json run_unet_interpreter_smoke(");
    require_contains(smoke, "diffusionBatchCandidates(true)");
    require_contains(smoke, "batch == 2 ? \"defer-inside-batch2\" : \"defer-inside-batch1\"");

    const auto text_encoder = function_body(source, "bool run_text_encoder_direct(");
    require_contains(text_encoder, "run_text_encoder_direct_once(");
    require_contains(text_encoder, "negativeIds");
    require_contains(text_encoder, "positiveIds");
    require_contains(text_encoder, "embeddings.shape = {2, 77, 768}");

    const auto initialize = function_body(source, "bool initialize(");
    require_contains(initialize, "mnn_tensor_shape(sample_input_)");
    require_contains(initialize, "mnn_tensor_shape(encoder_input_)");
    require_contains(initialize, "mnn_tensor_shape(timestep_input_)");
    require_contains(initialize, "UNet input shape mismatch after resize");
    require_contains(initialize, "close();");
    require_sequence(initialize, {
        "close();",
        "batch_ = batch;",
        "MNN::Interpreter::createFromFile",
        "mnn_tensor_shape(sample_input_)",
        "UNet input shape mismatch after resize",
        "close();"
    });

    const auto generate = function_body(source, "json run_mnn_sd15_interpreter_direct(");
    require_contains(generate, "diffusionBatchCandidates(contract.use_cfg)");
    require_contains(generate, "usesSequentialCfg(contract.use_cfg, unetSession.batch())");
    require_contains(generate, "shouldRetryFirstBatchedCfgExecution(");
    require_contains(generate, "runtimeBatchFallbackAttempted = true");
    require_contains(generate, "batch2ExecutionError");
    require_contains(generate, "Batch-1 compatibility reinitialization failed");
    require_contains(generate, "executeSequentialCfg = true");
    require_contains(generate, "batchedNoise.values.clear()");
    require_contains(generate, "unetRuntimeFallbackReason");
    require_contains(generate, "for (size_t branch = 0; branch < 2U; ++branch)");
    require_contains(generate, "if (branch == 0U)");
    require_contains(generate, "const auto cancelled = mnn_asset_cancel_callback()");
    require_contains(generate, "{\"cancelled\", true}");
    require_contains(generate, "++evidence.graph_invocation_count");
    require_contains(generate, "expectedGraphInvocationCount");
    require_sequence(generate, {
        "const std::string batch2ExecutionError = error",
        "if (!unetSession.initialize(",
        "batch1InitializationError",
        "executeSequentialCfg = true",
        "if (executeSequentialCfg)",
        "batchedNoise.values.clear()",
        "for (size_t branch = 0; branch < 2U; ++branch)",
        "++evidence.graph_invocation_count",
        "if (branch == 0U)",
        "const auto cancelled = mnn_asset_cancel_callback()"
    });
    return 0;
}
