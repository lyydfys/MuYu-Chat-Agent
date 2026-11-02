#ifndef MCA_LLM_BACKEND_LOADER_HPP
#define MCA_LLM_BACKEND_LOADER_HPP

#include <string>

namespace mca {
namespace llm {

bool file_exists(const std::string &path);

std::string join_path(const std::string &dir, const std::string &name);

std::string list_ggml_backend_files(const std::string &dir);

void load_baseline_cpu_backend_if_needed(
        const std::string &native_lib_dir,
        std::string &last_error);

void load_mca_ggml_backends(
        const std::string &native_lib_dir,
        std::string &diagnostic);

bool load_android_opencl_runtime_if_available_locked(
        void *&android_opencl_handle,
        std::string &backend_load_diagnostic);

}  // namespace llm
}  // namespace mca

#endif  // MCA_LLM_BACKEND_LOADER_HPP
