cmake_minimum_required(VERSION 3.22.1)
project(mca_sd_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(DEFINED ANDROID_ABI)
    message(STATUS "MCA stable-diffusion Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)
        set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
        set(GGML_OPENMP ON CACHE BOOL "" FORCE)
        set(SD_VULKAN OFF CACHE BOOL "" FORCE)
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86" CACHE STRING "" FORCE)
        set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
        set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
        set(SD_VULKAN OFF CACHE BOOL "" FORCE)
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

set(SDCPP_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/stable-diffusion.cpp)
if(NOT EXISTS ${SDCPP_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "stable-diffusion.cpp source is missing at ${SDCPP_SRC}")
endif()
if(NOT EXISTS ${SDCPP_SRC}/ggml/CMakeLists.txt)
    message(FATAL_ERROR "stable-diffusion.cpp ggml submodule is missing. Run: git -C third_party/stable-diffusion.cpp submodule update --init ggml")
endif()

set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)
set(SD_USE_SYSTEM_GGML OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(SD_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(SD_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(${SDCPP_SRC} build-stable-diffusion)

set(MCA_IMAGE_EXECUTION_MATH_DIR
        ${CMAKE_CURRENT_LIST_DIR}/../../../../native/src/main/cpp)
target_sources(stable-diffusion PRIVATE
        ${MCA_IMAGE_EXECUTION_MATH_DIR}/image_execution_math.cpp
        ${MCA_IMAGE_EXECUTION_MATH_DIR}/image_execution_math_tiling.cpp
        ${MCA_IMAGE_EXECUTION_MATH_DIR}/image_execution_math_ultrafix.cpp)
# T2B18 / R7-C7: the shared image_execution_math split above adds two TUs to this static
# library; keep their DWARF at line-table depth (-g1), like the T2B14/T2B16 split TUs, so
# the packaged libmca_sd_native.so stays bounded; code bytes and symbols are unchanged
# (size evidence: T2B18 b-REPORT.md).
set_source_files_properties(
        ${MCA_IMAGE_EXECUTION_MATH_DIR}/image_execution_math_tiling.cpp
        ${MCA_IMAGE_EXECUTION_MATH_DIR}/image_execution_math_ultrafix.cpp
        TARGET_DIRECTORY stable-diffusion
        PROPERTIES COMPILE_OPTIONS "-g1")
target_include_directories(stable-diffusion PRIVATE
        ${MCA_IMAGE_EXECUTION_MATH_DIR})

# vocab.cpp embeds huge tokenizer tables; Android NDK clang can OOM at Release -O3
# on Windows hosts. Keep this data-heavy file unoptimized while optimizing kernels.
set_source_files_properties(
        ${SDCPP_SRC}/src/tokenizers/vocab/vocab.cpp
        TARGET_DIRECTORY stable-diffusion
        PROPERTIES COMPILE_OPTIONS "-O0")

# Runtime lifecycle state for this DSO is intentionally backend-local.  The core
# native CMake project compiles the same source pair into its own DSOs; a STATIC
# singleton is never assumed to be unique across libraries or processes.  The
# Kotlin RuntimeSupervisor remains the authoritative process epoch.  Hidden
# visibility keeps ProcessRuntimeHost::instance() from being preempted by another
# DSO that happens to export the same implementation symbol.
add_library(mca_sd_runtime_backend_local STATIC
        ${MCA_IMAGE_EXECUTION_MATH_DIR}/mca/runtime/process_runtime_host.cpp
        ${MCA_IMAGE_EXECUTION_MATH_DIR}/mca/runtime/backend_runtime.cpp)
target_include_directories(mca_sd_runtime_backend_local PRIVATE
        ${MCA_IMAGE_EXECUTION_MATH_DIR})
set_target_properties(mca_sd_runtime_backend_local PROPERTIES
        POSITION_INDEPENDENT_CODE ON
        CXX_VISIBILITY_PRESET hidden
        C_VISIBILITY_PRESET hidden)

add_library(mca_sd_native SHARED
        stable_diffusion_bridge.cpp
        sd_video_generate.cpp
        sd_prompt_token_measure.cpp
        sd_anima_tokenize.cpp
        sd_generate_request.cpp
        sd_generate_context.cpp
        sd_generate_execute.cpp
        sd_generate_publish.cpp
# T2B17 / R7-C6: the five extracted generate-phase TUs intentionally carry no per-file
# -fvisibility=hidden.  The cross-TU split helpers are declared hidden in
# stable_diffusion_bridge_internal.hpp; a whole-TU hidden flag would also hide pre-existing
# weak template exports of the moved bodies and shrink the .so export surface (measured).
# The header pragma keeps the pre-split export set byte-identical (see b-REPORT.md).
        mca/sd/sd_progress_reporter.cpp
        mca/sd/sd_schedule_resolver.cpp
        mca/sd/sd_lora_adapter.cpp
        mca/sd/sd_image_io.cpp
        mca/sd/sd_textual_inversion.cpp
        mca/sd/sd_contract_parser.cpp
        mca/sd/sd_component_resolver.cpp
        mca/sd/sd_output_transaction.cpp
        mca/sd/sd_input_contract.cpp
        mca/sd/sd_phase_machine.cpp
        mca/sd/sd_request.cpp
        mca/sd/sd_runtime_owner.cpp
        mca/sd/sd_runtime_diagnostics.cpp
        mca/sd/sd_upscaler.cpp
        mca/sd/sd_context_key.cpp
        mca/sd/sd_effective_config.cpp
        mca/sd/sd_controlnet_adapter.cpp
        mca/sd/sd_inpaint_processor.cpp
        mca/sd/sd_preview_encoder.cpp
        mca/sd/sd_evidence_verifier.cpp
        mca/sd/sd_response_builder.cpp
        ${MCA_IMAGE_EXECUTION_MATH_DIR}/mca/common/logging.cpp)
target_include_directories(mca_sd_native PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}
        ${SDCPP_SRC}/include
        ${SDCPP_SRC}/src
        ${SDCPP_SRC}/thirdparty
        ${MCA_IMAGE_EXECUTION_MATH_DIR})
if(DEFINED ANDROID_ABI AND ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(mca_sd_native PRIVATE MCA_SD_ARM64=1 MCA_SD_CPU_KLEIDIAI=1 MCA_SD_OPENMP=1)
elseif(DEFINED ANDROID_ABI AND ANDROID_ABI STREQUAL "x86_64")
    target_compile_definitions(mca_sd_native PRIVATE MCA_SD_X86_64=1)
endif()
target_link_libraries(mca_sd_native PRIVATE mca_sd_runtime_backend_local stable-diffusion android log)
# The shipped APK also carries an out-of-tree HappyYzy libstable-diffusion.so (for the
# external video engine) that exports a different build of the same sd_* C ABI. Keeping
# this library's statically-linked copies exported would let the dynamic linker bind one
# engine's internal calls to the other's implementation; the two sd_ctx_params_t layouts
# differ, so that is silent memory corruption rather than an error. Hide every symbol
# pulled in from a static archive; JNI entry points compiled directly into this target
# stay exported. Gate: tools/verify-engine-abi.sh (check 1 must report no duplicates).
target_link_options(mca_sd_native PRIVATE "-Wl,--exclude-libs,ALL")
