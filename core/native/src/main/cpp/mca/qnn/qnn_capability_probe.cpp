#include "qnn_capability_probe.hpp"
#include "qnn_soc_htp_mapping.hpp"
#include "mca/common/logging.hpp"
#include "mca/common/safe_path.hpp"
#include <algorithm>
#include <sstream>

namespace mca {
namespace qnn {

namespace {
constexpr const char* kTag = "QnnCapabilityProbe";
constexpr int kGenericFallbackHtpArch = 68; // Baseline compatible HTP architecture
}

int QnnCapabilityProbe::resolve_htp_arch(
    const std::string& soc_hint,
    uint32_t soc_model,
    bool* out_is_generic_fallback
) {
    int arch = 0;
    if (soc_model > 0) {
        arch = htp_arch_version_for_soc_model(soc_model);
    }
    if (arch == 0 && !soc_hint.empty()) {
        arch = htp_arch_version_for_soc_hint(soc_hint);
    }

    if (arch > 0) {
        if (out_is_generic_fallback) *out_is_generic_fallback = false;
        MCA_LOGI(kTag, "Resolved specific HTP arch V%d for SoC hint='%s' model=%u",
                 arch, soc_hint.c_str(), soc_model);
        return arch;
    }

    // Hard Invariant: Universal Device Admission.
    // Unknown or untested devices are NEVER rejected; fallback to generic compatible path.
    if (out_is_generic_fallback) *out_is_generic_fallback = true;
    MCA_LOGI(kTag, "Unrecognized SoC hint='%s' model=%u; falling back to generic compatible HTP V%d path",
             soc_hint.c_str(), soc_model, kGenericFallbackHtpArch);
    return kGenericFallbackHtpArch;
}

std::vector<std::string> QnnCapabilityProbe::get_default_search_directories() {
    return {
        "/data/local/tmp/qnn",
        "/system/lib64",
        "/vendor/lib64",
        "/system/lib/rfsa/adsp",
        "/system/vendor/lib/rfsa/adsp",
        "/vendor/lib/rfsa/adsp",
        "/vendor/dsp",
        "/vendor/dsp/cdsp",
        "/dsp"
    };
}

std::string QnnCapabilityProbe::build_adsp_library_path(const std::vector<std::string>& search_dirs) {
    std::ostringstream oss;
    bool first = true;
    for (const auto& dir : search_dirs) {
        if (dir.empty()) continue;
        if (!first) oss << ":";
        oss << dir;
        first = false;
    }
    return oss.str();
}

QnnCapabilityProbe::CapabilityReport QnnCapabilityProbe::probe(
    const std::string& soc_hint,
    uint32_t soc_model,
    const std::vector<std::string>& search_dirs
) {
    CapabilityReport report;
    report.soc_hint = soc_hint;
    report.soc_model = soc_model;
    report.is_admitted = true; // Universal admission invariant: always admitted!

    bool is_fallback = false;
    report.htp_arch_version = resolve_htp_arch(soc_hint, soc_model, &is_fallback);
    report.is_generic_fallback = is_fallback;

    if (report.is_generic_fallback) {
        report.recommended_transport = "generic_htp";
        report.recommended_precision = "quantized_int8";
    } else {
        report.recommended_transport = "htp";
        report.recommended_precision = (report.htp_arch_version >= 75) ? "fp16" : "quantized_int8";
    }
    report.recommended_batch_size = 1;

    std::vector<std::string> all_dirs = search_dirs;
    if (all_dirs.empty()) {
        all_dirs = get_default_search_directories();
    }
    report.search_directories = all_dirs;
    report.adsp_library_path = build_adsp_library_path(all_dirs);

    // Inspect files across candidates
    for (const auto& dir : all_dirs) {
        const std::string system_candidate = common::SafePath::safe_join(dir, "libQnnSystem.so");
        const std::string htp_candidate = common::SafePath::safe_join(dir, "libQnnHtp.so");
        const std::string rpc_candidate = common::SafePath::safe_join(dir, "libcdsprpc.so");

        if (!report.system_present && common::SafePath::file_exists(system_candidate)) {
            report.system_present = true;
            report.system_path = system_candidate;
        }
        if (!report.htp_present && common::SafePath::file_exists(htp_candidate)) {
            report.htp_present = true;
            report.htp_path = htp_candidate;
        }
        if (!report.rpc_present && common::SafePath::file_exists(rpc_candidate)) {
            report.rpc_present = true;
            report.rpc_path = rpc_candidate;
        }

        // Check for skel libraries matching arch
        const std::string skel_candidate = common::SafePath::safe_join(
            dir, "libQnnHtpV" + std::to_string(report.htp_arch_version) + "Skel.so");
        if (!report.skel_present && common::SafePath::file_exists(skel_candidate)) {
            report.skel_present = true;
            report.skel_path = skel_candidate;
        }
    }

    if (report.is_generic_fallback) {
        report.diagnostic_message = "Advisory: Generic compatible QNN path selected; hardware detection does not block execution.";
    } else {
        report.diagnostic_message = "Specific QNN HTP profile matched (V" + std::to_string(report.htp_arch_version) + ").";
    }

    MCA_LOGI(kTag, "Probe result: arch=V%d, generic_fallback=%d, admitted=%d",
             report.htp_arch_version,
             report.is_generic_fallback ? 1 : 0,
             report.is_admitted ? 1 : 0);

    return report;
}

} // namespace qnn
} // namespace mca
