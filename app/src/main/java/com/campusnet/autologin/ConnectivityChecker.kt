package com.campusnet.autologin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 连网检测：请求 generate_204 探测点。
 * - 返回 204 → 已联网
 * - 返回 302/301 且带 Location → 被重定向到认证页（Portal）
 * - 探测点返回 200（说明内容被劫持替换）→ 再直接访问认证页确认
 */
object ConnectivityChecker {

    sealed class Result {
        data object Online : Result()
        data class Portal(val portalUrl: String) : Result()
        data object Offline : Result()
        data class Unknown(val message: String) : Result()
    }

    private val probes = listOf(
        "http://connect.rom.miui.com/generate_204",
        "http://wifi.vivo.com.cn/generate_204",
        "http://connectivitycheck.platform.hicloud.com/generate_204",
        "http://www.gstatic.cn/generate_204"
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun check(portalHostUrl: String = SettingsStore.DEFAULT_PORTAL): Result {
        var lastError = ""
        var sawHtml200 = false
        for (url in probes) {
            try {
                val response = withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url(url).build()).execute()
                }
                response.use { r ->
                    when {
                        r.code == 204 -> {
                            AppLog.info("探测 $url -> 204，已联网")
                            return Result.Online
                        }
                        r.code in 300..399 -> {
                            val loc = r.header("Location")
                            AppLog.info("探测 $url -> ${r.code}，重定向到 $loc")
                            if (!loc.isNullOrBlank()) return Result.Portal(resolve(url, loc))
                        }
                        r.code == 200 -> {
                            AppLog.verbose("探测 $url -> 200（内容疑似被劫持）")
                            sawHtml200 = true
                        }
                        else -> AppLog.verbose("探测 $url -> ${r.code}")
                    }
                }
            } catch (e: Exception) {
                AppLog.verbose("探测 $url 异常：${e.message}")
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        // 所有探测点都返回了 200 页面 → 疑似 DNS/HTTP 劫持到认证页，直接访问认证页确认
        if (sawHtml200) {
            val candidates = mutableListOf(portalHostUrl)
            // 部分部署把认证页挂在 :8888 端口（实测截图确认），一并尝试
            val m = Regex("^(https?://[^/:]+)").find(portalHostUrl)
            if (m != null) {
                val withPort = m.groupValues[1] + ":8888"
                if (withPort != portalHostUrl) candidates.add(withPort)
            }
            for (candidate in candidates) {
                try {
                    val resp = withContext(Dispatchers.IO) {
                        client.newCall(Request.Builder().url(candidate).build()).execute()
                    }
                    resp.use { r ->
                        val body = r.body?.string() ?: ""
                        AppLog.info("直访认证页 $candidate -> ${r.code}，含表单=${body.contains("goLoginForm")}")
                        if (body.contains("goLoginForm") || body.contains("webauth") ||
                            body.contains("实名认证", ignoreCase = false)
                        ) {
                            return Result.Portal(candidate)
                        }
                    }
                } catch (e: Exception) {
                    AppLog.verbose("直访认证页 $candidate 异常：${e.message}")
                    lastError = e.message ?: lastError
                }
            }
        }
        AppLog.verbose("联网检测结束：未发现 204/重定向")
        return if (lastError.isBlank()) Result.Offline else Result.Unknown(lastError)
    }

    internal fun resolve(base: String, location: String): String = try {
        URI(base).resolve(URI(location)).toString()
    } catch (e: Exception) {
        location
    }

    /**
     * 判断一个认证页是不是"我们的"校园网：
     * 页面里找稳定关键词「校园网实名认证系统」，辅以 ASCII 特征 goLoginForm / webauth。
     * 兼容 GBK 编码的页面。返回 null 表示抓取失败（不要据此下结论）。
     */
    suspend fun isCampusPortal(url: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            resp.use { r ->
                val bytes = r.body?.bytes() ?: return@use null
                val charsetName = r.header("Content-Type")
                    ?.let { Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                val body = if (charsetName != null) {
                    runCatching { String(bytes, charset(charsetName)) }.getOrDefault(String(bytes))
                } else {
                    String(bytes)
                }
                val hit = body.contains("校园网实名认证系统") ||
                    body.contains("goLoginForm") ||
                    body.contains("webauth")
                AppLog.info("识别认证页 $url -> ${r.code}，校园网特征=$hit")
                hit
            }
        } catch (e: Exception) {
            AppLog.info("识别认证页失败：${e.message}")
            null
        }
    }
}
