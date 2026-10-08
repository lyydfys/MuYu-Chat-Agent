#ifndef MCA_QNN_CAPABILITY_PROBE_HPP
#define MCA_QNN_CAPABILITY_PROBE_HPP

#include <string>
#include <vector>
#include <cstdint>
#include <atomic>

namespace mca {
namespace qnn {

/**
 * Runtime probe inspection result across host and DSP directories.
 */
struct RuntimeProbe {
    bool system_present = false;
    bool htp_present = false;
    bool skel_present = false;
    bool stub_present = false;
    bool rpc_present = false;
    bool rpc_loadable = false;
    bool loadable = false;
    bool qnn_interface_present = false;
    bool qnn_system_interface_present = false;
    bool exact_role_binding = false;
    std::string system_path;
    std::string htp_path;
    std::string skel_path;
    std::string stub_path;
    std::string rpc_path;
    std::string rpc_message;
    std::string adsp_library_path;
    std::string host_runtime_directory;
    std::string runtime_directory;
    std::string dsp_runtime_directory;
    int htp_arch_version = 0;
    std::vector<std::string> search_directories;
    std::string message;
};

int htp_library_version(const std::string& name);
int physical_device_htp_arch_version();
std::string htp_library_for_arch_in_dir(const std::string& dir, int arch_version, const std::string& suffix);
bool is_platform_qnn_runtime_directory(const std::string& directory);
bool may_pair_qnn_runtime_directories(const std::string& host_directory, const std::string& dsp_directory);
std::string best_htp_library_in_dir(const std::string& dir, const std::string& suffix);
void append_unique_path(std::vector<std::string>* values, const std::string& value);
std::string join_adsp_paths(const std::vector<std::string>& values);
std::vector<std::string> prioritize_adsp_paths(const std::vector<std::string>& values);
void append_common_android_adsp_paths(std::vector<std::string>* values);

RuntimeProbe inspect_runtime_internal(
    const std::vector<std::string>& dirs,
    bool probe,
    bool probe_htp = true,
    std::atomic<bool>* poisoned_flag = nullptr
);

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

    static CapabilityReport probe(
        const std::string& soc_hint = "",
        uint32_t soc_model = 0,
        const std::vector<std::string>& search_dirs = {}
    );

    static int resolve_htp_arch(
        const std::string& soc_hint,
        uint32_t soc_model,
        bool* out_is_generic_fallback = nullptr
    );

    static std::vector<std::string> get_default_search_directories();
    static std::string build_adsp_library_path(const std::vector<std::string>& search_dirs);
};

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_CAPABILITY_PROBE_HPP
