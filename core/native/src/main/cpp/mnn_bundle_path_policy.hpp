#pragma once

#include <algorithm>
#include <cctype>
#include <filesystem>
#include <stdexcept>
#include <string>

namespace mca::mnn {

inline std::string normalizeMnnBundleRelativePath(const std::string& raw, bool directory = false) {
    std::string path = raw;
    std::replace(path.begin(), path.end(), '\\', '/');
    if (path.find('\0') != std::string::npos || (!path.empty() && path.front() == '/') ||
        (path.size() > 1 && path[1] == ':')) {
        throw std::invalid_argument("MNN component must be a safe bundle-relative path: " + raw);
    }
    std::string normalized;
    size_t start = 0;
    while (start <= path.size()) {
        const auto end = path.find('/', start);
        const auto segment = path.substr(start, end == std::string::npos ? end : end - start);
        if (segment == ".." || segment.find(':') != std::string::npos) {
            throw std::invalid_argument("MNN path must not traverse outside the bundle: " + raw);
        }
        if (!segment.empty() && segment != ".") {
            if (!normalized.empty()) normalized += '/';
            normalized += segment;
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    if (normalized.empty() && !directory) {
        throw std::invalid_argument("MNN component path must not be empty: " + raw);
    }
    return normalized;
}

inline std::string relocatedMnnBasePrefix(const std::string& raw) {
    std::string path = raw;
    const auto isSpace = [](unsigned char ch) { return std::isspace(ch) != 0; };
    while (!path.empty() && isSpace(path.front())) path.erase(path.begin());
    while (!path.empty() && isSpace(path.back())) path.pop_back();
    std::replace(path.begin(), path.end(), '\\', '/');
    if (path.find('\0') != std::string::npos) {
        throw std::invalid_argument("MNN base_dir contains a NUL character.");
    }
    // Export-machine absolute paths are metadata, never an authority to read
    // outside the imported bundle. Relocate their components to this bundle.
    if ((!path.empty() && path.front() == '/') ||
        (path.size() > 2 && std::isalpha(static_cast<unsigned char>(path[0])) &&
         path[1] == ':' && path[2] == '/')) {
        // Reject parent traversal even in obsolete absolute declarations.
        auto check = path;
        if (check.size() > 1 && check[1] == ':') check = check.substr(2);
        while (!check.empty() && check.front() == '/') check.erase(check.begin());
        normalizeMnnBundleRelativePath(check, true);
        return {};
    }
    return normalizeMnnBundleRelativePath(path, true);
}

inline std::string mnnBundleRelativeComponent(const std::string& prefix, const std::string& raw) {
    const auto relative = normalizeMnnBundleRelativePath(raw);
    return prefix.empty() ? relative : prefix + '/' + relative;
}

inline std::filesystem::path confinedMnnBundlePath(
        const std::filesystem::path& root, const std::string& relative, bool directory = false) {
    const auto normalized = normalizeMnnBundleRelativePath(relative, directory);
    const auto canonicalRoot = std::filesystem::canonical(root);
    const auto canonicalPath = std::filesystem::weakly_canonical(canonicalRoot / normalized);
    auto rootIt = canonicalRoot.begin();
    auto pathIt = canonicalPath.begin();
    for (; rootIt != canonicalRoot.end(); ++rootIt, ++pathIt) {
        if (pathIt == canonicalPath.end() || *rootIt != *pathIt) {
            throw std::invalid_argument("MNN component resolves outside its bundle: " + relative);
        }
    }
    if (!directory && pathIt == canonicalPath.end()) {
        throw std::invalid_argument("MNN component resolves to its bundle directory: " + relative);
    }
    return canonicalPath;
}

}  // namespace mca::mnn
