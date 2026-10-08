#ifndef MCA_MNN_CLIP_TOKENIZER_HPP
#define MCA_MNN_CLIP_TOKENIZER_HPP

#include <string>
#include <vector>
#include <unordered_map>

namespace mca {
namespace mnn {

void append_utf8_codepoint(std::string& out, int codepoint);
std::vector<std::string> build_clip_byte_encoder();
std::vector<std::string> clip_pretokenize(std::string text);

struct ClipBpeTokenizer {
    std::unordered_map<std::string, int> vocab;
    std::unordered_map<std::string, int> ranks;
    std::vector<std::string> byte_encoder;
    int bos_id = 49406;
    int eos_id = 49407;
    std::string end_suffix = "</w>";

    static std::string pair_key(const std::string& a, const std::string& b);
    bool load(const std::string& path, std::string& error);
    std::vector<int> encode_single(const std::string& text, int max_len) const;
    std::vector<int> encode_pair(const std::string& text, int max_len) const;
};

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_CLIP_TOKENIZER_HPP
