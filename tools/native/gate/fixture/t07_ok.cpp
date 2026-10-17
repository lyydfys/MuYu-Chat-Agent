// T07 gate fixture - control TU.  Always listed in target_sources.
//
// Plain extern "C" definitions are used instead of <jni.h> so the fixture has
// no header dependency; the JNI long name the JVM resolves depends only on the
// extern "C" symbol name, not on the declared parameter types.

extern "C" void Java_com_muyuchat_t07_Gate_nativeOk(void*, void*, int) {}

extern "C" int t07_fixture_marker(void) { return 1; }
