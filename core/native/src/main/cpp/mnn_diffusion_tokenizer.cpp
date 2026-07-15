#include <mnn_diffusion_tokenizer.hpp>

#include <algorithm>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

#include "core/Macro.h"

#if defined(MNN_DIFFUSION_WITH_LLM_TOKENIZER)
#include "tokenizer/tokenizer.hpp"
#endif

namespace MNN {
namespace DIFFUSION {

static std::vector<std::string> BuildTokenizerPaths(const std::string& filePath) {
    return {
        filePath + "/tokenizer.mtok",
        filePath + "/tokenizer.txt"
    };
}

static bool FileExists(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    return input.good();
}

MtokTokenizer::MtokTokenizer(Style style, int bosId, int eosId) : mStyle(style), mBosId(bosId), mEosId(eosId) {}

MtokTokenizer::~MtokTokenizer() {
#if defined(MNN_DIFFUSION_WITH_LLM_TOKENIZER)
    delete mTokenizer;
    mTokenizer = nullptr;
#endif
}

bool MtokTokenizer::load(const std::string& filePath) {
    mDiffusionTokenizer.reset();
#if defined(MNN_DIFFUSION_WITH_LLM_TOKENIZER)
    delete mTokenizer;
    mTokenizer = nullptr;
    for (const auto& tokenizerPath : BuildTokenizerPaths(filePath)) {
        mTokenizer = MNN::Transformer::Tokenizer::createTokenizer(tokenizerPath);
        if (mTokenizer != nullptr) {
            return true;
        }
    }
#endif

    // Official MNN SD1.5 bundles use the Hugging Face CLIP vocabulary directly.
    // Keep mtok support for converted bundles, then fall back to the diffusion tokenizer.
    if (mStyle == Style::kPair && mBosId == 49406 && mEosId == 49407 &&
        FileExists(filePath + "/vocab.json") && FileExists(filePath + "/merges.txt")) {
        auto tokenizer = std::make_unique<CLIPTokenizer>();
        if (tokenizer->load(filePath)) {
            mDiffusionTokenizer = std::move(tokenizer);
            return true;
        }
    }
    if (mStyle == Style::kPair && mBosId == 101 && mEosId == 102 &&
        FileExists(filePath + "/vocab.txt")) {
        auto tokenizer = std::make_unique<BertTokenizer>();
        if (tokenizer->load(filePath)) {
            mDiffusionTokenizer = std::move(tokenizer);
            return true;
        }
    }

    MNN_ERROR("Failed to load tokenizer from %s (expected tokenizer.mtok/tokenizer.txt or diffusion vocabulary files)\n",
              filePath.c_str());
    return false;
}

std::vector<int> MtokTokenizer::encodeSingle(const std::string& sentence, int maxlen) const {
#if defined(MNN_DIFFUSION_WITH_LLM_TOKENIZER)
    if (mTokenizer == nullptr) {
        return {};
    }
    std::vector<int> ids = mTokenizer->encode(sentence);
    if (mBosId >= 0 && (ids.empty() || ids.front() != mBosId)) {
        ids.insert(ids.begin(), mBosId);
    }
    if (mEosId >= 0 && (ids.empty() || ids.back() != mEosId)) {
        ids.push_back(mEosId);
    }
    if (maxlen > 0) {
        if ((int)ids.size() > maxlen) {
            ids.resize(maxlen);
            if (mEosId >= 0) {
                ids[maxlen - 1] = mEosId;
            }
        } else {
            while ((int)ids.size() < maxlen) {
                ids.push_back(0);
            }
        }
    }
    return ids;
#else
    (void)sentence;
    (void)maxlen;
    return {};
#endif
}

std::vector<int> MtokTokenizer::encode(const std::string& sentence, int maxlen) {
    if (mDiffusionTokenizer) {
        return mDiffusionTokenizer->encode(sentence, maxlen);
    }
#if defined(MNN_DIFFUSION_WITH_LLM_TOKENIZER)
    if (mStyle == Style::kPair) {
        auto uncond = encodeSingle("", maxlen);
        auto cond = encodeSingle(sentence, maxlen);
        std::vector<int> ids;
        if (maxlen <= 0) {
            if (uncond.size() > ids.max_size() - cond.size()) {
                MNN_ERROR("Tokenizer pair output is too large to concatenate.\n");
                return {};
            }
            ids.reserve(uncond.size() + cond.size());
            ids.insert(ids.end(), uncond.begin(), uncond.end());
            ids.insert(ids.end(), cond.begin(), cond.end());
            return ids;
        }

        const auto tokenCount = static_cast<size_t>(maxlen);
        if (tokenCount > ids.max_size() / 2) {
            MNN_ERROR("Tokenizer max length is too large for pair encoding.\n");
            return {};
        }
        ids.assign(tokenCount * 2, 0);
        const auto uncondCount = std::min(tokenCount, uncond.size());
        const auto condCount = std::min(tokenCount, cond.size());
        std::copy_n(uncond.begin(), uncondCount, ids.begin());
        std::copy_n(cond.begin(), condCount, ids.begin() + tokenCount);
        return ids;
    }
    return encodeSingle(sentence, maxlen);
#else
    (void)sentence;
    (void)maxlen;
    return {};
#endif
}

} // namespace DIFFUSION
} // namespace MNN
