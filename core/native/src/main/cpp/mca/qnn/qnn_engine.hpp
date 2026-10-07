#ifndef MCA_QNN_ENGINE_HPP
#define MCA_QNN_ENGINE_HPP

#include <string>
#include <memory>
#include <vector>
#include <map>
#include <mutex>
#include "mca/runtime/engine.hpp"
#include "qnn_backend_owner.hpp"

namespace mca {
namespace qnn {

class QnnSession;

struct QnnGraphDescriptor {
    std::string name;
    void* graph_handle{nullptr};
    std::vector<std::string> input_tensor_names;
    std::vector<std::string> output_tensor_names;
    size_t memory_size_bytes{0};
};

/**
 * Long-lived model and graph resource owner for QNN SDXL pipelines.
 * Owns loaded graphs (TextEncoder, UNet, VAE, ControlNet) and weights.
 * Invariant: Does NOT hold UI/JNI callbacks or per-request temporary buffers.
 */
class QnnEngine : public mca::runtime::Engine {
public:
    QnnEngine(
        std::shared_ptr<QnnBackendOwner> backend,
        std::string model_id,
        std::string model_path
    );
    ~QnnEngine() override;

    QnnEngine(const QnnEngine&) = delete;
    QnnEngine& operator=(const QnnEngine&) = delete;

    bool register_graph(
        const std::string& name,
        void* handle,
        std::vector<std::string> inputs = {},
        std::vector<std::string> outputs = {}
    );

    [[nodiscard]] bool has_graph(const std::string& name) const noexcept;
    [[nodiscard]] void* get_graph_handle(const std::string& name) const noexcept;
    [[nodiscard]] std::vector<std::string> get_graph_names() const;

    [[nodiscard]] std::shared_ptr<QnnBackendOwner> get_qnn_backend() const noexcept;

    std::shared_ptr<QnnSession> create_qnn_session(int64_t initial_generation = 1);

    void unload() noexcept override;
    [[nodiscard]] bool is_valid() const noexcept;

private:
    std::shared_ptr<QnnBackendOwner> qnn_backend_;
    std::map<std::string, QnnGraphDescriptor> graphs_;
    mutable std::mutex engine_mutex_;
};

} // namespace qnn
} // namespace mca

#endif // MCA_QNN_ENGINE_HPP
