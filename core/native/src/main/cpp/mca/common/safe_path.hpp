#ifndef MCA_COMMON_SAFE_PATH_HPP
#define MCA_COMMON_SAFE_PATH_HPP

#include <string>

namespace mca {
namespace common {

/**
 * File system path safety utilities.
 * Protects model directories, temporary buffers, and export paths from path traversal.
 */
class SafePath {
public:
    /**
     * Checks if a path string contains path traversal tokens ("..", etc.).
     */
    [[nodiscard]] static bool has_traversal(const std::string& path) noexcept;

    /**
     * Normalizes directory separators to standard forward slashes.
     */
    [[nodiscard]] static std::string normalize_separators(const std::string& path);

    /**
     * Safely joins a trusted base directory with a relative path.
     * Returns empty string if the resulting path escapes the base directory.
     */
    [[nodiscard]] static std::string safe_join(
        const std::string& base_dir,
        const std::string& relative_path
    );

    /**
     * Verifies whether a file exists at the given path.
     */
    [[nodiscard]] static bool file_exists(const std::string& path) noexcept;

    /**
     * Computes the size of a file in bytes, or -1 on error.
     */
    [[nodiscard]] static int64_t file_size(const std::string& path) noexcept;
};

} // namespace common
} // namespace mca

#endif // MCA_COMMON_SAFE_PATH_HPP
