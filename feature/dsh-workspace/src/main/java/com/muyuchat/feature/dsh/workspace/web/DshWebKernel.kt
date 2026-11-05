package com.muyuchat.feature.dsh.workspace.web

/**
 * Which engine renders the official DSH page.
 *
 * MCA ships two kernels on purpose. The system WebView is the default because it is always present,
 * shares the platform cookie jar with the rest of the app and needs no extra ~91 MB artifact. The
 * GeckoView kernel exists for the cases the system WebView cannot serve (a device whose WebView
 * provider is stale or broken, or an upstream page that depends on a Gecko-only feature).
 *
 * The kernel is a *local* rendering choice: both paths load the exact same authenticated URL and
 * both are subject to [DshNavigationPolicy]. Nothing about the runtime, the payload or the token
 * changes with it.
 */
enum class DshWebKernel(val prefValue: String, val label: String, val summary: String) {
    SYSTEM_WEBVIEW(
        prefValue = "webview",
        label = "系统 WebView",
        summary = "系统内置内核：体积为 0，与应用其余部分共享 Cookie 罐，默认选择。"
    ),
    GECKOVIEW(
        prefValue = "gecko",
        label = "GeckoView",
        summary = "Mozilla Gecko 内核：独立 Cookie 罐与渲染栈，需要重新登录 DSH；APK 约 +91 MB。"
    );

    companion object {
        /** The kernel a fresh install uses; never the large one. */
        val DEFAULT: DshWebKernel = SYSTEM_WEBVIEW

        /** Unknown or missing values fall back to the default instead of failing the workspace. */
        fun fromPref(value: String?): DshWebKernel =
            entries.firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}

/**
 * Whether a kernel can actually run on this install.
 *
 * Pure on purpose: the answer must be provable on the JVM, and it must never silently pretend that
 * a missing 91 MB engine will work. A kernel that cannot run is *reported* and the host keeps the
 * system WebView, per the project rule that device/build facts may select a transport but must
 * never block the feature.
 */
object DshWebKernelPolicy {

    /** The only ABI the GeckoView artifact is packaged for. */
    const val GECKOVIEW_ABI: String = "arm64-v8a"

    /**
     * Null when [kernel] is usable; otherwise the human reason the caller must surface.
     *
     * @param supportedAbis `Build.SUPPORTED_ABIS`
     * @param geckoViewPresent the GeckoView classes are on the classpath (a no-Gecko build is legal)
     */
    fun unavailableReason(
        kernel: DshWebKernel,
        supportedAbis: List<String>,
        geckoViewPresent: Boolean
    ): String? = when (kernel) {
        DshWebKernel.SYSTEM_WEBVIEW -> null
        DshWebKernel.GECKOVIEW -> when {
            !geckoViewPresent ->
                "本次构建没有打入 GeckoView（依赖未解析）；已回退到系统 WebView。"
            supportedAbis.none { it == GECKOVIEW_ABI } ->
                "GeckoView 只有 $GECKOVIEW_ABI 构件，本机 ABI 是 " +
                    "${supportedAbis.joinToString("/").ifBlank { "未知" }}；已回退到系统 WebView。"
            else -> null
        }
    }

    /** The kernel the host should actually render, given the requested one and the facts. */
    fun effectiveKernel(
        requested: DshWebKernel,
        supportedAbis: List<String>,
        geckoViewPresent: Boolean
    ): DshWebKernel = if (unavailableReason(requested, supportedAbis, geckoViewPresent) == null) {
        requested
    } else {
        DshWebKernel.SYSTEM_WEBVIEW
    }
}
