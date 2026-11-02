# P0 baseline lock + build reproducibility evidence

Scope: this document fixes the inputs the MCA native video path is built from, the exact patch
queue that turns the pinned HappyYzy baseline into the working tree, and the hashes/ABI of every
artifact that was actually shipped or is on device 98a37aa7. Every number below came from a command
in this session; anything that could not be demonstrated is listed in
[§10 Reproducibility gaps](#10-reproducibility-gaps) and [§12 Not verified](#12-not-verified).

No Gradle build was run, no git commit was made, and no existing source file was modified. All
scratch data lives under `E:/MAC/build/repro-happyyzy/` (1.3 GB peak, see §11).

---

## 1. Baselines

### 1.1 MCA parent repository

| Item | Value | Source |
| --- | --- | --- |
| HEAD | `68b6eef1e67870b02bfef6afda2e625d36663da9` | `git -C E:/MAC rev-parse HEAD` |

### 1.2 `third_party/stable-diffusion.cpp` submodule (the tree the Gradle/Kotlin build actually uses)

| Item | Value | Source |
| --- | --- | --- |
| Revision | `16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0` (`master-663-be65ac7-1-g16b3846`) | `git submodule status` |
| `status --porcelain` entries | **21** | `git -C third_party/stable-diffusion.cpp status --porcelain \| wc -l` |
| Nested `ggml/` revision | `3f3d36f7146f5f1ed776b21bc251666aa0e41b7a` (`ggml : bump version to 0.12.0` lineage) | `git -C third_party/stable-diffusion.cpp/ggml rev-parse HEAD` |
| Nested `ggml/` local diff | ` M src/gguf.cpp` — 82 insertions, 15 deletions | `git -C .../ggml diff --stat` |

The 21 dirty entries (verbatim count, 16 modified incl. the `ggml` gitlink, 3 deleted, 2 untracked):

```
 D examples/server/frontend
 M ggml
 M include/stable-diffusion.h
 M src/conditioner.hpp
 M src/denoiser.hpp
 M src/diffusion_model.hpp
 M src/llm.hpp
 M src/model.cpp
 M src/model.h
 M src/name_conversion.cpp
 M src/stable-diffusion.cpp
 M src/tokenizers/bpe_tokenizer.cpp
 M src/tokenizers/t5_unigram_tokenizer.cpp
 M src/tokenizers/tokenize_util.cpp
 M src/tokenizers/tokenize_util.h
 M src/tokenizers/vocab/vocab.cpp
 M src/vae.hpp
 D thirdparty/libwebm
 D thirdparty/libwebp
?? src/qwen_image_2_1.hpp
?? src/qwen_image_2_1_vae.hpp
```

Two facts that matter for reproducibility (developed in §10):

* The submodule's `ggml` is **`3f3d36f…`, not HappyYzy `f8758628…`**, and it carries its own
  `src/gguf.cpp` edit. The Gradle path therefore builds a *different* ggml baseline than the one
  documented for the feature.
* Neither the submodule nor `third_party/patches/stable-diffusion.cpp-mca-android.patch` contains
  `generate_video_streaming` (`grep -rn generate_video_streaming third_party/stable-diffusion.cpp` →
  no match; `grep -c generate_video_streaming …/stable-diffusion.cpp-mca-android.patch` → `0`).
  The shipped `.so` that exports it cannot come from that path.

### 1.3 HappyYzy baseline (the video feature's engine)

| Item | Value | How verified |
| --- | --- | --- |
| sdcpp commit | `a63954dc794f6daade32c407da1a9c4bc2d73cf0` | tarball top-level directory is `stable-diffusion.cpp-a63954dc794f6daade32c407da1a9c4bc2d73cf0/` |
| sdcpp branch | `work/qualcomm-hexagon-optimizations` (given by the lead) | **not** verifiable from the archive: a git archive carries no branch ref, and the tree has no `.git` (`ls -a … \| grep -c '^\.git$'` → `0`) |
| ggml commit | `f8758628270072da26ad44baf5e189d4695d9800` | tarball top-level directory is `ggml-f8758628270072da26ad44baf5e189d4695d9800/` |
| ggml version | `0.24.0` | `CMakeLists.txt:6-9` → `GGML_VERSION_MAJOR 0 / MINOR 24 / PATCH 0`; string `0.24.0` is also embedded in the shipped `libstable-diffusion.so` |
| sdcpp version string | none static — `CMakeLists.txt:245-264` derives `SDCPP_BUILD_VERSION` from `git describe`, so an archive configures as `unknown` | read of pristine `CMakeLists.txt` |

Tarball hashes (inputs of record):

| Archive | Bytes | SHA-256 |
| --- | --- | --- |
| `E:/MAC/.dsh-tmp/happyyzy/sdcpp-a63954d.tar.gz` | 129,424,170 | `a891fc218e289305325046cac5b64aecc486b579d5ec2ff8ef40fc050f786853` |
| `E:/MAC/.dsh-tmp/happyyzy/ggml-f8758628.tar.gz` | 3,209,384 | `b334116da2682cfef5ce8d819e063019723d3f1d9798aaa57b21bbfba458adee` |

Archive coverage (this is the reason for the one remaining diff in §9):

| Tree | Files | Note |
| --- | --- | --- |
| sdcpp archive | 418 | holds pristine copies of all 13 files that later changed; does **not** hold `src/pipeline/video_stream_result.h` (new); `ggml/` is an **empty placeholder directory** |
| pristine sdcpp + pristine ggml wired into `ggml/` | 1,788 | what `patchwork/` and `verify/` were built from |
| extracted working tree | 1,811 | 1,789 patched-tree files + 22 `ggml/tests/*` files |
| ggml archive | 1,370 | **`tests/` is absent**: `tar -tzf …ggml…tar.gz \| grep -c '/tests/'` → `0` |

Extraction result: both archives produced exactly **one** top-level directory each
(`pristine/stable-diffusion.cpp-a63954dc794f6daade32c407da1a9c4bc2d73cf0/`,
`pristine/ggml-f8758628270072da26ad44baf5e189d4695d9800/`).

---

## 2. Toolchain as actually present

| Component | Value | Source |
| --- | --- | --- |
| NDK dirs (Windows SDK) | `29.0.13113456`, `29.0.14206865` | `ls E:/model/android-sdk/ndk/` |
| NDK used by the WSL build | `/e/tools/ndk-linux/android-ndk-r29`, `Pkg.Revision = 29.0.14206865` (r29) | `source.properties`; scripts `hy-build.sh`, `e1-build-cli.sh` |
| CMake on PATH (Windows) | 4.4.3 | `cmake --version` |
| SDK CMake installs | 3.22.1 (pinned by Gradle, `ninja 1.10.2`), 3.31.6 | `ls E:/model/android-sdk/cmake/`; `core/sd-native/build.gradle.kts:90` `version = "3.22.1"` |
| CMake used by the WSL build | ≥ 4.4.4 from `/root/.local/bin` (needed because the HTP skeleton sub-projects require `cmake_minimum_required(3.22.2)`) | comment in `.dsh-tmp/hexagon-build-htp.sh`; exact binary **not** re-executed here |
| Hexagon SDK | `E:/tools/hexagon-sdk-extract/6.7.0.0`, version **6.7.0.1** | `hexagon_sdk.json` → `root.id.version` |
| QAIRT / QNN SDK | `E:/tools/qairt-sdk/2.45.0.260326` | directory listing; used by the image path, not by this video build |
| Generator | Ninja | `.cxx/…/CMakeCache.txt` (`CMAKE_MAKE_PROGRAM`) |

Stale local cache — `E:/MAC/core/sd-native/.cxx/Release/2f22365k/arm64-v8a/CMakeCache.txt` still
records `ANDROID_NDK=D:\model\android-sdk\ndk\29.0.13113456` and
`CMAKE_MAKE_PROGRAM=D:\model\android-sdk\cmake\3.22.1\bin\ninja.exe`. Those `D:` paths do not exist
in this environment (the SDK is on `E:`), so that cache is from a previous machine layout and cannot
be reused as-is.

---

## 3. Patch queue: pristine → working tree

Directory: `docs/proposals/mca-native-video-workflow-v1/patches/`.
Every patch starts with three `#` comment lines (source files / what it changes / why it is required).
Paths are relative to the sdcpp tree root with the pinned ggml wired in at `ggml/`; apply in numeric
order with `git apply -p1` from that root.

| # | Patch | Bytes | SHA-256 | Files touched | Logical change |
| --- | --- | --- | --- | --- | --- |
| 0001 | `0001-ggml-gguf-5d-stacked-shape.patch` | 3,519 | `16cfb1bc392ad66e1b3f9d59fb453277bd12eedb5b6ce858a6f0c84f3064c9cc` | `ggml/src/gguf.cpp` | GGUF reader accepts >4D shapes and folds them into `ne[3]` |
| 0002 | `0002-ggml-hexagon-skeleton-name-env.patch` | 1,346 | `166799b8f3eb4dd6d1e1333b6c664740988aec43c008fdd784611a9de6737ba6` | `ggml/src/ggml-hexagon/ggml-hexagon.cpp` | HTP skeleton basename from `MCA_HTP_SKEL_NAME` (default `libggml-htp`, unchanged when unset) |
| 0003 | `0003-vae-temporal-tile-sink-interface.patch` | 16,307 | `7f88c346034dd90ec37a4fb64173dd4d3172bb9069c8b139caea20d6cca71d72` | `src/model/vae/vae_tiling.hpp`, `vae.hpp`, `ltx_vae.hpp`, `minimax_h3_vae.hpp` | `VAETileSink`/`VAETileSinkStatus` contract + streaming tile loop; LTX and MiniMax H3 accept and ignore the sink |
| 0004 | `0004-wan-vae-chunk-streaming-decode.patch` | 3,796 | `b426b24b28d205f200a4e6236dcfacf47ad350dd9b912c1bbd6c37dfbebb552d` | `src/model/vae/wan_vae.hpp` | Wan stateful chunk decode delivers each tile to the sink, never builds the whole-video tensor |
| 0005 | `0005-engine-decode-first-stage-sink.patch` | 4,712 | `7504fd2cc482d407dc7ef8b9c8ae8767c48c2f362f065083dff2734874395bae` | `src/pipeline/diffusion_engine.h`, `.cpp` | sink threaded through `decode_first_stage`; no retry-tiling after a streaming attempt |
| 0006 | `0006-video-pipeline-streaming-sink.patch` | 18,690 | `d993d39c81f53ad06d53bd76bb5ea20050838c05089a356fb7aedf5141591a5f` | `src/pipeline/video.cpp`, `generation.h`, `video_stream_result.h` (new) | frame sink path in `decode_video_outputs`, AnimateDiff forwarding, `MCA_PER_FRAME_DECODE` fallback, success decided by delivered frames |
| 0007 | `0007-public-generate-video-streaming.patch` | 4,254 | `d485d4cd313ef255ab0c29afa636a09f4a9b1cbbf6b2e92187e56eeca12a534b` | `include/stable-diffusion.h`, `src/stable-diffusion.cpp` | public `generate_video_streaming()` + `sd_video_frame_sink_t` |
| 0008 | `0008-cli-video-incremental-out.patch` | 8,906 | `2cbfd131dfc021c4916f9d33c7df77f08df6027d14ad93394d2231a1aac93ed0` | `examples/cli/main.cpp` | `--video-incremental-out <dir>`, per-frame PNG via temp-rename, cleanup on real failure |

### 3.1 Actual changed-file list (15 files, confirmed by diff, not assumed)

```
examples/cli/main.cpp                        +149
ggml/src/ggml-hexagon/ggml-hexagon.cpp         +3 -1
ggml/src/gguf.cpp                             +29 -16
include/stable-diffusion.h                    +23
src/model/vae/ltx_vae.hpp                     +11 -1
src/model/vae/minimax_h3_vae.hpp              +11 -1
src/model/vae/vae.hpp                         +84 -9
src/model/vae/vae_tiling.hpp                  +66
src/model/vae/wan_vae.hpp                     +29 -3
src/pipeline/diffusion_engine.cpp             +31 -2
src/pipeline/diffusion_engine.h                +8 -1
src/pipeline/generation.h                      +9 -1
src/pipeline/video.cpp                       +252 -5
src/pipeline/video_stream_result.h            +37 (new)
src/stable-diffusion.cpp                      +38
```

No file differs by line endings or temp artifacts: every changed file has LF endings on both sides
(`diff -u` produced byte-level hunks, not whole-file rewrites), and no `.orig`, `.rej`, `*.bak`,
`build/`, or tool state exists inside the working tree (`diff -rq` between the trees reported only
the 15 files and the `ggml/tests` directory).

### 3.2 Expected-but-absent changes

* `ggml/src/ggml-hexagon/*` skeleton-name change is real, but it is a **one-line URI construction**
  in `ggml-hexagon.cpp` only — no other file in that directory changed.
* There is **no** Wan VAE "stateful chunk decode" beyond patch 0004's streaming branch: the chunk
  state, `static_cast<int>(tile.start)` global chunk index and cache teardown already exist in the
  pristine file.

---

## 4. Why the 5D-shape patch is necessary, not cosmetic

### 4.1 Pristine code (verbatim, `pristine/ggml-…/src/gguf.cpp`)

```cpp
667:        // tensor shape
668:        {
669:            uint32_t n_dims = 0;
670:            ok = ok && gr.read(n_dims);
671:            if (n_dims > GGML_MAX_DIMS) {
672:                GGML_LOG_ERROR("%s: tensor '%s' has invalid number of dimensions: %" PRIu32 " > %" PRIu32 "\n",
673:                    __func__, info.t.name, n_dims, GGML_MAX_DIMS);
674:                ok = false;
675:                break;
676:            }
```

`GGML_MAX_DIMS` is `4` (`ggml/include/ggml.h:222`). The `break` leaves the tensor-info loop, and the
loader converts the error into a total failure:

```cpp
758:    if (!ok) {
759:        GGML_LOG_ERROR("%s: failed to read tensor info\n", __func__);
760:        gguf_free(ctx);
761:        return nullptr;
762:    }
```

So a single >4D tensor makes `gguf_init_from_file` return `nullptr` — the model cannot be loaded at
all, and every tensor after it is never even read.

### 4.2 The Wan GGUF really contains a 5D tensor

Parsed the GGUF header of the on-device model (first 32 MB pulled, `gguf-head32m.bin`):

```
magic b'GGUF' version 3 n_tensors 825 n_metadata_kv 3
general.architecture = wan
tensor_info_offset 144
n_dims distribution: {1: 487, 2: 306, 3: 31, 5: 1}
tensors with n_dims>4: 1
  5D+: ('patch_embedding.weight', 5, (2, 2, 1, 48, 3072), 0, 2848881024)
```

`patch_embedding.weight` is tensor index 814 of 825 (0-based declaration order; 589,824 elements;
ggml type `0` = F32), so upstream reads tensors 0–814 and then aborts — the file is unloadable, not
merely missing one weight. With patch 0001 it folds to `(2, 2, 1, 48*3072=147456)`; element count
589,824 is unchanged, and the log line
`tensor '%s' stacked %uD shape into 4D` (present in the shipped `.so`) records it.

---

## 5. Binary artifacts

All hashes are full-file SHA-256 on the local filesystem unless the row says "device".

| Artifact | Bytes | SHA-256 |
| --- | --- | --- |
| `core/sd-native/src/main/jniLibs/arm64-v8a/libstable-diffusion.so` | 162,473,432 | `1f320b7309fa72fabb550a7ab4acf944fa2e476421cd91dd0ae7d5ee856d4301` |
| `core/sd-native/src/main/jniLibs/arm64-v8a/libmca_wan.so` | 160,672 | `2f6f8d09c3b1fc6638d22b046383fd4ebfd64b14e6e816c41f9f9a776f908f82` |
| merged `:app:mergeDiagnosticNativeLibs` copy of the engine | 162,473,432 | `1f320b73…d4301` (identical to jniLibs) |
| merged copy of the adapter | 160,672 | `2f6f8d09…908f82` (identical to jniLibs) |
| `build/evidence/v1-27/mca_wan_probe.new` (typed-ABI probe, sigma schedule `1.0/0.25/0.0`) | 11,896 | `84e54ff89eb3b581c30fcc53fd090c00aa6a97d899af98feaf614a2043af957b` |
| `build/evidence/v1-27/mca_wan_abi_test` (ABI guard test) | 8,176 | `a71098b25bd30a24d4c2c3c6733759e1e244035a49b4566ba14cc1a210251203` |
| `build/evidence/v1-27/mca_htp_canary` (real HTP session + graph canary) | 12,248 | `c0aa2114f78a4790ba5f4a287d8a1110ebd6cf5bfd1ad7df90b2f21b020dac5a` |

Previous shipping pair, kept for provenance: engine `4d7cff47e2feb1ebbe55d24d9e12d8d71e69f8085bc27d0f635092f897fd5f6f`
(163,228,224 B) and adapter `3721fad2c3c79f2078f41a7e5928e0b2c7f3de5062628f953a001863f70c3762`
(49,360 B). The adapter hash listed in earlier revisions of this table (`d8a94ca8…`) was the
`.dsh-tmp/mca-wan-shim` copy, **not** the file that actually sat in `jniLibs`; the shipped file was
`3721fad2…`, built before `prep_ms` existed. Mixing it with a probe/engine from the current header is
exactly what produced the `prep_ms=21474836485` reading (see §5.1).
| `core/sd-native/src/main/jniLibs/arm64-v8a/libggml-htp-mca-v79.so` | 930,472 | `af4cc82a89959252b9d4489913df19d6c94a6729a541fe7db0ca759d9b3fd205` |
| device `/data/local/tmp/hy98/libstable-diffusion.so` | 163,237,312 | `f4dbd898136d3477338eb64d330f968476e28fbc8958f79f0b4da80e375b085d` |
| device `/data/local/tmp/hy98/sd-cli` | 11,768,136 | `3e0577fa2bfab39fd862b6bcaf4ad4af8f2ce298d97129dec551750530832738` |
| device `/data/local/tmp/hy98/libggml-htp-v79.so` | 930,472 | `af4cc82a89959252b9d4489913df19d6c94a6729a541fe7db0ca759d9b3fd205` |
| device `/data/local/tmp/hy98/libmca_wan.so` | 47,832 | `5abb3c34885348ff431d5f93fdc100ef748481648451e39fefa1c5b1b116910e` |
| device `/data/local/tmp/hy98/libggml-htp-mca-v79.so` | 930,472 | `af4cc82a89959252b9d4489913df19d6c94a6729a541fe7db0ca759d9b3fd205` |

Provenance matches found by hashing the retained build outputs (this is what ties the binaries to
the tree, not just the path strings):

| Local build output | SHA-256 | Matches |
| --- | --- | --- |
| `.dsh-tmp/happyyzy-build/bin/libstable-diffusion.so` | `f4dbd898…b085d` | device `/data/local/tmp/hy98/libstable-diffusion.so` |
| `.dsh-tmp/happyyzy-build/bin/sd-cli` | `3e0577fa…32738` | device `/data/local/tmp/hy98/sd-cli` |
| `.dsh-tmp/happyyzy-build/ggml/src/ggml-hexagon/libggml-htp-v79.so` | `af4cc82a…f205` | **both** packaged `libggml-htp-mca-v79.so` and device `libggml-htp-v79.so` |
| `.dsh-tmp/mca-wan-shim/libmca_wan.so` | `d8a94ca8…7b72` | packaged `libmca_wan.so` (device copy differs) |

### 5.1 ABI drift found while validating the reproducible engine

The first run of the reproducible engine (`1f320b73…`) on device reported
`timing load=711 prep=21474836485 sample=117948 …`. `21474836485` is `(5 << 32) | 5`, i.e. two
`int32` fields (`frames_written=5`, `delivered_frames=5`) read as one `int64`. The adapter on that
staging directory was the then-shipped `3721fad2…`, built **before** `prep_ms` was added to
`mca_wan_result_v2`; the probe and engine were built with the current header. The old adapter's
`struct_size <` check passed because the caller's struct was *larger*, and it then wrote every field
after `total_ms` eight bytes early.

Fix (adapter `fc138b07…`):

- `struct_size` must now equal the callee's `sizeof` exactly, in both directions;
  a mismatch is `MCA_WAN_ERR_ABI_MISMATCH` with both sizes printed, never a silent garbage read.
- `mca_wan_abi_test` exercises stale request, stale result and wrong-version calls on device
  (`ABI-GUARD PASS`), without loading a model.
- the probe prints `abi=` and both `sizeof` values at start-up.
- the adapter now creates/validates `out_dir` before decoding, so a caller no longer has to
  pre-create it (a missing directory used to surface only after sampling finished).

The same adapter change also means any older engine/probe pair is rejected loudly instead of
producing plausible-looking timings; that is why the packaged engine and adapter had to be
replaced together.

Field-semantics findings from the same device round:

- `init_image` is really consumed (`encode_first_stage` 1.17 s; frames differ from the identical T2V
  request), and `custom_sigmas` really reach the sampler (schedule `1.0/0.25/0.0` produces different
  frames than the default 2-step schedule).
- `end_image` is **not** honoured for Wan 2.2 TI2V-5B: the engine only conditions on an end image in
  its FLF2V / MiniMax-H3 / LTXAV / Hunyuan paths, and a TI2V run with an end image produced
  byte-identical frames to T2V. The adapter (`2f6f8d09…`) now refuses such a request with
  `MCA_WAN_STATUS_UNSUPPORTED` / `MCA_WAN_ERR_UNSUPPORTED_FIELD` and `unsupported_field=end_image_path`
  in 628 ms, instead of accepting a field it would silently drop.

Corroborating source-level evidence inside the packaged `libstable-diffusion.so`:

* The embedded debug paths point at the working tree:
  `/mnt/e/MAC/.dsh-tmp/happyyzy/stable-diffusion.cpp-a63954dc794f6daade32c407da1a9c4bc2d73cf0/src/...`.
* Patch-specific strings are present: `vae temporal tile %d/%d`, `vae temporal tile stream stopped by
  the consumer`, `streaming video tile %d`, `tensor '%s' stacked %uD shape into 4D`,
  `LTX VAE does not stream temporal tiles`, `MiniMax H3 VAE does not stream temporal tiles`.
* `MCA_HTP_SKEL_NAME` (patch 0002, source mtime 2026-10-31 01:02:54) and `MCA_PER_FRAME_DECODE`
  (patch 0006, source mtime 2026-10-31 00:55:42) are **absent** from the packaged `.so` (mtime
  2026-10-30 21:45) but **present** in the device `.so` (built 01:04). Every patched file's mtime
  precedes 01:04, so the device `.so` is consistent with the current working-tree state; the packaged
  one is one build older.

---

## 6. Model assets (device 98a37aa7)

Full-file hashes, no sampling: the five files in `/data/local/tmp/wan98` (13,841,300 KiB per `ls`)
hashed in 12.10 s on device, and the LoRA (332 MB) in 0.31 s, so partial hashing was unnecessary.

| Path | Bytes | SHA-256 |
| --- | --- | --- |
| `/data/local/tmp/wan98/Wan2.2-TI2V-5B-Q4_0.gguf` | 3,029,086,560 | `fcf40dd62cb5e15556eef5f975700ab0b071aacd1939607689276e88f81ba463` |
| `/data/local/tmp/wan98/Wan2.2_VAE.safetensors` | 1,409,400,960 | `e40321bd36b9709991dae2530eb4ac303dd168276980d3e9bc4b6e2b75fed156` |
| `/data/local/tmp/wan98/umt5-xxl-encoder-Q4_K_M.gguf` | 3,655,145,312 | `17cf97a5bbbc60a646d6105b832b6f657ce904a8a1ad970e4b59df0c67584a40` |
| `/data/local/tmp/wan98/umt5-xxl-encoder-Q8_0.gguf` | 6,043,068,256 | `2521d4de0bf9e1cc6549866463ceae85e4ec3239bc6063f7488810be39033bbc` |
| `/data/local/tmp/wan98/taew2_2.safetensors` | 22,848,048 | `b84609b2a133d48434bd9636bfcb44bf05168dc436e2d3cecf26256faa1f5325` |
| `/data/local/tmp/Wan22_TI2V_5B_Turbo_lora_rank_64_fp16.safetensors` | 332,348,584 | `0ace5244e3d1256f884662c261b017249796cf5b95f05d5ed93cc02a478967b8` |

Raw capture: `build/repro-happyyzy/device/models.txt`.

### 6.1 PC-side re-hash of the same six files (2026-11-02 06:08–06:10Z)

Full-file SHA-256 recomputed on this workstation over the local copies; every value equals the
device row above, so the shipped bytes are the same files that are archived locally.

| Local path | Bytes | SHA-256 | Matches device |
| --- | --- | --- | --- |
| `D:/mca-models/wan22/dit/Wan2.2-TI2V-5B-Q4_0.gguf` | 3,029,086,560 | `fcf40dd62cb5e15556eef5f975700ab0b071aacd1939607689276e88f81ba463` | yes |
| `D:/mca-models/wan22/VAE/Wan2.2_VAE.safetensors` | 1,409,400,960 | `e40321bd36b9709991dae2530eb4ac303dd168276980d3e9bc4b6e2b75fed156` | yes |
| `D:/mca-models/wan22/text_encoder/umt5-xxl-encoder-Q8_0.gguf` | 6,043,068,256 | `2521d4de0bf9e1cc6549866463ceae85e4ec3239bc6063f7488810be39033bbc` | yes |
| `E:/MAC/.tmp-models/umt5-xxl-encoder-Q4_K_M.gguf` | 3,655,145,312 | `17cf97a5bbbc60a646d6105b832b6f657ce904a8a1ad970e4b59df0c67584a40` | yes |
| `E:/MAC/.tmp-models/taew2_2.safetensors` | 22,848,048 | `b84609b2a133d48434bd9636bfcb44bf05168dc436e2d3cecf26256faa1f5325` | yes |
| `E:/MAC/.tmp-models/Wan22_TI2V_5B_Turbo_lora_rank_64_fp16.safetensors` | 332,348,584 | `0ace5244e3d1256f884662c261b017249796cf5b95f05d5ed93cc02a478967b8` | yes |

Raw capture: `build/evidence/v1-27/MODEL-HASH-LOCAL-20261102.txt` (`sha256sum`, exit 0).

**Licenses are NOT yet recorded.** No LICENSE/NOTICE file ships alongside these files locally,
`THIRD_PARTY_NOTICES.md` has no Wan/UMT5/TAE/Turbo-LoRA entry, and `D:/mca-models/download.log`
contains no repository id, so the exact upstream repo (and therefore the binding license text) of
the Q4_0 / Q8_0 / Q4_K_M quantizations is unverified. Closing that row needs one recorded source
repo + LICENSE per asset, not a guess from the file names.

---

## 7. ABI dump

Tool: `E:/model/android-sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe`.

### 7.1 `libstable-diffusion.so`

* ELF64, AArch64, `DYN`, build-id `bf3a5492c263986bb5b4075631eeaef55529eacd`.
* `DT_NEEDED`: `libomp.so`, `libm.so`, `libdl.so`, `libc.so`. `SONAME: libstable-diffusion.so`.
* 2,765 exported `FUNC/GLOBAL/DEFAULT` symbols.
* Exported `sd_*` / `generate_*` entry points relevant to the shim:

```
free_sd_audio              free_sd_ctx               free_sd_images
generate_video             generate_video_streaming  new_sd_ctx
sd_cancel_generation       sd_ctx_params_init        sd_ctx_supports_video_generation
sd_img_gen_params_init     sd_set_log_callback       sd_set_progress_callback
sd_vid_gen_params_init     sd_version                sd_list_devices
(… plus the remaining sd_* name/param helpers)
```

### 7.2 `libmca_wan.so` (JNI-side shim)

* ELF64, AArch64, `DYN`, no `PT_NOTE`/build-id.
* `DT_NEEDED`: `libstable-diffusion.so`, `libc++_shared.so`, `libm.so`, `libdl.so`, `libc.so`.
* Exports exactly two functions: **`mca_wan_generate`**, **`mca_wan_cancel`** (both present).
* Imports from `libstable-diffusion.so`: `generate_video_streaming`, `new_sd_ctx`, `free_sd_ctx`,
  `sd_ctx_params_init`, `sd_vid_gen_params_init`, `sd_set_log_callback`. This is why patch 0007
  cannot be dropped: the shim links the streaming symbol directly.
* **Mismatch found:** the packaged shim does **not** export `mca_wan_generate_v2`
  (`llvm-readelf --dyn-syms … | grep -c v2` → `0`), while the in-repo consumer
  `core/sd-native/src/main/cpp/sd_video_generate.cpp:204-218` `dlopen`s `libmca_wan.so` and fails with
  "has no mca_wan_generate_v2 symbol" if it is missing. The packaged shim is the v1 prototype from
  `.dsh-tmp/mca-wan-shim/` (144-line `mca_wan_api.cpp`), not the v2 contract in
  `core/sd-native/src/main/cpp/mca/wan/mca_wan_api_v2.h`.

### 7.3 `libggml-htp-mca-v79.so` (HTP skeleton)

* ELF32, machine **Qualcomm Hexagon**, `DYN`.
* `SONAME: libggml-htp-v79.so`; `DT_NEEDED`: `libc.so`, `libgcc.so`.
* Byte-identical to the device's `libggml-htp-v79.so` **and** the device's `libggml-htp-mca-v79.so`
  (all three are `af4cc82…`); only the packaged filename carries the `mca` infix, which is what patch
  0002's `MCA_HTP_SKEL_NAME` exists for. The skeleton is unaffected by patch 0002 because that patch
  is host-side (`ggml-hexagon.cpp` is compiled into `libstable-diffusion.so`, not into the skeleton).

---

## 8. Patch application procedure (operational gotchas)

Two environment behaviours had to be neutralised; both silently corrupt a naive run:

1. **`core.autocrlf=true`** is set globally here. `git apply` then writes CRLF into the target while
   the working tree is LF, producing a whole-file diff. Always pass `-c core.autocrlf=false`.
2. `build/` is in `E:/MAC/.gitignore`. Because the scratch tree lives under `E:/MAC/build/…`, a plain
   `git apply` run *inside* the MCA repo treats every path as ignored and prints
   `Skipped patch '…'` with exit code 0. Set `GIT_CEILING_DIRECTORIES` to the scratch root (or run
   outside the repo) so git does not consider it part of the MCA work tree.

---

## 9. Apply-and-verify evidence

Fresh copy of the pristine tree, ggml wired into `ggml/`, all eight patches applied in order:

```bash
cd E:/MAC/build/repro-happyyzy
rm -rf verify && mkdir verify
cp -a pristine/stable-diffusion.cpp-a63954dc794f6daade32c407da1a9c4bc2d73cf0/. verify/
cp -a pristine/ggml-f8758628270072da26ad44baf5e189d4695d9800/. verify/ggml/
cd verify
export GIT_CEILING_DIRECTORIES=E:/MAC/build/repro-happyyzy
for p in ../../../docs/proposals/mca-native-video-workflow-v1/patches/0*.patch; do
  git -c core.autocrlf=false apply -p1 --verbose "$p"
done
diff -rq . ../../.dsh-tmp/happyyzy/stable-diffusion.cpp-a63954dc794f6daade32c407da1a9c4bc2d73cf0
```

Apply output (exit 0 for all eight):

```
0001: Applied patch ggml/src/gguf.cpp cleanly.
0002: Applied patch ggml/src/ggml-hexagon/ggml-hexagon.cpp cleanly.
0003: Applied patch src/model/vae/ltx_vae.hpp cleanly.
      Applied patch src/model/vae/minimax_h3_vae.hpp cleanly.
      Applied patch src/model/vae/vae.hpp cleanly.
      Applied patch src/model/vae/vae_tiling.hpp cleanly.
0004: Applied patch src/model/vae/wan_vae.hpp cleanly.
0005: Applied patch src/pipeline/diffusion_engine.cpp cleanly.
      Applied patch src/pipeline/diffusion_engine.h cleanly.
0006: Applied patch src/pipeline/generation.h cleanly.
      Applied patch src/pipeline/video.cpp cleanly.
      Applied patch src/pipeline/video_stream_result.h cleanly.
0007: Applied patch include/stable-diffusion.h cleanly.
      Applied patch src/stable-diffusion.cpp cleanly.
0008: Applied patch examples/cli/main.cpp cleanly.
```

`diff -rq` output, first run:

```
Only in ../../.dsh-tmp/happyyzy/stable-diffusion.cpp-a63954dc794f6daade32c407da1a9c4bc2d73cf0/ggml: tests
```

This single line is the **archive gap of §1.3**, not a patch defect: `ggml-f8758628.tar.gz` contains no
`tests/` directory (22 files), so no patch can create them. Copying that directory in from the working
tree (the input the tarball is missing) and re-running the same command:

```
$ diff -rq . ../../.dsh-tmp/happyyzy/stable-diffusion.cpp-a63954dc794f6daade32c407da1a9c4bc2d73cf0
$ echo $?
0
```

Supplementary per-file check — SHA-256 of all 15 patched files equal on both sides (`OK` for each):

```
OK   ggml/src/gguf.cpp
OK   ggml/src/ggml-hexagon/ggml-hexagon.cpp
OK   src/model/vae/vae_tiling.hpp
OK   src/model/vae/vae.hpp
OK   src/model/vae/ltx_vae.hpp
OK   src/model/vae/minimax_h3_vae.hpp
OK   src/model/vae/wan_vae.hpp
OK   src/pipeline/diffusion_engine.h
OK   src/pipeline/diffusion_engine.cpp
OK   src/pipeline/video_stream_result.h
OK   src/pipeline/video.cpp
OK   src/pipeline/generation.h
OK   include/stable-diffusion.h
OK   src/stable-diffusion.cpp
OK   examples/cli/main.cpp
```

Conclusion: the patch queue reproduces the working tree byte-for-byte, with the single documented
exception of `ggml/tests/`, which the ggml archive does not contain.

Exclusions used: none beyond the `ggml/tests` directory named above. There is no `.git`, no build
directory and no temp artifact inside the working tree, so nothing else had to be filtered.

---

## 10. Reproducibility gaps

These are the parts that this evidence does **not** close. None of them is a claim of successful
reproduction.

1. **The Gradle path does not build the shipped library.** `core/sd-native/src/main/cpp/CMakeLists.txt:29`
   compiles `third_party/stable-diffusion.cpp` (submodule `16b3846`, ggml `3f3d36f` + local
   `gguf.cpp` edit, plus the 268,931-byte `stable-diffusion.cpp-mca-android.patch`). That source has
   no `generate_video_streaming` and a different ggml baseline than HappyYzy `f8758628`. Running
   `:core:sd-native` would therefore produce a *different* `libstable-diffusion.so`, not the one in
   `jniLibs`. Wiring the HappyYzy tree + this patch queue into the Gradle build is still to be done.
2. **The shipped `.so` was built out-of-tree under WSL.** Recipe from `.dsh-tmp/hy-build.sh`:
   `cmake -S <tree> -B .dsh-tmp/happyyzy-build -G Ninja -DCMAKE_TOOLCHAIN_FILE=/mnt/e/tools/ndk-linux/android-ndk-r29/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release -DGGML_HEXAGON=ON -DHEXAGON_SDK_ROOT=/mnt/e/tools/hexagon-sdk-extract/6.7.0.0 -DPREBUILT_LIB_DIR=$HS/libs -DBUILD_SHARED_LIBS=ON -DGGML_BUILD_TESTS=ON -DSD_BUILD_EXAMPLES=OFF -DSD_BUILD_SHARED_LIBS=ON`.
   The exact CMake binary version (claimed ≥ 4.4.4 at `/root/.local/bin`) was not re-executed here.
   `sd-cli` came from a second configure with `-DSD_BUILD_EXAMPLES=ON -DGGML_BUILD_TESTS=OFF`
   (`.dsh-tmp/e1-build-cli.sh`) plus `cmake --build . --target sd-cli` (`.dsh-tmp/build-incr.sh`).
3. **The packaged `libstable-diffusion.so` matches no retained build output.** `4d7cff…` (163,228,224 B,
   mtime 2026-10-30 21:45) predates patches 0002 and the `MCA_PER_FRAME_DECODE` branch of 0006, and no
   local artifact with that hash/size exists. The device copy (`f4dbd898…`) is the one that matches
   `.dsh-tmp/happyyzy-build/bin/libstable-diffusion.so`. Two different engines are therefore in play:
   the APK payload and the device test engine.
4. **The shim has no build recipe in the repository.** `libmca_wan.so` is produced from
   `.dsh-tmp/mca-wan-shim/{mca_wan_api.cpp,mca_wan_api.h}` out-of-tree (only empty `.err` logs remain);
   no CMake target, Gradle task or script creates it, and the packaged file does not implement the v2
   ABI the app code expects (§7.2). The device shim (`5abb3c3…`, 47,832 B) is a third variant.
5. **The skeleton `.so` build inputs are recorded but not hash-locked.** They are `ggml/src/ggml-hexagon/`
   of the pinned ggml built by `ninja htp-v79` with Hexagon SDK 6.7.0.1 (`hexagon-build-htp.sh`,
   `hy-build.sh`), and the output is byte-identical to the packaged file — but there is no logged
   inputs→output manifest, and `.dsh-tmp/hexagon-build/bin/` is empty, so the successful artifact
   cannot be attributed to a specific configure invocation from the retained directories.
6. **The ggml archive is incomplete.** `tests/` (22 files) is missing from `ggml-f8758628.tar.gz`; the
   sdcpp archive ships an empty `ggml/` placeholder. Rebuilding the baseline therefore needs both
   archives plus the `tests/` tree from the working checkout, or a fresh clone at `f8758628`.
7. **Branch identity is not provable from the archive.** `work/qualcomm-hexagon-optimizations` is an
   input from the lead; the archive only proves the commit id.
8. **No rebuild was attempted.** Reproducibility of the *binaries* from the patched source is asserted
   only through (a) identical hashes with retained build outputs, (b) embedded debug paths, and (c)
   patch-specific strings. A from-scratch rebuild hash comparison is still outstanding (and would
   require the WSL NDK + Hexagon SDK setup).

---

## 11. Scratch footprint

`E:/MAC/build/repro-happyyzy/` total **1.3 GB** (limit was ~6 GB): `pristine/` 358 MB, `patchwork/`
501 MB, `verify/` 360 MB, `device/` 85 MB (32 MB of it the GGUF header slice). `E:` had 34 GB free
before and after.

## 12. Not verified

* `NOT_RUN` — any Gradle/CMake build of the patched tree (explicitly out of scope for P0).
* `NOT_RUN` — byte-level reproducibility of `libstable-diffusion.so` / `sd-cli` / skeleton from source.
* `NOT_RUN` — whether the packaged `libmca_wan.so` (v1 ABI) can satisfy any current app code path; only
  the symbol mismatch is recorded.
* `NOT_RUN` — the branch name of the HappyYzy sdcpp checkout (no ref metadata in the archive).
* Unchecked — `hexagon_sdk.json` beyond `root.id.version`; QAIRT release notes; `.cxx` cache is stale
  (D: paths) and was not regenerated.
