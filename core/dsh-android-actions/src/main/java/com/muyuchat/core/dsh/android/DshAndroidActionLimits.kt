package com.muyuchat.core.dsh.android

/**
 * Wire and runtime limits of the App-local Android action routes (`/notify`, `/overlay`,
 * `/usage`, `/clipboard`, `/setting`, `/shell`).
 *
 * Values that the two pinned official tool packages already publish are mirrored exactly
 * (`dsh-tool-android` and `dsh-tool-shizuku` both cap stdout at 8000 characters and stderr at
 * 2000 characters, clamp the shell timeout to 1_000..120_000 ms, and clamp usage days to 1..30).
 * Values that only MCA needs (setting key/value shape, clipboard payload, usage list size) are
 * MCA policy: a handler rejects a request above a limit instead of silently clamping a caller
 * mistake, because a silent clamp is how the reference App turned a bad `POST /clipboard?action=write`
 * into a `read`.
 */
object DshAndroidActionLimits {
    /** `dsh-tool-android`/`dsh-tool-shizuku`: `MAX_STDOUT = 8000`. */
    const val MAX_SHELL_STDOUT_CHARS = 8_000

    /** `dsh-tool-android`/`dsh-tool-shizuku`: `MAX_STDERR = 2000`. */
    const val MAX_SHELL_STDERR_CHARS = 2_000

    /** `Math.max(1000, Math.min(timeoutMs || 30000, 120000))` in both official packages. */
    const val MIN_SHELL_TIMEOUT_MILLIS = 1_000L
    const val MAX_SHELL_TIMEOUT_MILLIS = 120_000L
    const val DEFAULT_SHELL_TIMEOUT_MILLIS = 30_000L

    /**
     * A privileged command is one shell command line, exactly as the official tool sends it.
     * The cap only bounds memory; the command deliberately keeps full shell power and that is
     * reported to the user by the consent prompt rather than hidden behind a fake sandbox.
     */
    const val MAX_SHELL_COMMAND_CHARS = 8_192

    /** Only one privileged command may run at a time; a second concurrent call is BUSY. */
    const val MAX_CONCURRENT_SHELL_COMMANDS = 1

    /** `dsh-tool-android`: usage days are clamped to 1..30; MCA rejects instead. */
    const val MIN_USAGE_DAYS = 1
    const val MAX_USAGE_DAYS = 30

    /** The official tool returns at most the first 200 packages of `pm list`; usage is smaller. */
    const val MAX_USAGE_APPS = 100
    const val MAX_USAGE_APP_NAME_CHARS = 120
    const val MAX_PACKAGE_NAME_CHARS = 200

    /** Clipboard text is user content; the cap is a memory bound, not a claim about the system. */
    const val MAX_CLIPBOARD_CHARS = 100_000

    /** `Settings.System` keys are identifiers: letters, digits, underscore and dot only. */
    const val MAX_SETTING_KEY_CHARS = 128
    const val MAX_SETTING_VALUE_CHARS = 512

    /** Overlay actions accepted by `android_overlay` in `dsh-tool-android`. */
    const val OVERLAY_ACTION_SHOW = "show"
    const val OVERLAY_ACTION_HIDE = "hide"
    const val OVERLAY_ACTION_STATUS = "status"

    /** Short user-facing reason lines stay prompt-free; 300 is the contract detail ceiling. */
    const val MAX_STATUS_DETAIL_CHARS = 300
}
