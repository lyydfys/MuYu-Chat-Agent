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
    mca_stq1_0_native_smoke_test
    stq1_0_native_smoke_test.cpp
)
target_compile_features(mca_stq1_0_native_smoke_test PRIVATE cxx_std_17)
target_link_libraries(mca_stq1_0_native_smoke_test PRIVATE ggml)

add_test(
    NAME mca_stq1_0_native_smoke
    COMMAND mca_stq1_0_native_smoke_test
)

add_executable(
    mca_mnn_embedding_bundle_policy_test
    mnn_embedding_bundle_policy_test.cpp
)

add_executable(mca_mnn_bundle_path_policy_test mnn_bundle_path_policy_test.cpp)
target_compile_features(mca_mnn_bundle_path_policy_test PRIVATE cxx_std_17)
add_test(NAME mca_mnn_bundle_path_policy COMMAND mca_mnn_bundle_path_policy_test)
target_compile_features(mca_mnn_embedding_bundle_policy_test PRIVATE cxx_std_17)

add_test(
    NAME mca_mnn_embedding_bundle_policy
    COMMAND mca_mnn_embedding_bundle_policy_test
)

add_executable(
    mca_mnn_vision_path_policy_test
    mnn_vision_path_policy_test.cpp
)
target_compile_features(mca_mnn_vision_path_policy_test PRIVATE cxx_std_17)

add_test(
    NAME mca_mnn_vision_path_policy
    COMMAND mca_mnn_vision_path_policy_test
)

add_executable(
    mca_opencl_dispatch_source_contract_test
    opencl_dispatch_source_contract_test.cpp
)
target_compile_features(mca_opencl_dispatch_source_contract_test PRIVATE cxx_std_17)

add_test(
    NAME mca_opencl_dispatch_source_contract
    COMMAND mca_opencl_dispatch_source_contract_test
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca_opencl_dispatch.cpp"
)

if(UNIX OR ANDROID)
    add_executable(mca_opencl_execution_evidence_test opencl_execution_evidence_test.cpp)
    target_compile_features(mca_opencl_execution_evidence_test PRIVATE cxx_std_17)
    target_compile_options(mca_opencl_execution_evidence_test PRIVATE -UNDEBUG)
    target_include_directories(mca_opencl_execution_evidence_test PRIVATE
        "${MCA_REPOSITORY_ROOT}/third_party/MNN/3rd_party/OpenCLHeaders")
    target_link_libraries(mca_opencl_execution_evidence_test PRIVATE ${CMAKE_DL_LIBS})
    add_test(NAME mca_opencl_execution_evidence COMMAND mca_opencl_execution_evidence_test)
endif()

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
    mca_qwen_mnn_backend_resolution_test
    qwen_mnn_backend_resolution_test.cpp
)
target_compile_features(mca_qwen_mnn_backend_resolution_test PRIVATE cxx_std_17)
target_include_directories(
    mca_qwen_mnn_backend_resolution_test
    PRIVATE "${MCA_REPOSITORY_ROOT}/vendor/qwen-image-2.1/include"
)
add_test(
    NAME mca_qwen_mnn_backend_resolution
    COMMAND mca_qwen_mnn_backend_resolution_test
)

add_executable(
    mca_qnn_soc_htp_mapping_test
    qnn_soc_htp_mapping_test.cpp
)
target_compile_features(mca_qnn_soc_htp_mapping_test PRIVATE cxx_std_17)
target_include_directories(
    mca_qnn_soc_htp_mapping_test
    PRIVATE "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp"
)
add_test(
    NAME mca_qnn_soc_htp_mapping
    COMMAND mca_qnn_soc_htp_mapping_test
)

add_executable(
    mca_mnn_diffusion_batch_source_contract_test
    mnn_diffusion_batch_source_contract_test.cpp
)
target_compile_features(mca_mnn_diffusion_batch_source_contract_test PRIVATE cxx_std_17)

add_test(
    NAME mca_mnn_diffusion_batch_source_contract
    COMMAND mca_mnn_diffusion_batch_source_contract_test
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/mnn/mnn_sd15_direct.cpp"
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/mnn/mnn_diffusion_tokenize.cpp"
)

add_executable(
    mca_mnn_diffusion_backend_source_contract_test
    mnn_diffusion_backend_source_contract_test.cpp
)
target_compile_features(mca_mnn_diffusion_backend_source_contract_test PRIVATE cxx_std_17)

add_test(
    NAME mca_mnn_diffusion_backend_source_contract
    COMMAND mca_mnn_diffusion_backend_source_contract_test
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/mnn/mnn_sd15_direct.cpp"
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/mnn/mnn_backend_predicates.cpp"
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
    mca_mnn_sdxl_pooled_source_contract_test
    mnn_sdxl_pooled_source_contract_test.cpp
)
target_compile_features(mca_mnn_sdxl_pooled_source_contract_test PRIVATE cxx_std_17)

