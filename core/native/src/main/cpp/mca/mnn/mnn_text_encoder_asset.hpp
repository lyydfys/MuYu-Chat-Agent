#ifndef MCA_MNN_TEXT_ENCODER_ASSET_HPP
#define MCA_MNN_TEXT_ENCODER_ASSET_HPP

#include <cstdint>
#include <string>

namespace mca {
namespace mnn {

// E08c include-split: pure value type extracted from mnn_protocol_parser.hpp so the diffusion asset
// contract header stays free of the POSIX/Android includes that mnn_protocol_parser.hpp pulls.
// Struct body is byte-identical to the previous definition (sha256 bea03e6221959d6830a27323d82d5c505116f7a363abcf9bfef8a3b9249096cc).

struct MnnTextEncoderAssetRequest {
    bool declared = false;
    std::string path;
    std::string sha256;
    uint64_t size_bytes = 0U;
    bool has_weight = false;
    std::string weight_path;
    std::string weight_sha256;
    uint64_t weight_size_bytes = 0U;
};
}  // namespace mnn
}  // namespace mca

#endif  // MCA_MNN_TEXT_ENCODER_ASSET_HPP
