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

class MtokTokenizer final : public Tokenizer {
public:
    enum class Style {
        kSingle,
        kPair,
    };

    MtokTokenizer(Style style, int bosId, int eosId);
    ~MtokTokenizer() override;

    bool load(const std::string& filePath) override;
    std::vector<int> encode(const std::string& sentence, int maxlen = 0) override;

private:
    std::vector<int> encodeSingle(const std::string& sentence, int maxlen) const;

    Style mStyle;
    int mBosId;
    int mEosId;
    std::unique_ptr<Tokenizer> mDiffusionTokenizer;
#if defined(MNN_DIFFUSION_WITH_LLM_TOKENIZER)
    MNN::Transformer::Tokenizer* mTokenizer = nullptr;
#endif
};

} // namespace DIFFUSION
} // namespace MNN

#endif // MCA_MNN_DIFFUSION_TOKENIZER_HPP
