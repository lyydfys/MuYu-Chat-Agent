#include "mca/qnn/qnn_capability_probe.hpp"

#include <array>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <utility>
#include <vector>
#include <unistd.h>

namespace {

template <typename Dirs, typename = void>
struct AllowsOmittedPoisonFlag : std::false_type {};

template <typename Dirs>
struct AllowsOmittedPoisonFlag<Dirs, std::void_t<decltype(
    mca::qnn::inspect_runtime_internal(std::declval<Dirs>(), true, false))>>
    : std::true_type {};

static_assert(!AllowsOmittedPoisonFlag<const std::vector<std::string>&>::value,
              "Runtime probes must explicitly supply their owner's poison flag.");

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

struct FakeLibrary {
    const char* name;
    const char* provider;
};

std::array<FakeLibrary, 3> libraries{{
    {"libQnnSystem.so", "QnnSystemInterface_getProviders"},
    {"libQnnHtp.so", "QnnInterface_getProviders"},
    {"libcdsprpc.so", ""},
}};

struct LoaderFaults {
    std::string fail_open;
    std::string fail_close;
    bool missing_system_provider = false;
    std::vector<std::string> opened;
    std::vector<std::string> closed;
};

LoaderFaults faults;
char open_error[] = "injected dlopen failure";
char close_error[] = "injected dlclose failure";
char* last_error = nullptr;

void reset_loader() {
    faults = LoaderFaults{};
    last_error = nullptr;
}

class RuntimeFixture {
public:
    explicit RuntimeFixture(const std::string& parent) {
        const std::string pattern = parent + "/qnn-probe-poison-XXXXXX";
        std::vector<char> buffer(pattern.begin(), pattern.end());
        buffer.push_back('\0');
        const char* created = mkdtemp(buffer.data());
        require(created != nullptr, "Unable to create isolated probe fixture directory");
        directory = created;
    }

    ~RuntimeFixture() {
        for (const char* name : names) std::remove((directory + "/" + name).c_str());
        rmdir(directory.c_str());
    }

    void populate() const {
        for (const char* name : names) {
            FILE* file = std::fopen((directory + "/" + name).c_str(), "wb");
            require(file != nullptr, "Unable to create probe fixture file");
            require(std::fclose(file) == 0, "Unable to close probe fixture file");
        }
    }

