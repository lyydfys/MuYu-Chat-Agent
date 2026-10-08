#include "mca/llm/llm_runtime_config.hpp"

#include <algorithm>
#include <cctype>
#include <limits>
#include <map>
#include <sstream>
#include <stdexcept>
#include <vector>
#include <unistd.h>
#include <nlohmann/json.hpp>

using ordered_json = nlohmann::ordered_json;

namespace mca {
namespace llm {

namespace {

std::string lower_ascii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return (char) std::tolower(c);
    });
    return value;
}

} // namespace

bool load_mode_uses_mmap(llama_load_mode mode) {
    // The pinned llama.cpp defaults to AUTO. AUTO starts with mmap and only
    // disables it when a selected backend cannot map the model, so preserve
    // the MCA default mmap policy when deriving the runtime profile.
    return mode == LLAMA_LOAD_MODE_AUTO ||
           mode == LLAMA_LOAD_MODE_MMAP ||
           mode == LLAMA_LOAD_MODE_MMAP_MLOCK;
}

bool load_mode_uses_mlock(llama_load_mode mode) {
    return mode == LLAMA_LOAD_MODE_MLOCK || mode == LLAMA_LOAD_MODE_MMAP_MLOCK;
}

llama_load_mode load_mode_from_flags(bool use_mmap, bool use_mlock) {
    if (use_mmap) {
        return use_mlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP;
    }
    return use_mlock ? LLAMA_LOAD_MODE_MLOCK : LLAMA_LOAD_MODE_NONE;
}

RuntimeConfig default_runtime_config() {
    RuntimeConfig config;
    const int threads_default = std::max(1, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2);
    config.n_threads = threads_default;
    config.n_threads_batch = threads_default;
    const auto model_defaults = llama_model_default_params();
    config.n_gpu_layers = model_defaults.n_gpu_layers;
    config.main_gpu = model_defaults.main_gpu;
    config.split_mode = "layer";
    config.mmap = load_mode_uses_mmap(model_defaults.load_mode);
    config.mlock = load_mode_uses_mlock(model_defaults.load_mode);
    return config;
}

