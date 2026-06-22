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
    target_include_directories(mca_native PRIVATE
            ${LLAMA_SRC}
            ${LLAMA_SRC}/common
            ${LLAMA_SRC}/include
            ${LLAMA_SRC}/ggml/include
            ${LLAMA_SRC}/ggml/src)
    target_link_libraries(mca_native PRIVATE llama llama-common android log)
else()
    message(WARNING "MCA_WITH_LLAMA_CPP is OFF or llama.cpp is missing; building JNI stub.")
    target_compile_definitions(mca_native PRIVATE MCA_WITH_LLAMA_CPP=0)
    target_link_libraries(mca_native PRIVATE android log)
endif()
