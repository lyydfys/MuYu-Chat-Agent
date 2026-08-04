#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "image_conditioning.hpp"
#include "nlohmann/json.hpp"

namespace mca::image::textual_inversion {

struct Artifact {
    size_t index = 0;
    std::string id;
    std::string name;
    std::string trigger;
    std::string path;
    std::string sha256;
    uint64_t size_bytes = 0;
    std::string format;
    std::string model_fingerprint;
    std::string tokenizer_fingerprint;
    std::string profile_id;
    int profile_revision = 0;
    std::string runtime;
    std::string binding_fingerprint;
    std::vector<float> clip_l;
    std::vector<float> clip_g;
};

struct Selection {
    std::vector<Artifact> artifacts;
    std::string binding_fingerprint;
    std::string native_mode;
    bool clip_g_required = false;
};

struct Audit {
    size_t requested_count = 0;
    size_t validated_count = 0;
    size_t load_attempt_count = 0;
    size_t loaded_count = 0;
    size_t applied_vector_count = 0;
    uint64_t requested_mask = 0;
    uint64_t loaded_mask = 0;
    uint64_t clip_l_match_mask = 0;
    uint64_t clip_g_match_mask = 0;
    uint64_t clip_l_mask = 0;
    uint64_t clip_g_mask = 0;
};

bool load_selection(const std::string& selection_json,
                    const std::string& expected_runtime,
                    bool require_clip_g,
                    Selection* selection,
                    Audit* audit,
                    std::string* error);

std::vector<ClipTextualInversionEmbedding> clip_embeddings(const Selection& selection,
                                                           bool clip_g);

void record_conditioned_pair(const ClipConditionedPair& pair,
                             bool clip_g,
                             Audit* audit);

nlohmann::json artifacts_json(const Selection& selection);

nlohmann::json evidence_json(const Selection& selection,
                             const Audit& audit,
                             bool conditioning_consumed);

}  // namespace mca::image::textual_inversion
