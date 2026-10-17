#pragma once

#include <algorithm>
#include <vector>

namespace mca::qnn {

// Hardware and context metadata rank transports. Availability and actual QNN
// loading determine whether a transport can execute the unchanged binary.
inline std::vector<int> qnn_htp_runtime_candidates(
        int context_arch,
        int device_arch,
        int preferred_arch,
        int probed_arch,
        std::vector<int> packaged_arches) {
    std::vector<int> candidates;
    const auto append = [&](int arch) {
        if (arch > 0 &&
            std::find(candidates.begin(), candidates.end(), arch) == candidates.end()) {
            candidates.push_back(arch);
        }
    };
    append(preferred_arch);
    append(device_arch);
    append(context_arch);
    append(probed_arch);
    std::sort(packaged_arches.begin(), packaged_arches.end(),
              [](int left, int right) { return left > right; });
    for (const int arch : packaged_arches) append(arch);
    return candidates;
}

}  // namespace mca::qnn
