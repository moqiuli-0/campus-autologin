package com.campusnet.autologin

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * 前台常驻服务：监听 WiFi 网络事件 + 定时复查。
 * SSID 匹配用户配置的关键词规则时检测连通性，被重定向到认证页则自动登录。
 */
class PortalMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.campusnet.autologin.START"
        const val ACTION_CHECK_NOW = "com.campusnet.autologin.CHECK_NOW"
        const val ACTION_PING = "com.campusnet.autologin.PING"
        const val ACTION_PORTAL_IGNORE = "com.campusnet.autologin.PORTAL_IGNORE"

        // 本进程内记住"忽略过提醒"的网络（重启后重新提醒）
        val portalRemindSuppressed = mutableSetOf<String>()

        // SSID 关键词规则由用户在设置中配置（见 SettingsStore.ssidRules），
        // WiFi 名包含任一关键词即视为校园网（忽略大小写）。

        // 地理围栏半径（米）：定位校验开启时，距校园圆心超过该距离拒绝自动填表
        const val CAMPUS_RADIUS_M = 500f

        private const val CH_MONITOR = "monitor"
        private const val CH_ALERT = "alerts"
        private const val NOTIF_ID_MONITOR = 1
        private const val NOTIF_ID_ALERT = 2
        const val NOTIF_ID_PORTAL = 3

        private const val CHECK_DEBOUNCE_MS = 2500L
        private const val MIN_AUTO_INTERVAL_MS = 15_000L
        private const val FAIL_NOTIFY_COOLDOWN_MS = 15 * 60_000L

        fun start(context: Context) {
            val intent = Intent(context, PortalMonitorService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun checkNow(context: Context) {
            val intent = Intent(context, PortalMonitorService::class.java).setAction(ACTION_CHECK_NOW)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PortalMonitorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val checkMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var scheduled = false
    private var lastAutoCheckAt = 0L
    private var lastFailMessage = ""
    private var lastFailNotifyAt = 0L
    private var manualChecksPending = 0
    private var periodicRunnable: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var notifTicker: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this, SettingsStore.load(this).verboseLog)
        AppLog.info("监控服务启动")
        createChannels()
        startAsForeground()
        AppStatus.initFrom(this)
        registerNetworkCallback()

        // 周期复查：间隔可在界面自定义（秒），每轮重新读取设置
        val runnable = object : Runnable {
            override fun run() {
                scope.launch { runCheck(manual = false) }
                val sec = SettingsStore.load(this@PortalMonitorService)
                    .checkIntervalSec.coerceIn(15, 3600)
                mainHandler.postDelayed(this, sec * 1000L)
            }
        }
        periodicRunnable = runnable
        mainHandler.postDelayed(
            runnable,
            SettingsStore.load(this).checkIntervalSec.coerceIn(15, 3600) * 1000L
        )
        scope.launch { runCheck(manual = false) }

        // 处理中状态每秒刷新通知，让"尝试用时"实时走动
        scope.launch {
            AppStatus.flow.collect { st ->
                if (st.busy) {
                    if (notifTicker == null) {
                        notifTicker = launch {
                            while (isActive) {
                                delay(1000)
                                updateMonitorNotification()
                            }
                        }
                    }
                } else {
                    notifTicker?.cancel()
                    notifTicker = null
                    updateMonitorNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 离校模式：服务不应存活（双保险，正常情况下进入离校模式时服务已被停止）
        if (SettingsStore.load(this).leftCampus) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_CHECK_NOW -> {
                manualChecksPending++
                scope.launch {
                    runCheck(manual = true)
                    manualChecksPending = (manualChecksPending - 1).coerceAtLeast(0)
                    if (manualChecksPending == 0 && !SettingsStore.load(this@PortalMonitorService).monitoring) {
                        stopSelf()
                    }
                }
            }
            ACTION_PING -> startAsForeground()
            ACTION_PORTAL_IGNORE -> {
                // 用户点了提醒通知上的"忽略"：取消通知，本轮连接内不再提醒
                intent?.getStringExtra("key")?.let { portalRemindSuppressed.add(it) }
                try { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_PORTAL) } catch (_: Exception) {}
            }
            else -> Unit
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        periodicRunnable?.let { mainHandler.removeCallbacks(it) }
        netCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        netCallback = null
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 检测主流程 ----------

    private suspend fun runCheck(manual: Boolean) = checkMutex.withLock {
        val settings = SettingsStore.load(this)

        // 离校模式：彻底跳过自动检测与登录（含手动"立即检测"）
        if (settings.leftCampus) {
            AppLog.info("离校模式已开启，跳过检查")
            AppStatus.update(this, AppStatus.IDLE, "离校模式已开启，自动认证已暂停（设置中可关闭）", null)
            return
        }

        if (!settings.configured) {
            AppLog.info("检查跳过：未配置账号")
            AppStatus.update(this, AppStatus.NO_CRED, "尚未配置校园网账号密码")
            return
        }

        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ssid = readSsid(caps)
        AppLog.info("开始检查（manual=$manual）：onWifi=$onWifi ssid=$ssid")

        if (!onWifi) {
            if (manual) AppStatus.update(this, AppStatus.IDLE, "当前未连接 WiFi", null)
            else AppStatus.update(this, AppStatus.IDLE, "未连接 WiFi，待命中", null)
            return
        }

        val ssidMatched = ssid != null && ssidMatchesRules(ssid, settings.ssidRules)
        if (!manual) {
            if (ssid != null && !ssidMatched) {
                AppLog.verbose("SSID 不匹配，跳过")
                AppStatus.update(this, AppStatus.WIFI_NO_MATCH, "当前 WiFi：$ssid（不是校园网，不处理）", ssid)
                return
            }
            if (ssid == null && !settings.fallbackWhenSsidUnknown) {
                AppLog.verbose("SSID 不可读且兜底开关关闭，跳过")
                AppStatus.update(this, AppStatus.IDLE, "已连 WiFi 但无法读取名称（需位置权限），兜底登录已关闭", null)
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastAutoCheckAt < MIN_AUTO_INTERVAL_MS) return
            lastAutoCheckAt = now
        }

        acquireWakeLock()
        try {
            // 清掉上一轮可能残留的"非校园网认证提醒"
            try { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_PORTAL) } catch (_: Exception) {}
            AppStatus.update(this, AppStatus.CHECKING, "正在检测联网状态…", ssid, busy = true)
            updateMonitorNotification()

            when (val result = ConnectivityChecker.check(settings.portalHost)) {
                is ConnectivityChecker.Result.Online -> {
                    AppLog.info("检测结果：已联网")
                    AppStatus.update(this, AppStatus.ONLINE, "已联网，无需登录", ssid)
                    updateMonitorNotification()
                }

                is ConnectivityChecker.Result.Portal -> {
                    AppLog.info("检测结果：认证页 ${result.portalUrl}")
                    handlePortal(settings, result.portalUrl, ssid, ssidMatched, manual)
                }

                is ConnectivityChecker.Result.Offline -> {
                    AppLog.info("检测结果：网络无响应")
                    AppStatus.update(this, AppStatus.FAILED, "网络无响应，稍后自动重试", ssid)
                    updateMonitorNotification()
                }

                is ConnectivityChecker.Result.Unknown -> {
                    AppLog.info("检测结果：异常 ${result.message}")
                    AppStatus.update(this, AppStatus.UNKNOWN, "网络检测异常：${result.message}", ssid)
                    updateMonitorNotification()
                }
            }
        } finally {
            releaseWakeLock()
        }
    }

    private suspend fun handlePortal(
        settings: AppSettings,
        portalUrl: String,
        ssid: String?,
        ssidMatched: Boolean,
        manual: Boolean
    ) {
        val portalHost = Uri.parse(settings.portalHost).host ?: "1.1.1.1"
        val redirectHost = Uri.parse(portalUrl).host ?: ""

        // 未配置关键词规则时：不做自动登录（避免把账号发往未知认证页）
        if (settings.ssidRules.isEmpty()) {
            AppLog.info("检查跳过：未配置 SSID 关键词规则")
            AppStatus.update(this, AppStatus.UNKNOWN, "未配置 WiFi 关键词规则，无法自动登录（设置 → 检测 中添加）", ssid)
            updateMonitorNotification()
            return
        }

        // 判断是否校园网认证页，三级：记忆 → SSID 规则 → 页面关键词学习（结果按 SSID 记住）
        val memory = ssid?.let { SsidMemoryStore.get(this, it) }
        val campus: Boolean = when {
            memory != null -> {
                AppLog.info("SSID 记忆：$ssid → 校园网=$memory")
                memory
            }

            ssidMatched -> {
                if (ssid != null) SsidMemoryStore.remember(this, ssid, true)
                true
            }

            else -> {
                val verdict = ConnectivityChecker.isCampusPortal(portalUrl)
                if (verdict == null) {
                    // 抓取失败不算数，不记忆，留待下轮
                    AppLog.info("认证页识别失败，本轮不处理")
                    AppStatus.update(this, AppStatus.UNKNOWN, "暂时无法识别认证页，稍后重试", ssid)
                    updateMonitorNotification()
                    return
                }
                AppLog.info("学习 SSID：$ssid → 校园网=$verdict")
                if (ssid != null) SsidMemoryStore.remember(this, ssid, verdict)
                verdict
            }
        }

        AppLog.info("认证页 host=$redirectHost，校园网=$campus，ssid=$ssid，manual=$manual")
        if (!campus) {
            // 其他网络的认证页（如商场/餐厅）：绝不尝试登录，账号密码零外发
            val remindKey = ssid ?: portalUrl
            AppStatus.update(
                this, AppStatus.FAILED,
                "此网络的认证页不是校园网（不会发送校园网账号）", ssid, portalUrl = portalUrl
            )
            updateMonitorNotification()
            if (remindKey !in portalRemindSuppressed) {
                portalRemindSuppressed.add(remindKey)
                postPortalReminder(portalUrl, ssid)
            }
            return
        }

        // 防钓鱼硬校验：无论 SSID 如何命中，重定向主机必须与配置的认证页主机一致才允许自动填表
        // （按 host 比较、不含端口，1.1.1.1:8888 也能通过；被劫持到钓鱼页时 host 不匹配，拒绝填表）
        if (!redirectHost.equals(portalHost, ignoreCase = true)) {
            AppStatus.update(
                this, AppStatus.FAILED,
                "认证页来源异常（$redirectHost），为安全起见未自动填写账号", ssid, portalUrl = portalUrl
            )
            updateMonitorNotification()
            notifyResult(false, "认证页来源异常（$redirectHost），未自动登录。如非人为劫持请检查网络")
            return
        }

        // 校园位置围栏（默认关）：自动填表前取一次当前位置，人不在学校周边 500 米内则拒绝填写。
        // 定位失败/超时按原逻辑放行（可用性优先）；坐标仅本地比对，不上传。
        if (settings.locationGuard && settings.campusLocationSet) {
            val loc = getLocationOnce()
            if (loc == null) {
                AppLog.info("位置围栏：定位失败，按原逻辑放行")
            } else {
                val (lat, lng) = loc
                val dist = distanceMeters(lat, lng, settings.campusLat!!, settings.campusLng!!)
                AppLog.info("位置围栏：距校园圆心 ${dist.roundToInt()} 米")
                if (dist > CAMPUS_RADIUS_M) {
                    val msg = "当前位置距学校约 ${if (dist > 1000) "%.1f".format(dist / 1000) + " 公里" else "${dist.roundToInt()} 米"}，不在校园范围内，未自动登录（可在设置关闭位置围栏）"
                    AppStatus.update(this, AppStatus.FAILED, msg, ssid, portalUrl = portalUrl)
                    updateMonitorNotification()
                    notifyResult(false, msg)
                    return
                }
            }
        }

        AppStatus.update(this, AppStatus.PORTAL, "检测到校园网认证页，开始自动登录", ssid, busy = true)
        updateMonitorNotification()

        var outcome: PortalLoginManager.LoginOutcome? = null
        for (attempt in 1..2) {
            AppLog.info("自动登录第 $attempt 次尝试")
            outcome = PortalLoginManager.login(this, portalUrl, settings.userId, settings.passwd)
            AppLog.info("登录尝试结果：success=${outcome.success} msg=${outcome.message}")
            if (outcome.success) break
            if (attempt == 1) delay(5000)
        }
        val final = outcome!!
        if (final.success) {
            AppStatus.update(this, AppStatus.SUCCESS, final.message, ssid)
        } else {
            AppStatus.update(this, AppStatus.FAILED, final.message, ssid)
        }
        updateMonitorNotification()
        notifyResult(final.success, final.message)
    }

    // ---------- SSID ----------

    @Suppress("DEPRECATION")
    private fun readSsid(caps: NetworkCapabilities?): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        // API 31+ 优先从 NetworkCapabilities.transportInfo 读取（WifiManager.connectionInfo 已废弃）
        if (Build.VERSION.SDK_INT >= 31) {
            (caps?.transportInfo as? android.net.wifi.WifiInfo)?.let { wifi ->
                val clean = wifi.ssid?.trim()?.removeSurrounding("\"")
                if (!clean.isNullOrEmpty() && !clean.equals("<unknown ssid>", ignoreCase = true)) return clean
            }
        }

        // 旧系统兜底：WifiManager
        return try {
            val wifi = application.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val raw = wifi.connectionInfo?.ssid ?: return null
            val clean = raw.trim().removeSurrounding("\"")
            if (clean.isEmpty() || clean.equals("<unknown ssid>", ignoreCase = true)) null else clean
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 定位 ----------

    /** 取一次当前位置（优先缓存，其次网络/GPS），超时返回 null。供地理围栏与圆心采集使用。 */
    private suspend fun getLocationOnce(timeoutMs: Long = 6000L): Pair<Double, Double>? =
        CampusLocator.getCurrentLocation(this, timeoutMs)

    /** 两点间球面距离（米）。 */
    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0]
    }

    // ---------- 网络回调 ----------

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scheduleCheck()
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    scheduleCheck()
                }

                override fun onLost(network: Network) {
                    AppStatus.update(this@PortalMonitorService, AppStatus.IDLE, "WiFi 已断开，待命中", null)
                }
            }
            cm.registerNetworkCallback(request, callback)
            netCallback = callback
        } catch (e: Exception) {
            // 注册失败时仍有周期复查兜底
        }
    }

    private fun scheduleCheck() {
        if (scheduled) return
        scheduled = true
        mainHandler.postDelayed({
            scheduled = false
            scope.launch { runCheck(manual = false) }
        }, CHECK_DEBOUNCE_MS)
    }

    // ---------- WakeLock ----------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "campusnet:autologin").apply {
                setReferenceCounted(false)
                acquire(3 * 60_000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    // ---------- 通知 ----------

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_MONITOR, "后台监控", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "常驻监控状态"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "登录提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "自动登录失败等重要提醒"
                }
            )
        }
    }

    private fun startAsForeground() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // 按系统版本生成候选前台类型，逐级降级尝试：
        //  API 34+：specialUse（无 6 小时运行上限的官方出口类型），已授权精确定位时叠加 location
        //  API 29–33：dataSync（+location）——Android 15 之前 dataSync 没有超时机制
        //  后台启动 location 类型在 Android 14+ 会被系统拒绝，靠降级列表兜底（开机自启路径）
        val candidates: List<Int> = when {
            Build.VERSION.SDK_INT >= 34 -> buildList {
                if (fineGranted) {
                    add(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                }
                add(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
            Build.VERSION.SDK_INT >= 29 -> buildList {
                if (fineGranted) {
                    add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                }
                add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
            else -> listOf(0)
        }

        for (type in candidates) {
            try {
                ServiceCompat.startForeground(this, NOTIF_ID_MONITOR, buildMonitorNotification(), type)
                return
            } catch (e: Exception) {
                AppLog.info("startForeground(type=$type) 失败，尝试降级：${e.message}")
            }
        }
        AppLog.info("前台服务启动全部失败，停止服务")
        stopSelf()
    }

    override fun onTimeout(startId: Int) {
        // Android 15+：dataSync 类型后台运行超时回调（34+ 已改用 specialUse，正常不会走到这里）
        AppLog.info("FGS 后台运行超时被系统叫停，服务停止；重新打开 APP 或重连网络可恢复监控")
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.info("FGS 后台运行超时(type=$fgsType)被系统叫停，服务停止")
        stopSelf()
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildMonitorNotification(): Notification {
        val status = AppStatus.flow.value
        return NotificationCompat.Builder(this, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("校园网监控")
            .setContentText(briefText(status))
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /** 通知栏简略状态（校园网视角，不显示时间戳）。 */
    private fun briefText(status: AppStatus.Status): String {
        val rules = SettingsStore.loadSsidRules(this)
        val onCampus = status.ssid != null && ssidMatchesRules(status.ssid, rules)
        return when (status.state) {
            AppStatus.CHECKING -> {
                val s = (System.currentTimeMillis() - status.time) / 1000
                "正在尝试连接中 · ${s}秒"
            }

            AppStatus.PORTAL, AppStatus.LOGGING_IN -> {
                val s = (System.currentTimeMillis() - status.time) / 1000
                "正在自动登录 · ${s}秒"
            }

            AppStatus.ONLINE, AppStatus.SUCCESS -> if (onCampus) "校园网已连接" else "未连接校园网"
            AppStatus.FAILED -> if (status.detail.contains("无响应")) "网络无响应，稍后重试" else "自动登录失败"
            AppStatus.UNKNOWN -> "网络检测异常"
            AppStatus.NO_CRED -> "未配置账号"
            else -> "未连接校园网"
        }
    }

    private fun updateMonitorNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID_MONITOR, buildMonitorNotification())
        } catch (_: Exception) {}
    }

    /** 非校园网认证页提醒：点"打开认证页"直接用浏览器打开（不发送校园网账号），点"忽略"本轮不再提醒。 */
    private fun postPortalReminder(portalUrl: String, ssid: String?) {
        try {
            val openIntent = PendingIntent.getActivity(
                this, 0,
                Intent(Intent.ACTION_VIEW, Uri.parse(portalUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val ignoreIntent = PendingIntent.getService(
                this, 0,
                Intent(this, PortalMonitorService::class.java)
                    .setAction(ACTION_PORTAL_IGNORE)
                    .putExtra("key", ssid ?: portalUrl),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("此网络需要登录（非校园网）")
                .setContentText("点「打开认证页」用浏览器登录；不会使用你的校园网账号。")
                .addAction(0, "打开认证页", openIntent)
                .addAction(0, "忽略", ignoreIntent)
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID_PORTAL, n)
        } catch (_: Exception) {}
    }

    private fun notifyResult(success: Boolean, message: String) {
        if (success) return // 成功保持静默，仅在常驻通知中体现
        val now = System.currentTimeMillis()
        if (message == lastFailMessage && now - lastFailNotifyAt < FAIL_NOTIFY_COOLDOWN_MS) return
        lastFailMessage = message
        lastFailNotifyAt = now
        try {
            val notification = NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("校园网自动登录失败")
                .setContentText(message.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID_ALERT, notification)
        } catch (_: Exception) {}
    }
}
