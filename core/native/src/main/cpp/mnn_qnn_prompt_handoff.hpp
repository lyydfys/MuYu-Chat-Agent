#pragma once

#include <cstdint>
#include <string>

#if defined(_WIN32)
#if defined(MCA_PROMPT_HANDOFF_BUILDING)
#define MCA_PROMPT_HANDOFF_API __declspec(dllexport)
#else
#define MCA_PROMPT_HANDOFF_API __declspec(dllimport)
#endif
#elif defined(__GNUC__) || defined(__clang__)
#define MCA_PROMPT_HANDOFF_API __attribute__((visibility("default")))
#else
#define MCA_PROMPT_HANDOFF_API
#endif

namespace mca::image::prompt_handoff {

struct Record {
    std::string tokenizer_canonical_path;
    uint64_t tokenizer_device = 0U;
    uint64_t tokenizer_inode = 0U;
    uint64_t tokenizer_size_bytes = 0U;
    std::string tokenizer_sha256;
    std::string prompt_pair_sha256;
    std::string payload_sha256;
    std::string prompt_to_encoder_closure_sha256;
};

MCA_PROMPT_HANDOFF_API bool issue(
    const Record& record,
    std::string& handle,
    std::string& error);

MCA_PROMPT_HANDOFF_API bool consume(
    const std::string& handle,
    const Record& observed_record,
    Record& consumed_record,
    std::string& error);

}  // namespace mca::image::prompt_handoff
