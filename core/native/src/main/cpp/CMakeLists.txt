cmake_minimum_required(VERSION 3.31.6)
project(mca_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(MCA_WITH_LLAMA_CPP "Build MCA native backend with llama.cpp" ON)

if(DEFINED ANDROID_ABI)
    message(STATUS "MCA Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

add_library(mca_native SHARED native_engine.cpp)

target_compile_definitions(mca_native PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp)
if(MCA_WITH_LLAMA_CPP AND EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(STATUS "MCA building with llama.cpp from ${LLAMA_SRC}")
    target_compile_definitions(mca_native PRIVATE MCA_WITH_LLAMA_CPP=1)
    add_subdirectory(${LLAMA_SRC} build-llama)
    find_package(Threads REQUIRED)
    set(MTMD_SRC ${LLAMA_SRC}/tools/mtmd)
    add_library(mca_mtmd STATIC
            ${MTMD_SRC}/mtmd.cpp
            ${MTMD_SRC}/mtmd-audio.cpp
            ${MTMD_SRC}/mtmd-image.cpp
            ${MTMD_SRC}/mtmd-helper.cpp
            ${MTMD_SRC}/clip.cpp
            ${MTMD_SRC}/models/cogvlm.cpp
            ${MTMD_SRC}/models/conformer.cpp
            ${MTMD_SRC}/models/dotsocr.cpp
            ${MTMD_SRC}/models/gemma4a.cpp
            ${MTMD_SRC}/models/gemma4v.cpp
            ${MTMD_SRC}/models/glm4v.cpp
            ${MTMD_SRC}/models/granite-speech.cpp
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
            ${MTMD_SRC}/models/qwen2vl.cpp
            ${MTMD_SRC}/models/qwen3vl.cpp
            ${MTMD_SRC}/models/mimovl.cpp
            ${MTMD_SRC}/models/qwen3a.cpp
            ${MTMD_SRC}/models/step3vl.cpp
            ${MTMD_SRC}/models/siglip.cpp
            ${MTMD_SRC}/models/whisper-enc.cpp
            ${MTMD_SRC}/models/deepseekocr.cpp
            ${MTMD_SRC}/models/mobilenetv5.cpp
            ${MTMD_SRC}/models/youtuvl.cpp
            ${MTMD_SRC}/models/yasa2.cpp)
    target_include_directories(mca_mtmd PUBLIC ${MTMD_SRC})
    target_include_directories(mca_mtmd PRIVATE ${LLAMA_SRC} ${LLAMA_SRC}/vendor)
    target_link_libraries(mca_mtmd PUBLIC ggml llama)
    target_link_libraries(mca_mtmd PRIVATE Threads::Threads)
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
    target_link_libraries(mca_native PRIVATE llama llama-common mca_mtmd android log)
else()
    message(WARNING "MCA_WITH_LLAMA_CPP is OFF or llama.cpp is missing; building JNI stub.")
    target_compile_definitions(mca_native PRIVATE MCA_WITH_LLAMA_CPP=0)
    target_link_libraries(mca_native PRIVATE android log)
endif()
