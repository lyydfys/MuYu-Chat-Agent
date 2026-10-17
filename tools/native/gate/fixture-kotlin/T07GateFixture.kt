// T07 gate fixture - Kotlin source analyzed (NOT compiled) by
// tools/native/gate/check-jni-overload-exports.ps1.
//
// This file models what the JVM will actually try to resolve.  It is parsed
// statically, exactly like the product bridge declarations under
// core/native/src/main/java/com/muyuchat/core/nativebridge, so the checker can
// be proven to require the exact JNI overload signature instead of accepting
// any `__*` suffix.

package com.muyuchat.t07

class Gate {
    init {
        System.loadLibrary("t07_gate")
    }

    external fun nativeOverloaded(value: Int): Unit
    external fun nativeOverloaded(value: Long): Unit
    external fun nativeOk(): Int
}
