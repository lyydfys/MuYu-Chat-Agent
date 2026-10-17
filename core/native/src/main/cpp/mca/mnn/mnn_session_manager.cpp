// E08j (R6): this translation unit is retained as the session-manager compatibility surface.
// Disposition (see artifacts/architecture/20261012-e08j-session-manager/):
//   * opt_bool / opt_int / opt_string / optional_json_string|int|double|bool / merge_json_object
//     -> single owner is mca/mnn/mnn_json_options.hpp (inline; the bridge links them via using).
//   - build_mnn_llm_config -> superseded by the bridge's build_mnn_config() (evolved version:
//     defensive params_json parsing, backend spelling resolution incl. advanced_json, same
//     vision/mmap policy and audit annotations).
// The file compiles empty so the target keeps checking the header contract.
#include "mca/mnn/mnn_session_manager.hpp"
#include "mca/mnn/mnn_json_options.hpp"

namespace mca {
namespace mnn {

}  // namespace mnn
}  // namespace mca
