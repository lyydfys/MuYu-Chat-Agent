// T07 gate fixture - the required second library target.
//
// Real-world analogue: core/sd-native's `mca_sd_native` target, which lives in
// its OWN CMake project (core/sd-native/src/main/cpp/CMakeLists.txt) and a
// separate Gradle module, so a gate that only configures
// core/native/src/main/cpp never sees it at all.

extern "C" int mca_t07_sd_native_marker(void) { return 1; }
