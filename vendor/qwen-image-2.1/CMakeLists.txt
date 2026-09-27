cmake_minimum_required(VERSION 3.22)
project(qwenimage21_jni LANGUAGES CXX)

# Standalone reproducibility target for the pinned JNI bridge. The Qwen MNN
# binary is imported from this directory and must be loaded in an isolated
# Android process before the JNI library (see README-MCA.md).
get_filename_component(MCA_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(QWEN21_MNN "${CMAKE_CURRENT_LIST_DIR}/arm64-v8a/libmca_qwenimage21_mnn.so")

if(NOT EXISTS "${QWEN21_MNN}")
    message(FATAL_ERROR "Missing pinned Qwen MNN runtime: ${QWEN21_MNN}")
endif()

add_library(mca_qwenimage21_mnn SHARED IMPORTED GLOBAL)
set_target_properties(mca_qwenimage21_mnn PROPERTIES
    IMPORTED_LOCATION "${QWEN21_MNN}"
    IMPORTED_SONAME "libmca_qwenimage21_mnn.so"
    IMPORTED_NO_SONAME FALSE
)

add_library(qwenimage21_jni SHARED
    "${MCA_ROOT}/core/qwenimage21/src/main/cpp/qwenimage21_jni.cpp"
)
target_include_directories(qwenimage21_jni PRIVATE "${CMAKE_CURRENT_LIST_DIR}/include")
target_compile_features(qwenimage21_jni PRIVATE cxx_std_17)
target_compile_options(qwenimage21_jni PRIVATE -fexceptions)
target_link_libraries(qwenimage21_jni PRIVATE mca_qwenimage21_mnn log android)
