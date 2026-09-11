#include <cassert>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>

namespace {

std::string read_text(const char* path) {
    std::ifstream input(path, std::ios::binary);
    assert(input.good());
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

std::string function_body(const std::string& source, const std::string& signature) {
    const auto signaturePosition = source.find(signature);
    const auto openingBrace = signaturePosition == std::string::npos
            ? std::string::npos
            : source.find('{', signaturePosition + signature.size());
    assert(signaturePosition != std::string::npos);
    assert(openingBrace != std::string::npos);
    int depth = 0;
    for (size_t position = openingBrace; position < source.size(); ++position) {
        if (source[position] == '{') ++depth;
        if (source[position] == '}' && --depth == 0) {
            return source.substr(openingBrace, position - openingBrace + 1U);
        }
    }
    assert(false);
    return {};
}

void require_contains(const std::string& source, const std::string& needle) {
    if (source.find(needle) == std::string::npos) {
        std::fprintf(stderr, "missing MNN SDXL pooled contract: %s\n", needle.c_str());
        assert(false);
    }
}

}  // namespace

int main(int argc, char** argv) {
    assert(argc == 2);
    const auto source = read_text(argv[1]);

    const auto clipEncoder = function_body(
            source, "bool run_sdxl_clip_encoder_direct(");

    // The exact [1, emb] projected pooled output stays the preferred contract.
    require_contains(clipEncoder, "const std::vector<int> pooledShape = {1, embeddingSize};");
    require_contains(clipEncoder, "const std::vector<std::string> pooledNames = "
                                  "{\"text_embeds\", \"pooled_output\"};");
    require_contains(clipEncoder,
            "auto* pooledTensor = find_named_output_by_shape(\n"
            "                outputs, pooledShape, pooledNames);");

    // Diffusers-style CLIP-bigG exports project every position and publish
    // [1, seq, emb]; the pooled vector must then come from the sequence's EOS
    // row instead of failing the whole generation.
    require_contains(clipEncoder,
            "auto* pooledSequenceTensor = find_named_output_by_shape(\n"
            "                    outputs,\n"
            "                    {1, static_cast<int>(tokenIds.size()), embeddingSize},\n"
            "                    pooledNames);");
    require_contains(clipEncoder, "constexpr int kClipEosTokenId = 49407;");
    require_contains(clipEncoder,
            "const auto eosPosition = std::find(\n"
            "                    tokenIds.begin(), tokenIds.end(), kClipEosTokenId);");
    require_contains(clipEncoder, "pooledSequence.values.begin() + eosRow * embeddingSize");
    require_contains(clipEncoder, "pooled->shape = pooledShape;");

    // Both branches must satisfy the same strict shape contract and keep the
    // refusal for unprojected hidden-state rows.
    require_contains(clipEncoder,
            "modelFile + \" projected pooled output\", error)");
    require_contains(clipEncoder, "refusing to substitute an EOS hidden-state row.");
    require_contains(clipEncoder, "projected pooled sequence requires EOS token id");

    // Debug evidence identifies the sequence fallback explicitly.
    require_contains(clipEncoder, "\"pooledSource\"] = \"projected_sequence_eos_row\"");

    std::puts("mca_mnn_sdxl_pooled_source_contract passed");
    return 0;
}
