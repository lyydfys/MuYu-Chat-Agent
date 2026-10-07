#ifndef MCA_QNN_CAPABILITY_PROBE_HPP
#define MCA_QNN_CAPABILITY_PROBE_HPP

#include <string>
#include <vector>
#include <cstdint>

namespace mca {
namespace qnn {

/**
 * Hardware capability probe and advisory recommender for QNN acceleration.
 *
 * INVARIANT: Universal Device Admission:
 * - A missing or unknown device profile is strictly advisory.
 * - Unknown or untested SoCs always fall back to the generic compatible path.
 * - Hardware probe MUST NEVER block or reject user download, import, load, or run actions.
 */
class QnnCapabilityProbe {
public:
    struct CapabilityReport {
        std::string soc_hint;
        uint32_t soc_model{0};
        int htp_arch_version{0};
        bool is_generic_fallback{false};
        bool is_admitted{true}; // Hard rule: always true
        std::string recommended_transport{"htp"};
        std::string recommended_precision{"quantized_int8"};
        int32_t recommended_batch_size{1};
        std::vector<std::string> search_directories;
        std::string adsp_library_path;
        bool system_present{false};
        bool htp_present{false};
        bool skel_present{false};
        bool rpc_present{false};
        std::string system_path;
        std::string htp_path;
        std::string skel_path;
        std::string rpc_path;
        std::string diagnostic_message;
    };

    /**
     * Probes device capabilities given SoC hint, model number, and search directories.
     */
    static CapabilityReport probe(
        const std::string& soc_hint = "",
        uint32_t soc_model = 0,
        const std::vector<std::string>& search_dirs = {}
    );

    /**
     * Resolves HTP architecture version.
     * Unknown SoCs fall back to generic architecture and mark out_is_generic_fallback = true.
     */
    static int resolve_htp_arch(
        const std::string& soc_hint,
        uint32_t soc_model,
        bool* out_is_generic_fallback = nullptr
    );

    /**
     * Gathers standard default search paths for QNN shared libraries.
     */
    static std::vector<std::string> get_default_search_directories();

    /**
     * Builds prioritized colon-separated ADSP_LIBRARY_PATH.
     */
    static std::string build_adsp_library_path(const std::vector<std::string>& search_dirs);
};

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_CAPABILITY_PROBE_HPP
