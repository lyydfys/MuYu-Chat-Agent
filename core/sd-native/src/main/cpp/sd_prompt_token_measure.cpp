// T2B17 / R7-C6 (2026-10-14): split out of stable_diffusion_bridge.cpp (pre-split sha256
// c11dcdd8a564ad59573e40d4554db435c1251d7946c5b99c52a9230eee8865cd).  Body lines are byte-exact
// relocations; every delta is registered in b-SPLIT-PLAN.json.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <dirent.h>
#include <exception>
#include <fcntl.h>
#include <iomanip>
#include <limits.h>
#include <limits>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <unordered_set>
#include <vector>

#include "json.hpp"
#include "stable-diffusion.h"
#include "tokenizers/clip_tokenizer.h"
#include "util.h"
#include "image_execution_math.hpp"
#include "execution_asset_binding.hpp"
#include "native_prompt_language_contract.hpp"
#include "mca/sd/sd_output_transaction.hpp"
#include "mca/sd/sd_input_contract.hpp"
#include "mca/sd/sd_progress_reporter.hpp"
#include "mca/sd/sd_schedule_resolver.hpp"
#include "mca/sd/sd_lora_adapter.hpp"
#include "mca/sd/sd_image_io.hpp"
#include "mca/sd/sd_textual_inversion.hpp"
#include "mca/sd/sd_component_resolver.hpp"
#include "mca/sd/sd_runtime_diagnostics.hpp"
#include "mca/sd/sd_upscaler.hpp"
#include "mca/sd/sd_context_key.hpp"
#include "mca/sd/sd_effective_config.hpp"
#include "mca/sd/sd_controlnet_adapter.hpp"
#include "mca/sd/sd_inpaint_processor.hpp"
#include "mca/sd/sd_preview_encoder.hpp"
#include "mca/sd/sd_evidence_verifier.hpp"
#include "mca/sd/sd_response_builder.hpp"
using mca::sd::preview_mode_from_contract;
using mca::sd::PreviewPublicationContext;
using mca::sd::SdPreviewScope;
using mca::sd::verify_preview_publication;
using mca::sd::populate_preview_response_json;
using mca::sd::validate_controlnet_component;
using mca::sd::configure_controlnet_params;
using mca::sd::verify_controlnet_wiring;
using mca::sd::extract_controlnet_evidence;
using mca::sd::verify_controlnet_branch_classification;
using mca::sd::populate_controlnet_effective_json;
using mca::sd::populate_controlnet_response_json;
using mca::sd::load_and_validate_inpaint_mask;
using mca::sd::configure_inpaint_params;
using mca::sd::verify_inpaint_wiring;
using mca::sd::populate_inpaint_effective_json;
using mca::sd::populate_inpaint_response_json;
using mca::sd::progress_to_json;
using mca::sd::runtime_config_json;
using mca::sd::set_progress_from_sd_log;
using mca::sd::safe_sd_runtime_message;
using mca::sd::sd_log_callback;
using mca::sd::make_context_key;
using mca::sd::observed_prediction_mode;
using mca::sd::ComponentPaths;
using mca::sd::execution_assets_match_consumers;
using mca::sd::validate_lora_file;
using mca::sd::validate_textual_inversion_execution_evidence;
using mca::sd::component_selection_json;
using mca::sd::resolve_component_paths;
using mca::sd::is_sha256;
using mca::sd::LoadedInputImage;
using mca::sd::load_canonical_input_image;
using mca::sd::build_output_paths;
using mca::sd::free_generated_images;
using mca::sd::remove_output_files;
using mca::sd::ContractTextualInversion;
using mca::sd::regular_file_identity;
using mca::sd::same_regular_file_identity;
using mca::sd::sha256_hex;
using mca::sd::sha256_utf8;
using mca::sd::fixed_width_lower_hex_u64;
using mca::sd::textual_inversion_binding_fingerprint;
using mca::sd::textual_inversion_selection_fingerprint;
using mca::sd::image_prompt_execution_sha256;
using mca::sd::sha256_implementation_ready;
using mca::sd::sha256_regular_file;
using mca::sd::validate_textual_inversion_file;


#include "mca/sd/sd_contract_parser.hpp"
using mca::sd::ContractError;
using mca::sd::invalid_contract;
using mca::sd::unsupported_contract;
using mca::sd::execution_mismatch;
using mca::sd::contract_failure_json;
using mca::sd::optional_string;
using mca::sd::StableDiffusionExecutionContract;
using mca::sd::task_uses_init_image;
using mca::sd::task_uses_control_image;
using mca::sd::backend_denoising_strength;
using mca::sd::parse_execution_contract;
using mca::sd::observed_prediction_wire;
using mca::sd::runtime_failure;
using mca::sd::native_effective_json;

#include "stable_diffusion_bridge_internal.hpp"

using json = nlohmann::json;

namespace {

std::mutex g_prompt_tokenizer_mutex;

} // namespace

namespace mca_sd_bridge_internal {

json measure_clip_prompt_tokens(const std::string &prompt, int max_tokens) {
    // FrozenCLIPEmbedderWithCustomWords always executes 77-token CLIP chunks.
    // Reject a mismatched profile instead of presenting a precise-looking count
    // against a limit that the production conditioner does not use.
    if (max_tokens != 77) {
        return json({
                {"ok", false},
                {"backend", "SDCPP_NATIVE"},
                {"error", "stable-diffusion.cpp CLIP prompt capacity must be 77 tokens"}
        });
    }

    try {
        const auto parsed_attention = parse_prompt_attention(prompt);
        size_t content_token_count = 0u;
        std::lock_guard<std::mutex> lock(g_prompt_tokenizer_mutex);
        // The embedded CLIP merge table is immutable after construction. Keep a
        // single tokenizer because rebuilding its 49k-entry vocabulary for every
        // debounced UI edit is needlessly expensive.
        static CLIPTokenizer tokenizer;
        for (const auto &part : parsed_attention) {
            if (part.first == "BREAK" && part.second == -1.0f) {
                constexpr size_t kClipContentTokensPerChunk = 75u;
                const size_t padding =
                        (kClipContentTokensPerChunk -
                         (content_token_count % kClipContentTokensPerChunk)) %
                        kClipContentTokensPerChunk;
                content_token_count += padding;
                continue;
            }
            const auto tokens = tokenizer.encode(part.first);
            if (tokens.size() > std::numeric_limits<size_t>::max() - content_token_count) {
                throw std::overflow_error("CLIP prompt token count overflow");
            }
            content_token_count += tokens.size();
        }
        if (content_token_count > std::numeric_limits<size_t>::max() - 2u) {
            throw std::overflow_error("CLIP prompt token count overflow");
        }
        return json({
                {"ok", true},
                {"backend", "SDCPP_NATIVE"},
                {"exact", true},
                // Match the app's existing exact-count contract: include BOS and
                // EOS, but do not include graph padding or truncate long prompts.
                {"count", content_token_count + 2u},
                {"maxTokens", max_tokens}
        });
    } catch (const std::exception &error) {
        return json({
                {"ok", false},
                {"backend", "SDCPP_NATIVE"},
                {"error", error.what()}
        });
    } catch (...) {
        return json({
                {"ok", false},
                {"backend", "SDCPP_NATIVE"},
                {"error", "stable-diffusion.cpp CLIP tokenizer failed"}
        });
    }
}

} // namespace mca_sd_bridge_internal
