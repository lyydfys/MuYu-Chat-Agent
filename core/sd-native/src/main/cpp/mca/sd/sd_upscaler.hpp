#ifndef MCA_SD_UPSCALER_HPP
#define MCA_SD_UPSCALER_HPP

#include <atomic>
#include <cstdint>
#include <string>

namespace mca {
namespace sd {

inline constexpr uint64_t kMaxUpscalerBytes = 2ull * 1024ull * 1024ull * 1024ull;
inline constexpr uint32_t kMaxUpscaleProductOutputSide = 4096u;
inline constexpr uint64_t kMaxUpscaleProductOutputPixels = 16000000ull;
// A fixed-scale model may produce a larger intermediate before Android publishes
// the requested 2x/3x/4x result. Keep that intermediate bounded independently.
inline constexpr uint32_t kMaxUpscaleNativeOutputSide = 8192u;
inline constexpr uint64_t kMaxUpscaleNativeOutputPixels = 64000000ull;

/**
 * Super-resolution upscaler orchestrator managing ESRGAN model verification,
 * tiling inference, compute evidence recording, and image writeback.
 */
class SdUpscaler {
public:
    static std::string execute(
            const std::string &raw_upscaler_path,
            const std::string &raw_upscaler_root,
            const std::string &raw_input_path,
            const std::string &raw_params,
            const std::string &raw_output_path,
            std::atomic<uint64_t> *sequence_counter = nullptr);
};

/**
 * Executes ESRGAN super-resolution upscaling via stable-diffusion.cpp native runtime.
 */
std::string execute_sd_upscale(
        const std::string &raw_upscaler_path,
        const std::string &raw_upscaler_root,
        const std::string &raw_input_path,
        const std::string &raw_params,
        const std::string &raw_output_path,
        std::atomic<uint64_t> *sequence_counter = nullptr);

} // namespace sd
} // namespace mca

#endif // MCA_SD_UPSCALER_HPP
