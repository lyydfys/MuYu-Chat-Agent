#pragma once

#include <algorithm>
#include <cctype>
#include <string>
#include <utility>
#include <vector>

namespace mca::mnn {

/**
 * Vision component path policy shared by the native loader and host tests.
 *
 * MNN exporters do not all place the Omni graph at the historical
 * `visual.mnn` root path.  Keep discovery conservative: an explicit path from
 * model metadata wins, followed by a small set of known nested layouts.  The
 * caller supplies an existence predicate so this policy stays independent of
 * Android/Posix filesystem APIs.
 */
inline std::string lowerAscii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

inline bool isMnnVisualPathKey(const std::string& rawKey) {
    const auto key = lowerAscii(rawKey);
    return key == "visual_model" ||
           key == "vision_model" ||
           key == "visual_encoder" ||
           key == "vision_encoder";
}

/** Return a safe relative path, or an empty string for an unsafe declaration. */
inline std::string normalizeMnnVisualRelativePath(const std::string& rawPath) {
    std::string path = rawPath;
    std::replace(path.begin(), path.end(), '\\', '/');
    while (!path.empty() && path.front() == ' ') path.erase(path.begin());
    while (!path.empty() && path.back() == ' ') path.pop_back();
    if (path.empty() || path.front() == '/' || path.find('\0') != std::string::npos) return {};
    if (path.size() >= 2 && std::isalpha(static_cast<unsigned char>(path[0])) && path[1] == ':') {
        return {};
    }
    size_t begin = 0;
    while (begin <= path.size()) {
        const size_t end = path.find('/', begin);
        const std::string segment = path.substr(
                begin, end == std::string::npos ? std::string::npos : end - begin);
        if (segment.empty() || segment == "." || segment == "..") return {};
        if (end == std::string::npos) break;
        begin = end + 1;
    }
    return path;
}

inline void appendUniqueMnnVisualPath(
        const std::string& rawPath,
        std::vector<std::string>& target) {
    const auto normalized = normalizeMnnVisualRelativePath(rawPath);
    if (normalized.empty()) return;
    if (std::find(target.begin(), target.end(), normalized) == target.end()) {
        target.push_back(normalized);
    }
}

inline std::vector<std::string> mnnVisualPathCandidates(
        const std::string& configuredPath,
        const std::vector<std::string>& declaredPaths = {}) {
    std::vector<std::string> candidates;
    appendUniqueMnnVisualPath(configuredPath, candidates);
    for (const auto& path : declaredPaths) appendUniqueMnnVisualPath(path, candidates);

    // Keep these fallbacks in sync with the layouts emitted by the official
    // MNN/Qwen exporters.  Root visual.mnn remains first for backwards
    // compatibility; nested paths cover bundles that were previously reported
    // as text-only because the native bridge never discovered their graph.
    for (const auto& path : {
             std::string("visual.mnn"),
             std::string("vision/visual.mnn"),
             std::string("vision/encoder.mnn"),
             std::string("vision/vision_encoder.mnn"),
             std::string("vision/visual_encoder.mnn"),
             std::string("visual/visual.mnn"),
             std::string("visual/encoder.mnn"),
             std::string("visual_encoder.mnn"),
             std::string("vision_encoder.mnn")}) {
        appendUniqueMnnVisualPath(path, candidates);
    }
    return candidates;
}

template <typename ExistsFn>
inline std::string selectMnnVisualModelPath(
        const std::string& configuredPath,
        const std::vector<std::string>& declaredPaths,
        ExistsFn&& exists) {
    for (const auto& path : mnnVisualPathCandidates(configuredPath, declaredPaths)) {
        if (exists(path)) return path;
    }
    return {};
}

}  // namespace mca::mnn