    std::string directory;

private:
    const std::array<const char*, 4> names{{
        "libQnnSystem.so", "libQnnHtp.so", "libQnnHtpV68Skel.so", "libcdsprpc.so",
    }};
};

mca::qnn::RuntimeProbe probe(const RuntimeFixture& fixture, bool htp,
                             std::atomic<bool>& poisoned) {
    // Inject a per-test atomic; do not define another production poison owner.
    return mca::qnn::inspect_runtime_internal({fixture.directory}, true, htp, &poisoned);
}

void test_success_and_sticky_poison(const RuntimeFixture& fixture) {
    for (bool htp : {false, true}) {
        for (bool initially_poisoned : {false, true}) {
            reset_loader();
            std::atomic<bool> poisoned{initially_poisoned};
            const auto result = probe(fixture, htp, poisoned);
            require(result.loadable && result.qnn_system_interface_present,
                    "Successful cleanup must preserve a loadable probe result");
            require(result.qnn_interface_present == htp, "Unexpected HTP probe behavior");
            require(poisoned.load() == initially_poisoned,
                    "Successful probe must neither poison nor clear an existing poison flag");
            const std::vector<std::string> expected = htp
                ? std::vector<std::string>{"libQnnHtp.so", "libQnnSystem.so", "libcdsprpc.so"}
                : std::vector<std::string>{"libQnnSystem.so"};
            require(faults.closed == expected, "Successful unload must preserve dependency order");
        }
    }
}

void test_unload_failures(const RuntimeFixture& fixture) {
    struct Case {
        bool htp;
        const char* fail_close;
        std::vector<std::string> expected_closed;
    };
    const std::array<Case, 4> cases{{
        {false, "libQnnSystem.so", {"libQnnSystem.so"}},
        {true, "libQnnHtp.so", {"libQnnHtp.so"}},
        {true, "libQnnSystem.so", {"libQnnHtp.so", "libQnnSystem.so"}},
        {true, "libcdsprpc.so", {"libQnnHtp.so", "libQnnSystem.so", "libcdsprpc.so"}},
    }};
    for (const auto& test : cases) {
        reset_loader();
        faults.fail_close = test.fail_close;
        std::atomic<bool> poisoned{false};
        const auto result = probe(fixture, test.htp, poisoned);
        require(poisoned.load(), "Unload failure must reach the caller-supplied poison flag");
        require(!result.loadable && !result.qnn_system_interface_present && !result.qnn_interface_present,
                "Unload failure must invalidate the probe result");
        require(result.message.find(close_error) != std::string::npos,
                "Unload failure must retain the concrete loader error");
        require(faults.closed == test.expected_closed,
                "Failed unload must retain dependencies and must not retry in the destructor");
    }
}

void test_load_and_package_failures_do_not_poison(const RuntimeFixture& fixture) {
    for (bool htp : {false, true}) {
        reset_loader();
        faults.fail_open = htp ? "libQnnHtp.so" : "libQnnSystem.so";
        std::atomic<bool> poisoned{false};
        const auto result = probe(fixture, htp, poisoned);
        require(!result.loadable && !poisoned.load(),
                "A failed load with successful cleanup is not runtime poison");
        require(result.message.find(open_error) != std::string::npos,
                "Load failure must retain the concrete loader error");
        const std::vector<std::string> expected = htp
            ? std::vector<std::string>{"libQnnSystem.so", "libcdsprpc.so"}
            : std::vector<std::string>{};
        require(faults.closed == expected, "Partial load must clean up only acquired libraries");
    }

    reset_loader();
    std::atomic<bool> poisoned{false};
    const auto missing = mca::qnn::inspect_runtime_internal(
        {fixture.directory + "/missing"}, true, false, &poisoned);
    require(!missing.loadable && !poisoned.load() && faults.opened.empty(),
            "Missing runtime files must not load libraries or poison the runtime");

    reset_loader();
    faults.missing_system_provider = true;
    const auto incompatible = probe(fixture, false, poisoned);
    require(incompatible.loadable && !incompatible.qnn_system_interface_present && !poisoned.load(),
            "A missing provider with successful unload must remain a package compatibility failure");

    reset_loader();
    const auto files_only = mca::qnn::inspect_runtime_internal(
        {fixture.directory}, false, false, &poisoned);
    require(files_only.system_present && !files_only.loadable && !poisoned.load() && faults.opened.empty(),
            "A file-only probe must not load libraries or poison the runtime");
}

void test_failed_load_and_failed_cleanup(const RuntimeFixture& fixture) {
    reset_loader();
    faults.fail_open = "libQnnHtp.so";
    faults.fail_close = "libQnnSystem.so";
    std::atomic<bool> poisoned{false};
    const auto result = probe(fixture, true, poisoned);
    require(poisoned.load() && !result.loadable, "Cleanup failure after a failed load must poison");
    require(result.message.find(open_error) != std::string::npos &&
            result.message.find(close_error) != std::string::npos,
            "Probe diagnostics must retain both the load and cleanup failures");
    require(faults.closed == std::vector<std::string>{"libQnnSystem.so"},
            "Failed system unload must retain its RPC dependency");
}

} // namespace

// Linker wrappers exercise the real production probe without loading QNN or RPC binaries.
extern "C" void* __wrap_dlopen(const char* path, int) {
    const char* slash = std::strrchr(path, '/');
    const std::string name = slash == nullptr ? path : slash + 1;
    faults.opened.push_back(name);
    if (name == faults.fail_open) {
        last_error = open_error;
        return nullptr;
    }
    for (auto& library : libraries) if (name == library.name) return &library;
    last_error = open_error;
    return nullptr;
}

extern "C" void* __wrap_dlsym(void* handle, const char* symbol) {
    auto* library = static_cast<FakeLibrary*>(handle);
    if (faults.missing_system_provider && std::strcmp(symbol, "QnnSystemInterface_getProviders") == 0)
        return nullptr;
    return std::strcmp(symbol, library->provider) == 0 ? handle : nullptr;
}

extern "C" int __wrap_dlclose(void* handle) {
    auto* library = static_cast<FakeLibrary*>(handle);
    faults.closed.emplace_back(library->name);
    if (faults.fail_close == library->name) {
        last_error = close_error;
        return -1;
    }
    return 0;
}

extern "C" char* __wrap_dlerror() {
    char* error = last_error;
    last_error = nullptr;
    return error;
}

int main(int argc, char** argv) {
    try {
        RuntimeFixture fixture(argc > 1 ? argv[1] : ".");
        fixture.populate();
        test_success_and_sticky_poison(fixture);
        test_unload_failures(fixture);
        test_load_and_package_failures_do_not_poison(fixture);
        test_failed_load_and_failed_cleanup(fixture);
        std::cout << "QNN probe poison behavior tests passed.\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "QNN probe poison behavior test failed: " << error.what() << '\n';
        return 1;
    }
}
