#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cmath>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <fcntl.h>
#include <fstream>
#include <functional>
#include <limits.h>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <ostream>
#include <random>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <type_traits>
#include <unordered_map>
#include <utility>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>

#ifndef MCA_WITH_MNN_LLM
#define MCA_WITH_MNN_LLM 0
#endif

#ifndef MCA_WITH_MNN_DIFFUSION
#define MCA_WITH_MNN_DIFFUSION 0
#endif

#include "nlohmann/json.hpp"
#include "diffusion_scheduler.hpp"
#include "image_conditioning.hpp"
#include "jni_utf8_codec.hpp"
#include "mnn_diffusion_batch_policy.hpp"
#include "mnn_legacy_chat_template_policy.hpp"
#include "mnn_embedding_bundle_policy.hpp"
#include "mnn_bundle_path_policy.hpp"
#include "mca/mnn/mnn_bundle_config.hpp"
#include "mca/mnn/mnn_template_parser.hpp"
#include "mca/mnn/mnn_vision_adapter.hpp"
#include "mca/mnn/mnn_stats_collector.hpp"
#include "mca/mnn/mnn_prompt_cache_manager.hpp"
#include "mca/mnn/mnn_protocol_parser.hpp"
#include "mca/mnn/mnn_diffusion_assets.hpp"
#include "mca/mnn/mnn_qnn_prompt_payload.hpp"
#include "mca/mnn/mnn_clip_tokenizer.hpp"
#include "mca/mnn/mnn_unet_timestep.hpp"
#include "mca/mnn/mnn_file_utils.hpp"
#include "mca/mnn/mnn_json_options.hpp"
#include "mca/mnn/mnn_prompt_weighting.hpp"
#include "mca/mnn/mnn_numeric_convert.hpp"
#include "mca/mnn/mnn_backend_predicates.hpp"
#include "mca/mnn/mnn_chat_jni_guards.hpp"
#include "mca/mnn/mnn_jni_convert.hpp"
#include "mca/mnn/mnn_diffusion_asset_contract.hpp"
#include "mnn_qnn_prompt_handoff.hpp"
#include "native_prompt_language_contract.hpp"
#include "mnn_runtime_capability_policy.hpp"
#include "mnn_sampling_config.hpp"
#include "mnn_stream_protocol_filter.hpp"
#include "mnn_visual_stream_queue.hpp"
#include "textual_inversion_conditioning.hpp"
#include "mnn_vision_path_policy.hpp"
#include "execution_asset_binding.hpp"

#if MCA_WITH_MNN_LLM
#include "llm/llm.hpp"
#endif

#if MCA_WITH_MNN_DIFFUSION
#include "mca/mnn/mnn_tensor_ops.hpp"
#include "mca/mnn/mnn_vae_image_writer.hpp"
#include "mca/mnn/mnn_clip_weighting.hpp"
#include "mca/mnn/mnn_pndm_scheduler.hpp"
#include "mca/mnn/mnn_tensor_copy.hpp"
#include "mca/mnn/mnn_diffusion_pipeline.hpp"
#include "mca/mnn/mnn_unet_smoke.hpp"
#include "mca/mnn/mnn_sdxl_pipeline.hpp"
#endif

#ifndef MCA_WITH_TOKENIZERS_CPP
#define MCA_WITH_TOKENIZERS_CPP 0
#endif

#ifndef MCA_MNN_RUNTIME_CACHE_NAMESPACE
#if MCA_WITH_MNN_LLM
#error "MNN runtime cache namespace must come from the verified vendor patch"
#else
#define MCA_MNN_RUNTIME_CACHE_NAMESPACE "unlinked"
#endif
#endif

#if (MCA_WITH_MNN_LLM || MCA_WITH_MNN_DIFFUSION) && defined(MNN_IMGCODECS)
#include <cv/cv.hpp>
#endif

#if MCA_WITH_MNN_DIFFUSION
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <MNN/expr/Module.hpp>
#include "mnn_diffusion_tokenizer.hpp"
#include "scheduler.hpp"
#include "diffusion/stable_diffusion.hpp"
#include "diffusion/sana_diffusion.hpp"
#include "mnn_sana_session.hpp"
#endif

// T2B3 (B18): the former monolith body dissolved into the owner split
//   mca/mnn/mnn_diffusion_internal.hpp (state externs + declarations)
//   mca/mnn/mnn_diffusion_state.cpp (state + helpers)
//   mca/mnn/mnn_diffusion_generate.cpp (generate-side JNI entries)
//   mca/mnn/mnn_diffusion_tokenize.cpp (tokenize/measure/encode JNI + helpers)
//   mca/mnn/mnn_clip_encode.cpp (SDXL/community CLIP pipelines)
//   mca/mnn/mnn_sd15_direct.cpp (direct SD1.5 pipeline + Diffusion shims)
// This TU stays in mca_mnn_native as a compatibility shell.

