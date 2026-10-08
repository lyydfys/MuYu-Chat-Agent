#include "mca/mnn/mnn_file_utils.hpp"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

namespace mca {
namespace mnn {

bool file_exists(const std::string& path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool remove_existing_output_file(const std::string& path, std::string& error) {
    struct stat st {};
    if (stat(path.c_str(), &st) != 0) {
        if (errno == ENOENT) return true;
        error = "Unable to inspect output path: " + path + ": " + std::strerror(errno);
        return false;
    }
    if (!S_ISREG(st.st_mode)) {
        error = "Output path exists but is not a regular file: " + path;
        return false;
    }
    if (std::remove(path.c_str()) != 0) {
        error = "Unable to remove existing output file: " + path + ": " + std::strerror(errno);
        return false;
    }
    return true;
}

bool validate_output_path_for_write(const std::string& path, std::string& error) {
    if (path.empty()) {
        error = "Output path is empty.";
        return false;
    }
    const auto parent = parent_dir(path);
    struct stat parent_stat {};
    if (stat(parent.c_str(), &parent_stat) != 0) {
        error = "Output parent directory does not exist or cannot be inspected: " + parent +
                ": " + std::strerror(errno);
        return false;
    }
    if (!S_ISDIR(parent_stat.st_mode)) {
        error = "Output parent path is not a directory: " + parent;
        return false;
    }

    struct stat output_stat {};
    if (stat(path.c_str(), &output_stat) != 0) {
        if (errno == ENOENT) {
            return true;
        }
        error = "Unable to inspect output path: " + path + ": " + std::strerror(errno);
        return false;
    }
    if (!S_ISREG(output_stat.st_mode)) {
        error = "Output path exists but is not a regular file: " + path;
        return false;
    }
    return true;
}

}  // namespace mnn
}  // namespace mca
