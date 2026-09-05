# MCA MNN vendor overlay

The application uses upstream MNN `3.6.1` at commit
`d407447ed56c4121a11ccbd266dc184ca1ead0c2` plus the repository-owned
`mca-mnn-3.6.1.patch` overlay.

The complete upstream 3.6.1 runtime is required for models exported with its
Transformer-C4 graph contract. In particular, Qwen3.5 depends on the matching
CPU and OpenCL LinearAttention shape/layout implementations, fused `MUL_SILU`
executors, fp16 accumulation fix, and OpenCL correctness fixes. Do not replace
this baseline with a partial operator backport: such a runtime can load a 3.6.1
graph while silently producing invalid logits.

The overlay contains only MCA-owned prompt-cache, multimodal embedding, Sana
diffusion, deterministic sampler, and fail-closed execution safeguards. All
general CPU/OpenCL graph behavior comes from the pinned upstream release.

`third_party/MNN` remains an untracked vendor checkout. Restore it with:

```powershell
./tools/vendor/bootstrap-mnn-vendor.ps1
```

The overlay may add new vendor source files. Bootstrap applies it with Git's
`--intent-to-add` mode so those files remain represented by the canonical
`git diff HEAD --` without staging their contents or committing vendor changes.
Do not replace the intent-to-add entry with a normal staged file.

Validate an existing checkout without changing it with:

```powershell
./tools/vendor/verify-mnn-vendor.ps1
```

Release builds, typed native builds, and `tools/build-mnn-runtime.ps1`
fail closed unless the checkout is at the pinned commit with exactly this
overlay applied and no additional tracked or untracked drift.

## Multimodal prefill invariant

The overlay treats every materialized image/audio embedding and its complete
pad-token run as one atomic prefill unit. The normal text `chunk` setting must
not split that run: a visual module can produce one embedding for the complete
image, but it cannot safely provide an arbitrary prefix of that embedding to a
language chunk. The runtime therefore uses a full-token atomic prefill whenever
media embeddings are pending, while pure-text requests retain normal chunking.
The atomic guard preserves MNN's upstream `generate(input_ids)` path so the
engine continues to own history insertion, embedding timing, and KV/prefix
bookkeeping; MCA does not hand-build a parallel `embedding -> generate(VARP)`
prefill path.

The contract validates media run counts and lengths, verifies the final merged
embedding sequence equals the original token count, and fails closed on null or
invalid embeddings/logits. JNI surfaces multimodal prefill failure as the stable
`mnn_multimodal_prefill_failed` error and must not enter token decode afterward.

## Qwen3.5 visual preprocessing invariant

The shipping MNNChat path is the default runtime baseline for Qwen3.5: linear
image resizing and its legacy fourth interpolation corner. Bicubic resizing
and the mathematically corrected corner remain explicit A/B overrides rather
than silent defaults. The runtime logs the source and aligned dimensions,
patch/grid contract, positional and interpolation bounds, and finite-value
statistics for both the flattened patches and materialized image embedding.

The public Qwen3.5 vision config has an empty `deepstack_visual_indexes` list,
but MNN 3.4-era bundles retain a `deepstack_embeds` language-graph input while
exporting only one visual output. MCA permits an exact-zero legacy graph input
only for `qwen3_5` / `qwen3_5_moe`, preserving MNNChat's `[3,1,1]` placeholder;
it does not invent full-sequence deep-stack features, and every other
missing-deepstack case fails closed.
