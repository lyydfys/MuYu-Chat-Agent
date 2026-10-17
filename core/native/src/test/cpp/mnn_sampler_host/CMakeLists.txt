cmake_minimum_required(VERSION 3.22)
project(mca_mnn_sampler_host LANGUAGES C CXX)
enable_testing()
get_filename_component(REPO "${CMAKE_CURRENT_LIST_DIR}/../../../../../.." ABSOLUTE)
set(MNN_ROOT "${REPO}/third_party/MNN")
# Needed by the targets below; must be defined before first use. (The previous
# definition sat further down the file, after mnn_sampler_numeric_test.)
set(MCA_NATIVE_CPP "${REPO}/core/native/src/main/cpp")
set(MNN_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_LLM OFF CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)
set(MNN_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DEMO OFF CACHE BOOL "" FORCE)
set(MNN_OPENCL OFF CACHE BOOL "" FORCE)
set(MNN_OPENMP OFF CACHE BOOL "" FORCE)
add_subdirectory("${MNN_ROOT}" mnn)
add_executable(mnn_sampler_numeric_test
    ../mnn_sampler_numeric_test.cpp
    "${MNN_ROOT}/transformers/llm/engine/src/sampler.cpp"
    # The test calls the real production mca::mnn::applySamplingConfig /
    # applyCpuCacheSafety; without this TU the link failed with
    # LNK2019 unresolved external and CTest reported "mnn_sampler_numeric (Not Run)".
    "${MCA_NATIVE_CPP}/mca/mnn/mnn_sampling_strategy.cpp")
target_compile_features(mnn_sampler_numeric_test PRIVATE cxx_std_17)
target_compile_definitions(mnn_sampler_numeric_test PRIVATE MNN_BUILD_STATIC_LIBS)
target_include_directories(mnn_sampler_numeric_test PRIVATE
    "${MNN_ROOT}/include" "${MNN_ROOT}/transformers/llm/engine/include"
    "${MNN_ROOT}/transformers/llm/engine/src" "${MNN_ROOT}/3rd_party/half"
    "${MNN_ROOT}/3rd_party" "${REPO}/third_party/llama.cpp/vendor"
    # mnn_sampling_strategy.cpp includes "mca/mnn/mnn_sampling_strategy.hpp"
    # relative to the MCA native root, so that root must be on the include path.
    "${MCA_NATIVE_CPP}")
target_link_libraries(mnn_sampler_numeric_test PRIVATE MNN)
add_test(NAME mnn_sampler_numeric COMMAND mnn_sampler_numeric_test)
add_test(NAME mnn_cache_namespace COMMAND "${CMAKE_COMMAND}"
    "-DTEST_DIR=${CMAKE_CURRENT_BINARY_DIR}/cache-test"
    -P "${CMAKE_CURRENT_LIST_DIR}/cache_namespace_test.cmake")
add_test(NAME mnn_cache_namespace_rejects_tamper COMMAND "${CMAKE_COMMAND}"
    "-DTEST_DIR=${CMAKE_CURRENT_BINARY_DIR}/cache-tamper-test" -DTAMPER=ON
    -P "${CMAKE_CURRENT_LIST_DIR}/cache_namespace_test.cmake")
set_tests_properties(mnn_cache_namespace_rejects_tamper PROPERTIES WILL_FAIL TRUE)
add_executable(mnn_sampler_source_contract ../mnn_sampler_source_contract_test.cpp)
target_compile_features(mnn_sampler_source_contract PRIVATE cxx_std_17)
if(MSVC)
    target_compile_options(mnn_sampler_numeric_test PRIVATE /utf-8)
    target_compile_options(mnn_sampler_source_contract PRIVATE /UNDEBUG)
else()
    target_compile_options(mnn_sampler_source_contract PRIVATE -UNDEBUG)
endif()
add_test(NAME mnn_sampler_source_contract COMMAND mnn_sampler_source_contract
    "${REPO}/core/native/src/main/cpp/mnn_native_engine.cpp"
    "${MNN_ROOT}/transformers/llm/engine/src/sampler.cpp")

foreach(test_name IN ITEMS mnn_prompt_history mnn_stream_protocol_filter mnn_visual_stream_queue mnn_runtime_capability_policy)
    add_executable(${test_name}_test "../${test_name}_test.cpp")
    target_compile_features(${test_name}_test PRIVATE cxx_std_17)
    if(MSVC)
        target_compile_options(${test_name}_test PRIVATE /UNDEBUG)
    else()
        target_compile_options(${test_name}_test PRIVATE -UNDEBUG)
    endif()
    add_test(NAME ${test_name} COMMAND ${test_name}_test)
endforeach()

add_executable(mnn_recurrent_cache_test ../mnn_recurrent_cache_test.cpp)
target_compile_features(mnn_recurrent_cache_test PRIVATE cxx_std_17)
target_compile_definitions(mnn_recurrent_cache_test PRIVATE MNN_BUILD_STATIC_LIBS)
target_include_directories(mnn_recurrent_cache_test PRIVATE
    "${MNN_ROOT}/include" "${MNN_ROOT}/source" "${MNN_ROOT}/schema/current"
    "${MNN_ROOT}/3rd_party/flatbuffers/include" "${MNN_ROOT}/transformers/llm/engine/src")
