# MCA Qwen-Image-2.1 native runtime snapshot

This directory contains the pinned Android native dependency snapshot used to
integrate the `scsonic/libQwenImage21` MNN port. It contains **runtime code and
headers only**. It does not contain, fetch, or redistribute any model weights.

## Pinned source and licenses

- JNI/Qwen pipeline source: `https://github.com/scsonic/libQwenImage21`, commit
  `93a6f603729505250c1c4bb5b1a6597a0b8b9304`.
- MNN submodule: `https://github.com/scsonic/MNN`, commit
  `1965a2632b9d1197dff2ef364d307e40c150a5c7` (branch `qwen-image-21`).
- Port source and MNN are Apache-2.0; copies of both upstream license texts are
  included here. MNN's corresponding third-party license files from the pinned
  source tree are copied under `licenses/mnn/`. The separate converted Qwen model weights are governed by the
  Qwen Research License Agreement and are not covered by these code licenses.
  Review that agreement before redistributing or using weights commercially.
- Upstream model repository referenced by the port:
  `https://huggingface.co/evankuo/Qwen-Image-2.1-MNN`.

## Contents and ABI facts

- `arm64-v8a/libmca_qwenimage21_mnn.so` is an arm64 MNN OpenCL/diffusion
  runtime rebuilt from the pinned MNN submodule with a unique SONAME.
  SHA-256 is maintained in `SHA256SUMS.txt` and `arm64-v8a/runtime-manifest.json`.
- `arm64-v8a/libqwenimage21_jni.so` is the matching arm64 JNI build snapshot.
  SHA-256: `1A73A0966F42D3078CB2FCA2595ADF8C7E90BA00C3E30FC9A8A64C7CA397B8A5`.
- `arm64-v8a/libc++_shared.so` is the C++ runtime from Android NDK
  `29.0.14206865`, at
  `toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so`.
  SHA-256 is maintained in `SHA256SUMS.txt` and `arm64-v8a/runtime-manifest.json`.
- All three are ELF64 AArch64. The JNI object's SONAME is
  `libqwenimage21_jni.so`; its `DT_NEEDED` includes the unique
  `libmca_qwenimage21_mnn.so` and Android system libraries (`liblog.so`,
  `libandroid.so`, `libm.so`, `libc++_shared.so`, `libdl.so`, `libc.so`). The
  MNN runtime has SONAME `libmca_qwenimage21_mnn.so` and needs the same C++
  runtime plus Android system libraries. `libc++_shared.so` itself needs only
  `libc.so`, `libm.so`, and `libdl.so`.
- Keep all three binaries as assets. Extract them to the dedicated Qwen worker
  process's private directory and call `System.load` on absolute paths in this
  order: `libc++_shared.so`, `libmca_qwenimage21_mnn.so`, then
  `libqwenimage21_jni.so`. Do not load them in MCA's existing `:local_image`
  process or merge either MNN into `jniLibs` under the shared MNN filename.
- This snapshot only provides arm64-v8a. The app's x86_64 APK does not include
  the Qwen-Image-2.1 runtime and must not advertise this backend there.

The checked-in JNI binary is a build/reference artifact, not proof that the
model executes end to end in MCA. User-visible availability still requires a
real worker integration and device image-generation test.

## JNI bridge source

`../../core/qwenimage21/src/main/cpp/qwenimage21_jni.cpp` is copied from the
pinned source commit. It exports JNI entry points for the upstream Java class
`com.scsonic.qwenimage21.QwenImage21`:

- `nativeCreate(modelDir, useGpu, textEncoderOnCpu, vaeOnCpu, memoryMode, threads)`
- `nativeGenerate(handle, prompt, inputImage, outputPng, steps, seed, width, height, listener)`
- `nativeLastError(handle)`
- `nativeAvailableMemoryMB()`
- `nativeRelease(handle)`

The JNI source itself does not offer a native interrupt/cancel entry point.
Cancellation must therefore be implemented and validated at the isolated
worker-process lifecycle boundary before claiming cancel support.

## Rebuild the JNI bridge against the pinned MNN blob

Windows PowerShell example (the NDK revision is pinned to 29.0.14206865;
CMake 3.22.1 and Ninja are used for the verified build):

```powershell
$env:ANDROID_NDK = 'D:\model\android-sdk\ndk\29.0.14206865'
& 'D:\model\android-sdk\cmake\3.22.1\bin\ninja.exe' --version
& '.\vendor\qwen-image-2.1\build-jni.ps1' `
  -AndroidNdk $env:ANDROID_NDK `
  -Ninja 'D:\model\android-sdk\cmake\3.22.1\bin\ninja.exe'
```

