#include "safe_path.hpp"

#include <sys/stat.h>
#include <algorithm>

namespace mca {
namespace common {

bool SafePath::has_traversal(const std::string& path) noexcept {
    if (path.empty()) return false;
    const std::string normalized = normalize_separators(path);

    if (normalized == ".." || normalized.rfind("../", 0) == 0) return true;
    if (normalized.find("/../") != std::string::npos) return true;
    if (normalized.length() >= 3 && normalized.substr(normalized.length() - 3) == "/..") return true;

    return false;
}

std::string SafePath::normalize_separators(const std::string& path) {
    std::string out = path;
    std::replace(out.begin(), out.end(), '\\', '/');
    return out;
}

std::string SafePath::safe_join(
    const std::string& base_dir,
    const std::string& relative_path
) {
    if (has_traversal(relative_path)) {
        return ""; // Rejected due to traversal
    }

    std::string base = normalize_separators(base_dir);
    std::string rel = normalize_separators(relative_path);

    if (!base.empty() && base.back() == '/') {
        base.pop_back();
    }
    if (!rel.empty() && rel.front() == '/') {
        rel.erase(0, 1);
    }

    if (base.empty()) return rel;
    if (rel.empty()) return base;
    return base + "/" + rel;
}

bool SafePath::file_exists(const std::string& path) noexcept {
    if (path.empty()) return false;
    struct stat st{};
    return (stat(path.c_str(), &st) == 0);
}

int64_t SafePath::file_size(const std::string& path) noexcept {
    if (path.empty()) return -1;
    struct stat st{};
    if (stat(path.c_str(), &st) != 0) {
        return -1;
    }
    return static_cast<int64_t>(st.st_size);
}

} // namespace common
} // namespace mca
