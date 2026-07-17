#include "../../main/cpp/image_conditioning.hpp"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <string>
#include <vector>

namespace {

using mca::image::ClipEmbeddingWeightStats;
using mca::image::ClipTokenizerConfig;
using mca::image::ClipTokenPair;
using mca::image::ClipTokenSequence;
using mca::image::WeightedPromptFragment;

constexpr float kTolerance = 1.0e-5f;

void assert_near(float actual, float expected, float tolerance = kTolerance) {
    assert(std::abs(actual - expected) <= tolerance);
}

void assert_fragments(const std::string& prompt, const std::vector<std::string>& expected_text,
                      const std::vector<float>& expected_weight) {
    std::vector<WeightedPromptFragment> fragments;
    std::string error;
    const bool ok = mca::image::parse_clip_prompt_weighting(prompt, &fragments, &error);
    if (!ok) std::fprintf(stderr, "prompt parse failed: %s\n", error.c_str());
    assert(ok);
    assert(fragments.size() == expected_text.size());
    assert(fragments.size() == expected_weight.size());
    for (size_t index = 0; index < fragments.size(); ++index) {
        assert(fragments[index].text == expected_text[index]);
        assert_near(fragments[index].weight, expected_weight[index]);
    }
}

bool encode_each_byte(const std::string& text, std::vector<int32_t>* ids, std::string* error) {
    if (ids == nullptr || error == nullptr) return false;
    ids->clear();
    for (const unsigned char value : text) ids->push_back(static_cast<int32_t>(value));
    return true;
}

void test_prompt_parser_default_explicit_and_nested_weights() {
    assert_fragments("a (bright) [shadow] (ceramic lantern:1.5)",
                     {"a ", "bright", " ", "shadow", " ", "ceramic lantern"},
                     {1.0f, 1.1f, 1.0f, 1.0f / 1.1f, 1.0f, 1.5f});
    assert_fragments("(outer (inner) tail)", {"outer ", "inner", " tail"}, {1.1f, 1.21f, 1.1f});
    assert_fragments("[(nested:2.0)]", {"nested"}, {2.0f / 1.1f});
    assert_fragments("plain", {"plain"}, {1.0f});
}

void test_prompt_parser_escaping_and_literal_unmatched_delimiters() {
    assert_fragments(R"(literal \(round\) \[square\] \: \\ (weighted\):1.25))",
                     {"literal (round) [square] : \\ ", "weighted)"}, {1.0f, 1.25f});
    assert_fragments("unmatched ( text", {"unmatched ( text"}, {1.0f});
    assert_fragments("(not-a-number:value)", {"not-a-number:value"}, {1.1f});
}

void test_segment_tokenization_padding_stats_and_fingerprint() {
    ClipTokenizerConfig config;
    config.bos_id = 101;
    config.eos_id = 102;
    config.pad_id = 0;
    config.max_length = 8;
    config.enable_prompt_weighting = true;

    ClipTokenSequence first;
    std::string error;
    assert(mca::image::tokenize_weighted_clip_sequence_with_encoder(
            "a (bc:1.5)", config, encode_each_byte, &first, &error));
    const std::vector<int32_t> expected_ids = {101, 'a', ' ', 'b', 'c', 102, 0, 0};
    const std::vector<float> expected_weights = {1, 1, 1, 1.5f, 1.5f, 1, 1, 1};
    assert(first.ids == expected_ids);
    assert(first.weights == expected_weights);
    assert(first.untruncated_token_count == 6);
    assert(!first.truncated);
    assert(first.weighting_applied);
    assert(first.weighted_token_count == 2);
    assert_near(first.min_weight, 1.0f);
    assert_near(first.max_weight, 1.5f);
    assert_near(first.mean_weight, 1.25f);
    assert(first.weighting_fingerprint ==
           "b9574ea3575106cabef95f24c28e8b07ff3bf356961651993261175f4f87ece0");

    ClipTokenSequence second;
    assert(mca::image::tokenize_weighted_clip_sequence_with_encoder(
            "a (bc:1.5)", config, encode_each_byte, &second, &error));
    assert(first.weighting_fingerprint == second.weighting_fingerprint);

    ClipTokenSequence changed;
    assert(mca::image::tokenize_weighted_clip_sequence_with_encoder(
            "a (bc:1.6)", config, encode_each_byte, &changed, &error));
    assert(first.ids == changed.ids);
    assert(changed.weighting_fingerprint ==
           "8291e2ca395eb924031d79e5a0424138e159b72dcc057e62d6a901f06651d0d6");

    ClipTokenPair pair;
    pair.negative = first;
    pair.positive = changed;
    const std::string pair_fingerprint = pair.weighting_fingerprint();
    assert(pair_fingerprint == "1a2c3a52fbd890c78d42a51abb111fc94f1892afee5c4fdfa8d273c340d63ca7");
    assert(pair_fingerprint == pair.weighting_fingerprint());
    std::swap(pair.negative, pair.positive);
    assert(pair_fingerprint != pair.weighting_fingerprint());
}

void test_truncation_keeps_special_weights_at_one() {
    ClipTokenizerConfig config;
    config.bos_id = 101;
    config.eos_id = 102;
    config.pad_id = 0;
    config.max_length = 5;
    config.enable_prompt_weighting = true;

    ClipTokenSequence sequence;
    std::string error;
    assert(mca::image::tokenize_weighted_clip_sequence_with_encoder(
            "(abcdef:2)", config, encode_each_byte, &sequence, &error));
    const std::vector<int32_t> expected_ids = {101, 'a', 'b', 'c', 102};
    const std::vector<float> expected_weights = {1, 2, 2, 2, 1};
    assert(sequence.ids == expected_ids);
    assert(sequence.weights == expected_weights);
    assert(sequence.untruncated_token_count == 8);
    assert(sequence.truncated);
    assert(sequence.weighted_token_count == 3);
    assert_near(sequence.min_weight, 2.0f);
    assert_near(sequence.max_weight, 2.0f);
    assert_near(sequence.mean_weight, 2.0f);
}

void test_disabled_weighting_tokenizes_attention_syntax_literally() {
    ClipTokenizerConfig config;
    config.bos_id = 101;
    config.eos_id = 102;
    config.pad_id = 0;
    config.max_length = 16;
    assert(!config.enable_prompt_weighting);

    ClipTokenSequence sequence;
    std::string error;
    assert(mca::image::tokenize_weighted_clip_sequence_with_encoder(
            "(a:2)[b]", config, encode_each_byte, &sequence, &error));
    const std::vector<int32_t> literal = {'(', 'a', ':', '2', ')', '[', 'b', ']'};
    assert(std::equal(literal.begin(), literal.end(), sequence.ids.begin() + 1));
    assert(!sequence.weighting_applied);
    assert(sequence.weighted_token_count == 0);
    for (const float weight : sequence.weights) assert_near(weight, 1.0f);
}

void test_embedding_weighting_with_mean_amplitude_normalization() {
    const std::vector<float> embeddings = {1, -1, 2, -2, 1, 3};
    const std::vector<float> weights = {1, 2, 0.5f};
    std::vector<float> output;
    ClipEmbeddingWeightStats stats;
    std::string error;
    assert(mca::image::apply_clip_token_weights_to_embeddings(embeddings, 3, 2, weights, true,
                                                              &output, &stats, &error));
    assert(output.size() == embeddings.size());
    assert(stats.weighting_applied);
    assert(stats.weighted_token_count == 2);
    assert_near(stats.input_mean_amplitude, 10.0f / 6.0f);
    assert_near(stats.weighted_mean_amplitude, 2.0f);
    assert_near(stats.normalization_scale, 5.0f / 6.0f);
    assert_near(stats.output_mean_amplitude, stats.input_mean_amplitude);
    const std::vector<float> expected = {
            5.0f / 6.0f, -5.0f / 6.0f, 10.0f / 3.0f, -10.0f / 3.0f, 5.0f / 12.0f, 5.0f / 4.0f,
    };
    for (size_t index = 0; index < output.size(); ++index) {
        assert_near(output[index], expected[index]);
    }

    assert(mca::image::apply_clip_token_weights_to_embeddings(embeddings, 3, 2, weights, false,
                                                              &output, &stats, &error));
    assert_near(stats.normalization_scale, 1.0f);
    assert_near(output[2], 4.0f);
    assert_near(output[4], 0.5f);

    std::vector<float> in_place = embeddings;
    assert(mca::image::apply_clip_token_weights_to_embeddings(
            in_place, 3, 2, weights, true, &in_place, &stats, &error));
    for (size_t index = 0; index < in_place.size(); ++index) {
        assert_near(in_place[index], expected[index]);
    }
}

void test_embedding_weighting_rejects_invalid_shape_and_values() {
    std::vector<float> output;
    ClipEmbeddingWeightStats stats;
    std::string error;
    assert(!mca::image::apply_clip_token_weights_to_embeddings({1, 2}, 2, 2, {1, 1}, true, &output,
                                                               &stats, &error));
    assert(error.find("shape") != std::string::npos);
    assert(!mca::image::apply_clip_token_weights_to_embeddings(
            {1, 2}, 1, 2, {std::numeric_limits<float>::quiet_NaN()}, true, &output, &stats,
            &error));
    assert(error.find("finite") != std::string::npos);
}

}  // namespace

int main() {
    test_prompt_parser_default_explicit_and_nested_weights();
    test_prompt_parser_escaping_and_literal_unmatched_delimiters();
    test_segment_tokenization_padding_stats_and_fingerprint();
    test_truncation_keeps_special_weights_at_one();
    test_disabled_weighting_tokenizes_attention_syntax_literally();
    test_embedding_weighting_with_mean_amplitude_normalization();
    test_embedding_weighting_rejects_invalid_shape_and_values();
    return 0;
}