add_test(
    NAME mca_mnn_sdxl_pooled_source_contract
    COMMAND mca_mnn_sdxl_pooled_source_contract_test
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
    mca_mnn_stop_cancellation_source_contract_test
    mnn_stop_cancellation_source_contract_test.cpp
)
target_compile_features(mca_mnn_stop_cancellation_source_contract_test PRIVATE cxx_std_17)

add_test(
    NAME mca_mnn_stop_cancellation_source_contract
    COMMAND mca_mnn_stop_cancellation_source_contract_test
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mnn_native_engine.cpp"
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
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/native_engine_prefix_cache.cpp"
)

# P1-01 guard: drives the real mca/llm/llama_speculative_engine.cpp state machine against scripted
# llama.cpp doubles (no GGUF model needed) and pins the native_engine.cpp session-owner borrow
# protocol.  It deliberately does NOT link llama/common: fixtures/llama_test_doubles.cpp supplies
# the llama.cpp symbols the production MTP translation unit references.
# Added by T01 at T03's request (T01 owns this target graph; T03 owns the test source).
add_executable(
    mca_llama_speculative_owner_test
    llama_speculative_owner_test.cpp
    fixtures/llama_test_doubles.cpp
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/llm/llama_speculative_engine.cpp"
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/request/llama_request_parser.cpp"
)
target_compile_features(mca_llama_speculative_owner_test PRIVATE cxx_std_17)
target_compile_definitions(mca_llama_speculative_owner_test PRIVATE MCA_WITH_LLAMA_CPP=1)
target_include_directories(mca_llama_speculative_owner_test PRIVATE
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp"
    "${MCA_REPOSITORY_ROOT}/core/native/src/test/cpp"
    "${MCA_LLAMA_SOURCE}/include"
    "${MCA_LLAMA_SOURCE}/ggml/include"
    "${MCA_LLAMA_SOURCE}/common"
    "${MCA_LLAMA_SOURCE}/vendor")
target_compile_options(mca_llama_speculative_owner_test PRIVATE
    "$<$<CXX_COMPILER_ID:MSVC>:/utf-8>"
    "$<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-UNDEBUG>")
add_test(
    NAME mca_llama_speculative_owner
    COMMAND mca_llama_speculative_owner_test
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/native_engine.cpp"
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/native_engine_speculative.cpp"
)

