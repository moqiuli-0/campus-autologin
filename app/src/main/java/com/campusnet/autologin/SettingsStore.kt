package com.campusnet.autologin

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class AppSettings(
    val userId: String,
    val passwd: String,
    val portalHost: String,
    val fallbackWhenSsidUnknown: Boolean,
    val monitoring: Boolean,
    val verboseLog: Boolean,
    val themeColor: Int,
    val checkIntervalSec: Int,
    val batteryCheckOverride: Boolean,
    val ssidRules: List<String>,
    val leftCampus: Boolean,
    val locationGuard: Boolean,
    val desktopUA: Boolean,
    val campusLat: Double?,
    val campusLng: Double?
) {
    val configured: Boolean get() = userId.isNotBlank() && passwd.isNotBlank()
    val campusLocationSet: Boolean get() = campusLat != null && campusLng != null
}

/** 账号密码用 EncryptedSharedPreferences 加密保存，普通设置用明文 prefs。 */
object SettingsStore {
    private const val PREF_SECURE = "secure_prefs"
    private const val PREF_STATE = "run_state"

    private const val K_USER = "userId"
    private const val K_PASS = "passwd"
    private const val K_PORTAL = "portalHost"
    private const val K_FALLBACK = "fallbackSsidUnknown"
    private const val K_MONITOR = "monitoring"
    private const val K_VERBOSE = "verboseLog"
    private const val K_ONBOARDING = "onboardingDone"
    private const val K_THEME = "themeColor"
    private const val K_INTERVAL = "checkIntervalSec"
    private const val K_INTERVAL_EXPLAINED = "intervalExplained"
    private const val K_BATTERY_OVERRIDE = "batteryCheckOverride"
    private const val K_SSID_RULES = "ssidRules"
    private const val K_LEFT_CAMPUS = "leftCampus"
    private const val K_LOCATION_GUARD = "locationGuard"
    private const val K_DESKTOP_UA = "desktopUA"
    private const val K_CAMPUS_LAT = "campusLat"
    private const val K_CAMPUS_LNG = "campusLng"

    /** 多值字段分隔符（SSID 规则等列表存储用）。 */
    private const val SEP = "\u0001"

    const val DEFAULT_PORTAL = "http://1.1.1.1"

    @Volatile
    private var secureCache: SharedPreferences? = null

    @Synchronized
    private fun securePrefs(context: Context): SharedPreferences {
        val app = context.applicationContext
        secureCache?.let { return it }
        val create: () -> SharedPreferences = {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app, PREF_SECURE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        val prefs = try {
            create()
        } catch (e: Exception) {
            // 密钥库异常（如系统备份恢复导致密钥不匹配）时重置后重建
            app.deleteSharedPreferences(PREF_SECURE)
            create()
        }
        secureCache = prefs
        return prefs
    }

    private fun statePrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_STATE, Context.MODE_PRIVATE)

    fun load(context: Context): AppSettings {
        val app = context.applicationContext
        val s = securePrefs(app)
        val st = statePrefs(app)
        return AppSettings(
            userId = s.getString(K_USER, "") ?: "",
            passwd = s.getString(K_PASS, "") ?: "",
            portalHost = st.getString(K_PORTAL, DEFAULT_PORTAL) ?: DEFAULT_PORTAL,
            fallbackWhenSsidUnknown = st.getBoolean(K_FALLBACK, true),
            monitoring = st.getBoolean(K_MONITOR, false),
            verboseLog = st.getBoolean(K_VERBOSE, false),
            themeColor = st.getInt(K_THEME, 0xFF6750A4.toInt()),
            checkIntervalSec = st.getInt(K_INTERVAL, 300),
            batteryCheckOverride = st.getBoolean(K_BATTERY_OVERRIDE, false),
            ssidRules = (st.getString(K_SSID_RULES, null) ?: "").split(SEP).filter { it.isNotBlank() },
            leftCampus = st.getBoolean(K_LEFT_CAMPUS, false),
            locationGuard = st.getBoolean(K_LOCATION_GUARD, false),
            desktopUA = st.getBoolean(K_DESKTOP_UA, false),
            campusLat = st.getString(K_CAMPUS_LAT, null)?.toDoubleOrNull(),
            campusLng = st.getString(K_CAMPUS_LNG, null)?.toDoubleOrNull()
        )
    }

