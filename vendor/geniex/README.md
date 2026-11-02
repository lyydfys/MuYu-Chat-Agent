# GenieX Android vendor AAR

The app builds against the official Qualcomm GenieX Android AAR
`com.qualcomm.qti:geniex-android:0.10.0`, vendored here as
`geniex-android-0.10.0-mca1.aar`.

- Upstream: <https://github.com/qualcomm/GenieX>
- Upstream tag: `v0.10.0`
- Vendored AAR SHA-256:
  `3A40DEF4890FE4991D3547ACF8BCFA9F1BE491AD7790A1CB5D26BBECB2DDE69B`
- License: BSD-3-Clause (`LICENSE-GenieX.txt`, `NOTICE-GenieX.txt`)

MCA keeps its embedded llama.cpp/ggml DSOs under `mca_` output names
(`libmca_llama`, `libmca_llama-common`, `libmca_ggml`, `libmca_ggml-base`,
`libmca_ggml-cpu-*`). This lets GenieX's canonical `libllama`/`libggml` set
coexist in the same APK. GenieX's llama.cpp NPU path uses the AAR's canonical
set; MCA's embedded runner uses the prefixed set.

The 0.3.12 AAR and `0001-reserve-llm-chat-message-string-storage.patch` are
retained only as rollback/reference artifacts. The 0.10.0 AAR is unpatched;
do not apply the 0.3.12 patch to it.
