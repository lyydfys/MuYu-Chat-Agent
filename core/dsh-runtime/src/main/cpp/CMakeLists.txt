# Native helpers of the Android DSH runtime (D11/D12).
#
# Wired from core/dsh-runtime/build.gradle.kts:
#   android {
#     ndkVersion = libs.versions.ndk.get()
#     defaultConfig {
#       ndk { abiFilters += listOf("arm64-v8a") }
#       externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=none") } }
#     }
#     externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
#   }
#
# dshpty is a JNI shared library (packaged as libdshpty.so). It provides the real PTY primitive.
# dshcmd is a PIE *executable* used as the payload command dispatcher. AGP does not package
# executables from externalNativeBuild, so the artifact is built and staged into
# src/main/jniLibs/arm64-v8a/libdshcmd.so by scripts/dsh/build-exec-dispatch.ps1. Both are pure C
# (ANDROID_STL=none).

cmake_minimum_required(VERSION 3.22.1)
project(dsh_native C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(dshpty SHARED dsh_pty.c)
target_compile_options(dshpty PRIVATE -Wall -Wextra)
target_link_libraries(dshpty PRIVATE log)

# Executable command dispatcher. Not packaged by AGP: staged by
# scripts/dsh/build-exec-dispatch.ps1 as jniLibs/arm64-v8a/libdshcmd.so.
add_executable(dshcmd dsh_cmd.c)
target_compile_options(dshcmd PRIVATE -Wall -Wextra)