bool validate_runtime_config(const RuntimeConfig &config, std::string &error) {
    auto fail = [&](const std::string &message) {
        error = message;
        return false;
    };
    if (config.n_ctx < 128 || config.n_ctx > 1048576) {
        return fail("n_ctx must be in [128, 1048576].");
    }
    if (config.n_threads < 1 || config.n_threads > 256) {
        return fail("n_threads must be in [1, 256].");
    }
    if (config.n_threads_batch < 1 || config.n_threads_batch > 256) {
        return fail("n_threads_batch must be in [1, 256].");
    }
    if (config.n_batch < 1 || config.n_batch > 65536) {
        return fail("n_batch must be in [1, 65536].");
    }
    if (config.n_ubatch < 1 || config.n_ubatch > config.n_batch) {
        return fail("n_ubatch must be in [1, n_batch].");
    }
    if (config.n_gpu_layers < -2 || config.n_gpu_layers > 100000) {
        return fail("n_gpu_layers must be -2 (all), -1 (auto), or a non-negative integer.");
    }
    if (config.main_gpu < 0 || config.main_gpu >= (int) llama_max_devices()) {
        return fail("main_gpu is outside llama_max_devices().");
    }
    if (config.split_mode != "none" && config.split_mode != "layer" &&
        config.split_mode != "row" && config.split_mode != "tensor") {
        return fail("split_mode must be one of: none, layer, row, tensor.");
    }
    if (config.n_cpu_moe < 0 || config.n_cpu_moe > 10000) {
        return fail("n_cpu_moe must be in [0, 10000].");
    }
    static const std::vector<std::string> cache_types = {
            "f32", "f16", "bf16", "q8_0", "q4_0", "q4_1", "iq4_nl", "q5_0", "q5_1"};
    if (std::find(cache_types.begin(), cache_types.end(), config.cache_type_k) == cache_types.end()) {
        return fail("cache_type_k is unsupported: " + config.cache_type_k);
    }
    if (std::find(cache_types.begin(), cache_types.end(), config.cache_type_v) == cache_types.end()) {
        return fail("cache_type_v is unsupported: " + config.cache_type_v);
    }
    if (config.flash_attn != "on" && config.flash_attn != "off" && config.flash_attn != "auto") {
        return fail("flash_attn must be one of: on, off, auto.");
    }
    if (config.n_parallel != 1) {
        return fail("n_parallel must be 1: the Android JNI engine is single-sequence.");
    }
    if (config.cache_reuse < 0 || config.cache_reuse > config.n_ctx) {
        return fail("cache_reuse must be in [0, n_ctx].");
    }
    if (config.spec_type != "none" && config.spec_type != "draft-mtp") {
        return fail("spec_type must be one of: none, draft-mtp.");
    }
    if (config.spec_type == "none" && config.spec_draft_n_max != 0) {
        return fail("spec_draft_n_max must be 0 when spec_type is none.");
    }
    if (config.spec_type == "draft-mtp" &&
        (config.spec_draft_n_max < 1 || config.spec_draft_n_max > 8)) {
        return fail("spec_draft_n_max must be in [1, 8] for draft-mtp.");
    }
    if (config.spec_type == "draft-mtp" && config.n_batch < config.spec_draft_n_max + 1) {
        return fail("n_batch must be at least spec_draft_n_max + 1 for draft-mtp verification.");
    }
    const bool quantized_k = config.cache_type_k != "f32" && config.cache_type_k != "f16" && config.cache_type_k != "bf16";
    const bool quantized_v = config.cache_type_v != "f32" && config.cache_type_v != "f16" && config.cache_type_v != "bf16";
    if (quantized_v && config.flash_attn == "off") {
        return fail("quantized cache_type_v requires flash_attn on or auto.");
    }
    if (config.split_mode == "tensor" && (quantized_k || quantized_v)) {
        return fail("split_mode=tensor cannot be combined with quantized KV cache.");
    }
    return true;
}

