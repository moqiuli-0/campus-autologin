package com.campusnet.autologin

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 后台检测/登录的运行状态中心：服务更新，界面订阅。 */
object AppStatus {
    const val IDLE = "IDLE"                 // 空闲/未在校园 WiFi
    const val NO_CRED = "NO_CRED"           // 未配置账号
    const val WIFI_NO_MATCH = "WIFI_NO_MATCH"
    const val CHECKING = "CHECKING"
    const val PORTAL = "PORTAL"             // 检测到认证页
    const val LOGGING_IN = "LOGGING_IN"
    const val ONLINE = "ONLINE"             // 已联网
    const val SUCCESS = "SUCCESS"           // 自动登录成功
    const val FAILED = "FAILED"
    const val UNKNOWN = "UNKNOWN"

    data class Status(
        val state: String = IDLE,
        val detail: String = "",
        val ssid: String? = null,
        val time: Long = 0L,
        val busy: Boolean = false,
        val portalUrl: String? = null
    )

    private const val PREF_STATUS = "status"
    private const val K_STATE = "state"
    private const val K_DETAIL = "detail"
    private const val K_SSID = "ssid"
    private const val K_TIME = "time"
    private const val K_PORTAL_URL = "portalUrl"

    private val _flow = MutableStateFlow(Status())
    val flow: StateFlow<Status> = _flow.asStateFlow()

    fun initFrom(context: Context) {
        if (_flow.value.time != 0L) return
        val p = context.applicationContext.getSharedPreferences(PREF_STATUS, Context.MODE_PRIVATE)
        _flow.value = Status(
            state = p.getString(K_STATE, IDLE) ?: IDLE,
            detail = p.getString(K_DETAIL, "") ?: "",
            ssid = p.getString(K_SSID, null),
            time = p.getLong(K_TIME, 0L),
            portalUrl = p.getString(K_PORTAL_URL, null)
        )
    }

    fun update(
        context: Context?,
        state: String,
        detail: String,
        ssid: String? = null,
        busy: Boolean = false,
        portalUrl: String? = null
    ) {
        val s = Status(
            state = state,
            detail = detail,
            ssid = ssid ?: _flow.value.ssid,
            time = if (state == _flow.value.state && detail == _flow.value.detail) _flow.value.time else System.currentTimeMillis(),
            busy = busy,
            portalUrl = portalUrl ?: _flow.value.portalUrl
        )
        _flow.value = s
        HistoryStore.maybeRecord(context, s.state, s.detail, s.ssid)
        context?.let { c ->
            c.applicationContext.getSharedPreferences(PREF_STATUS, Context.MODE_PRIVATE).edit()
                .putString(K_STATE, s.state)
                .putString(K_DETAIL, s.detail)
                .putString(K_SSID, s.ssid)
                .putLong(K_TIME, s.time)
                .putString(K_PORTAL_URL, s.portalUrl)
                .apply()
        }
    }
}
