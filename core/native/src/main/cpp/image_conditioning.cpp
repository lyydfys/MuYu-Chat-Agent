#include "image_conditioning.hpp"

#include <algorithm>
#include <exception>
#include <fstream>
#include <memory>
#include <sstream>

#if MCA_WITH_TOKENIZERS_CPP
#include <tokenizers_cpp.h>
#endif

namespace mca::image {
namespace {

constexpr std::streamoff kMaxTokenizerJsonBytes = 64LL * 1024LL * 1024LL;

bool read_bounded_file(
        const std::string& path,
        std::string* contents,
        std::string* error) {
    if (contents == nullptr || error == nullptr) return false;
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input.good()) {
        *error = "Tokenizer JSON is missing or unreadable: " + path;
        return false;
    }
    const std::streamoff size = input.tellg();
    if (size <= 0 || size > kMaxTokenizerJsonBytes) {
        std::ostringstream message;
        message << "Tokenizer JSON size is invalid: " << size << " bytes.";
        *error = message.str();
        return false;
    }
    input.seekg(0, std::ios::beg);
    contents->assign(static_cast<size_t>(size), '\0');
    input.read(contents->data(), size);
    if (!input.good()) {
        contents->clear();
        *error = "Tokenizer JSON could not be read completely: " + path;
        return false;
    }
    return true;
}

bool validate_config(const ClipTokenizerConfig& config, std::string* error) {
    if (error == nullptr) return false;
    if (config.max_length < 2 || config.max_length > 4096) {
        *error = "CLIP tokenizer max_length must be between 2 and 4096.";
        return false;
    }
    if (config.bos_id < 0 || config.eos_id < 0 || config.pad_id < 0) {
        *error = "CLIP tokenizer BOS, EOS and PAD ids must be non-negative.";
        return false;
    }
    return true;
}

#if MCA_WITH_TOKENIZERS_CPP
bool encode_clip_sequence(
        tokenizers::Tokenizer* tokenizer,
        const std::string& text,
        const ClipTokenizerConfig& config,
        ClipTokenSequence* output,
        std::string* error) {
    if (tokenizer == nullptr || output == nullptr || error == nullptr) return false;
    std::vector<int32_t> raw;
    try {
        // Encode() deliberately disables tokenizer post-processor special
        // tokens. The profile contract below owns BOS/EOS/PAD explicitly.
        raw = tokenizer->Encode(text);
    } catch (const std::exception& exception) {
        *error = std::string("Tokenizer encode failed: ") + exception.what();
        return false;
    } catch (...) {
        *error = "Tokenizer encode failed with an unknown native exception.";
        return false;
    }
    if (std::any_of(raw.begin(), raw.end(), [](int32_t id) { return id < 0; })) {
        *error = "Tokenizer returned a negative token id.";
        return false;
    }

    output->untruncated_token_count = raw.size() +
            (config.add_bos ? 1U : 0U) +
            (config.add_eos ? 1U : 0U);
    output->ids.clear();
    output->ids.reserve(static_cast<size_t>(config.max_length));
    if (config.add_bos) output->ids.push_back(config.bos_id);
    output->ids.insert(output->ids.end(), raw.begin(), raw.end());
    if (config.add_eos) output->ids.push_back(config.eos_id);

    output->truncated = output->ids.size() > static_cast<size_t>(config.max_length);
    if (output->truncated) {
        output->ids.resize(static_cast<size_t>(config.max_length));
        if (config.add_eos) output->ids.back() = config.eos_id;
    }
    output->ids.resize(static_cast<size_t>(config.max_length), config.pad_id);
    return true;
}
#endif

}  // namespace

std::vector<int32_t> ClipTokenPair::negative_then_positive() const {
    std::vector<int32_t> combined;
    combined.reserve(negative.ids.size() + positive.ids.size());
    combined.insert(combined.end(), negative.ids.begin(), negative.ids.end());
    combined.insert(combined.end(), positive.ids.begin(), positive.ids.end());
    return combined;
}

bool tokenize_clip_pair_from_json(
        const std::string& tokenizer_json_path,
        const std::string& positive_prompt,
        const std::string& negative_prompt,
        const ClipTokenizerConfig& config,
        ClipTokenPair* output,
        std::string* error) {
    if (output == nullptr || error == nullptr) return false;
    output->negative = ClipTokenSequence{};
    output->positive = ClipTokenSequence{};
    error->clear();
    if (!validate_config(config, error)) return false;

#if MCA_WITH_TOKENIZERS_CPP
    std::string tokenizer_json;
    if (!read_bounded_file(tokenizer_json_path, &tokenizer_json, error)) return false;
    std::unique_ptr<tokenizers::Tokenizer> tokenizer;
    try {
        tokenizer = tokenizers::Tokenizer::FromBlobJSON(tokenizer_json);
    } catch (const std::exception& exception) {
        *error = std::string("Tokenizer JSON parse failed: ") + exception.what();
        return false;
    } catch (...) {
        *error = "Tokenizer JSON parse failed with an unknown native exception.";
        return false;
    }
    if (!tokenizer) {
        *error = "Tokenizer JSON did not create a tokenizer instance.";
        return false;
    }
    return encode_clip_sequence(
                   tokenizer.get(), negative_prompt, config, &output->negative, error) &&
            encode_clip_sequence(
                   tokenizer.get(), positive_prompt, config, &output->positive, error);
#else
    (void)tokenizer_json_path;
    (void)positive_prompt;
    (void)negative_prompt;
    *error = "The standard tokenizer backend is not packaged in this build.";
    return false;
#endif
}

}  // namespace mca::image
