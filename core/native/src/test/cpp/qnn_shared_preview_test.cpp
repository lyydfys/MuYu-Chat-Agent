#include "../../main/cpp/qnn_shared_preview.hpp"

#include <cassert>
#include <string>
#include <vector>

int main() {
    using namespace mca::qnn::preview;

    const Contract community_shared =
        resolve_contract(true, "shared_unet_vae", "vae", 1);
    assert(community_shared.enabled);
    assert(community_shared.interval == 1);
    assert(community_shared.error_code.empty());
    const Contract gen5_shared =
        resolve_contract(true, "shared_text_unet_vae", "vae", 3);
    assert(gen5_shared.enabled);
    assert(gen5_shared.error_code.empty());

    const Contract split = resolve_contract(true, "split_unet_vae", "vae", 3);
    assert(!split.enabled);
    assert(split.error_code == "UNSUPPORTED_PREVIEW_TRANSPORT");

    assert(!resolve_contract(false, "", "", 0).enabled);
    assert(resolve_contract(true, "shared_unet_vae", "projection", 3).error_code ==
        "UNSUPPORTED_PREVIEW_MODE");
    assert(resolve_contract(true, "shared_unet_vae", "vae", 0).error_code ==
        "INVALID_PREVIEW_INTERVAL");
    assert(resolve_contract(true, "shared_unet_vae", "vae", 11).error_code ==
        "INVALID_PREVIEW_INTERVAL");

    const Contract every_two = resolve_contract(true, "shared_unet_vae", "vae", 2);
    assert(!should_publish(every_two, 1, 20, false));
    assert(should_publish(every_two, 2, 20, false));
    assert(should_publish(every_two, 18, 20, false));
    assert(!should_publish(every_two, 20, 20, false));
    assert(!should_publish(every_two, 4, 20, true));

    const std::string journal = "/data/user/0/com.muyuchat.mca/cache/local_image_outputs/qnn-1.qnn-stage.json";
    const std::string directory = request_directory_for_journal(journal);
    assert(directory == journal + ".previews");
    assert(request_directory_for_journal("relative.qnn-stage.json").empty());
    assert(request_directory_for_journal("/data/cache/../escape.qnn-stage.json").empty());
    assert(request_directory_for_journal("/data/cache/qnn-stage.json.other").empty());
    assert(immutable_revision_file_name(0U).empty());
    assert(immutable_revision_file_name(1U) == "preview-1.png");
    assert(immutable_revision_file_name(42U) == "preview-42.png");

    Audit audit;
    audit.vae_execution_attempt_count = 2U;
    audit.vae_execution_count = 1U;
    audit.publication_count = 1U;
    audit.last_step = 4;
    audit.last_revision = 1U;
    assert(!audit.stopped_after_failure());
    audit.failure_code = "PREVIEW_VAE_EXECUTE_FAILED";
    assert(audit.stopped_after_failure());
    return 0;
}