target_link_libraries(mnn_recurrent_cache_test PRIVATE MNN)
add_test(NAME mnn_recurrent_cache COMMAND mnn_recurrent_cache_test)

set(MCA_NATIVE_CPP "${REPO}/core/native/src/main/cpp")   # moved to the top of this file
add_executable(mca_native_helper_unit_tests
    ../mnn/test_mnn_sampling_and_boundary.cpp
    "${MCA_NATIVE_CPP}/mca/mnn/mnn_sampling_strategy.cpp"
    "${MCA_NATIVE_CPP}/mca/mnn/mnn_token_boundary_guard.cpp"
    "${MCA_NATIVE_CPP}/mca/llm/llama_context_manager.cpp")
target_compile_features(mca_native_helper_unit_tests PRIVATE cxx_std_17)
target_compile_definitions(mca_native_helper_unit_tests PRIVATE MCA_WITH_LLAMA_CPP=0)
target_include_directories(mca_native_helper_unit_tests PRIVATE
    "${MCA_NATIVE_CPP}"
    "${REPO}/third_party/llama.cpp/vendor")
if(MSVC)
    target_compile_options(mca_native_helper_unit_tests PRIVATE /UNDEBUG)
else()
    target_compile_options(mca_native_helper_unit_tests PRIVATE -UNDEBUG)
endif()
add_test(NAME mca_native_helper_unit_tests COMMAND mca_native_helper_unit_tests)

# T02 P0-04: numeric protection for the restored MNN SD1.5 chain. Runs the real
# production scheduler plus the real seed/Gaussian/init_noise_sigma and
# guided-prediction/eta seams; no model files and no MNN dependency, so it stays
# a fast host test.
add_executable(mnn_sd15_numeric_seam_test
    ../mnn/sd15_numeric_seam_test.cpp
    "${MCA_NATIVE_CPP}/diffusion_scheduler.cpp")
target_compile_features(mnn_sd15_numeric_seam_test PRIVATE cxx_std_17)
target_include_directories(mnn_sd15_numeric_seam_test PRIVATE "${MCA_NATIVE_CPP}")
if(MSVC)
    target_compile_options(mnn_sd15_numeric_seam_test PRIVATE /UNDEBUG)
else()
    target_compile_options(mnn_sd15_numeric_seam_test PRIVATE -UNDEBUG)
endif()
add_test(NAME mnn_sd15_numeric_seam COMMAND mnn_sd15_numeric_seam_test)

# T02 SD15: Euler+trailing ends on the Local Dream sentinel -1, which the UNet
# graph rejects. The device run executed 19 of 20 steps (~876 s) and aborted on
# the last one ("UNet timestep must be finite and in [0, 999]."). This test drives
# the real scheduler and asserts that the value published to the graph is clamped
# while the scheduler keeps its sentinel. No MNN, no model files.
add_executable(mnn_sd15_euler_trailing_timestep_test
    ../mnn/sd15_euler_trailing_timestep_test.cpp
    "${MCA_NATIVE_CPP}/diffusion_scheduler.cpp")
target_compile_features(mnn_sd15_euler_trailing_timestep_test PRIVATE cxx_std_17)
target_include_directories(mnn_sd15_euler_trailing_timestep_test PRIVATE "${MCA_NATIVE_CPP}")
if(MSVC)
    target_compile_options(mnn_sd15_euler_trailing_timestep_test PRIVATE /UNDEBUG)
else()
    target_compile_options(mnn_sd15_euler_trailing_timestep_test PRIVATE -UNDEBUG)
endif()
add_test(NAME mnn_sd15_euler_trailing_timestep COMMAND mnn_sd15_euler_trailing_timestep_test)

add_library(mca_qnn_helper_test_support STATIC
    "${MCA_NATIVE_CPP}/mca/qnn/qnn_context_binary_cache.cpp"
    "${MCA_NATIVE_CPP}/mca/qnn/qnn_graph_executor_helper.cpp"
    "${MCA_NATIVE_CPP}/mca/qnn/qnn_tensor_codec.cpp"
    "${MCA_NATIVE_CPP}/mca/qnn/qnn_tensor_inspect.cpp"
    "${MCA_NATIVE_CPP}/mca/qnn/qnn_tensor_quantizer.cpp")
target_compile_features(mca_qnn_helper_test_support PUBLIC cxx_std_17)
# QNN SDK wiring for the host tests (T01-D, review 4.3).
#
# The typed-quantizer test used to skip unconditionally because this test project
# never received the QNN SDK include directories - the SDK is present on the
# machine, so the skip was a wiring gap, not a missing SDK. Resolve the SDK root
# (overridable) and let the configure log state which branch was taken.
#
# Scope note: the SDK headers are deliberately NOT enabled on the shared support
# library. With MCA_WITH_QNN_SDK_HEADERS=1, qnn_context_binary_cache.cpp pulls in
# references to QNN runtime entry points that a host test cannot link (observed:
# LNK1120 unresolved external for mca_qnn_context_binary_cache_unit_test). Only
# the tensor-quantizer target actually needs the real SDK types, so it is built
# directly below while the other two tests keep the stub path.
set(MCA_QNN_SDK_ROOT "E:/MAC/build/qnn-runtime-sdk/qairt/2.45.0.260326"
        CACHE PATH "QNN SDK root used by the host test targets")
