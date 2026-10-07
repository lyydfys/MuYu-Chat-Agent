#include "qnn_engine.hpp"
#include "qnn_session.hpp"
#include "mca/common/logging.hpp"

namespace mca {
namespace qnn {

namespace {
constexpr const char* kTag = "QnnEngine";
static std::atomic<int64_t> s_qnn_session_counter{1};
}

QnnEngine::QnnEngine(
    std::shared_ptr<QnnBackendOwner> backend,
    std::string model_id,
    std::string model_path
) : mca::runtime::Engine(backend, std::move(model_id), std::move(model_path)),
    qnn_backend_(std::move(backend)) {
    MCA_LOGI(kTag, "QnnEngine created for model %s at %s",
             get_model_id().c_str(), get_model_path().c_str());
}

QnnEngine::~QnnEngine() {
    unload();
}

bool QnnEngine::register_graph(
    const std::string& name,
    void* handle,
    std::vector<std::string> inputs,
    std::vector<std::string> outputs
) {
    std::lock_guard<std::mutex> lock(engine_mutex_);
    if (name.empty()) {
        MCA_LOGE(kTag, "Cannot register graph with empty name");
        return false;
    }
    if (!handle) {
        MCA_LOGE(kTag, "Cannot register graph %s with null handle", name.c_str());
        return false;
    }

    QnnGraphDescriptor desc;
    desc.name = name;
    desc.graph_handle = handle;
    desc.input_tensor_names = std::move(inputs);
    desc.output_tensor_names = std::move(outputs);
    desc.memory_size_bytes = 0;

    graphs_[name] = std::move(desc);
    MCA_LOGI(kTag, "Registered graph: %s", name.c_str());
    return true;
}

bool QnnEngine::has_graph(const std::string& name) const noexcept {
    std::lock_guard<std::mutex> lock(engine_mutex_);
    return graphs_.find(name) != graphs_.end();
}

void* QnnEngine::get_graph_handle(const std::string& name) const noexcept {
    std::lock_guard<std::mutex> lock(engine_mutex_);
    auto it = graphs_.find(name);
    return (it != graphs_.end()) ? it->second.graph_handle : nullptr;
}

std::vector<std::string> QnnEngine::get_graph_names() const {
    std::lock_guard<std::mutex> lock(engine_mutex_);
    std::vector<std::string> names;
    names.reserve(graphs_.size());
    for (const auto& kv : graphs_) {
        names.push_back(kv.first);
    }
    return names;
}

std::shared_ptr<QnnBackendOwner> QnnEngine::get_qnn_backend() const noexcept {
    return qnn_backend_;
}

std::shared_ptr<QnnSession> QnnEngine::create_qnn_session(int64_t initial_generation) {
    std::lock_guard<std::mutex> lock(engine_mutex_);
    if (!is_valid()) {
        MCA_LOGE(kTag, "Cannot create QnnSession on invalid/unloaded QnnEngine");
        return nullptr;
    }

    const int64_t session_id = s_qnn_session_counter.fetch_add(1, std::memory_order_relaxed);
    auto self = std::static_pointer_cast<QnnEngine>(shared_from_this());
    return std::make_shared<QnnSession>(self, session_id, initial_generation);
}

void QnnEngine::unload() noexcept {
    std::lock_guard<std::mutex> lock(engine_mutex_);
    if (!graphs_.empty()) {
        MCA_LOGI(kTag, "Unloading %zu registered graphs for model %s",
                 graphs_.size(), get_model_id().c_str());
        graphs_.clear();
    }
    Engine::unload();
}

bool QnnEngine::is_valid() const noexcept {
    return Engine::is_valid() && (qnn_backend_ != nullptr && qnn_backend_->is_valid());
}

} // namespace qnn
} // namespace mca
