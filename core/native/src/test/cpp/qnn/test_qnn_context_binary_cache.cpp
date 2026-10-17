#include "mca/qnn/qnn_context_binary_cache.hpp"

#include <cassert>
#include <iostream>
#include <utility>

using namespace mca::qnn;

void test_scoped_readonly_mmap_missing_file() {
    ScopedReadOnlyMmap mmap_guard;
    std::string error;
    bool ok = mmap_guard.open_file("/definitely/nonexistent/context.bin", &error);
    assert(!ok);
    assert(!error.empty());
    assert(mmap_guard.empty());
    assert(mmap_guard.data() == nullptr);
    assert(mmap_guard.size() == 0);
}

void test_scoped_readonly_mmap_move() {
    ScopedReadOnlyMmap first;
    ScopedReadOnlyMmap second(std::move(first));
    assert(second.empty());
}

void test_scoped_dlhandle_lifecycle() {
    ScopedDlHandle handle(nullptr);
    assert(!handle);
    assert(handle.get() == nullptr);
    std::string error;
    assert(handle.close_checked(&error));
}

void test_read_binary_file_missing() {
    auto data = read_binary_file("/nonexistent/file.bin");
    assert(data.empty());
}

#if MCA_QNN_CONTEXT_CACHE_HAS_SDK
void test_inspect_empty_context_binary() {
    RuntimeProbe probe;
    probe.system_present = true;
    probe.system_path = "/nonexistent/libQnnSystem.so";

    auto metadata = inspect_qnn_context_binary_metadata(probe, nullptr, 0);
    assert(metadata.attempted);
    assert(!metadata.parsed);
    assert(metadata.message == "Context binary is empty.");
}

void test_inspect_missing_system_library() {
    RuntimeProbe probe;
    probe.system_present = false;

    uint8_t dummy[16] = {0};
    auto metadata = inspect_qnn_context_binary_metadata(probe, dummy, sizeof(dummy));
    assert(metadata.attempted);
    assert(!metadata.parsed);
    assert(metadata.message == "libQnnSystem.so is missing.");
}
#endif

int main() {
    test_scoped_readonly_mmap_missing_file();
    test_scoped_readonly_mmap_move();
    test_scoped_dlhandle_lifecycle();
    test_read_binary_file_missing();
#if MCA_QNN_CONTEXT_CACHE_HAS_SDK
    test_inspect_empty_context_binary();
    test_inspect_missing_system_library();
#endif
    std::cout << "QNN context binary cache unit tests passed." << std::endl;
    return 0;
}
