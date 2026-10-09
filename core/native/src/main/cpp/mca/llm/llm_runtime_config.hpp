#ifndef MCA_LLM_RUNTIME_CONFIG_HPP
#define MCA_LLM_RUNTIME_CONFIG_HPP

#include <string>
#include <vector>
#include <llama.h>
#include "mca/llm/llm_stats_collector.hpp"

namespace mca {
namespace llm {

bool load_mode_uses_mmap(llama_load_mode mode);
bool load_mode_uses_mlock(llama_load_mode mode);
llama_load_mode load_mode_from_flags(bool use_mmap, bool use_mlock);

RuntimeConfig default_runtime_config();
bool validate_runtime_config(const RuntimeConfig &config, std::string &error);
bool parse_runtime_config(const std::string &text,
                          const RuntimeConfig &defaults,
                          RuntimeConfig &config,
                          std::string &error);
bool runtime_config_equal_load_bound(const RuntimeConfig &a, const RuntimeConfig &b);

ggml_type cache_type_from_name(const std::string &name);
llama_split_mode split_mode_from_name(const std::string &name);
llama_flash_attn_type flash_attn_from_name(const std::string &name);

std::string model_meta_string(const llama_model *model, const std::string &key);
std::string lowercase_trimmed_ascii(std::string value);
bool model_meta_declares_non_causal(const llama_model *model, const std::string &architecture);
bool model_meta_declares_pooling(const llama_model *model, const std::string &architecture);
bool is_dedicated_non_chat_architecture(const std::string &architecture);
bool model_supports_autoregressive_chat(const llama_model *model, const std::string &architecture);
int model_mtp_layer_count(const llama_model *model);
bool model_supports_requested_mtp(const std::string &architecture, int mtp_layers);

} // namespace llm
} // namespace mca

#endif // MCA_LLM_RUNTIME_CONFIG_HPP