bool parse_runtime_config(const std::string &text,
                          const RuntimeConfig &defaults,
                          RuntimeConfig &config,
                          std::string &error) {
    config = defaults;
    ordered_json root;
    try {
        root = ordered_json::parse(text.empty() ? "{}" : text);
    } catch (const std::exception &e) {
        error = std::string("Invalid params JSON: ") + e.what();
        return false;
    }
    if (!root.is_object()) {
        error = "Params JSON must be an object.";
        return false;
    }

    ordered_json advanced = ordered_json::object();
    if (root.contains("advanced_json") && !root["advanced_json"].is_null()) {
        try {
            if (root["advanced_json"].is_object()) {
                advanced = root["advanced_json"];
            } else if (root["advanced_json"].is_string()) {
                advanced = ordered_json::parse(root["advanced_json"].get<std::string>());
                if (!advanced.is_object()) throw std::runtime_error("advanced_json must decode to an object");
            } else {
                throw std::runtime_error("advanced_json must be an object or JSON object string");
            }
        } catch (const std::exception &e) {
            error = std::string("Invalid advanced_json: ") + e.what();
            return false;
        }
    }

    auto value_for = [&](const char *key) -> const ordered_json * {
        if (root.contains(key) && !root[key].is_null()) return &root[key];
        if (advanced.contains(key) && !advanced[key].is_null()) return &advanced[key];
        return nullptr;
    };
    auto read_int = [&](const char *key, int &target) -> bool {
        const auto *value = value_for(key);
        if (value == nullptr) return true;
        if (!value->is_number_integer() && !value->is_number_unsigned()) {
            error = std::string(key) + " must be an integer.";
            return false;
        }
        try {
            const long long parsed = value->get<long long>();
            if (parsed < std::numeric_limits<int>::min() || parsed > std::numeric_limits<int>::max()) {
                error = std::string(key) + " is outside the 32-bit integer range.";
                return false;
            }
            target = (int) parsed;
            return true;
        } catch (const std::exception &e) {
            error = std::string("Invalid ") + key + ": " + e.what();
            return false;
        }
    };
    auto read_bool = [&](const char *key, bool &target) -> bool {
        const auto *value = value_for(key);
        if (value == nullptr) return true;
        if (!value->is_boolean()) {
            error = std::string(key) + " must be a boolean.";
            return false;
        }
        target = value->get<bool>();
        return true;
    };
    auto read_string = [&](const char *key, std::string &target) -> bool {
        const auto *value = value_for(key);
        if (value == nullptr) return true;
        if (!value->is_string()) {
            error = std::string(key) + " must be a string.";
            return false;
        }
        target = lower_ascii(value->get<std::string>());
        return true;
    };

    if (!read_int("n_ctx", config.n_ctx) ||
        !read_int("n_threads", config.n_threads) ||
        !read_int("n_threads_batch", config.n_threads_batch) ||
        !read_int("n_batch", config.n_batch) ||
        !read_int("n_ubatch", config.n_ubatch) ||
        !read_int("main_gpu", config.main_gpu) ||
        !read_int("n_cpu_moe", config.n_cpu_moe) ||
        !read_int("n_parallel", config.n_parallel) ||
        !read_int("cache_reuse", config.cache_reuse) ||
        !read_int("spec_draft_n_max", config.spec_draft_n_max) ||
        !read_bool("perf", config.perf) ||
        !read_bool("mmap", config.mmap) ||
        !read_bool("mlock", config.mlock) ||
        !read_string("split_mode", config.split_mode) ||
        !read_string("cache_type_k", config.cache_type_k) ||
        !read_string("cache_type_v", config.cache_type_v) ||
        !read_string("spec_type", config.spec_type)) {
        return false;
    }

    if (const auto *value = value_for("n_gpu_layers")) {
        if (value->is_string()) {
            const std::string mode = lower_ascii(value->get<std::string>());
            if (mode == "auto") config.n_gpu_layers = -1;
            else if (mode == "all") config.n_gpu_layers = -2;
            else {
                error = "n_gpu_layers string must be auto or all.";
                return false;
            }
        } else if (value->is_number_integer() || value->is_number_unsigned()) {
            try {
                config.n_gpu_layers = value->get<int>();
            } catch (const std::exception &e) {
                error = std::string("Invalid n_gpu_layers: ") + e.what();
                return false;
            }
        } else {
            error = "n_gpu_layers must be an integer, auto, or all.";
            return false;
        }
    }

    if (const auto *value = value_for("flash_attn")) {
        if (value->is_boolean()) {
            config.flash_attn = value->get<bool>() ? "on" : "off";
        } else if (value->is_string()) {
            config.flash_attn = lower_ascii(value->get<std::string>());
        } else {
            error = "flash_attn must be a boolean or one of: on, off, auto.";
            return false;
        }
    }

    return validate_runtime_config(config, error);
}

ggml_type cache_type_from_name(const std::string &name) {
    static const std::map<std::string, ggml_type> types = {
            {"f32", GGML_TYPE_F32}, {"f16", GGML_TYPE_F16}, {"bf16", GGML_TYPE_BF16},
            {"q8_0", GGML_TYPE_Q8_0}, {"q4_0", GGML_TYPE_Q4_0}, {"q4_1", GGML_TYPE_Q4_1},
            {"iq4_nl", GGML_TYPE_IQ4_NL}, {"q5_0", GGML_TYPE_Q5_0}, {"q5_1", GGML_TYPE_Q5_1}};
    const auto it = types.find(name);
    return it == types.end() ? GGML_TYPE_COUNT : it->second;
}

