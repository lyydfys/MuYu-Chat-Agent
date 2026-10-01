package com.muyuchat.core.engine

/**
 * Addition that clamps at Long.MAX_VALUE instead of wrapping.
 *
 * One implementation, in one place. The same two lines existed twice in this module, in
 * ContextWindowAdmission and in PersistentPrefixCacheStore. Both compute a budget -- a context
 * window in one, a prefix cache size in the other -- and a wrap would turn an oversized total
 * into a small plausible-looking one, so the failure would read as "this fits" rather than as
 * an error.
 *
 * `right > 0L` is part of the guard, not decoration. Without it, a negative `right` makes
 * `Long.MAX_VALUE - right` overflow positive, the comparison turns true, and the result is
 * clamped to MAX for an addition that should have been a subtraction. Both copies had the
 * guard, and it is preserved.
 *
 * Note there is a second saturating add in the repository, in
 * core:modelstore's QairtBundleRiskAnalyzer, written as
 * `if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE`. That form is correct for
 * non-negative inputs only, and it is deliberately left where it is: its callers pass byte
 * counts built from tensor dimensions, so a negative operand would be an upstream bug, and
 * changing it here would be a behaviour change outside this extraction. Recorded so the two
 * forms are not mistaken for the same helper.
 */
internal fun saturatedAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
