# Android has an optional platform OpenCL implementation but the NDK does not
# provide an import library. The llama.cpp backend only needs Khronos headers
# at compile time; MCA resolves the vendor library with dlopen/dlsym at runtime.
# This module intentionally never creates a link dependency on libOpenCL.so.

set(_mca_opencl_headers "${MCA_OPENCL_HEADERS_ROOT}")
if(NOT EXISTS "${_mca_opencl_headers}/CL/opencl.h")
    set(OpenCL_FOUND FALSE)
    if(OpenCL_FIND_REQUIRED)
        message(FATAL_ERROR
            "MCA OpenCL headers were not found at ${_mca_opencl_headers}. "
            "Set MCA_OPENCL_HEADERS_ROOT to a Khronos OpenCLHeaders checkout.")
    endif()
    return()
endif()

set(OpenCL_FOUND TRUE)
set(OpenCL_INCLUDE_DIRS "${_mca_opencl_headers}")
set(OpenCL_LIBRARIES "")
if(NOT TARGET OpenCL::OpenCL)
    add_library(OpenCL::OpenCL INTERFACE IMPORTED)
    set_target_properties(OpenCL::OpenCL PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${_mca_opencl_headers}")
endif()

if(NOT OpenCL_FIND_QUIETLY)
    message(STATUS "MCA Android OpenCL headers: ${_mca_opencl_headers}; runtime is supplied by the platform")
endif()