    fun saveCredentials(context: Context, userId: String, passwd: String) {
        securePrefs(context).edit()
            .putString(K_USER, userId.trim())
            .putString(K_PASS, passwd)
            .apply()
    }

    fun saveOptions(context: Context, portalHost: String, fallbackWhenSsidUnknown: Boolean) {
        statePrefs(context).edit()
            .putString(K_PORTAL, portalHost.trim().ifBlank { DEFAULT_PORTAL })
            .putBoolean(K_FALLBACK, fallbackWhenSsidUnknown)
            .apply()
    }

    fun setMonitoring(context: Context, enabled: Boolean) {
        statePrefs(context).edit().putBoolean(K_MONITOR, enabled).apply()
    }

    fun setVerboseLog(context: Context, enabled: Boolean) {
        statePrefs(context).edit().putBoolean(K_VERBOSE, enabled).apply()
    }

    fun isOnboardingDone(context: Context): Boolean =
        statePrefs(context).getBoolean(K_ONBOARDING, false)

    fun setOnboardingDone(context: Context) {
        statePrefs(context).edit().putBoolean(K_ONBOARDING, true).apply()
    }

    fun setThemeColor(context: Context, argb: Int) {
        statePrefs(context).edit().putInt(K_THEME, argb).apply()
    }

    fun setCheckInterval(context: Context, seconds: Int) {
        statePrefs(context).edit().putInt(K_INTERVAL, seconds.coerceIn(15, 3600)).apply()
    }

    fun isIntervalExplained(context: Context): Boolean =
        statePrefs(context).getBoolean(K_INTERVAL_EXPLAINED, false)

    fun setIntervalExplained(context: Context) {
        statePrefs(context).edit().putBoolean(K_INTERVAL_EXPLAINED, true).apply()
    }

    /** 手动屏蔽"忽略电池优化"检查：检测方式受限（如仅在厂商设置里放行）时由用户手动标记。 */
    fun setBatteryCheckOverride(context: Context, enabled: Boolean) {
        statePrefs(context).edit().putBoolean(K_BATTERY_OVERRIDE, enabled).apply()
    }

    fun setSsidRules(context: Context, rules: List<String>) {
        statePrefs(context).edit().putString(K_SSID_RULES, rules.filter { it.isNotBlank() }.joinToString(SEP)).apply()
    }

    /** 轻量读取（不碰加密存储），供通知栏等高频路径使用。 */
    fun loadSsidRules(context: Context): List<String> =
        statePrefs(context).getString(K_SSID_RULES, null)?.split(SEP)?.filter { it.isNotBlank() } ?: emptyList()

    fun setLeftCampus(context: Context, enabled: Boolean) {
        statePrefs(context).edit().putBoolean(K_LEFT_CAMPUS, enabled).apply()
    }

    fun setLocationGuard(context: Context, enabled: Boolean) {
        statePrefs(context).edit().putBoolean(K_LOCATION_GUARD, enabled).apply()
    }

    fun setDesktopUA(context: Context, enabled: Boolean) {
        statePrefs(context).edit().putBoolean(K_DESKTOP_UA, enabled).apply()
    }

    fun setCampusLocation(context: Context, lat: Double, lng: Double) {
        statePrefs(context).edit()
            .putString(K_CAMPUS_LAT, "%.6f".format(lat))
            .putString(K_CAMPUS_LNG, "%.6f".format(lng))
            .apply()
    }

    fun clearCampusLocation(context: Context) {
        statePrefs(context).edit()
            .remove(K_CAMPUS_LAT)
            .remove(K_CAMPUS_LNG)
            .apply()
    }
}
