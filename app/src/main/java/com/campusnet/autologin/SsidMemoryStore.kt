package com.campusnet.autologin

import android.content.Context
import org.json.JSONObject

/** SSID 关键词匹配：WiFi 名包含任一关键词（忽略大小写）即命中。 */
fun ssidMatchesRules(ssid: String, rules: List<String>): Boolean =
    rules.any { it.isNotBlank() && ssid.contains(it.trim(), ignoreCase = true) }

/**
 * WiFi 记忆库：第一次见到某个 WiFi 的认证页时，用页面关键词判断它是不是校园网，
 * 结果按 SSID 记住。以后重连直接查记忆，不再重复判断。
 *  - true  = 校园网（自动登录）
 *  - false = 其他网络的认证页（永不尝试登录，账号密码绝不外发）
 *  - null  = 没见过（需要现场判断）
 */
object SsidMemoryStore {
    private const val PREF = "ssid_memory"
    private const val KEY = "map"
    private const val MAX = 40

    fun get(context: Context, ssid: String): Boolean? = try {
        val map = JSONObject(
            context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, "{}") ?: "{}"
        )
        if (map.has(ssid)) map.getJSONObject(ssid).getBoolean("campus") else null
    } catch (e: Exception) {
        null
    }

    fun remember(context: Context, ssid: String, campus: Boolean) {
        try {
            val app = context.applicationContext
            val map = JSONObject(
                app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString(KEY, "{}") ?: "{}"
            )
            map.put(
                ssid,
                JSONObject().put("campus", campus).put("t", System.currentTimeMillis())
            )
            // 超出上限时删掉最旧的一条
            if (map.length() > MAX) {
                var oldestKey: String? = null
                var oldest = Long.MAX_VALUE
                val keys = mutableListOf<String>()
                for (k in map.keys()) keys.add(k)
                for (k in keys) {
                    val t = map.getJSONObject(k).optLong("t", 0L)
                    if (t < oldest) { oldest = t; oldestKey = k }
                }
                oldestKey?.let { map.remove(it) }
            }
            app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, map.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun count(context: Context): Int = try {
        JSONObject(
            context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, "{}") ?: "{}"
        ).length()
    } catch (e: Exception) {
        0
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
