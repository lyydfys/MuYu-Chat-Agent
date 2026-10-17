package com.muyuchat.core.download

/**
 * Detects whether a chipset code identifies a Snapdragon part.
 *
 * One implementation, in one place, on purpose. This same five-line function existed twice
 * -- as `isSnapdragonChipsetCode` in the former ModelScopeTypes aggregate and as
 * `isSnapdragonChipsetCodeForRecommendation` in feature:modelhub's RecommendationCatalog.kt --
 * with byte-identical bodies. Two copies of a vendor-detection predicate is how a ranking
 * hint eventually becomes a gate in one place and not the other.
 *
 * **This is a ranking input and never an admission check.** The download policy enum says so
 * explicitly: LISTED_CHIPSETS is documented as "recommendation hints only; unmatched devices
 * keep the download action", and ANY_SNAPDRAGON as "unknown and non-matching devices remain
 * downloadable". `downloadEligibilityFor` keeps `chipsetMatched`, which this feeds, separate
 * from `downloadable`, which comes from the model's own availability. Callers must keep that
 * split: this function may order results and choose a transport, and must never hide or block
 * a download, import, load or run action.
 *
 * Matching rules, unchanged from both originals:
 *   - blank is not a Snapdragon code
 *   - a case-sensitive occurrence of SNAPDRAGON or 骁龙 counts
 *   - otherwise the code must match the Qualcomm prefix set, anchored on both ends
 *
 * **Callers must pass an upper-cased code.** The vendor-name check and the regex are both
 * case sensitive, so a lower-cased "sm8550" returns false. Both original call sites already
 * passed `uppercase(Locale.ROOT)`, which is what made the two copies interchangeable; that
 * precondition is stated here rather than left implicit, because a caller that forgets it
 * gets a silent false and a wrongly downgraded ranking rather than an error.
 */
public fun String.isSnapdragonChipsetCode(): Boolean {
    if (isBlank()) return false
    if (contains("SNAPDRAGON") || contains("骁龙")) return true
    return matches(Regex("^(?:SM|SDM|MSM|APQ|QCS|QCM)[A-Z0-9_-]+$"))
}
