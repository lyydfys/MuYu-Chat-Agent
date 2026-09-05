cmake_minimum_required(VERSION 3.22)

project(mca_llama_prefix_cache_tests LANGUAGES C CXX)

include(CTest)

set(MCA_LLAMA_PREFIX_TEST_MODEL "" CACHE FILEPATH
    "A real GGUF used by mca_persistent_prefix_cache_equivalence_test")

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

get_filename_component(MCA_REPOSITORY_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../" ABSOLUTE)
set(MCA_LLAMA_SOURCE "${MCA_REPOSITORY_ROOT}/third_party/llama.cpp")

if(NOT EXISTS "${MCA_LLAMA_SOURCE}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp source is missing at ${MCA_LLAMA_SOURCE}")
endif()

add_subdirectory("${MCA_LLAMA_SOURCE}" llama-build)

add_executable(
    mca_llama_load_failure_policy_test
    llama_load_failure_policy_test.cpp
)
target_compile_features(mca_llama_load_failure_policy_test PRIVATE cxx_std_17)

add_test(
    NAME mca_llama_load_failure_policy
    COMMAND mca_llama_load_failure_policy_test
)

add_executable(
    mca_mnn_embedding_bundle_policy_test
    mnn_embedding_bundle_policy_test.cpp
)
target_compile_features(mca_mnn_embedding_bundle_policy_test PRIVATE cxx_std_17)

add_test(
    NAME mca_mnn_embedding_bundle_policy
    COMMAND mca_mnn_embedding_bundle_policy_test
)

add_executable(
    mca_mnn_diffusion_batch_policy_test
    mnn_diffusion_batch_policy_test.cpp
)
target_compile_features(mca_mnn_diffusion_batch_policy_test PRIVATE cxx_std_17)

add_test(
    NAME mca_mnn_diffusion_batch_policy
    COMMAND mca_mnn_diffusion_batch_policy_test
)

add_executable(
    mca_mnn_diffusion_batch_source_contract_test
    mnn_diffusion_batch_source_contract_test.cpp
)
target_compile_features(mca_mnn_diffusion_batch_source_contract_test PRIVATE cxx_std_17)

add_test(
    NAME mca_mnn_diffusion_batch_source_contract
    COMMAND mca_mnn_diffusion_batch_source_contract_test
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mnn_native_engine.cpp"
)

add_executable(
    mca_mnn_diffusion_backend_source_contract_test
    mnn_diffusion_backend_source_contract_test.cpp
)
target_compile_features(mca_mnn_diffusion_backend_source_contract_test PRIVATE cxx_std_17)

add_test(
    NAME mca_mnn_diffusion_backend_source_contract
    COMMAND mca_mnn_diffusion_backend_source_contract_test
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mnn_native_engine.cpp"
)

add_executable(
    mca_mnn_chat_debug_trace_source_contract_test
    mnn_chat_debug_trace_source_contract_test.cpp
)
target_compile_features(mca_mnn_chat_debug_trace_source_contract_test PRIVATE cxx_std_17)

add_test(
    NAME mca_mnn_chat_debug_trace_source_contract
    COMMAND mca_mnn_chat_debug_trace_source_contract_test
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mnn_native_engine.cpp"
)

add_executable(
    mca_mnn_sampler_source_contract_test
    mnn_sampler_source_contract_test.cpp
)
target_compile_features(mca_mnn_sampler_source_contract_test PRIVATE cxx_std_17)

add_test(
    NAME mca_mnn_sampler_source_contract
    COMMAND mca_mnn_sampler_source_contract_test
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mnn_native_engine.cpp"
        "${MCA_REPOSITORY_ROOT}/third_party/MNN/transformers/llm/engine/src/sampler.cpp"
)

add_executable(
    mca_llama_prefix_cache_policy_test
    llama_prefix_cache_policy_test.cpp
)
target_compile_features(mca_llama_prefix_cache_policy_test PRIVATE cxx_std_17)

add_test(
    NAME mca_llama_prefix_cache_policy
    COMMAND mca_llama_prefix_cache_policy_test
)

add_executable(
    mca_native_generation_cache_contract_test
    native_generation_cache_contract_test.cpp
)
target_compile_features(mca_native_generation_cache_contract_test PRIVATE cxx_std_17)

add_test(
    NAME mca_native_generation_cache_contract
    COMMAND mca_native_generation_cache_contract_test
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/native_engine.cpp"
)

add_executable(
    mca_persistent_prefix_cache_equivalence_test
    persistent_prefix_cache_equivalence_test.cpp
)
target_compile_features(mca_persistent_prefix_cache_equivalence_test PRIVATE cxx_std_17)
target_link_libraries(mca_persistent_prefix_cache_equivalence_test PRIVATE llama)

add_test(
    NAME mca_persistent_prefix_cache_equivalence
    COMMAND mca_persistent_prefix_cache_equivalence_test --model "${MCA_LLAMA_PREFIX_TEST_MODEL}"
)
# A real model is deliberately supplied by CI or a local validation run, never
# committed as a binary fixture. The executable returns 77 when none is given.
set_tests_properties(mca_persistent_prefix_cache_equivalence PROPERTIES SKIP_RETURN_CODE 77)
