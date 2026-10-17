cmake_minimum_required(VERSION 3.22)
project(mca_qnn_probe_poison_tests LANGUAGES CXX)

# Standalone NDK test: no application, QNN SDK, model, or production build changes.
if(NOT ANDROID)
    message(FATAL_ERROR "Configure with the Android NDK toolchain; the production probe uses Android headers.")
endif()

enable_testing()
get_filename_component(MCA_NATIVE_CPP "${CMAKE_CURRENT_LIST_DIR}/../../../../main/cpp" ABSOLUTE)

add_executable(qnn_probe_poison_test
    test_qnn_probe_poison.cpp
    "${MCA_NATIVE_CPP}/mca/qnn/qnn_capability_probe.cpp"
    "${MCA_NATIVE_CPP}/mca/common/logging.cpp"
    "${MCA_NATIVE_CPP}/mca/common/safe_path.cpp")
target_compile_features(qnn_probe_poison_test PRIVATE cxx_std_17)
target_include_directories(qnn_probe_poison_test PRIVATE "${MCA_NATIVE_CPP}")
target_compile_definitions(qnn_probe_poison_test PRIVATE
    MCA_QNN_SDK_ROOT_CONFIGURED=0 MCA_WITH_QNN_SDK_HEADERS=0)
target_link_options(qnn_probe_poison_test PRIVATE
    "-Wl,--wrap=dlopen" "-Wl,--wrap=dlsym"
    "-Wl,--wrap=dlclose" "-Wl,--wrap=dlerror")
target_link_libraries(qnn_probe_poison_test PRIVATE dl log)

# Execute on Android, or supply CMAKE_CROSSCOMPILING_EMULATOR to CTest.
add_test(NAME qnn_probe_poison COMMAND qnn_probe_poison_test .)
