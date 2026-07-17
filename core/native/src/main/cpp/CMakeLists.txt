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
option(MCA_WITH_MNN_DIFFUSION "Build MCA native backend with MNN Stable Diffusion runner" ON)
option(MCA_WITH_TOKENIZERS_CPP "Build the complete tokenizer.json backend" ON)
set(MCA_MNN_SOURCE_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/MNN" CACHE PATH "Official MNN source root")
set(MCA_MNN_ANDROID_BUILD_ROOT "${MCA_MNN_SOURCE_ROOT}/project/android/build_64" CACHE PATH "Official MNN Android build root")
set(MCA_QNN_SDK_ROOT "" CACHE PATH "Qualcomm AI Runtime / QNN SDK root")
set(MCA_TOKENIZERS_CPP_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/tokenizers-cpp" CACHE PATH "tokenizers-cpp source root")

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
add_library(mca_mnn_native SHARED mnn_native_engine.cpp)
add_library(mca_qnn_native SHARED qnn_native_bridge.cpp)
target_sources(mca_mnn_native PRIVATE
        image_conditioning.cpp
        diffusion_scheduler.cpp)
target_sources(mca_qnn_native PRIVATE
        image_conditioning.cpp
        diffusion_scheduler.cpp)
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
        "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/stable-diffusion.cpp/thirdparty")
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
    message(STATUS "MCA QNN SDK headers found at ${MCA_QNN_INCLUDE_DIR}")
    target_include_directories(mca_qnn_native PRIVATE
            "${MCA_QNN_INCLUDE_DIR}"
            "${MCA_QNN_INCLUDE_DIR}/QNN")
    target_compile_definitions(mca_qnn_native PRIVATE
            MCA_WITH_QNN_SDK_HEADERS=1
            MCA_QNN_SDK_ROOT_CONFIGURED=1)
else()
    message(STATUS "MCA QNN SDK headers not found. Set MCA_QNN_SDK_ROOT, QNN_SDK_ROOT, or QAIRT_SDK_ROOT to enable typed QNN graph bindings.")
    target_compile_definitions(mca_qnn_native PRIVATE
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
    add_subdirectory(${LLAMA_SRC} build-llama)
    # Replace only llama-model.cpp with an MCA-owned overlay that disables
    # MAP_POPULATE/WILLNEED for multi-gigabyte model mappings. The llama.cpp
    # submodule remains pristine and every product build applies this override.
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

set(MCA_MNN_LLM_HEADER "${MCA_MNN_SOURCE_ROOT}/transformers/llm/engine/include/llm/llm.hpp")
set(MCA_MNN_DIFFUSION_HEADER "${MCA_MNN_SOURCE_ROOT}/transformers/diffusion/engine/include/diffusion/stable_diffusion.hpp")
set(MCA_MNN_SANA_HEADER "${MCA_MNN_SOURCE_ROOT}/transformers/diffusion/engine/include/diffusion/sana_llm.hpp")
set(MCA_MNN_SANA_SESSION_SRC "${CMAKE_CURRENT_LIST_DIR}/mnn_sana_session.cpp")
set(MCA_MNN_DIFFUSION_SAFE_SRC "${CMAKE_CURRENT_LIST_DIR}/mnn_stable_diffusion_safe.cpp")
set(MCA_MNN_SANA_DIFFUSION_SRC "${MCA_MNN_SOURCE_ROOT}/transformers/diffusion/engine/src/sana_diffusion.cpp")
set(MCA_MNN_DIFFUSION_OFFICIAL_TOKENIZER_SRC "${MCA_MNN_SOURCE_ROOT}/transformers/diffusion/engine/src/tokenizer.cpp")
set(MCA_MNN_DIFFUSION_SCHEDULER_SRC "${MCA_MNN_SOURCE_ROOT}/transformers/diffusion/engine/src/scheduler.cpp")
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
            AND EXISTS "${MCA_MNN_LLM_TOKENIZER_SRC}"
            AND EXISTS "${MCA_MNN_LLM_UNICODE_SRC}"
            AND EXISTS "${MCA_MNN_LLM_UNICODE_DATA_SRC}"
            AND EXISTS "${MCA_MNN_CV_COLOR_SRC}"
            AND EXISTS "${MCA_MNN_CV_IMGCODECS_SRC}")
        message(STATUS "MCA building MNN Stable Diffusion safe runner shim.")
        target_sources(mca_mnn_native PRIVATE
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
    target_link_libraries(mca_mnn_native PRIVATE android log)
endif()