# T07 (test-trust) behavioural test: calls the real production
# mca::mnn::format_mnn_stats_json and asserts the VALUE of the three debug keys,
# plus a mutated negative control.  Registered by T01 at T07's request (T01 owns
# this target graph; T07 owns the test source).  It compiles the production
# translation unit directly so the assertion runs against real code rather than
# a re-implementation.
add_executable(
    mnn_debug_trace_stats_json_behavior_test
    mnn_debug_trace_stats_json_behavior_test.cpp
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/mnn/mnn_stats_collector.cpp")
target_compile_features(mnn_debug_trace_stats_json_behavior_test PRIVATE cxx_std_17)
target_include_directories(mnn_debug_trace_stats_json_behavior_test PRIVATE
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp"
    "${MCA_REPOSITORY_ROOT}/third_party/llama.cpp/vendor")
target_compile_options(mnn_debug_trace_stats_json_behavior_test PRIVATE
    "$<$<CXX_COMPILER_ID:MSVC>:/utf-8>"
    "$<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-UNDEBUG>")
add_test(NAME mnn_debug_trace_stats_json_behavior COMMAND mnn_debug_trace_stats_json_behavior_test)

# T2 batch-1 (lane A) behavioural test: calls the real production
# mca::mnn::mnn_sd15_unet_backend_unsupported predicate (relocated from the
# monolith into mca/mnn/mnn_backend_predicates.cpp) and asserts its value
# semantics plus negative controls.
add_executable(
    mnn_backend_predicates_behavior_test
    mnn_backend_predicates_behavior_test.cpp
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/mnn/mnn_backend_predicates.cpp")
target_compile_features(mnn_backend_predicates_behavior_test PRIVATE cxx_std_17)
target_compile_definitions(mnn_backend_predicates_behavior_test PRIVATE MCA_WITH_MNN_DIFFUSION=1)
target_include_directories(mnn_backend_predicates_behavior_test PRIVATE
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp")
target_compile_options(mnn_backend_predicates_behavior_test PRIVATE
    "$<$<CXX_COMPILER_ID:MSVC>:/utf-8>"
    "$<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-UNDEBUG>")
add_test(NAME mnn_backend_predicates_behavior COMMAND mnn_backend_predicates_behavior_test)

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

add_executable(mca_native_lifecycle_chain_test
    runtime/test_lifecycle_chain.cpp
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/runtime/process_runtime_host.cpp"
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/runtime/backend_runtime.cpp"
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/runtime/engine.cpp"
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/runtime/engine_session.cpp"
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/request/native_request_context.cpp"
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/callback/callback_router.cpp"
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/mnn/mnn_request.cpp"
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/common/logging.cpp"
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/common/safe_path.cpp"
)
target_compile_features(mca_native_lifecycle_chain_test PRIVATE cxx_std_17)
target_include_directories(mca_native_lifecycle_chain_test PRIVATE
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp")
target_compile_options(mca_native_lifecycle_chain_test PRIVATE
    "$<$<CXX_COMPILER_ID:MSVC>:/UNDEBUG>"
    "$<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-UNDEBUG>")
add_test(NAME mca_native_lifecycle_chain COMMAND mca_native_lifecycle_chain_test)

# ---------------------------------------------------------------------------
# T01-E (review 4.3 / 5.1): wire the two SD source-contract tests that existed
# but were registered in no CMakeLists, plus T07's behavioural prompt-cache test.
#
# IMPORTANT - read-set synthesis is a prerequisite, not a detail. These two SD
# tests assert needles that are spread across the SD modules; with a single-file
# read set most assertions are vacuous while the test still "passes". T07
# synthesises the read set inside the tests (image_input: 6 modules,
# execution_evidence: 9 modules) and keeps every require_absent anchored to the
# bridge base file. The tests FAIL when a synthesised module is missing, which is
# the anti-vacuity property we want.
#
# The execution-evidence test reads the pinned stable-diffusion.cpp submodule
# directly, so its paths are recorded here as a version dependency: a submodule
# bump must not silently turn those needles into no-ops.
# ---------------------------------------------------------------------------

add_executable(mca_sd_bridge_image_input_contract_test
        "${MCA_REPOSITORY_ROOT}/core/sd-native/src/test/cpp/stable_diffusion_bridge_image_input_contract_test.cpp")
target_compile_features(mca_sd_bridge_image_input_contract_test PRIVATE cxx_std_17)
target_compile_options(mca_sd_bridge_image_input_contract_test PRIVATE
        "$<$<CXX_COMPILER_ID:MSVC>:/UNDEBUG>"
        "$<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-UNDEBUG>")
add_test(NAME mca_sd_bridge_image_input_contract
        COMMAND mca_sd_bridge_image_input_contract_test
        "${MCA_REPOSITORY_ROOT}/core/sd-native/src/main/cpp/stable_diffusion_bridge.cpp")

add_executable(mca_sd_execution_evidence_contract_test
        "${MCA_REPOSITORY_ROOT}/core/sd-native/src/test/cpp/stable_diffusion_execution_evidence_contract_test.cpp")
target_compile_features(mca_sd_execution_evidence_contract_test PRIVATE cxx_std_17)
target_compile_options(mca_sd_execution_evidence_contract_test PRIVATE
        "$<$<CXX_COMPILER_ID:MSVC>:/UNDEBUG>"
        "$<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-UNDEBUG>")
add_test(NAME mca_sd_execution_evidence_contract
        COMMAND mca_sd_execution_evidence_contract_test
        "${MCA_REPOSITORY_ROOT}/third_party/stable-diffusion.cpp/include/stable-diffusion.h"
        "${MCA_REPOSITORY_ROOT}/third_party/stable-diffusion.cpp/src/conditioner.hpp"
        "${MCA_REPOSITORY_ROOT}/third_party/stable-diffusion.cpp/src/stable-diffusion.cpp"
        "${MCA_REPOSITORY_ROOT}/core/sd-native/src/main/cpp/stable_diffusion_bridge.cpp"
        "${MCA_REPOSITORY_ROOT}/third_party/stable-diffusion.cpp/src/vae.hpp")

# T07's behavioural Tier A test for the MNN prompt cache. Built on the lightweight
# entry (no add_subdirectory(MNN)): the four sources below are what it needs, and
# mnn_template_parser.cpp is required because it defines mnn_chat_messages_prefix.
# No SKIP_RETURN_CODE on purpose - this test must actually run.
add_executable(mnn_prompt_cache_behavior_test
        "${MCA_REPOSITORY_ROOT}/core/native/src/test/cpp/mnn_prompt_cache_behavior_test.cpp"
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/mnn/mnn_prompt_cache_manager.cpp"
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/mnn/mnn_stats_collector.cpp"
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp/mca/mnn/mnn_template_parser.cpp")
target_compile_features(mnn_prompt_cache_behavior_test PRIVATE cxx_std_17)
target_compile_options(mnn_prompt_cache_behavior_test PRIVATE
        "$<$<CXX_COMPILER_ID:MSVC>:/UNDEBUG>"
        "$<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-UNDEBUG>")
target_include_directories(mnn_prompt_cache_behavior_test PRIVATE
        "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp"
        "${MCA_REPOSITORY_ROOT}/third_party/llama.cpp/vendor")
add_test(NAME mnn_prompt_cache_behavior COMMAND mnn_prompt_cache_behavior_test)

# E04 (pure migration, L1): behavioural test for mca/mnn/mnn_json_options.hpp.  The JSON
# option helpers were moved verbatim out of mnn_native_engine.cpp; this compiles the real
# production header and pins defaults/null/coercion/range/error-string/merge/advanced_json
# behaviour, including degenerate negative controls.
add_executable(
    mca_mnn_json_options_behavior_test
    mnn_json_options_behavior_test.cpp)
target_compile_features(mca_mnn_json_options_behavior_test PRIVATE cxx_std_17)
target_include_directories(mca_mnn_json_options_behavior_test PRIVATE
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp"
    "${MCA_REPOSITORY_ROOT}/third_party/llama.cpp/vendor")
target_compile_options(mca_mnn_json_options_behavior_test PRIVATE
    "$<$<CXX_COMPILER_ID:MSVC>:/utf-8>"
    "$<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-UNDEBUG>")
add_test(NAME mca_mnn_json_options_behavior COMMAND mca_mnn_json_options_behavior_test)
# E08a (pure migration, L1): behavioural test for mca/mnn/mnn_numeric_convert.hpp.  The binary
# read and half-precision conversion helpers were moved verbatim out of mnn_native_engine.cpp;
# this compiles the real production header and pins exhaustive fp16/bf16 value contracts plus
# temp-file round-trip behaviour for read_binary_bytes.
add_executable(
    mca_mnn_numeric_convert_behavior_test
    mnn_numeric_convert_behavior_test.cpp)
target_compile_features(mca_mnn_numeric_convert_behavior_test PRIVATE cxx_std_17)
target_include_directories(mca_mnn_numeric_convert_behavior_test PRIVATE
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp")
target_compile_options(mca_mnn_numeric_convert_behavior_test PRIVATE
    "$<$<CXX_COMPILER_ID:MSVC>:/utf-8>"
    "$<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-UNDEBUG>")
add_test(NAME mca_mnn_numeric_convert_behavior COMMAND mca_mnn_numeric_convert_behavior_test)
# E08c (pure migration, L1): behavioural test for mca/mnn/mnn_diffusion_asset_contract.hpp.  The
# family predicates, the two failure payloads and the missing-component contract were moved
# verbatim out of mnn_native_engine.cpp; this compiles the real production header and pins the
# classification, exact JSON payloads and the temporary-directory missing-component contract.
add_executable(
    mca_mnn_diffusion_asset_contract_behavior_test
    mnn_diffusion_asset_contract_behavior_test.cpp)
target_compile_features(mca_mnn_diffusion_asset_contract_behavior_test PRIVATE cxx_std_17)
target_include_directories(mca_mnn_diffusion_asset_contract_behavior_test PRIVATE
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp"
    "${MCA_REPOSITORY_ROOT}/third_party/llama.cpp/vendor")
target_compile_options(mca_mnn_diffusion_asset_contract_behavior_test PRIVATE
    "$<$<CXX_COMPILER_ID:MSVC>:/utf-8>"
    "$<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-UNDEBUG>")
add_test(NAME mca_mnn_diffusion_asset_contract_behavior
         COMMAND mca_mnn_diffusion_asset_contract_behavior_test)

# E08k: host behaviour test for the revived debug-trace state machine
# (mnn_debug_trace.cpp). Compiles the real production header and pins the enable/truncation
# contract (4096-byte raw/prompt caps, 64-token cap, start clamping, JSON id serialization).
add_executable(
    mca_mnn_debug_trace_behavior_test
    mnn_debug_trace_behavior_test.cpp)
target_compile_features(mca_mnn_debug_trace_behavior_test PRIVATE cxx_std_17)
target_include_directories(mca_mnn_debug_trace_behavior_test PRIVATE
    "${MCA_REPOSITORY_ROOT}/core/native/src/main/cpp"
    "${MCA_REPOSITORY_ROOT}/third_party/llama.cpp/vendor")
target_compile_options(mca_mnn_debug_trace_behavior_test PRIVATE
    "$<$<CXX_COMPILER_ID:MSVC>:/utf-8>"
    "$<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-UNDEBUG>")
add_test(NAME mca_mnn_debug_trace_behavior
         COMMAND mca_mnn_debug_trace_behavior_test)
