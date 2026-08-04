#ifndef MCA_MNN_DIFFUSION_TOKENIZER_HPP
#define MCA_MNN_DIFFUSION_TOKENIZER_HPP

#include <memory>
#include <string>
#include <vector>

#include <tokenizer.hpp>

#if defined(MNN_DIFFUSION_WITH_LLM_TOKENIZER)
#include <tokenizer/tokenizer.hpp>
#endif

namespace MNN {
namespace DIFFUSION {

/**
 * Measurement-only adapter for the official MNN MtokTokenizer kPair
 * conditional branch. It intentionally does not replace the production
 * MtokTokenizer class: generation continues to use MNN's shipped type while
 * this helper exposes only the otherwise-private, untruncated conditional
 * sequence needed for an honest UI token count.
 */
class McaMtokPromptMeasurementTokenizer final {
public:
    McaMtokPromptMeasurementTokenizer(int bosId, int eosId);
    ~McaMtokPromptMeasurementTokenizer();

    McaMtokPromptMeasurementTokenizer(const McaMtokPromptMeasurementTokenizer&) = delete;
    McaMtokPromptMeasurementTokenizer& operator=(
            const McaMtokPromptMeasurementTokenizer&) = delete;

    bool load(const std::string& filePath);

    /**
     * Returns the untruncated conditional branch exactly as the kPair runtime
     * builds it. This is deliberately unavailable for legacy vocabulary
     * fallbacks: their public pair adapter does not expose a stable conditional
     * branch boundary, so a UI count must remain unavailable rather than guess.
     */
    bool encodeConditionalPromptForMeasurement(const std::string& sentence,
                                               std::vector<int>* tokenIds) const;

private:
    int mBosId;
    int mEosId;
#if defined(MNN_DIFFUSION_WITH_LLM_TOKENIZER)
    MNN::Transformer::Tokenizer* mTokenizer = nullptr;
#endif
};

} // namespace DIFFUSION
} // namespace MNN

#endif // MCA_MNN_DIFFUSION_TOKENIZER_HPP