The output is written outside the repository by default, under
`$env:TEMP\mca-qwenimage21-jni-arm64`. The source snapshot and MNN blob remain
unchanged. The upstream MNN build recipe is
`https://github.com/scsonic/libQwenImage21/blob/93a6f603729505250c1c4bb5b1a6597a0b8b9304/scripts/build_libmnn_android.sh`;
it enables Android arm64, MNN diffusion, LLM, OpenCL, OpenCV/image codecs,
low-memory mode, logcat logging, and shared libc++.

Rebuild the uniquely named MNN binary from the pinned submodule source (this
applies the project's memory-reclaim patch to the pinned checkout before
building):

```powershell
$env:QWEN21_MNN_SOURCE = 'E:\model\.tmp-qwen21\third_party\MNN'
$env:ANDROID_NDK = 'D:\model\android-sdk\ndk\29.0.14206865'
& '.\vendor\qwen-image-2.1\build-mnn-qwen21.ps1' `
  -MnnSource $env:QWEN21_MNN_SOURCE `
  -AndroidNdk $env:ANDROID_NDK `
  -Ninja 'D:\model\android-sdk\cmake\3.22.1\bin\ninja.exe' `
  -CopyToVendor
```

The script rejects any MNN revision other than
`1965a2632b9d1197dff2ef364d307e40c150a5c7`, builds out of tree, and sets
`OUTPUT_NAME mca_qwenimage21_mnn`. It was exercised against all 754 Ninja build
steps on 2026-09-24. NDK `llvm-readelf -d` verified the output SONAME is
`libmca_qwenimage21_mnn.so`; its `DT_NEEDED` contains no second MNN library.
The JNI bridge was then rebuilt
against it; the new `DT_NEEDED` points to `libmca_qwenimage21_mnn.so`, verified
with `llvm-readelf -d`.

On 2026-09-28, the Qwen-only MNN runtime and JNI bridge were rebuilt with the
advisory memory policy described below. The build and ELF checks passed; the
current hashes are recorded in the runtime manifest and `SHA256SUMS.txt`.
This rebuild does not by itself establish successful device image generation.

`patches/mnn/qwen-image21-runtime-adaptation.patch` is the MCA-owned delta on
top of that exact upstream commit. The build script applies it once to
`-MnnSource` (and recognizes it on later builds); the source checkout is
intentionally left patched. The patch aligns the runtime's DiT KV-cache input
and output names with the model graph's packed `past_kv` / `present_kv`
contract, verifies that the returned cache omits the masked prefill sentinel,
and derives the denoise attention-mask length from the returned cache. It
reclaims free executor and Android heap pages at the text-encoder/DiT stage
boundary. Stage memory estimates and the 400 MB margin are advisory reclaim
targets; they never reject text encoding, DiT, or VAE execution. A low
`MemAvailable` reading is not proof of an allocation failure, and a failed
stage is reported as a runtime failure unless a concrete allocation failure
was caught. An earlier detailed stage error is preserved. It also rejects fewer
than two flow-matching steps (one step produces a non-finite schedule) and
aborts immediately if sigma or denoising latents become non-finite.

The Android bundle is dynamically resized, but the product only advertises the
explicit sizes that have passed the MCA worker and PNG-output checks. The
verified grid is:

- **Standard:** 512x512, 576x448, 448x576, 640x416, 416x640, 672x384,
  384x672
- **Fast:** 384x384, 448x320, 320x448, 480x320, 320x480, 512x288, 288x512
- **Tiny:** 320x320, 384x288, 288x384, 384x256, 256x384, 416x256, 256x416

All dimensions are multiples of 32 and are bounded by the memory-safe
256--672 pixel envelope, but an arbitrary 32-aligned pair is still rejected
unless it is in this grid. The upstream model README's 2K examples require a
separate high-resolution export and must not be inferred to work with this
Android package. The runtime retains the requested model precision and image
dimensions while reclaiming only free/cached allocations. A successful build or
graph load is not proof of image generation; retain real-device PNG evidence
for each runtime update.

`libc++_shared.so` is copied from the exact NDK revision above and its SHA is
recorded in `SHA256SUMS.txt`; `licenses/NDK-29-toolchain-NOTICE.txt` is copied
from the same NDK's LLVM toolchain notice. Do not substitute a different NDK
runtime without rebuilding the native pair and refreshing all hashes.

### SONAME isolation

The original upstream prebuilt used SONAME `libMNN.so`; this MCA snapshot no
longer uses it. It rebuilds MNN from the exact pinned source and gives the
target a unique `OUTPUT_NAME`, then links JNI to the resulting SONAME. Do not
patch ELF string tables or claim name isolation based only on renaming a file.

## Hash manifest

`SHA256SUMS.txt` records hashes of the vendored binary and source/header files.
Regenerate it only when the corresponding pinned source, binary, or copied
license files are intentionally updated.

