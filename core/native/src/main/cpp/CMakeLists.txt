cmake_minimum_required(VERSION 3.22.1)
project(mca_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# A clean arm64 llama.cpp build has several very large translation units.
# Unbounded Ninja parallelism can crash the Windows NDK clang frontend before
# an APK is produced, so keep compilation parallel but memory-bounded.
set_property(GLOBAL PROPERTY JOB_POOLS mca_compile_pool=2 mca_link_pool=1)
set(CMAKE_JOB_POOL_COMPILE mca_compile_pool)
set(CMAKE_JOB_POOL_LINK mca_link_pool)

option(MCA_WITH_LLAMA_CPP "Build MCA native backend with llama.cpp" ON)
option(MCA_WITH_LLAMA_OPENCL "Build the llama.cpp OpenCL backend from the pinned source" ON)
option(MCA_WITH_MNN_DIFFUSION "Build MCA native backend with MNN Stable Diffusion runner" ON)
option(MCA_WITH_TOKENIZERS_CPP "Build the complete tokenizer.json backend" ON)
option(MCA_QNN_TYPED_BINDINGS_REQUIRED "Fail configuration instead of building an unusable QNN graph stub" ON)
set(MCA_MNN_SOURCE_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/MNN" CACHE PATH "Official MNN source root")
set(MCA_MNN_ANDROID_BUILD_ROOT "${MCA_MNN_SOURCE_ROOT}/project/android/build_64" CACHE PATH "Official MNN Android build root")
set(MCA_QNN_SDK_ROOT "" CACHE PATH "Qualcomm AI Runtime / QNN SDK root")
set(MCA_TOKENIZERS_CPP_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/tokenizers-cpp" CACHE PATH "tokenizers-cpp source root")
set(MCA_OPENCL_HEADERS_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/MNN/3rd_party/OpenCLHeaders" CACHE PATH "OpenCL headers used by the Android llama.cpp backend")

# Android exposes the vendor OpenCL implementation as a platform native
# library.  There is no NDK import library to link against, so the backend is
# compiled against the Khronos headers and resolves the platform library at
# runtime (see FindOpenCL.cmake and native_engine.cpp).  Keep x86_64 builds
# CPU-only because Android OpenCL is only available on the arm64 device path.
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/cmake")

if(DEFINED ANDROID_ABI)
    message(STATUS "MCA Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
        if(MCA_WITH_LLAMA_OPENCL)
            set(GGML_OPENCL ON CACHE BOOL "ggml: use OpenCL" FORCE)
            # The pinned MNN OpenCLHeaders checkout targets OpenCL 2.2.  The
            # ggml 3.0 large-buffer extension is optional; keep it disabled
            # until a 3.0 header set is vendored so the normal clCreateBuffer
            # path remains portable across Adreno driver generations.
            set(GGML_OPENCL_TARGET_VERSION "220" CACHE STRING "ggml: OpenCL API target" FORCE)
        else()
            set(GGML_OPENCL OFF CACHE BOOL "ggml: use OpenCL" FORCE)
        endif()
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
        set(GGML_OPENCL OFF CACHE BOOL "ggml: use OpenCL" FORCE)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

add_library(mca_contract INTERFACE)
target_include_directories(mca_contract INTERFACE
        "${CMAKE_CURRENT_LIST_DIR}")

add_library(mca_common STATIC
        mca/common/safe_path.cpp
        mca/common/logging.cpp)
target_include_directories(mca_common PUBLIC
        "${CMAKE_CURRENT_LIST_DIR}")
target_link_libraries(mca_common PUBLIC mca_contract)
if(ANDROID)
    target_link_libraries(mca_common PRIVATE log)
endif()

add_library(mca_observability STATIC
        mca/common/observability.cpp)
target_include_directories(mca_observability PUBLIC
        "${CMAKE_CURRENT_LIST_DIR}")
target_link_libraries(mca_observability PUBLIC mca_common mca_contract)

add_library(mca_jni_boundary STATIC
        mca/jni/jni_converters.cpp)
target_include_directories(mca_jni_boundary PUBLIC
        "${CMAKE_CURRENT_LIST_DIR}")
target_link_libraries(mca_jni_boundary PUBLIC mca_common mca_contract)

add_library(mca_runtime_lifecycle STATIC
        mca/runtime/process_runtime_host.cpp
        mca/runtime/backend_runtime.cpp
        mca/runtime/engine.cpp
        mca/runtime/engine_session.cpp
        mca/request/native_request_context.cpp
        mca/request/llama_request_parser.cpp
        mca/callback/callback_router.cpp)
target_include_directories(mca_runtime_lifecycle PUBLIC
        "${CMAKE_CURRENT_LIST_DIR}")
target_link_libraries(mca_runtime_lifecycle PUBLIC mca_common mca_contract mca_jni_boundary)

# mca_native sources.
#
# Listed inline, in full, on purpose: a text-parsing gate must be able to see every
# translation unit. An earlier revision assembled this list through a helper variable
# plus list(APPEND ...) so that the ON build stayed byte-identical; the variable made
# the list invisible to the source-set gate (review finding #1) and that feedback loop
# is exactly what this comment used to encode. Source order still matters for the
# linked bytes, so the ON sequence below is the original one, unchanged.
#
# On the acceptance condition: "the produced .so still hashes to the old value" is NOT
# a correctness criterion - it would let a stale artifact stand in for current source,
# which the audit forbids. Reproducibility is the property that matters (same source ->
# same bytes on a repeat build), and parity with a historical artifact may be reported
# alongside functional verification but never as the acceptance condition on its own.
add_library(mca_native SHARED
        native_engine.cpp
        native_engine_runtime_state.cpp
        native_engine_backend_loader.cpp
        native_engine_prefix_cache.cpp
        native_engine_speculative.cpp
        native_engine_stats_gpu.cpp
        mca/llm/llm_prefill_tracker.cpp
        mca/llm/llm_prefix_cache_manager.cpp
        mca/llm/llm_stats_collector.cpp
        mca/llm/llm_runtime_config.cpp
        mca/llm/llm_backend_loader.cpp
        mca/llm/llm_turn_cache.cpp
        mca/llm/llm_prompt_formatter.cpp
        mca/llm/llama_context_manager.cpp
        mca/llm/llama_gpu_evaluator.cpp
        mca/llm/llama_speculative_engine.cpp
        mca/llm/llama_completion_planner.cpp
        mca/llm/llama_model_loader.cpp)
# Mode-0 compile boundary (restored): the llama-dependent units include llama/ggml
# headers without a guarded fallback, so they must not be compiled when llama.cpp is
# disabled. This mirrors the pre-extraction layout, where mca_native was a single
# native_engine.cpp that guarded its own includes, and it is why the historical OFF
# branch (message(WARNING ...) + MCA_WITH_LLAMA_CPP=0 + android/log) below is
# sufficient again. Verified empirically: exactly these six plus native_engine.cpp were
# the TUs that failed with 'file not found' in a real OFF build; native_engine.cpp stays
# unconditional because T03 restores its guards.
# (T2B16) The five native_engine_* companions above follow the same discipline: every
# llama-only region keeps its MCA_WITH_LLAMA_CPP guard, so OFF mode compiles them from
# this same list (verified with a dedicated OFF-mode compile pass).
#
# The removal happens here rather than by conditionalising the add_library call so that
# the ON path keeps one flat, parseable, correctly-ordered list.
if(NOT MCA_WITH_LLAMA_CPP)
    get_target_property(MCA_NATIVE_SOURCES_RAW mca_native SOURCES)
    set(MCA_NATIVE_SOURCES_KEPT ${MCA_NATIVE_SOURCES_RAW})
    list(FILTER MCA_NATIVE_SOURCES_KEPT EXCLUDE REGEX
            "mca/llm/(llm_runtime_config|llm_backend_loader|llama_gpu_evaluator|llama_speculative_engine|llama_completion_planner|llama_model_loader)\\.cpp$")
    list(LENGTH MCA_NATIVE_SOURCES_RAW MCA_NATIVE_SRC_BEFORE)
    list(LENGTH MCA_NATIVE_SOURCES_KEPT MCA_NATIVE_SRC_AFTER)
    if(MCA_NATIVE_SRC_AFTER EQUAL MCA_NATIVE_SRC_BEFORE)
        message(FATAL_ERROR "MCA_WITH_LLAMA_CPP=OFF: expected to drop the llama-dependent mca/llm units but the source list was unchanged.")
    endif()
    set_property(TARGET mca_native PROPERTY SOURCES ${MCA_NATIVE_SOURCES_KEPT})
    message(STATUS "MCA mode 0: mca_native sources ${MCA_NATIVE_SRC_BEFORE} -> ${MCA_NATIVE_SRC_AFTER} (llama-dependent units removed)")
endif()
# T2B16: the split native_engine family gained external-linkage helpers; keep every symbol
# of these six units hidden so the .so export surface stays the 14 JNI entries (JNIEXPORT
# carries visibility("default") and is unaffected; verified by the object/
# synthetic-.so symbol audit).
set_source_files_properties(
        native_engine.cpp
        native_engine_runtime_state.cpp
        native_engine_backend_loader.cpp
        native_engine_prefix_cache.cpp
        native_engine_speculative.cpp
        native_engine_stats_gpu.cpp
        PROPERTIES COMPILE_OPTIONS "-fvisibility=hidden")
target_link_libraries(mca_native PRIVATE mca_contract mca_common mca_observability mca_jni_boundary mca_runtime_lifecycle)
add_library(mca_prompt_handoff SHARED
        mnn_qnn_prompt_handoff.cpp
        mca/prompt/mnn_qnn_prompt_gateway.cpp)
target_compile_definitions(mca_prompt_handoff PRIVATE MCA_PROMPT_HANDOFF_BUILDING=1)
target_link_libraries(mca_prompt_handoff PRIVATE mca_contract mca_common)

add_library(mca_qnn_runtime STATIC
        mca/qnn/qnn_runtime_loader.cpp
        mca/qnn/qnn_capability_probe.cpp
        mca/qnn/qnn_backend_owner.cpp
        mca/qnn/qnn_engine.cpp
        mca/qnn/qnn_session.cpp
        mca/qnn/qnn_request.cpp
        mca/qnn/qnn_preview_manager.cpp
        mca/qnn/qnn_bundle_files.cpp
        mca/qnn/qnn_sdxl_phase_support.cpp
        mca/qnn/qnn_tensor_codec.cpp
        mca/qnn/qnn_device_support.cpp
        mca/qnn/qnn_tensor_inspect.cpp
        mca/qnn/qnn_ultrafix_handler.cpp
        mca/qnn/qnn_inpaint_blend.cpp
        mca/qnn/qnn_contract_parser.cpp
        # qnn_context_binary_cache.cpp is deliberately NOT compiled: its header is included only
        # by itself and its own test, while the symbols it defines (ScopedReadOnlyMmap,
        # read_binary_file, select_qnn_system_interface, append_graph_names,
        # qnn_context_load_failure_message, inspect_qnn_context_binary_metadata) are duplicated
        # verbatim in qnn_system_symbol_loader.{hpp,cpp} -- which is the pair production actually
        # includes (qnn_executable_graph.cpp, qnn_native_bridge.cpp).  Linking both translation
        # units put several strong definitions of the same symbol in mca_qnn_runtime, so which
        # body won depended on link order.  The file is kept on disk, uncompiled, until its
        # remaining unique content is either folded into qnn_system_symbol_loader or deleted.
        mca/qnn/qnn_tensor_quantizer.cpp
        mca/qnn/qnn_graph_executor_helper.cpp
        mca/qnn/qnn_bundle_manifest.cpp
        mca/qnn/qnn_system_symbol_loader.cpp
        mca/qnn/qnn_htp_controller.cpp
        mca/qnn/qnn_tensor_memory.cpp
        mca/qnn/qnn_executable_graph.cpp
        mca/qnn/qnn_conditioning_pipeline.cpp
        mca/qnn/qnn_model_execution_pipeline.cpp
        mca/qnn/qnn_bridge_payloads.cpp
        mca/qnn/qnn_semantic_pipeline.cpp
        mca/qnn/qnn_semantic_request.cpp
        mca/qnn/qnn_semantic_conditioning.cpp
        mca/qnn/qnn_semantic_sdxl.cpp
        mca/qnn/qnn_semantic_stage_runner.cpp
        mca/qnn/qnn_semantic_output.cpp
        mca/qnn/qnn_semantic_evidence.cpp
        mca/qnn/qnn_coherent_runtime_session.cpp
        mca/qnn/qnn_graph_lifecycle.cpp
        mca/qnn/qnn_graph_smoke.cpp
        mca/qnn/qnn_graph_runtime_support.cpp
        mca/qnn/qnn_conditioning_token_payload.cpp
        mca/qnn/qnn_conditioning_json_fields.cpp
        mca/qnn/qnn_conditioning_asset_evidence.cpp
        mca/qnn/qnn_conditioning_asset_capture.cpp
        mca/qnn/qnn_conditioning_handoff_binding.cpp
        mca/qnn/qnn_model_execution_unet.cpp
        mca/qnn/qnn_model_execution_ultrafix.cpp
        mca/qnn/qnn_model_execution_sdxl.cpp
        mca/qnn/qnn_model_execution_vae_decode.cpp
        mca/qnn/qnn_model_execution_img2img_encoder.cpp
        mca/qnn/qnn_sdxl_unet_phase.cpp
        mca/qnn/qnn_sdxl_unet_tiling.cpp
        mca/qnn/qnn_sdxl_unet_execution.cpp)
target_include_directories(mca_qnn_runtime PUBLIC
        "${CMAKE_CURRENT_LIST_DIR}")
target_link_libraries(mca_qnn_runtime PUBLIC mca_common mca_contract mca_runtime_lifecycle)
# T2B14/T2B16 TU splits widened this static library's exported symbol face (arm64
# libmca_qnn_native.so +1,131,968 B from debug-20261013-153623-334Z to
# debug-20261013-165122-963Z).  Keep implementation symbols out of the shared object's
# dynamic table; the JNI entries in mca_qnn_native carry JNIEXPORT (default visibility),
# so the DSO's real external surface is unaffected.  Evidence: T2B16 b-REPORT.md.
set_target_properties(mca_qnn_runtime PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        C_VISIBILITY_PRESET hidden)
# DWARF amplification offset (T2B16, measured): each split-derived TU re-emits per-CU
# debug metadata (.debug_info/.debug_loc/.debug_ranges/.debug_line) even though the code
# is byte-identical, so the packaged libmca_qnn_native.so grew while its .text shrank.
# The TUs produced by the T2B14/T2B16 splits below therefore carry line-table debug
# (-g1); every other TU keeps full -g.  Size/export tables: b-REPORT.md visibility
# section.  This is a debug-fidelity offset only: code bytes and symbols are unchanged.
set_source_files_properties(
        mca/qnn/qnn_semantic_request.cpp
        mca/qnn/qnn_semantic_conditioning.cpp
        mca/qnn/qnn_semantic_sdxl.cpp
        mca/qnn/qnn_semantic_stage_runner.cpp
        mca/qnn/qnn_semantic_output.cpp
        mca/qnn/qnn_semantic_evidence.cpp
        mca/qnn/qnn_coherent_runtime_session.cpp
        mca/qnn/qnn_graph_lifecycle.cpp
        mca/qnn/qnn_graph_smoke.cpp
        mca/qnn/qnn_graph_runtime_support.cpp
        mca/qnn/qnn_conditioning_token_payload.cpp
        mca/qnn/qnn_conditioning_json_fields.cpp
        mca/qnn/qnn_conditioning_asset_evidence.cpp
        mca/qnn/qnn_conditioning_asset_capture.cpp
        mca/qnn/qnn_conditioning_handoff_binding.cpp
        mca/qnn/qnn_sdxl_unet_phase.cpp
        mca/qnn/qnn_sdxl_unet_tiling.cpp
        mca/qnn/qnn_sdxl_unet_execution.cpp
        PROPERTIES COMPILE_OPTIONS "-g1")

add_library(mca_mnn_runtime STATIC
        mca/mnn/mnn_runtime_loader.cpp
        mca/mnn/mnn_backend_owner.cpp
        mca/mnn/mnn_engine.cpp
        mca/mnn/mnn_session.cpp
        mca/mnn/mnn_request.cpp
        mca/mnn/mnn_stream_router.cpp
        mca/mnn/mnn_token_emitter.cpp
        mca/mnn/mnn_bundle_config.cpp
        mca/mnn/mnn_template_parser.cpp
        mca/mnn/mnn_vision_adapter.cpp
        mca/mnn/mnn_stats_collector.cpp
        mca/mnn/mnn_prompt_cache_manager.cpp
        mca/mnn/mnn_protocol_parser.cpp
        mca/mnn/mnn_clip_tokenizer.cpp
        mca/mnn/mnn_file_utils.cpp
        mca/mnn/mnn_sampling_strategy.cpp
        mca/mnn/mnn_token_boundary_guard.cpp)
target_include_directories(mca_mnn_runtime PUBLIC
        "${CMAKE_CURRENT_LIST_DIR}"
        "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp/vendor")
target_link_libraries(mca_mnn_runtime PUBLIC mca_common mca_contract mca_runtime_lifecycle)

add_library(mca_mnn_native SHARED mnn_native_engine.cpp)
add_library(mca_qnn_native SHARED qnn_native_bridge.cpp)
target_link_libraries(mca_mnn_native PRIVATE mca_prompt_handoff mca_contract mca_common mca_observability mca_jni_boundary mca_runtime_lifecycle mca_mnn_runtime)
target_link_libraries(mca_qnn_native PRIVATE mca_prompt_handoff mca_contract mca_common mca_observability mca_jni_boundary mca_runtime_lifecycle mca_qnn_runtime)
target_sources(mca_mnn_native PRIVATE
        image_conditioning.cpp
        textual_inversion_conditioning.cpp
        diffusion_scheduler.cpp
        mca/mnn/mnn_prompt_weighting.cpp
        mca/mnn/mnn_backend_predicates.cpp
        mca/mnn/mnn_chat_session.cpp
        mca/mnn/mnn_chat_entries.cpp
        mca/mnn/mnn_diffusion_state.cpp
        mca/mnn/mnn_diffusion_generate.cpp
        mca/mnn/mnn_diffusion_tokenize.cpp
        mca/mnn/mnn_clip_encode.cpp
        mca/mnn/mnn_sd15_direct.cpp)
# T01 patch 1 (at T02's request, PLAN 1.4 "断开残缺委托/去重"): the eight entries
# formerly listed here were refactor-era extraction modules that the restored
# monolith no longer references.  Two of them (mnn_sdxl_pipeline.cpp,
# mnn_qnn_prompt_payload.cpp) also carried includes of headers that do not exist
# anywhere in the repository (`prompt_language_rules.hpp`, `prompt_handoff.hpp`),
# which made mca_mnn_native unbuildable:
#   E:/MAC/core/native/src/main/cpp/mca/mnn/mnn_qnn_prompt_payload.cpp:6:10:
#   fatal error: 'prompt_handoff.hpp' file not found
# The .cpp files remain on disk, unmodified.  The restored mnn_native_engine.cpp
# keeps every helper it needs in an anonymous namespace, so re-adding them later
# would not duplicate symbols -- but their includes must be fixed first.
# Revived since (listed in the arm64 diffusion source block above): mnn_tensor_copy
#          (E08d), mnn_diffusion_assets (E08e), mnn_diffusion_pipeline (E08f).
# All eight refactor-era extraction modules have been revived or dispositioned:
#   E08d mnn_tensor_copy, E08e mnn_diffusion_assets, E08f mnn_diffusion_pipeline,
#   E08g mnn_unet_smoke, E08h mnn_sdxl_pipeline, E08i mnn_qnn_prompt_payload,
#   E08j mnn_session_manager (compatibility TU), E08k mnn_debug_trace.
target_sources(mca_qnn_native PRIVATE
        image_conditioning.cpp
        textual_inversion_conditioning.cpp
        image_execution_math.cpp
        image_execution_math_tiling.cpp
        image_execution_math_ultrafix.cpp
        qnn_controlnet_execution.cpp
        diffusion_scheduler.cpp
        mca/qnn/qnn_image_writer.cpp)
# T2B18 / R7-C7: the image_execution_math split below adds two TUs to mca_qnn_native.
# Give them line-table debug (-g1), like the T2B14/T2B16 split TUs, so the packaged
# libmca_qnn_native.so DWARF stays bounded; code bytes and symbols are unchanged
# (size/export evidence: T2B18 b-REPORT.md).
set_source_files_properties(
        image_execution_math_tiling.cpp
        image_execution_math_ultrafix.cpp
        PROPERTIES COMPILE_OPTIONS "-g1")
target_compile_definitions(mca_qnn_native PRIVATE MCA_WITH_TOKENIZERS_CPP=0)

# tokenizers-cpp executes the complete Hugging Face tokenizer.json contract.
# Release builds use only the pinned third_party submodule so clean checkouts
# and CI cannot silently select a developer-local implementation.
if(MCA_WITH_TOKENIZERS_CPP AND EXISTS "${MCA_TOKENIZERS_CPP_ROOT}/CMakeLists.txt")
    message(STATUS "MCA building complete tokenizer.json support from ${MCA_TOKENIZERS_CPP_ROOT}")
    set(MLC_ENABLE_SENTENCEPIECE_TOKENIZER OFF CACHE BOOL "MCA image pipeline uses tokenizer.json" FORCE)
    add_subdirectory("${MCA_TOKENIZERS_CPP_ROOT}" build-tokenizers-cpp EXCLUDE_FROM_ALL)
    # Android Gradle enumerates every CMake executable even when its source
    # subdirectory is EXCLUDE_FROM_ALL.  Older pinned SentencePiece revisions
    # use __android_log_write in protobuf-lite but omit liblog on their CLI
    # targets; make those incidental tools linkable without patching the
    # third-party submodule.  MCA ships only the tokenizer library.
    if(ANDROID)
        foreach(SPM_TOOL_TARGET
                spm_encode
                spm_decode
                spm_normalize
                spm_train
                spm_export_vocab)
            if(TARGET ${SPM_TOOL_TARGET})
                target_link_libraries(${SPM_TOOL_TARGET} log)
            endif()
        endforeach()
    endif()
    target_compile_definitions(mca_mnn_native PRIVATE MCA_WITH_TOKENIZERS_CPP=1)
    target_link_libraries(mca_mnn_native PRIVATE tokenizers_cpp)
else()
    message(WARNING "tokenizers-cpp is missing; complete tokenizer.json support is unavailable.")
    target_compile_definitions(mca_mnn_native PRIVATE MCA_WITH_TOKENIZERS_CPP=0)
endif()
target_link_libraries(mca_qnn_native PRIVATE android log dl)
target_include_directories(mca_qnn_native PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/stable-diffusion.cpp/thirdparty"
        "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp/vendor")
set(MCA_QNN_INCLUDE_DIR "")
foreach(QNN_INCLUDE_CANDIDATE
        "${MCA_QNN_SDK_ROOT}/include"
        "$ENV{QNN_SDK_ROOT}/include"
        "$ENV{QAIRT_SDK_ROOT}/include")
    if(EXISTS "${QNN_INCLUDE_CANDIDATE}/QNN/QnnInterface.h")
        set(MCA_QNN_INCLUDE_DIR "${QNN_INCLUDE_CANDIDATE}")
        break()
    endif()
endforeach()
if(MCA_QNN_INCLUDE_DIR)
    set(MCA_QNN_REQUIRED_HEADER_RELATIVE_PATHS
            "QNN/QnnInterface.h"
            "QNN/QnnBackend.h"
            "QNN/QnnContext.h"
            "QNN/QnnDevice.h"
            "QNN/QnnGraph.h"
            "QNN/QnnTensor.h"
            "QNN/HTP/QnnHtpDevice.h"
            "QNN/System/QnnSystemInterface.h")
    set(MCA_QNN_MISSING_HEADERS "")
    foreach(QNN_HEADER_RELATIVE_PATH IN LISTS MCA_QNN_REQUIRED_HEADER_RELATIVE_PATHS)
        if(NOT EXISTS "${MCA_QNN_INCLUDE_DIR}/${QNN_HEADER_RELATIVE_PATH}")
            list(APPEND MCA_QNN_MISSING_HEADERS "${QNN_HEADER_RELATIVE_PATH}")
        endif()
    endforeach()
    if(MCA_QNN_MISSING_HEADERS)
        if(MCA_QNN_TYPED_BINDINGS_REQUIRED)
            message(FATAL_ERROR
                    "Typed QNN graph bindings are required, but the configured SDK is missing headers: ${MCA_QNN_MISSING_HEADERS}")
        endif()
        message(WARNING
                "QNN SDK header set is incomplete (${MCA_QNN_MISSING_HEADERS}); building the QNN graph stub.")
        set(MCA_QNN_INCLUDE_DIR "")
    endif()
endif()

if(MCA_QNN_INCLUDE_DIR)
    message(STATUS "MCA QNN SDK headers found at ${MCA_QNN_INCLUDE_DIR}")
    target_include_directories(mca_qnn_native PRIVATE
            "${MCA_QNN_INCLUDE_DIR}"
            "${MCA_QNN_INCLUDE_DIR}/QNN")
    target_compile_definitions(mca_qnn_native PRIVATE
            MCA_WITH_QNN_SDK_HEADERS=1
            MCA_QNN_SDK_ROOT_CONFIGURED=1)
    target_include_directories(mca_qnn_runtime PUBLIC
            "${MCA_QNN_INCLUDE_DIR}"
            "${MCA_QNN_INCLUDE_DIR}/QNN")
    target_compile_definitions(mca_qnn_runtime PUBLIC
            MCA_WITH_QNN_SDK_HEADERS=1
            MCA_QNN_SDK_ROOT_CONFIGURED=1)
else()
    if(MCA_QNN_TYPED_BINDINGS_REQUIRED)
        message(FATAL_ERROR
                "Typed QNN graph bindings are required for this product build. Configure MCA_QNN_SDK_ROOT, QNN_SDK_ROOT, or QAIRT_SDK_ROOT with a complete QAIRT/QNN SDK include tree.")
    endif()
    message(STATUS "MCA QNN SDK headers not found. Building an explicit stub; set MCA_QNN_SDK_ROOT, QNN_SDK_ROOT, or QAIRT_SDK_ROOT to enable typed QNN graph bindings.")
    target_compile_definitions(mca_qnn_native PRIVATE
            MCA_WITH_QNN_SDK_HEADERS=0
            MCA_QNN_SDK_ROOT_CONFIGURED=$<BOOL:${MCA_QNN_SDK_ROOT}>)
    target_compile_definitions(mca_qnn_runtime PUBLIC
            MCA_WITH_QNN_SDK_HEADERS=0
            MCA_QNN_SDK_ROOT_CONFIGURED=$<BOOL:${MCA_QNN_SDK_ROOT}>)
endif()

target_compile_definitions(mca_native PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp)
target_include_directories(mca_mnn_native PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}
        ${LLAMA_SRC}/vendor)
if(MCA_WITH_LLAMA_CPP AND EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(STATUS "MCA building with llama.cpp from ${LLAMA_SRC}")
    target_compile_definitions(mca_native PRIVATE MCA_WITH_LLAMA_CPP=1)
    # The embedded build defaults LLAMA_BUILD_COMMON to OFF because llama.cpp
    # is added as a subdirectory. MCA still links the legacy compatibility
    # target below, so request that target before configuring the subproject.
    set(LLAMA_BUILD_COMMON ON CACHE BOOL "llama: build common utils library" FORCE)
    add_subdirectory(${LLAMA_SRC} build-llama)

    # The GenieX AAR ships a libggml-opencl.so built against its private
    # ggml-base.  Loading that library beside MCA's newer ggml-base can make
    # the registry silently fall back to CPU.  Build an MCA-owned variant
    # with a distinct filename so both artifacts can coexist; the native
    # loader explicitly prefers this variant when present.
    if(TARGET ggml-opencl)
        set_target_properties(ggml-opencl PROPERTIES OUTPUT_NAME "ggml-opencl-mca")
        if(ANDROID)
            # Android vendor OpenCL symbols are not reliably visible to a
            # plugin loaded through the app linker namespace. Use MCA's
            # explicit dispatch trampolines instead of leaving direct cl*
            # imports. The dispatch loads the optional
            # vendor runtime and resolves each entry with dlsym; a missing
            # runtime therefore disables only this backend and leaves CPU
            # available.
            if(ANDROID_ABI STREQUAL "arm64-v8a")
                target_sources(ggml-opencl PRIVATE
                        "${CMAKE_CURRENT_LIST_DIR}/mca_opencl_dispatch.cpp")
                target_link_libraries(ggml-opencl PRIVATE dl)
                target_link_options(ggml-opencl PRIVATE "-Wl,-Bsymbolic-functions")
            endif()
        endif()
    endif()
    # Replace only llama-model.cpp with an MCA-owned overlay that disables
    # MAP_POPULATE/WILLNEED for multi-gigabyte model mappings. The Gradle
    # preparation task applies the audited STQ1_0 compatibility patch before
    # this target is configured.
    get_target_property(MCA_LLAMA_TARGET_SOURCES llama SOURCES)
    set(MCA_LLAMA_TARGET_SOURCES_WITHOUT_MODEL ${MCA_LLAMA_TARGET_SOURCES})
    list(FILTER MCA_LLAMA_TARGET_SOURCES_WITHOUT_MODEL EXCLUDE REGEX "(^|[/\\\\])llama-model[.]cpp$")
    list(LENGTH MCA_LLAMA_TARGET_SOURCES MCA_LLAMA_SOURCE_COUNT_BEFORE)
    list(LENGTH MCA_LLAMA_TARGET_SOURCES_WITHOUT_MODEL MCA_LLAMA_SOURCE_COUNT_AFTER)
    if(MCA_LLAMA_SOURCE_COUNT_AFTER EQUAL MCA_LLAMA_SOURCE_COUNT_BEFORE)
        message(FATAL_ERROR "Unable to replace upstream llama-model.cpp with the MCA mmap prefetch overlay.")
    endif()
    set_property(TARGET llama PROPERTY SOURCES ${MCA_LLAMA_TARGET_SOURCES_WITHOUT_MODEL})
    target_sources(llama PRIVATE ${CMAKE_CURRENT_LIST_DIR}/llama_model_mmap_prefetch_override.cpp)
    # The APK intentionally uses the current llama/ggml libraries for both the
    # built-in backend and GenieX. Export the one legacy llama-common entry
    # still referenced by GenieX 0.3.12 so its plugin remains loadable without
    # packaging a second, conflicting native dependency set.
    target_sources(llama-common PRIVATE
            ${CMAKE_CURRENT_LIST_DIR}/llama_common_legacy_compat.cpp)
    find_package(Threads REQUIRED)
    set(MTMD_SRC ${LLAMA_SRC}/tools/mtmd)
    add_library(mca_mtmd STATIC
            ${MTMD_SRC}/mtmd.cpp
            ${MTMD_SRC}/mtmd-audio.cpp
            ${MTMD_SRC}/mtmd-image.cpp
            ${MTMD_SRC}/mtmd-helper.cpp
            ${MTMD_SRC}/mtmd-helper-gen.cpp
            ${MTMD_SRC}/clip.cpp
            ${MTMD_SRC}/models/cogvlm.cpp
            ${MTMD_SRC}/models/conformer.cpp
            ${MTMD_SRC}/models/dots3note.cpp
            ${MTMD_SRC}/models/dotsocr.cpp
            ${MTMD_SRC}/models/exaone4_5.cpp
            ${MTMD_SRC}/models/gemma4a.cpp
            ${MTMD_SRC}/models/gemma4v.cpp
            ${MTMD_SRC}/models/gemma4ua.cpp
            ${MTMD_SRC}/models/gemma4uv.cpp
            ${MTMD_SRC}/models/glm4v.cpp
            ${MTMD_SRC}/models/granite-speech.cpp
            ${MTMD_SRC}/models/granite4-vision.cpp
            ${MTMD_SRC}/models/hunyuanvl.cpp
            ${MTMD_SRC}/models/internvl.cpp
            ${MTMD_SRC}/models/kimivl.cpp
            ${MTMD_SRC}/models/kimik25.cpp
            ${MTMD_SRC}/models/nemotron-v2-vl.cpp
            ${MTMD_SRC}/models/llama4.cpp
            ${MTMD_SRC}/models/llava.cpp
            ${MTMD_SRC}/models/minicpmv.cpp
            ${MTMD_SRC}/models/paddleocr.cpp
            ${MTMD_SRC}/models/pixtral.cpp
            ${MTMD_SRC}/models/minimax-m3.cpp
            ${MTMD_SRC}/models/muse-glimmer.cpp
            ${MTMD_SRC}/models/qwen2vl.cpp
            ${MTMD_SRC}/models/qwen3vl.cpp
            ${MTMD_SRC}/models/mimovl.cpp
            ${MTMD_SRC}/models/mimo-audio.cpp
            ${MTMD_SRC}/models/qwen3a.cpp
            ${MTMD_SRC}/models/step3vl.cpp
            ${MTMD_SRC}/models/siglip.cpp
            ${MTMD_SRC}/models/qwen3tts-spkenc.cpp
            ${MTMD_SRC}/models/qwen3tts-gen.cpp
            ${MTMD_SRC}/models/pockettts-seanet.cpp
            ${MTMD_SRC}/models/pockettts-spkenc.cpp
            ${MTMD_SRC}/models/pockettts-gen.cpp
            ${MTMD_SRC}/models/whisper-enc.cpp
            ${MTMD_SRC}/models/deepseekocr.cpp
            ${MTMD_SRC}/models/deepseekocr2.cpp
            ${MTMD_SRC}/models/deepseek4v.cpp
            ${MTMD_SRC}/models/mobilenetv5.cpp
            ${MTMD_SRC}/models/youtuvl.cpp
            ${MTMD_SRC}/models/yasa2.cpp
            ${MTMD_SRC}/models/parakeet.cpp)
    target_include_directories(mca_mtmd PUBLIC ${MTMD_SRC})
    target_include_directories(mca_mtmd PRIVATE ${LLAMA_SRC} ${LLAMA_SRC}/vendor)
    target_link_libraries(mca_mtmd PUBLIC ggml llama)
    target_link_libraries(mca_mtmd PRIVATE
            Threads::Threads
            vendor::hash
            vendor::miniaudio
            vendor::stb
            vendor::sheredom)
    target_compile_features(mca_mtmd PRIVATE cxx_std_17)
    set_target_properties(mca_mtmd PROPERTIES POSITION_INDEPENDENT_CODE ON)
    if(ANDROID)
        target_compile_options(mca_mtmd PRIVATE -Wno-missing-prototypes -Wno-cast-qual)
    endif()
    target_include_directories(mca_native PRIVATE
            ${LLAMA_SRC}
            ${LLAMA_SRC}/common
            ${LLAMA_SRC}/tools/mtmd
            ${LLAMA_SRC}/include
            ${LLAMA_SRC}/ggml/include
            ${LLAMA_SRC}/ggml/src)
    target_link_libraries(mca_native PRIVATE llama llama-common mca_mtmd android log dl)
else()
    # Restored verbatim from the reference layout (76348a5b:406-410).
    #
    # This is the original mode-0 contract: compile the bridge with
    # MCA_WITH_LLAMA_CPP=0, link only android/log, and let native_engine.cpp's own
    # #if MCA_WITH_LLAMA_CPP guards select the OFF paths (no backend capability ->
    # the original error outcomes, empty device enumeration, no fabricated tokens).
    #
    # An earlier revision of this file replaced this branch with message(FATAL_ERROR)
    # on the theory that OFF needed "a second architecture". That was wrong: the
    # OFF build only broke because the llama-dependent mca/llm TUs were listed
    # UNCONDITIONALLY and had no OFF fallback, and because native_engine.cpp lost its
    # guards during the extraction. The compile boundary (conditional source list
    # above) plus T03's restored guards make the original branch sufficient again,
    # exactly as it was when mca_native was a single guarded native_engine.cpp.
    message(WARNING "MCA_WITH_LLAMA_CPP is OFF or llama.cpp is missing; building JNI stub.")
    target_compile_definitions(mca_native PRIVATE MCA_WITH_LLAMA_CPP=0)
    target_link_libraries(mca_native PRIVATE android log)
endif()

set(MCA_MNN_LLM_HEADER "${MCA_MNN_SOURCE_ROOT}/transformers/llm/engine/include/llm/llm.hpp")
set(MCA_MNN_DIFFUSION_HEADER "${MCA_MNN_SOURCE_ROOT}/transformers/diffusion/engine/include/diffusion/stable_diffusion.hpp")
set(MCA_MNN_SANA_HEADER "${MCA_MNN_SOURCE_ROOT}/transformers/diffusion/engine/include/diffusion/sana_llm.hpp")
set(MCA_MNN_SANA_SESSION_SRC "${CMAKE_CURRENT_LIST_DIR}/mnn_sana_session.cpp")
set(MCA_MNN_DIFFUSION_SAFE_SRC "${CMAKE_CURRENT_LIST_DIR}/mnn_stable_diffusion_safe.cpp")
set(MCA_MNN_SANA_DIFFUSION_SRC "${MCA_MNN_SOURCE_ROOT}/transformers/diffusion/engine/src/sana_diffusion.cpp")
set(MCA_MNN_DIFFUSION_OFFICIAL_TOKENIZER_SRC "${MCA_MNN_SOURCE_ROOT}/transformers/diffusion/engine/src/tokenizer.cpp")
set(MCA_MNN_DIFFUSION_SCHEDULER_SRC "${MCA_MNN_SOURCE_ROOT}/transformers/diffusion/engine/src/scheduler.cpp")
set(MCA_MNN_LLM_TOKENIZER_HEADER "${MCA_MNN_SOURCE_ROOT}/transformers/llm/engine/src/tokenizer/tokenizer.hpp")
set(MCA_MNN_LLM_TOKENIZER_SRC "${MCA_MNN_SOURCE_ROOT}/transformers/llm/engine/src/tokenizer/tokenizer.cpp")
set(MCA_MNN_LLM_UNICODE_SRC "${MCA_MNN_SOURCE_ROOT}/transformers/llm/engine/src/tokenizer/unicode.cpp")
set(MCA_MNN_LLM_UNICODE_DATA_SRC "${MCA_MNN_SOURCE_ROOT}/transformers/llm/engine/src/tokenizer/unicode_data.cpp")
set(MCA_MNN_CV_COLOR_SRC "${MCA_MNN_SOURCE_ROOT}/tools/cv/source/imgproc/color.cpp")
set(MCA_MNN_CV_IMGCODECS_SRC "${MCA_MNN_SOURCE_ROOT}/tools/cv/source/imgcodecs/imgcodecs.cpp")
## The current MNN Android CMake build publishes one coherent split runtime at
## its build root.  Some source trees also retain an older nested OFF/ layout.
## libllm is ABI-coupled to all five dependency libraries, so never link one
## layout while packaging the other.  Prefer the current set as a unit and
## retain the nested layout only for old vendor builds that do not have it.
set(MCA_MNN_RUNTIME_CURRENT_REQUIRED_LIBS
        "libMNN.so"
        "libMNN_Express.so"
        "libMNN_CL.so"
        "tools/cv/libMNNOpenCV.so"
        "tools/audio/libMNNAudio.so"
        "libllm.so")
set(MCA_MNN_RUNTIME_LEGACY_REQUIRED_LIBS
        "OFF/${ANDROID_ABI}/libMNN.so"
        "express/OFF/${ANDROID_ABI}/libMNN_Express.so"
        "source/backend/opencl/OFF/${ANDROID_ABI}/libMNN_CL.so"
        "tools/cv/OFF/${ANDROID_ABI}/libMNNOpenCV.so"
        "tools/audio/OFF/${ANDROID_ABI}/libMNNAudio.so"
        "OFF/${ANDROID_ABI}/libllm.so")
set(MCA_MNN_RUNTIME_ROOT "")
set(MCA_MNN_RUNTIME_LAYOUT "")
foreach(MNN_RUNTIME_ROOT_CANDIDATE
        "${MCA_MNN_ANDROID_BUILD_ROOT}"
        "${MCA_MNN_SOURCE_ROOT}/project/android/build_64_mca_full")
    foreach(MNN_RUNTIME_LAYOUT_CANDIDATE current legacy)
        if(MNN_RUNTIME_LAYOUT_CANDIDATE STREQUAL "current")
            set(MNN_RUNTIME_REQUIRED_LIBS ${MCA_MNN_RUNTIME_CURRENT_REQUIRED_LIBS})
        else()
            set(MNN_RUNTIME_REQUIRED_LIBS ${MCA_MNN_RUNTIME_LEGACY_REQUIRED_LIBS})
        endif()
        set(MNN_RUNTIME_COMPLETE TRUE)
        foreach(MNN_RUNTIME_LIB_RELATIVE IN LISTS MNN_RUNTIME_REQUIRED_LIBS)
            if(NOT EXISTS "${MNN_RUNTIME_ROOT_CANDIDATE}/${MNN_RUNTIME_LIB_RELATIVE}")
                set(MNN_RUNTIME_COMPLETE FALSE)
                break()
            endif()
        endforeach()
        if(MNN_RUNTIME_COMPLETE)
            set(MCA_MNN_RUNTIME_ROOT "${MNN_RUNTIME_ROOT_CANDIDATE}")
            set(MCA_MNN_RUNTIME_LAYOUT "${MNN_RUNTIME_LAYOUT_CANDIDATE}")
            list(GET MNN_RUNTIME_REQUIRED_LIBS 0 MCA_MNN_RUNTIME_CORE_RELATIVE)
            list(GET MNN_RUNTIME_REQUIRED_LIBS 1 MCA_MNN_RUNTIME_EXPRESS_RELATIVE)
            list(GET MNN_RUNTIME_REQUIRED_LIBS 2 MCA_MNN_RUNTIME_OPENCL_RELATIVE)
            list(GET MNN_RUNTIME_REQUIRED_LIBS 3 MCA_MNN_RUNTIME_OPENCV_RELATIVE)
            list(GET MNN_RUNTIME_REQUIRED_LIBS 4 MCA_MNN_RUNTIME_AUDIO_RELATIVE)
            list(GET MNN_RUNTIME_REQUIRED_LIBS 5 MCA_MNN_RUNTIME_LLM_RELATIVE)
            break()
        endif()
    endforeach()
    if(MCA_MNN_RUNTIME_ROOT)
        break()
    endif()
endforeach()
if(ANDROID_ABI STREQUAL "arm64-v8a" AND EXISTS "${MCA_MNN_LLM_HEADER}" AND MCA_MNN_RUNTIME_ROOT)
    message(STATUS "MCA building MNN runner with complete ${MCA_MNN_RUNTIME_LAYOUT} split runtime from ${MCA_MNN_RUNTIME_ROOT}")
    add_library(mca_imported_mnn_core SHARED IMPORTED)
    set_target_properties(mca_imported_mnn_core PROPERTIES
            IMPORTED_LOCATION "${MCA_MNN_RUNTIME_ROOT}/${MCA_MNN_RUNTIME_CORE_RELATIVE}")
    add_library(mca_imported_mnn_express SHARED IMPORTED)
    set_target_properties(mca_imported_mnn_express PROPERTIES
            IMPORTED_LOCATION "${MCA_MNN_RUNTIME_ROOT}/${MCA_MNN_RUNTIME_EXPRESS_RELATIVE}")
    add_library(mca_imported_mnn_opencl SHARED IMPORTED)
    set_target_properties(mca_imported_mnn_opencl PROPERTIES
            IMPORTED_LOCATION "${MCA_MNN_RUNTIME_ROOT}/${MCA_MNN_RUNTIME_OPENCL_RELATIVE}")
    add_library(mca_imported_mnn_opencv SHARED IMPORTED)
    set_target_properties(mca_imported_mnn_opencv PROPERTIES
            IMPORTED_LOCATION "${MCA_MNN_RUNTIME_ROOT}/${MCA_MNN_RUNTIME_OPENCV_RELATIVE}")
    add_library(mca_imported_mnn_audio SHARED IMPORTED)
    set_target_properties(mca_imported_mnn_audio PROPERTIES
            IMPORTED_LOCATION "${MCA_MNN_RUNTIME_ROOT}/${MCA_MNN_RUNTIME_AUDIO_RELATIVE}")
    add_library(mca_imported_mnn_llm SHARED IMPORTED)
    set_target_properties(mca_imported_mnn_llm PROPERTIES
            IMPORTED_LOCATION "${MCA_MNN_RUNTIME_ROOT}/${MCA_MNN_RUNTIME_LLM_RELATIVE}")
    target_compile_definitions(mca_mnn_native PRIVATE MCA_WITH_MNN_LLM=1)
    # The MNN helper library compiles capability-gated TUs of its own
    # (mca/mnn/mnn_prompt_cache_manager.cpp, mca/mnn/mnn_vision_adapter.cpp).
    # PRIVATE definitions and include dirs do not propagate into a STATIC
    # library's own compilation, so without this wiring their #else stubs stay
    # live and every MNN request fails in beginCompletion with -203:
    #   text  -> "MCA built without MNN LLM."          (prompt cache prefill stub)
    #   vision-> "does not include MNN image codecs"   (MNN_IMGCODECS gate)
    # Device evidence (build3 .so): text beginCompletion=-203
    # "MNN beginCompletion exception (backend=cpu): MCA built without MNN LLM.";
    # vision beginCompletion=-203 "This MCA build does not include MNN image codecs".
    # mca_mnn_runtime is linked only by mca_mnn_native (see target_link_libraries
    # below), so teaching it the capability adds no new deliverable dependency.
    target_compile_definitions(mca_mnn_runtime PRIVATE
            MCA_WITH_MNN_LLM=1
            MNN_IMGCODECS=1
            MNN_IMGPROC_COLOR=1)
    target_include_directories(mca_mnn_runtime PRIVATE
            ${MCA_MNN_SOURCE_ROOT}/include
            ${MCA_MNN_SOURCE_ROOT}/source
            ${MCA_MNN_SOURCE_ROOT}/tools/cv/include
            ${MCA_MNN_SOURCE_ROOT}/tools/audio/include
            ${MCA_MNN_SOURCE_ROOT}/3rd_party
            ${MCA_MNN_SOURCE_ROOT}/transformers/llm/engine/include
            ${MCA_MNN_SOURCE_ROOT}/transformers/llm/engine/src)
    include("${CMAKE_CURRENT_LIST_DIR}/mnn_vendor_cache.cmake")
    mca_mnn_cache_namespace(
        "${CMAKE_CURRENT_LIST_DIR}/../../../../../vendor/mnn/mnn-vendor.properties"
        MCA_MNN_RUNTIME_CACHE_NAMESPACE)
    target_compile_definitions(mca_mnn_native PRIVATE
        MCA_MNN_RUNTIME_CACHE_NAMESPACE="${MCA_MNN_RUNTIME_CACHE_NAMESPACE}")
    target_include_directories(mca_mnn_native PRIVATE
            ${MCA_MNN_SOURCE_ROOT}/include
            ${MCA_MNN_SOURCE_ROOT}/source
            ${MCA_MNN_SOURCE_ROOT}/tools/cv/include
            ${MCA_MNN_SOURCE_ROOT}/tools/audio/include
            ${MCA_MNN_SOURCE_ROOT}/3rd_party
            ${MCA_MNN_SOURCE_ROOT}/transformers/llm/engine/include)
    # Visual MNN-LLM models call MNN::CV::imread even when the optional local
    # diffusion shim is omitted. The coherent runtime selection above already
    # requires libMNNOpenCV, so keep image-codec compilation tied to the
    # runtime, not to the unrelated Stable Diffusion sources.
    target_compile_definitions(mca_mnn_native PRIVATE
            MNN_IMGCODECS=1
            MNN_IMGPROC_COLOR=1)
    if(MCA_WITH_MNN_DIFFUSION
            AND EXISTS "${MCA_MNN_DIFFUSION_HEADER}"
            AND EXISTS "${MCA_MNN_SANA_HEADER}"
            AND EXISTS "${MCA_MNN_SANA_SESSION_SRC}"
            AND EXISTS "${MCA_MNN_DIFFUSION_SAFE_SRC}"
            AND EXISTS "${MCA_MNN_SANA_DIFFUSION_SRC}"
            AND EXISTS "${MCA_MNN_DIFFUSION_OFFICIAL_TOKENIZER_SRC}"
            AND EXISTS "${MCA_MNN_DIFFUSION_SCHEDULER_SRC}"
            AND EXISTS "${MCA_MNN_LLM_TOKENIZER_HEADER}"
            AND EXISTS "${MCA_MNN_LLM_TOKENIZER_SRC}"
            AND EXISTS "${MCA_MNN_LLM_UNICODE_SRC}"
            AND EXISTS "${MCA_MNN_LLM_UNICODE_DATA_SRC}"
            AND EXISTS "${MCA_MNN_CV_COLOR_SRC}"
            AND EXISTS "${MCA_MNN_CV_IMGCODECS_SRC}")
        message(STATUS "MCA building MNN Stable Diffusion safe runner shim.")
        target_sources(mca_mnn_native PRIVATE
                mca/mnn/mnn_tensor_ops.cpp
                mca/mnn/mnn_tensor_copy.cpp
                mca/mnn/mnn_diffusion_assets.cpp
                mca/mnn/mnn_diffusion_pipeline.cpp
                mca/mnn/mnn_unet_smoke.cpp
                mca/mnn/mnn_sdxl_pipeline.cpp
                mca/mnn/mnn_qnn_prompt_payload.cpp
                mca/mnn/mnn_session_manager.cpp
                mca/mnn/mnn_debug_trace.cpp
                mca/mnn/mnn_vae_image_writer.cpp
                mca/mnn/mnn_clip_weighting.cpp
                mca/mnn/mnn_pndm_scheduler.cpp
                "${CMAKE_CURRENT_LIST_DIR}/mnn_diffusion_tokenizer.cpp"
                "${MCA_MNN_SANA_SESSION_SRC}"
                "${MCA_MNN_DIFFUSION_SAFE_SRC}"
                # The local engine owns Diffusion's base/factory symbols so the
                # official diffusion.cpp is intentionally excluded here.
                "${MCA_MNN_SANA_DIFFUSION_SRC}"
                "${MCA_MNN_DIFFUSION_OFFICIAL_TOKENIZER_SRC}"
                "${MCA_MNN_DIFFUSION_SCHEDULER_SRC}"
                "${MCA_MNN_LLM_TOKENIZER_SRC}"
                "${MCA_MNN_LLM_UNICODE_SRC}"
                "${MCA_MNN_LLM_UNICODE_DATA_SRC}"
                "${MCA_MNN_CV_COLOR_SRC}"
                "${MCA_MNN_CV_IMGCODECS_SRC}")
        target_compile_definitions(mca_mnn_native PRIVATE
                MCA_WITH_MNN_DIFFUSION=1
                MCA_WITH_MNN_DIFFUSION_SAFE_RUNNER=1
                FREE_LLM_INSTANCE=1
                MNN_DIFFUSION_WITH_LLM_TOKENIZER=1
                # mca_mnn_native also compiles MNN's Tokenizer for the
                # diffusion shim. Those duplicate symbols interpose libllm's
                # tokenizer at runtime, so they must retain upstream Jinja
                # support or every chat template silently degrades to raw
                # system+user text.
                LLM_USE_JINJA=1
                )
        target_include_directories(mca_mnn_native PRIVATE
                ${MCA_MNN_SOURCE_ROOT}/transformers/diffusion/engine/include
                ${MCA_MNN_SOURCE_ROOT}/transformers/diffusion/engine/src
                ${MCA_MNN_SOURCE_ROOT}/transformers/llm/engine/src
                ${MCA_MNN_SOURCE_ROOT}/transformers/llm/engine/src/tokenizer
                ${MCA_MNN_SOURCE_ROOT}/3rd_party/imageHelper)
    else()
        message(STATUS "MCA building without MNN Stable Diffusion runner sources.")
        target_compile_definitions(mca_mnn_native PRIVATE MCA_WITH_MNN_DIFFUSION=0)
    endif()
    target_link_libraries(mca_mnn_native PRIVATE
            mca_imported_mnn_llm
            mca_imported_mnn_opencl
            mca_imported_mnn_opencv
            mca_imported_mnn_audio
            mca_imported_mnn_express
            mca_imported_mnn_core
            android
            log)
else()
    message(STATUS "MCA building MNN runner stub. Build the complete MNN split runtime to enable MNN chat and diffusion.")
    target_compile_definitions(mca_mnn_native PRIVATE MCA_WITH_MNN_LLM=0)
    target_compile_definitions(mca_mnn_native PRIVATE MCA_WITH_MNN_DIFFUSION=0)
    # Keep mca_mnn_runtime aligned with mca_mnn_native so its capability-gated TUs
    # take the same #else path in a non-MNN build. MNN_IMGCODECS stays undefined
    # here on purpose: mnn_vision_adapter.cpp tests !defined(MNN_IMGCODECS).
    target_compile_definitions(mca_mnn_runtime PRIVATE MCA_WITH_MNN_LLM=0)
    target_link_libraries(mca_mnn_native PRIVATE android log)
endif()