set(MCA_QNN_SDK_INCLUDE_DIR "${MCA_QNN_SDK_ROOT}/include")
if(EXISTS "${MCA_QNN_SDK_INCLUDE_DIR}/QNN/QnnTypes.h")
    message(STATUS "MCA QNN host tests: SDK headers found at ${MCA_QNN_SDK_INCLUDE_DIR}; typed quantizer test ENABLED (no skip)")
    set(MCA_QNN_HOST_TESTS_HAVE_SDK TRUE)
else()
    message(STATUS "MCA QNN host tests: SDK headers NOT found under ${MCA_QNN_SDK_ROOT}; typed quantizer test falls back to skip (exit 77)")
    set(MCA_QNN_HOST_TESTS_HAVE_SDK FALSE)
endif()
target_compile_definitions(mca_qnn_helper_test_support PUBLIC MCA_WITH_QNN_SDK_HEADERS=0)
target_include_directories(mca_qnn_helper_test_support PUBLIC "${MCA_NATIVE_CPP}")
target_link_libraries(mca_qnn_helper_test_support PUBLIC ${CMAKE_DL_LIBS})

# The tensor-quantizer contract needs the real SDK types, so it is built from its
# own two sources rather than through the support library. Both SDK roots are
# required: MCA sources include <QNN/QnnTypes.h> (needs .../include), while the
# SDK's own headers include each other relatively, e.g.
# QNN/System/QnnSystemInterface.h does #include "System/QnnSystemCommon.h"
# (needs .../include/QNN). Omitting the second root fails with C1083.
if(MCA_QNN_HOST_TESTS_HAVE_SDK)
    add_executable(mca_qnn_tensor_quantizer_unit_test
            "../qnn/test_qnn_tensor_quantizer.cpp"
            "${MCA_NATIVE_CPP}/mca/qnn/qnn_tensor_quantizer.cpp"
            "${MCA_NATIVE_CPP}/mca/qnn/qnn_tensor_codec.cpp"
            "${MCA_NATIVE_CPP}/mca/qnn/qnn_tensor_inspect.cpp")
    target_compile_features(mca_qnn_tensor_quantizer_unit_test PRIVATE cxx_std_17)
    target_compile_definitions(mca_qnn_tensor_quantizer_unit_test PRIVATE MCA_WITH_QNN_SDK_HEADERS=1)
    target_include_directories(mca_qnn_tensor_quantizer_unit_test PRIVATE
            "${MCA_NATIVE_CPP}"
            "${MCA_QNN_SDK_INCLUDE_DIR}"
            "${MCA_QNN_SDK_INCLUDE_DIR}/QNN")
    if(MSVC)
        target_compile_options(mca_qnn_tensor_quantizer_unit_test PRIVATE /UNDEBUG)
    else()
        target_compile_options(mca_qnn_tensor_quantizer_unit_test PRIVATE -UNDEBUG)
    endif()
    add_test(NAME mca_qnn_tensor_quantizer_unit_test COMMAND mca_qnn_tensor_quantizer_unit_test)
endif()

foreach(test_name IN ITEMS
        qnn_context_binary_cache
        qnn_graph_executor_helper)
    add_executable(mca_${test_name}_unit_test "../qnn/test_${test_name}.cpp")
    target_link_libraries(mca_${test_name}_unit_test PRIVATE mca_qnn_helper_test_support)
    if(MSVC)
        target_compile_options(mca_${test_name}_unit_test PRIVATE /UNDEBUG)
    else()
        target_compile_options(mca_${test_name}_unit_test PRIVATE -UNDEBUG)
    endif()
    add_test(NAME mca_${test_name}_unit_test COMMAND mca_${test_name}_unit_test)
endforeach()

# Without the SDK the quantizer test still exists but reports the documented skip.
if(NOT MCA_QNN_HOST_TESTS_HAVE_SDK)
    add_executable(mca_qnn_tensor_quantizer_unit_test "../qnn/test_qnn_tensor_quantizer.cpp")
    target_link_libraries(mca_qnn_tensor_quantizer_unit_test PRIVATE mca_qnn_helper_test_support)
    if(MSVC)
        target_compile_options(mca_qnn_tensor_quantizer_unit_test PRIVATE /UNDEBUG)
    else()
        target_compile_options(mca_qnn_tensor_quantizer_unit_test PRIVATE -UNDEBUG)
    endif()
    add_test(NAME mca_qnn_tensor_quantizer_unit_test COMMAND mca_qnn_tensor_quantizer_unit_test)
    set_tests_properties(mca_qnn_tensor_quantizer_unit_test PROPERTIES SKIP_RETURN_CODE 77)
endif()
