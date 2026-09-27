#pragma once
// core/crispasr_env.h — unified environment-variable access (issue #265).
//
// CrispASR standardizes tunable/debug env vars on the
// CRISPASR_<BACKEND>_<FEATURE> convention (e.g. CRISPASR_OMNIVOICE_CODEC_GPU,
// matching CRISPASR_IRODORI_CODEC_GPU). Many backends historically shipped
// bare-prefixed names (OMNIVOICE_CODEC_GPU, QWEN3_TTS_CODEC_GPU, CHATTERBOX_BENCH,
// …) that are baked into external scripts, docs, and Kaggle A/B kernels, so those
// keep working forever as LEGACY ALIASES — but using one prints a one-time
// deprecation warning to stderr pointing at the canonical name.
//
// The alias is derived automatically: get("CRISPASR_FOO_BAR") first reads
// CRISPASR_FOO_BAR, and if unset, falls back to the prefix-stripped legacy name
// FOO_BAR. So a call site only ever names the canonical CRISPASR_-prefixed
// variable — the bare form is honored implicitly. (For the rare case where the
// legacy name is NOT just the prefix-stripped form, pass it explicitly as the
// second argument.)
//
// This resolves only the NAME — it returns the raw value string (or nullptr),
// exactly like std::getenv — because call sites keep their own truthiness test
// (some treat "set at all" as true, others require a non-"0" value). Two
// convenience predicates cover the common cases.
//
// The deprecation warning is one-time per legacy name and can be silenced with
// CRISPASR_SUPPRESS_ENV_DEPRECATION=1 (for pipelines that scrape stderr and
// can't yet migrate).
//
// Header-only, no external dependencies.

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <set>
#include <string>

namespace crispasr_env {

inline constexpr char kCanonicalPrefix[] = "CRISPASR_";

namespace detail {

inline std::mutex& warn_mutex() {
    static std::mutex m;
    return m;
}

inline std::set<std::string>& warned() {
    static std::set<std::string> s;
    return s;
}

// Print a one-time "legacy env var is deprecated" notice for `legacy`, naming
// the `canonical` replacement. Silenced by CRISPASR_SUPPRESS_ENV_DEPRECATION.
inline void warn_deprecated(const char* legacy, const char* canonical) {
    if (std::getenv("CRISPASR_SUPPRESS_ENV_DEPRECATION"))
        return;
    std::lock_guard<std::mutex> lock(warn_mutex());
    if (!warned().insert(legacy).second)
        return; // already warned for this name
    std::fprintf(stderr,
                 "[crispasr] warning: environment variable '%s' is deprecated and "
                 "will be removed in a future release; use '%s' instead (the old "
                 "name still works for now). Silence with "
                 "CRISPASR_SUPPRESS_ENV_DEPRECATION=1.\n",
                 legacy, canonical);
}

} // namespace detail

// Canonical-first lookup. Reads `canonical`; if unset and `canonical` begins
// with "CRISPASR_", falls back to the prefix-stripped legacy name; if a distinct
// `legacy` alias is given, that is tried last. A hit on either legacy form emits
// a one-time deprecation warning. Returns nullptr if none is set. Drop-in for
// std::getenv at a standardized call site.
inline const char* get(const char* canonical, const char* legacy = nullptr) {
    if (const char* v = std::getenv(canonical))
        return v;
    constexpr size_t plen = sizeof(kCanonicalPrefix) - 1; // strlen("CRISPASR_")
    if (std::strncmp(canonical, kCanonicalPrefix, plen) == 0 && canonical[plen] != '\0') {
        const char* bare = canonical + plen;
        if (const char* v = std::getenv(bare)) {
            detail::warn_deprecated(bare, canonical);
            return v;
        }
    }
    if (legacy)
        if (const char* v = std::getenv(legacy)) {
            detail::warn_deprecated(legacy, canonical);
            return v;
        }
    return nullptr;
}

// "Flag on unless explicitly 0": set, non-empty, and not "0".
inline bool truthy(const char* canonical, const char* legacy = nullptr) {
    const char* v = get(canonical, legacy);
    return v && *v && std::strcmp(v, "0") != 0;
}

// "Set at all" semantics — matches `std::getenv(x) != nullptr` gates where even
// an empty or "0" value enables the path.
inline bool present(const char* canonical, const char* legacy = nullptr) {
    return get(canonical, legacy) != nullptr;
}

} // namespace crispasr_env
