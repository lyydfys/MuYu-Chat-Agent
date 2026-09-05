# LiteRT Qualcomm dispatch runtime

`jniLibs/arm64-v8a/libLiteRtDispatch_Qualcomm.so` and
`jniLibs/arm64-v8a/libLiteRtCompilerPlugin_Qualcomm.so` are the official
Qualcomm dispatch/compiler plugins from LiteRT `v2.2.0`'s
`litert_npu_runtime_libraries_jit.zip` release asset. The dispatch/compiler
plugin binaries are common across the Qualcomm variants; the staged QNN
transport set below is the V81 build used for the SM8850 family. They were
downloaded from:

`https://github.com/google-ai-edge/LiteRT/releases/download/v2.2.0/litert_npu_runtime_libraries_jit.zip`

The vendored plugin SHA-256 is:

`c4abfff6c99ec218f545415a81a2a03a3ee3e21df2ea911902d6b7bbfeda80bf`

The compiler plugin SHA-256 is:

`425e5caf007f834748c6bf67aff265d7e21512a01910f219fab6b7749ef57732`

The app-private `runtime-assets` tree follows the five-file Qualcomm layout
used by the Edge Gallery APKs (dispatch, QNN system/HTP, and the matching HTP
Stub/Skel pair). Its QNN/HTP binaries are the matching QAIRT 2.47 set; the
dispatch binary is the LiteRT v2.2.0 build paired with this app's LiteRT-LM
0.16.x runtime. Precompiled Qualcomm models require only dispatch plus the
coherent QNN/HTP set. The compiler plugin is optional: when a caller provides
it in the selected variant directory, or once in the generic V81 asset root,
the stager carries it into the private directory; the production APK sync
currently excludes it because its QNN IR/Saver dependencies must be shipped
as a matching generic/JIT QAIRT set. This keeps the precompiled path on one
coherent QAIRT runtime instead of resolving the GenieX AAR's QAIRT `2.45.0`
libraries. The vendored LiteRT v2.2 plugin and QNN set are built against QAIRT
`2.47.0.260601114230`; a graph-only bundle may select these assets only when it
declares that exact build. Other or incomplete build IDs continue through the
generic runtime path, where native load and graph smoke remain the compatibility
authority. Gradle verifies the packaged asset sets, while the app-private stager
verifies every runtime file's length and SHA-256 before publication.

## Edge Gallery transport selection

The Edge Gallery Qualcomm APKs use the same LiteRT dispatch entry point with an
HTP transport matched to the target family:

| SoC hint | Edge Gallery reference | HTP files | MCA staged variant |
|---|---|---|---|
| `SM8550` / `SM8550P` | SM8550 APK | `libQnnHtpV73Skel.so`, `libQnnHtpV73Stub.so` | `v73` |
| `SM8650` / `SM8650P` | SM8650 APK | `libQnnHtpV75Skel.so`, `libQnnHtpV75Stub.so` | `v75` |
| `SM8750` / `SM8750P` | SM8750 APK | `libQnnHtpV79Skel.so`, `libQnnHtpV79Stub.so` | `v79` |
| `SM8850` / `SM8850P` | SM8850 APK | `libQnnHtpV81Skel.so`, `libQnnHtpV81Stub.so` | `v81` |
| missing, unknown, or another SoC | no device-specific claim | OEM/APK generic discovery | no packaged override |

The SoC value only selects a packaged transport preference. It never blocks
model import, download, or load. An unknown value continues through generic
OEM/APK discovery, and real LiteRT native load/graph execution decides whether
that path works. The V81 asset layout is retained for explicit `SM8850`
selection, while an explicit `SM8750` hint selects the V79 asset subtree. The
returned stage records the selected variant, fingerprint prefix, and whether
the stage was reused; when staging is unavailable, the caller continues with
its generic native-directory path and preserves the same runtime-load
authority.
