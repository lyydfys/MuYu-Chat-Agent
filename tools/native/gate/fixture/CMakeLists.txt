cmake_minimum_required(VERSION 3.22.1)
project(mca_t07_gate_fixture LANGUAGES C CXX)

# T07 independent gate-sensitivity fixture (runId=20261009-parity, PLAN 9.3).
#
# This project is standalone and is NEVER referenced by the product build, so no
# pollution is injected into the shared business tree.  It exists to prove the
# gate detects the defect classes that T01's fixture
# (artifacts/repair/20261009-parity/native/gate-fixture) does NOT cover:
#
#   ORPHAN_TU      a .cpp exists on disk but is listed in NO target_sources and
#                  is not a JNI entry point.  The link succeeds and no export
#                  disappears, so the link gate AND the export gate are both
#                  blind; only an on-disk-vs-configured source-set comparison
#                  can see it.
#   MISSING_TARGET a whole required library target (the real-world analogue is
#                  core/sd-native's `mca_sd_native`, which lives in a separate
#                  CMake project) is never created by the configure.  Everything
#                  the configure produced still builds and links.
#   MACRO_FLIP     a required capability macro is configured OFF while the build
#                  still succeeds AND the dynamic export set is unchanged.  The
#                  real-world analogue is MCA_QNN_TYPED_BINDINGS_REQUIRED going
#                  OFF: no JNI entry point disappears, QNN silently degrades to
#                  stub bindings.  Only a required-define assertion sees it.
#   JNI_OVERLOAD   an overloaded `external fun` whose .so export carries a
#                  DIFFERENT JNI signature suffix.  A `-like '__*'` matcher says
#                  PRESENT, but the JVM would throw UnsatisfiedLinkError.
#
# Cases OK/ORPHAN_TU/MISSING_TARGET/MACRO_FLIP still build and link successfully
# by design: a gate that only watches compiler/linker exit codes calls them
# green.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(MCA_T07_CASE "OK" CACHE STRING "OK | ORPHAN_TU | MISSING_TARGET | MACRO_FLIP | JNI_OVERLOAD")
set(MCA_T07_FEATURE ON CACHE BOOL "capability macro that gates a JNI entry point")
set(MCA_T07_REQUIRED_BINDINGS ON CACHE BOOL "required macro that gates NO export (silent degradation)")
set(MCA_T07_ADD_SD ON CACHE BOOL "create the required target mca_t07_sd_native")

if(MCA_T07_CASE STREQUAL "MACRO_FLIP")
    # MACRO_FLIP turns the REQUIRED bindings macro off and leaves the capability
    # macro ON, so the export set stays byte-for-byte identical.
    set(MCA_T07_REQUIRED_BINDINGS OFF)
endif()
if(MCA_T07_CASE STREQUAL "MISSING_TARGET")
    set(MCA_T07_ADD_SD OFF)
endif()

set(MCA_T07_SOURCES
    t07_ok.cpp
    t07_gated.cpp
    t07_overload_exports.cpp)

# NOTE: the ORPHAN_TU defect is materialized by the driver as
# t07_dropped_tu.cpp in this directory without ever being listed here.  The
# template lives in pending/ so no case accidentally ships the defect.
add_library(t07_gate SHARED ${MCA_T07_SOURCES})
target_compile_definitions(t07_gate PRIVATE
    MCA_T07_FEATURE=$<BOOL:${MCA_T07_FEATURE}>
    MCA_T07_REQUIRED_BINDINGS=$<BOOL:${MCA_T07_REQUIRED_BINDINGS}>
    MCA_T07_DROP_INT_OVERLOAD=$<BOOL:$<STREQUAL:${MCA_T07_CASE},JNI_OVERLOAD>>)

if(MCA_T07_ADD_SD)
    add_library(mca_t07_sd_native SHARED t07_sd.cpp)
endif()
