cmake_minimum_required(VERSION 3.22)
project(mca_mnn_sampler_host LANGUAGES C CXX)
enable_testing()
get_filename_component(REPO "${CMAKE_CURRENT_LIST_DIR}/../../../../../.." ABSOLUTE)
set(MNN_ROOT "${REPO}/third_party/MNN")
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
    "${MNN_ROOT}/transformers/llm/engine/src/sampler.cpp")
target_compile_features(mnn_sampler_numeric_test PRIVATE cxx_std_17)
target_compile_definitions(mnn_sampler_numeric_test PRIVATE MNN_BUILD_STATIC_LIBS)
target_include_directories(mnn_sampler_numeric_test PRIVATE
    "${MNN_ROOT}/include" "${MNN_ROOT}/transformers/llm/engine/include"
    "${MNN_ROOT}/transformers/llm/engine/src" "${MNN_ROOT}/3rd_party/half"
    "${MNN_ROOT}/3rd_party" "${REPO}/third_party/llama.cpp/vendor")
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

foreach(test_name IN ITEMS mnn_prompt_history mnn_stream_protocol_filter)
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
