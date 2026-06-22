cmake_minimum_required(VERSION 3.31.6)
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
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(${SDCPP_SRC} build-stable-diffusion)

# vocab.cpp embeds huge tokenizer tables; Android NDK clang can OOM at Release -O3
# on Windows hosts. Keep this data-heavy file unoptimized while optimizing kernels.
set_source_files_properties(
        ${SDCPP_SRC}/src/tokenizers/vocab/vocab.cpp
        TARGET_DIRECTORY stable-diffusion
        PROPERTIES COMPILE_OPTIONS "-O0")

add_library(mca_sd_native SHARED
        stable_diffusion_bridge.cpp)
target_include_directories(mca_sd_native PRIVATE
        ${SDCPP_SRC}/include
        ${SDCPP_SRC}/thirdparty)
if(DEFINED ANDROID_ABI AND ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(mca_sd_native PRIVATE MCA_SD_ARM64=1 MCA_SD_CPU_KLEIDIAI=1 MCA_SD_OPENMP=1)
elseif(DEFINED ANDROID_ABI AND ANDROID_ABI STREQUAL "x86_64")
    target_compile_definitions(mca_sd_native PRIVATE MCA_SD_X86_64=1)
endif()
target_link_libraries(mca_sd_native PRIVATE stable-diffusion android log)
