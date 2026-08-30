package com.campusnet.autologin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 登录历史：只记录状态变化与登录动作，最多保留 60 条。 */
object HistoryStore {
    data class Entry(
        val time: Long,
        val ok: Boolean,       // true=正常/成功，false=异常/失败
        val title: String,
        val detail: String,
        val ssid: String?
    )

    private const val PREF = "history"
    private const val KEY = "entries"
    private const val MAX = 60

    fun load(context: Context): List<Entry> {
        val json = context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    time = o.getLong("t"),
                    ok = o.getBoolean("ok"),
                    title = o.getString("title"),
                    detail = o.optString("detail"),
                    ssid = if (o.isNull("ssid")) null else o.optString("ssid")
                )
            }.sortedByDescending { it.time }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, entry: Entry) {
        try {
            val app = context.applicationContext
            val arr = JSONArray(app.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: "[]")
            val o = JSONObject()
                .put("t", entry.time)
                .put("ok", entry.ok)
                .put("title", entry.title)
                .put("detail", entry.detail)
                .put("ssid", entry.ssid ?: JSONObject.NULL)
            // 新条目插到头部（load 时也排了序，双保险）
            val newArr = JSONArray()
            newArr.put(o)
            for (i in 0 until arr.length()) {
                if (i >= MAX - 1) break
                newArr.put(arr.getJSONObject(i))
            }
            app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, newArr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    /**
     * 按"状态变化才记一条"的规则由 AppStatus.update 自动调用：
     * - 检测到认证页 / 登录成功 / 登录或检测失败 → 记录
     * - 已联网：仅从非联网状态恢复时记录一次
     */
    fun maybeRecord(context: Context?, state: String, detail: String, ssid: String?) {
        if (context == null) return
        val last = lastRecorded
        if (state == last) return
        when (state) {
            AppStatus.PORTAL -> add(context, Entry(System.currentTimeMillis(), false, "检测到认证页，开始自动登录", detail, ssid))
            AppStatus.SUCCESS -> add(context, Entry(System.currentTimeMillis(), true, "自动登录成功", detail, ssid))
            AppStatus.FAILED -> add(context, Entry(System.currentTimeMillis(), false, "登录或检测失败", detail, ssid))
            AppStatus.UNKNOWN -> add(context, Entry(System.currentTimeMillis(), false, "网络检测异常", detail, ssid))
            AppStatus.ONLINE -> if (last != AppStatus.ONLINE) add(context, Entry(System.currentTimeMillis(), true, "已联网", detail, ssid))
            else -> return
        }
        lastRecorded = state
    }

    @Volatile
    private var lastRecorded: String? = null
}
