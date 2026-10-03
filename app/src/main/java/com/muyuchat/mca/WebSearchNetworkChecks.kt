package com.muyuchat.mca

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.content.Context

/**
 * The Android environment checks behind the "network preflight" the settings screen offers.
 *
 * One function, one Android dependency: it reads the system's own view of the network
 * (`ConnectivityManager` and the private-DNS settings) and turns it into user-facing lines. It holds
 * no owner state and writes nothing, so it moved out as a plain function taking the `Application` the
 * ViewModel already held -- **no port was required**, and no new guard was invented: the original
 * returned the "network service unavailable" line only when `ConnectivityManager` was null, and it
 * still does.
 *
 * The wording is a user-visible contract and is reproduced verbatim, including the distinction between
 * "需检查" (a problem the user may have to act on), "通过" (verified) and "提示" (worth knowing, not a
 * failure). The preflight report counts `需检查` lines as warnings, so changing a prefix changes the
 * reported health -- the three prefixes are load-bearing.
 *
 * Order is preserved as well: the checks are appended in the same sequence, and the early returns for
 * "no connectivity service", "no active network" and "capabilities unavailable" short-circuit with
 * exactly the lines they did before.
 */
internal fun androidWebSearchNetworkChecks(app: Application): List<String> {
    val checks = mutableListOf<String>()
    val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    if (connectivity == null) {
        return listOf("需检查：系统网络状态服务不可用，无法确认当前手机联网环境。")
    }
    when (connectivity.restrictBackgroundStatus) {
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> {
            checks += "需检查：系统流量节省正在限制后台联网；请允许 MCA 使用网络后再测试。"
        }
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> {
            checks += "通过：系统流量节省未限制 MCA 联网。"
        }
    }
    val activeNetwork = connectivity.activeNetwork
    if (activeNetwork == null) {
        checks += "需检查：手机当前没有活动网络，请先连接 Wi-Fi 或移动数据。"
        checks += "需检查：如果系统浏览器可以联网，请检查系统设置或安全中心是否禁止 MCA 使用 WLAN/移动数据，并排查 VPN、私人 DNS、代理或省电策略。"
        return checks
    }
    val capabilities = connectivity.getNetworkCapabilities(activeNetwork)
    if (capabilities == null) {
        checks += "需检查：无法读取当前网络能力，请检查系统网络权限或重新连接网络。"
        return checks
    }
    val transports = buildList {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("移动数据")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("以太网")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
    }.ifEmpty { listOf("未知网络") }
    checks += "通过：当前活动网络：${transports.joinToString(" / ")}。"
    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
        checks += "通过：系统报告当前网络具备 Internet 能力。"
    } else {
        checks += "需检查：系统报告当前网络不具备 Internet 能力，搜索请求可能无法发出。"
    }
    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
        checks += "通过：系统已验证当前网络可访问公网。"
    } else {
        checks += "需检查：系统尚未验证当前网络可访问公网，可能是未登录 Wi-Fi、DNS 异常、网络受限或代理/VPN 问题。"
    }
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    ) {
        checks += "提示：当前网络可能经过 VPN；如果 DNS 失败，请优先检查 VPN 或代理规则。"
    }
    val proxyHost = listOfNotNull(
        System.getProperty("http.proxyHost")?.takeIf { it.isNotBlank() },
        System.getProperty("https.proxyHost")?.takeIf { it.isNotBlank() }
    ).distinct()
    if (proxyHost.isNotEmpty()) {
        checks += "提示：检测到系统代理 ${proxyHost.joinToString(" / ")}；搜索接口会受代理可达性影响。"
    }
    runCatching {
        val resolver = app.contentResolver
        val privateDnsMode = Settings.Global.getString(resolver, "private_dns_mode").orEmpty()
        val privateDnsHost = Settings.Global.getString(resolver, "private_dns_specifier").orEmpty()
        if (privateDnsMode.isNotBlank() && privateDnsMode != "off") {
            checks += buildString {
                append("提示：私人 DNS 模式为 ")
                append(privateDnsMode)
                if (privateDnsHost.isNotBlank()) append("（$privateDnsHost）")
                append("；如域名解析失败，请检查该 DNS 服务。")
            }
        }
    }
    return checks
}
