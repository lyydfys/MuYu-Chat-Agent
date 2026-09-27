#pragma once

#include <algorithm>
#include <cctype>
#include <sstream>
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

/**
 * Canonicalizes an image reference for duplicate suppression at the native
 * multimodal boundary. Android callers normally provide a prepared absolute
 * file path, but Local API clients may spell the same input as a file URI or
 * repeat a data/HTTP URL. This helper is lexical and performs no I/O.
 */
inline std::string canonicalMnnImageReference(std::string raw) {
    while (!raw.empty() && std::isspace(static_cast<unsigned char>(raw.front()))) {
        raw.erase(raw.begin());
    }
    while (!raw.empty() && std::isspace(static_cast<unsigned char>(raw.back()))) {
        raw.pop_back();
    }
    if (raw.empty()) return {};

    // URI fragments identify a presentation location, not image bytes. Keep
    // the scheme separator intact: collapsing every "//" would turn
    // https://host/image into https:/host/image and could merge distinct
    // remote references.
    const auto schemeEnd = raw.find(':');
    const auto scheme = schemeEnd == std::string::npos
            ? std::string()
            : lowerAscii(raw.substr(0, schemeEnd));
    if (scheme == "data" || scheme == "http" || scheme == "https") {
        const auto fragment = raw.find('#');
        if (fragment != std::string::npos) raw.erase(fragment);
        if (schemeEnd != std::string::npos) raw.replace(0, schemeEnd, scheme);
        return raw;
    }

    if (scheme == "file") {
        // Strip file:// / file: before path normalization. URI decoding is
        // intentionally left to the Android/JVM side; this function only
        // needs a stable lexical key at the native boundary.
        raw.erase(0, schemeEnd + 1);
        while (raw.rfind("//", 0) == 0) raw.erase(0, 1);
    }

    std::replace(raw.begin(), raw.end(), '\\', '/');
    // Normalize repeated separators and '.' path components while retaining
    // '..' components for the caller's confined-path validation. This makes
    // /tmp/photo.png, /tmp/./photo.png, and file:///tmp/photo.png share a
    // key without allowing a lexical escape to be hidden.
    const bool absolute = !raw.empty() && raw.front() == '/';
    std::vector<std::string> segments;
    size_t begin = 0;
    while (begin <= raw.size()) {
        const auto end = raw.find('/', begin);
        const auto segment = raw.substr(
                begin,
                end == std::string::npos ? std::string::npos : end - begin);
        if (!segment.empty() && segment != ".") segments.push_back(segment);
        if (end == std::string::npos) break;
        begin = end + 1;
    }
    std::ostringstream normalized;
    if (absolute) normalized << '/';
    for (size_t index = 0; index < segments.size(); ++index) {
        if (index > 0) normalized << '/';
        normalized << segments[index];
    }
    auto result = normalized.str();
    if (result.empty() && absolute) result = "/";
    return result;
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
