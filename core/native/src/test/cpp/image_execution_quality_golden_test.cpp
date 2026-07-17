#include "../../main/cpp/diffusion_scheduler.hpp"
#include "../../main/cpp/image_conditioning.hpp"
#include "fixtures/image_execution_quality_golden.hpp"

#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

namespace {

using mca::diffusion::DiffusionScheduler;
using mca::diffusion::DiffusionSchedulerConfig;
using mca::diffusion::SchedulerStepResult;
using mca::diffusion::SchedulerTensor;
using mca::image::ClipEmbeddingWeightStats;
using mca::image::ClipTokenPair;
using mca::image::ClipTokenSequence;
using mca::image::ClipTokenizerConfig;
using namespace mca::image::test_fixture;

constexpr float kTolerance = 4.0e-5f;
constexpr float kVaeScalingFactor = 0.18215f;

bool encode_each_byte(
        const std::string& text,
        std::vector<int32_t>* ids,
        std::string* error) {
    if (ids == nullptr || error == nullptr) return false;
    ids->clear();
    for (const unsigned char value : text) ids->push_back(static_cast<int32_t>(value));
    return true;
}

template <size_t Size>
void assert_near(
        const std::vector<float>& actual,
        const std::array<float, Size>& expected) {
    assert(actual.size() == expected.size());
    for (size_t index = 0; index < actual.size(); ++index) {
        assert(std::abs(actual[index] - expected[index]) <= kTolerance);
    }
}

void append_u32_le(uint32_t value, std::vector<uint8_t>* bytes) {
    for (int shift = 0; shift < 32; shift += 8) {
        bytes->push_back(static_cast<uint8_t>((value >> shift) & 0xffU));
    }
}

void append_i32_values(const std::vector<int32_t>& values, std::vector<uint8_t>* bytes) {
    for (const int32_t value : values) append_u32_le(static_cast<uint32_t>(value), bytes);
}

void append_float_values(const std::vector<float>& values, std::vector<uint8_t>* bytes) {
    for (const float value : values) {
        uint32_t bits = 0;
        static_assert(sizeof(bits) == sizeof(value));
        std::memcpy(&bits, &value, sizeof(bits));
        append_u32_le(bits, bytes);
    }
}

std::string fnv1a64_hex(const std::vector<uint8_t>& bytes) {
    uint64_t hash = 14695981039346656037ULL;
    for (const uint8_t value : bytes) {
        hash ^= static_cast<uint64_t>(value);
        hash *= 1099511628211ULL;
    }
    std::ostringstream output;
    output << std::hex << std::setfill('0') << std::setw(16) << hash;
    return output.str();
}

std::vector<float> synthetic_embeddings(const std::vector<int32_t>& ids) {
    constexpr size_t kEmbeddingWidth = 3;
    std::vector<float> embeddings;
    embeddings.reserve(ids.size() * kEmbeddingWidth);
    for (const int32_t id : ids) {
        for (size_t column = 0; column < kEmbeddingWidth; ++column) {
            const float token_component =
                    static_cast<float>((id % 17) - 8) * 0.125f;
            const float column_component =
                    (static_cast<float>(column) - 1.0f) * 0.05f;
            embeddings.push_back(token_component + column_component);
        }
    }
    return embeddings;
}

std::vector<float> conditioning_signal(
        const std::vector<float>& pair_embeddings,
        size_t tokens_per_prompt) {
    constexpr size_t kEmbeddingWidth = 3;
    assert(pair_embeddings.size() == tokens_per_prompt * 2 * kEmbeddingWidth);
    std::vector<float> signal(kEmbeddingWidth, 0.0f);
    for (size_t token = 0; token < tokens_per_prompt; ++token) {
        for (size_t column = 0; column < kEmbeddingWidth; ++column) {
            const float negative = pair_embeddings[token * kEmbeddingWidth + column];
            const float positive = pair_embeddings[
                    (tokens_per_prompt + token) * kEmbeddingWidth + column];
            signal[column] += positive - negative;
        }
    }
    for (float& value : signal) value /= static_cast<float>(tokens_per_prompt);
    return signal;
}

#if MCA_UPDATE_IMAGE_QUALITY_GOLDEN
void print_vector(const char* name, const std::vector<float>& values) {
    std::printf("%s = {", name);
    for (size_t index = 0; index < values.size(); ++index) {
        std::printf("%.9gf%s", values[index], index + 1 == values.size() ? "" : ", ");
    }
    std::printf("}\n");
}
#endif

void test_conditioning_scheduler_and_vae_golden() {
    ClipTokenizerConfig config;
    config.bos_id = 101;
    config.eos_id = 102;
    config.pad_id = 0;
    config.max_length = 8;
    config.enable_prompt_weighting = true;

    ClipTokenPair pair;
    std::string error;
    assert(mca::image::tokenize_weighted_clip_sequence_with_encoder(
            "[de]", config, encode_each_byte, &pair.negative, &error));
    assert(mca::image::tokenize_weighted_clip_sequence_with_encoder(
            "a (bc:1.5)", config, encode_each_byte, &pair.positive, &error));
#if MCA_UPDATE_IMAGE_QUALITY_GOLDEN
    std::fprintf(stderr, "negative_ids = {");
    for (const int32_t id : pair.negative.ids) std::fprintf(stderr, "%d,", id);
    std::fprintf(stderr, "}\npositive_ids = {");
    for (const int32_t id : pair.positive.ids) std::fprintf(stderr, "%d,", id);
    std::fprintf(stderr, "}\nweighted_counts = {%zu,%zu}\n",
            pair.negative.weighted_token_count,
            pair.positive.weighted_token_count);
#else
    assert(pair.negative.ids == std::vector<int32_t>({101, 'd', 'e', 102, 0, 0, 0, 0}));
    assert(pair.positive.ids == std::vector<int32_t>({101, 'a', ' ', 'b', 'c', 102, 0, 0}));
#endif
#if !MCA_UPDATE_IMAGE_QUALITY_GOLDEN
    assert(pair.negative.weighted_token_count == 2);
    assert(pair.positive.weighted_token_count == 2);
#endif

    const std::vector<int32_t> pair_ids = pair.negative_then_positive();
    const std::vector<float> pair_weights = pair.negative_then_positive_weights();
    const std::vector<float> embeddings = synthetic_embeddings(pair_ids);
    std::vector<float> weighted_embeddings;
    ClipEmbeddingWeightStats stats;
    assert(mca::image::apply_clip_token_weights_to_embeddings(
            embeddings,
            pair_ids.size(),
            3,
            pair_weights,
            true,
            &weighted_embeddings,
            &stats,
            &error));
    assert(stats.weighting_applied);
    assert(stats.weighted_token_count == 4);
    const std::vector<float> conditioning =
            conditioning_signal(weighted_embeddings, pair.negative.ids.size());

    DiffusionScheduler scheduler(DiffusionSchedulerConfig::stable_diffusion_euler());
    assert(scheduler.set_timesteps(2, &error));
    SchedulerTensor latent = {0.25f, -0.5f, 0.75f, -1.0f};
    for (float& value : latent) {
        value *= static_cast<float>(scheduler.init_noise_sigma());
    }
    std::vector<float> latent_after_step_one;
    for (size_t schedule_index = 0;
         schedule_index < scheduler.timesteps().size();
         ++schedule_index) {
        SchedulerTensor scaled;
        assert(scheduler.scale_model_input(latent, schedule_index, &scaled, &error));
        SchedulerTensor model_output(latent.size());
        for (size_t index = 0; index < model_output.size(); ++index) {
            model_output[index] = conditioning[index % conditioning.size()] * 0.2f +
                    scaled[index] * 0.1f +
                    static_cast<float>(schedule_index + 1) * 0.01f;
        }
        SchedulerStepResult step;
        assert(scheduler.step(model_output, schedule_index, latent, &step, &error));
        latent = std::move(step.previous_sample);
        if (schedule_index == 0) latent_after_step_one = latent;
    }
    assert(scheduler.completed_step_count() == 2);

    std::vector<float> vae_latent = latent;
    for (float& value : vae_latent) value /= kVaeScalingFactor;

    std::vector<uint8_t> fingerprint_payload;
    append_i32_values(pair_ids, &fingerprint_payload);
    append_float_values(pair_weights, &fingerprint_payload);
    append_float_values(weighted_embeddings, &fingerprint_payload);
    append_float_values(conditioning, &fingerprint_payload);
    append_float_values(latent_after_step_one, &fingerprint_payload);
    append_float_values(latent, &fingerprint_payload);
    append_float_values(vae_latent, &fingerprint_payload);
    const std::string pipeline_fingerprint = fnv1a64_hex(fingerprint_payload);

#if MCA_UPDATE_IMAGE_QUALITY_GOLDEN
    std::printf("pair_fingerprint = %s\n", pair.weighting_fingerprint().c_str());
    std::printf("normalization_scale = %.9gf\n", stats.normalization_scale);
    print_vector("conditioning", conditioning);
    print_vector("latent_step_one", latent_after_step_one);
    print_vector("latent_step_two", latent);
    print_vector("vae_host_scaled", vae_latent);
    std::printf("pipeline_fingerprint = %s\n", pipeline_fingerprint.c_str());
#else
    assert(pair.weighting_fingerprint() == kPromptWeightPairFingerprint);
    assert(std::abs(stats.normalization_scale - kEmbeddingNormalizationScale) <= kTolerance);
    assert_near(conditioning, kConditioningSignal);
    assert_near(latent_after_step_one, kLatentAfterStepOne);
    assert_near(latent, kLatentAfterStepTwo);
    assert_near(vae_latent, kVaeHostScaledLatent);
    assert(pipeline_fingerprint == kPipelineFingerprint);
#endif
}

}  // namespace

int main() {
    test_conditioning_scheduler_and_vae_golden();
    return 0;
}
