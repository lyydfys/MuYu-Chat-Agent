#include "mnn_diffusion_tokenizer.hpp"

#include <string>
#include <vector>

#if defined(MNN_DIFFUSION_WITH_LLM_TOKENIZER)
#include "tokenizer/tokenizer.hpp"
#endif

namespace MNN {
namespace DIFFUSION {

McaMtokPromptMeasurementTokenizer::McaMtokPromptMeasurementTokenizer(
        int bosId,
        int eosId)
        : mBosId(bosId), mEosId(eosId) {}

McaMtokPromptMeasurementTokenizer::~McaMtokPromptMeasurementTokenizer() {
#if defined(MNN_DIFFUSION_WITH_LLM_TOKENIZER)
    delete mTokenizer;
    mTokenizer = nullptr;
#endif
}

bool McaMtokPromptMeasurementTokenizer::load(const std::string& filePath) {
#if defined(MNN_DIFFUSION_WITH_LLM_TOKENIZER)
    delete mTokenizer;
    mTokenizer = MNN::Transformer::Tokenizer::createTokenizer(filePath + "/tokenizer.mtok");
    return mTokenizer != nullptr;
#else
    (void)filePath;
    return false;
#endif
}

bool McaMtokPromptMeasurementTokenizer::encodeConditionalPromptForMeasurement(
        const std::string& sentence,
        std::vector<int>* tokenIds) const {
    if (tokenIds == nullptr) return false;
    tokenIds->clear();
#if defined(MNN_DIFFUSION_WITH_LLM_TOKENIZER)
    // This exactly matches the official MtokTokenizer::encodeSingle(sentence, 0)
    // branch before kPair concatenates its unconditional sequence. Measurement
    // intentionally permits no vocabulary fallback, so unavailable means unknown.
    if (mTokenizer == nullptr) return false;
    *tokenIds = mTokenizer->encode(sentence);
    if (mBosId >= 0 && (tokenIds->empty() || tokenIds->front() != mBosId)) {
        tokenIds->insert(tokenIds->begin(), mBosId);
    }
    if (mEosId >= 0 && (tokenIds->empty() || tokenIds->back() != mEosId)) {
        tokenIds->push_back(mEosId);
    }
    return !tokenIds->empty();
#else
    (void)sentence;
    return false;
#endif
}

} // namespace DIFFUSION
} // namespace MNN
