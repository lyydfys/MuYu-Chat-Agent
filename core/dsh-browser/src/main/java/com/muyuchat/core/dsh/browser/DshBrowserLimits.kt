package com.muyuchat.core.dsh.browser

/**
 * Hard budgets of the isolated AI browser.
 *
 * These are deliberately below the reference shell's unstated values (it caps nodes and text but
 * has no tab ceiling at all) because MCA owns the process: every tab is a live `WebView`, so
 * tab count, node count and text size are memory budgets, not cosmetic limits. The numbers are
 * public (they are reported by `browser_caps`) so the DSH tool package can plan around them.
 */
object DshBrowserLimits {
    /** Bounded page-tab count for one browser session. Closing a tab frees it immediately. */
    const val MAX_TABS = 6

    /** Bounded semantic nodes in one snapshot. */
    const val MAX_NODES = 400

    /** Bounded element name / label length in one snapshot node. */
    const val MAX_NAME_CHARS = 120

    /** Bounded text read in one `read` call. */
    const val MAX_TEXT_CHARS = 8_000

    /** Bounded visible text lines attached to a snapshot. */
    const val MAX_TEXT_LINES = 60

    /** Bounded characters per rendered text line (a line is display evidence, not a document). */
    const val TEXT_LINE_CHARS = 160

    /** Bounded navigation URL length. */
    const val MAX_URL_CHARS = 2_048

    /** Bounded element ref length (`r` + 7 hex + optional `-N`). */
    const val MAX_REF_CHARS = 40

    /** Bounded search query length. */
    const val MAX_QUERY_CHARS = 200

    /** Bounded role filter length. */
    const val MAX_ROLE_CHARS = 32

    /** Bounded typed text per `type` call. */
    const val MAX_TYPE_CHARS = 4_000

    /** Bounded tab id length (`t` + number). */
    const val MAX_TAB_ID_CHARS = 16

    /** Bounded `find` candidate count. */
    const val MAX_FIND_LIMIT = 20

    /** Default `find` candidate count. */
    const val DEFAULT_FIND_LIMIT = 5

    /** Bounded `snapshot` node rows rendered by the official package (display only). */
    const val MAX_SNAPSHOT_LIMIT = 200

    /** Bounded scroll step in CSS pixels. */
    const val MAX_SCROLL_AMOUNT = 20_000

    /** Default scroll step in CSS pixels (matches the pinned reference). */
    const val DEFAULT_SCROLL_AMOUNT = 700

    /** Lowest accepted per-call timeout. */
    const val MIN_TIMEOUT_MILLIS = 1_000L

    /** Highest accepted per-call timeout. */
    const val MAX_TIMEOUT_MILLIS = 120_000L

    /** Timeout the official package uses when it does not pass one. */
    const val DEFAULT_TIMEOUT_MILLIS = 30_000L

    /** Same ceiling the capability route enforces before a handler sees a body. */
    const val MAX_REQUEST_BYTES = 262_144

    /** Response ceiling kept under one Binder transaction (~1 MiB) so the bridge never truncates. */
    const val MAX_RESPONSE_BYTES = 524_288
}
