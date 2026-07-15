# GenieX Android 0.3.12 MCA vendor patch

This directory contains a locally vendored copy of Qualcomm's
`com.qualcomm.qti:geniex-android:0.3.12` AAR with one JNI lifetime fix.

- Upstream: <https://github.com/qualcomm/GenieX>
- Upstream tag: `v0.3.12`
- Upstream commit: `15e1a121d29d320ace1336000cf994b74cf67c9e`
- Local version: `0.3.12-mca1`
- Patch: `0001-reserve-llm-chat-message-string-storage.patch`

Only `jni/arm64-v8a/libnpu_jni.so` differs from the official AAR. The
patched library reserves storage for both strings in every LLM chat message
before retaining `c_str()` pointers. This prevents vector growth from
invalidating role/content pointers while JNI is assembling multi-message
input.

The project dependency is intentionally not switched by preparing this
artifact. Integration must happen only after the product gate accepts it.

## Integrity

- Official AAR SHA-256:
  `0D76FA11CD0A1F89069B87AF87A9E1D3CC7B82AB9A5D44796165C8325B1831D2`
- Official `libnpu_jni.so` SHA-256:
  `6806BA6D48020D19B21033288B6C936B72BBC26CFEC19596498A437C0F0A1A38`
- Patched `libnpu_jni.so` SHA-256:
  `90FCD8AB1C80D41EB8FE622901DE86F21DBFB1A1399ECFB59CF378FB747A8251`
- Vendored AAR SHA-256:
  `089F266569D1D9BAFBC7F5D5748FBDCE332FE0EB4077B9D49DBE3F1D50950401`

The official and vendored AARs both contain 59 entries. Excluding
`jni/arm64-v8a/libnpu_jni.so`, all entry names, uncompressed sizes, and
SHA-256 values are identical.

## ABI gate

The patched library is Android arm64 (`ELF64`, `AArch64`) and preserves:

- SONAME: `libnpu_jni.so`
- DT_NEEDED: `liblog.so`, `libgeniex.so`, `libm.so`, `libdl.so`, `libc.so`
- all 623 strong dynamic exports
- all 32 JNI exports, including `JNI_OnLoad`

Compiler-generated weak libc++ template symbols are not part of the JNI ABI
gate. They may vary with optimization and template instantiation, while all
strong and JNI exports remain identical.

## Build provenance

- CMake: `3.22.1`
- Android NDK: `29.0.14206865` (r29)
- ABI: `arm64-v8a`
- Android platform: `android-27`
- Build type: `Release`
- Link input: the official AAR's unmodified `libgeniex.so`

Qualcomm's BSD 3-Clause license and upstream notice are retained as
`LICENSE-GenieX.txt` and `NOTICE-GenieX.txt`.
