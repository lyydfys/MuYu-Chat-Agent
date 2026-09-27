#include "../../main/cpp/mnn_bundle_path_policy.hpp"

#include <chrono>
#include <fstream>
#include <iostream>
#include <stdexcept>

namespace fs = std::filesystem;

static void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

template <class Function>
static void rejects(Function function) {
    bool rejected = false;
    try { function(); } catch (const std::invalid_argument&) { rejected = true; }
    require(rejected, "Unsafe path was accepted");
}

int main() {
    using namespace mca::mnn;
    const auto workspace = fs::temp_directory_path() /
            ("mca-mnn-path-policy-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    const auto root = workspace / "bundle";
    try {
        fs::create_directories(root / "weights");
        fs::create_directories(workspace / "outside");
        std::ofstream(root / "llm.mnn") << "current-bundle-model";
        std::ofstream(root / "weights" / "llm.mnn") << "nested-model";
        std::ofstream(workspace / "outside" / "llm.mnn") << "must-not-load";

        require(normalizeMnnBundleRelativePath("./weights/./llm.mnn") == "weights/llm.mnn", "Dot prefix failed");
        require(normalizeMnnBundleRelativePath(".\\weights\\llm.mnn") == "weights/llm.mnn", "Windows separators failed");
        for (const auto& value : {"", ".", "./", "  ./  ", "/home/export/model/", "C:\\export\\model\\"}) {
            require(relocatedMnnBasePrefix(value).empty(), "Stale/root base_dir was not relocated");
            const auto relative = mnnBundleRelativeComponent(relocatedMnnBasePrefix(value), "./llm.mnn");
            require(confinedMnnBundlePath(root, relative) == fs::canonical(root / "llm.mnn"), "Relocation left the bundle");
        }
        const auto prefix = relocatedMnnBasePrefix("./weights/");
        require(prefix == "weights", "Relative base directory was lost");
        require(confinedMnnBundlePath(root, mnnBundleRelativeComponent(prefix, "./llm.mnn")) ==
                fs::canonical(root / "weights" / "llm.mnn"), "Nested base directory was not applied");
        // Nonexistent components remain inside the bundle; readiness reports
        // the missing file, instead of accidentally consulting an old export.
        require(confinedMnnBundlePath(root, "./weights/missing.mnn") ==
                fs::canonical(root / "weights") / "missing.mnn", "Missing path resolved incorrectly");
        for (const auto& value : {"../llm.mnn", "weights/../llm.mnn", "/tmp/llm.mnn", "C:/export/llm.mnn",
                                  "C:llm.mnn", "file:llm.mnn", ".", ""}) {
            rejects([&] { normalizeMnnBundleRelativePath(value); });
        }
        for (const auto& value : {"../outside", "weights/../outside", "/export/../outside", "C:\\export\\..\\outside", "C:relative"}) {
            rejects([&] { relocatedMnnBasePrefix(value); });
        }
        rejects([&] { normalizeMnnBundleRelativePath(std::string("llm\0.mnn", 8)); });

        std::error_code ec;
        fs::create_directory_symlink(workspace / "outside", root / "escape", ec);
        if (!ec) {
            rejects([&] { confinedMnnBundlePath(root, "escape/llm.mnn"); });
            rejects([&] { confinedMnnBundlePath(root, "escape", true); });
        } else {
            std::cout << "Symlink case unavailable on this host: " << ec.message() << '\n';
        }
        fs::remove_all(workspace);
        std::cout << "MNN bundle relocation path behavior passed\n";
        return 0;
    } catch (...) {
        std::error_code ignored;
        fs::remove_all(workspace, ignored);
        throw;
    }
}
