// T07 gate fixture - the JNI_OVERLOAD defect.
//
// Two Kotlin declarations of `nativeOverloaded` exist (Int and Long), so the
// JVM resolves the mangled names with a signature suffix:
//     Java_com_muyuchat_t07_Gate_nativeOverloaded__I   (Int)
//     Java_com_muyuchat_t07_Gate_nativeOverloaded__J   (Long)
//
// MCA_T07_DROP_INT_OVERLOAD=1 (case JNI_OVERLOAD) removes the __I export while
// the build still succeeds.  A checker that accepts any `__*` suffix reports
// PRESENT; the real JVM throws UnsatisfiedLinkError for the Int overload.

extern "C" void Java_com_muyuchat_t07_Gate_nativeOverloaded__J(void*, void*, long long) {}

#if !MCA_T07_DROP_INT_OVERLOAD
extern "C" void Java_com_muyuchat_t07_Gate_nativeOverloaded__I(void*, void*, int) {}
#endif

extern "C" int t07_overload_marker(void) { return 1; }