llama_split_mode split_mode_from_name(const std::string &name) {
    if (name == "none") return LLAMA_SPLIT_MODE_NONE;
    if (name == "row") return LLAMA_SPLIT_MODE_ROW;
    if (name == "tensor") return LLAMA_SPLIT_MODE_TENSOR;
    return LLAMA_SPLIT_MODE_LAYER;
}

llama_flash_attn_type flash_attn_from_name(const std::string &name) {
    if (name == "on") return LLAMA_FLASH_ATTN_TYPE_ENABLED;
    if (name == "off") return LLAMA_FLASH_ATTN_TYPE_DISABLED;
    return LLAMA_FLASH_ATTN_TYPE_AUTO;
}

std::string model_meta_string(const llama_model *model, const std::string &key) {
    if (model == nullptr) return "";
    const int32_t needed = llama_model_meta_val_str(model, key.c_str(), nullptr, 0);
    if (needed <= 0) return "";
    std::vector<char> buffer((size_t) needed + 1, '\0');
    if (llama_model_meta_val_str(model, key.c_str(), buffer.data(), buffer.size()) < 0) return "";
    return std::string(buffer.data());
}

std::string lowercase_trimmed_ascii(std::string value) {
    const auto first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return "";
    const auto last = value.find_last_not_of(" \t\r\n");
    value = value.substr(first, last - first + 1);
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return (char) std::tolower(c);
    });
    return value;
}

bool model_meta_declares_non_causal(
        const llama_model *model,
        const std::string &architecture) {
    if (architecture.empty()) return false;
    const auto raw = lowercase_trimmed_ascii(
            model_meta_string(model, architecture + ".attention.causal"));
    return raw == "false" || raw == "0";
}

bool model_meta_declares_pooling(
        const llama_model *model,
        const std::string &architecture) {
    if (architecture.empty()) return false;
    const auto raw = lowercase_trimmed_ascii(
            model_meta_string(model, architecture + ".pooling_type"));
    if (raw.empty() || raw == "0" || raw == "-1" || raw == "none" || raw == "unspecified") {
        return false;
    }
    try {
        return std::stoll(raw) > 0;
    } catch (...) {
        // Pooling metadata is not part of the autoregressive chat contract.
        // An unfamiliar non-empty representation is therefore rejected rather
        // than being silently routed into token generation.
        return true;
    }
}

bool is_dedicated_non_chat_architecture(const std::string &architecture) {
    static const std::vector<std::string> architectures = {
            "clip",
            "bert",
            "modern-bert",
            "nomic-bert",
            "nomic-bert-moe",
            "neo-bert",
            "jina-bert-v2",
            "jina-bert-v3",
            "eurobert",
            "gemma-embedding",
            "llama-embed",
            "wavtokenizer-dec",
    };
    const auto normalized = lowercase_trimmed_ascii(architecture);
    return std::find(architectures.begin(), architectures.end(), normalized) != architectures.end();
}

bool model_supports_autoregressive_chat(
        const llama_model *model,
        const std::string &architecture) {
    return model != nullptr &&
            llama_model_has_decoder(model) &&
            !llama_model_has_encoder(model) &&
            !llama_model_is_diffusion(model) &&
            !is_dedicated_non_chat_architecture(architecture) &&
            !model_meta_declares_non_causal(model, architecture) &&
            !model_meta_declares_pooling(model, architecture);
}

int model_mtp_layer_count(const llama_model *model) {
    return model == nullptr ? 0 : llama_model_n_layer_nextn(model);
}

bool model_supports_requested_mtp(
        const std::string &architecture,
        int mtp_layers) {
    if (mtp_layers <= 0) return false;
    if (mtp_layers == 1) return true;
    // The pinned llama.cpp speculative driver has a chained-head graph only
    // for Step35.  Other architectures still assert a single MTP block in
    // their graph builders, so accepting them here would turn a capability
    // request into a native crash.
    return lowercase_trimmed_ascii(architecture) == "step35";
}

} // namespace llm
} // namespace mca
