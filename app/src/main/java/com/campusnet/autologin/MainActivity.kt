package com.campusnet.autologin

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStatus.initFrom(this)
        AppLog.init(this, SettingsStore.load(this).verboseLog)
        exitLeftCampusModeIfNeeded()
        setContent {
            AppRoot()
        }
    }

    override fun onStart() {
        super.onStart()
        // 服务在跑时通知它按当前权限升级前台服务类型（保证能读 SSID）
        if (SettingsStore.load(this).monitoring) {
            try {
                startService(Intent(this, PortalMonitorService::class.java).setAction(PortalMonitorService.ACTION_PING))
            } catch (_: Exception) {
            }
        }
    }


    /** 离校模式在下次打开 APP 时自动退出：恢复开机自启并重启监控。 */
    private fun exitLeftCampusModeIfNeeded() = exitLeftCampusModeIfNeeded(applicationContext)

    /** 进入离校模式：停止监控、禁用开机自启并关闭应用，直到下次手动打开。 */
    private fun enterLeftCampusMode() = enterLeftCampusMode(this)
}

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}


/** 进入离校模式：停服务、禁开机自启、关闭应用；下次手动打开时自动恢复。 */
private fun enterLeftCampusMode(activity: Activity) {
    val context = activity.applicationContext
    SettingsStore.setLeftCampus(context, true)
    SettingsStore.setMonitoring(context, false)
    PortalMonitorService.stop(context)
    try {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, BootReceiver::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    } catch (_: Exception) {}
    Toast.makeText(context, "已进入离校模式：后台监控与开机自启已停止", Toast.LENGTH_LONG).show()
    activity.finishAffinity()
}

private fun exitLeftCampusModeIfNeeded(context: Context) {
    if (!SettingsStore.load(context).leftCampus) return
    SettingsStore.setLeftCampus(context, false)
    SettingsStore.setMonitoring(context, true)
    try {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, BootReceiver::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    } catch (_: Exception) {}
    PortalMonitorService.start(context)
    Toast.makeText(context, "已退出离校模式，监控已恢复", Toast.LENGTH_SHORT).show()
}

// ============================ 根导航 ============================

private val THEME_PRESETS = listOf(
    Color(0xFF6750A4), // 经典紫（默认）
    Color(0xFF1565C0), // 深海蓝
    Color(0xFF0288D1), // 天青蓝
    Color(0xFF00897B), // 青碧绿
    Color(0xFF2E7D32), // 森林绿
    Color(0xFFD81B60), // 珊瑚粉
    Color(0xFFEF6C00), // 落日橙
    Color(0xFF455A64)  // 石墨灰
)

/** 由主题基色派生整套 Material 配色（自动算按钮文字黑/白）。 */
private fun themeScheme(base: Color): ColorScheme {
    val onBase = if (base.luminance() > 0.55f) Color.Black else Color.White
    val container = lerp(base, Color.White, 0.80f)
    val onContainer = lerp(base, Color.Black, 0.60f)
    return lightColorScheme(
        primary = base,
        onPrimary = onBase,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        secondary = base,
        onSecondary = onBase,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        tertiary = base
    )
}

// ============================ 联系作者 ============================

private const val REPO_URL = "https://github.com/moqiuli-0/campus-autologin"
private const val CONTACT_EMAIL = "dachaiquan@foxmail.com"

private fun copyText(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制 $label", Toast.LENGTH_SHORT).show()
}

private fun sendFeedbackEmail(context: Context) {
    try {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:dachaiquan@foxmail.com")
            putExtra(Intent.EXTRA_SUBJECT, "【APP反馈】")
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        copyText(context, "邮箱：dachaiquan@foxmail.com", "dachaiquan@foxmail.com")
        Toast.makeText(context, "未找到邮件应用，已复制邮箱地址", Toast.LENGTH_LONG).show()
    }
}

// ============================ 系统门户跳转 ============================

/** 系统门户检测是否开启（null=读取失败）。键名为系统设置键。 */
private fun portalDetectionEnabled(context: Context): Boolean? = try {
    Settings.Global.getInt(context.contentResolver, "captive_portal_detection_enabled", 1) != 0
} catch (e: Exception) {
    null
}

private fun canWriteSecureSettings(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
        PackageManager.PERMISSION_GRANTED

private fun disablePortalDetection(context: Context): Boolean = try {
    Settings.Global.putInt(context.contentResolver, "captive_portal_detection_enabled", 0)
    Settings.Global.putInt(context.contentResolver, "captive_portal_mode", 0)
    true
} catch (e: Exception) {
    false
}

/** 后悔药：恢复系统默认的门户检测（1=开启检测，1=弹窗确认）。 */
private fun restorePortalDetection(context: Context): Boolean = try {
    Settings.Global.putInt(context.contentResolver, "captive_portal_detection_enabled", 1)
    Settings.Global.putInt(context.contentResolver, "captive_portal_mode", 1)
    true
} catch (e: Exception) {
    false
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var themeColor by remember { mutableStateOf(Color(SettingsStore.load(context).themeColor)) }
    var batteryOverride by remember { mutableStateOf(SettingsStore.load(context).batteryCheckOverride) }
    var onboardingDone by remember { mutableStateOf(SettingsStore.isOnboardingDone(context)) }
    var showHistory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAway by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var permTick by remember { mutableIntStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permTick++
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permTick++
    }

    val locGranted = remember(permTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
    val notifGranted = remember(permTick) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
    val locationOn = remember(permTick) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            true
        }
    }
    val batteryIgnored = remember(permTick) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            true
        }
    }

    fun requestLocation() {
        permLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    fun requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 老用户直接进主界面时，若缺权限补弹一次
    LaunchedEffect(Unit) {
        if (onboardingDone && (!locGranted || !notifGranted)) {
            val wanted = buildList {
                if (!locGranted) {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                if (!notifGranted && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (wanted.isNotEmpty()) permLauncher.launch(wanted.toTypedArray())
        }
    }

    BackHandler(enabled = onboardingDone && (showHistory || showSettings)) {
        showHistory = false
        showSettings = false
    }

    MaterialTheme(colorScheme = themeScheme(themeColor)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            when {
                !onboardingDone -> OnboardingPager(
                    locGranted = locGranted,
                    notifGranted = notifGranted,
                    locationOn = locationOn,
                    onRequestLocation = { requestLocation() },
                    onRequestNotif = { requestNotif() },
                    onFinish = {
                        SettingsStore.setOnboardingDone(context)
                        onboardingDone = true
                    }
                )

                showHistory -> HistoryPage(onBack = { showHistory = false })

                showSettings -> SettingsPage(
                    onBack = { showSettings = false },
                    onShowPrivacy = { showPrivacy = true },
                    themeColor = themeColor,
                    onThemeChange = {
                        themeColor = it
                        SettingsStore.setThemeColor(context, it.toArgb())
                    },
                    locGranted = locGranted,
                    locationOn = locationOn,
                    notifGranted = notifGranted,
                    batteryIgnored = batteryIgnored,
                    batteryOverride = batteryOverride,
                    onBatteryOverrideChange = {
                        batteryOverride = it
                        SettingsStore.setBatteryCheckOverride(context, it)
                    },
                    onRequestLocation = { requestLocation() },
                    onRequestNotif = { requestNotif() }
                )

                else -> MainScreen(
                    onOpenHistory = { showHistory = true },
                    onOpenSettings = { showSettings = true }
                )
            }
        }

        if (showPrivacy) {
            PrivacyDialog(onDismiss = { showPrivacy = false })
        }
    }
}

// ============================ 主页 ============================

@Composable
fun MainScreen(onOpenHistory: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(SettingsStore.load(context)) }
    var userId by remember { mutableStateOf(settings.userId) }
    var passwd by remember { mutableStateOf(settings.passwd) }
    var portalHost by remember { mutableStateOf(settings.portalHost) }
    var showPass by remember { mutableStateOf(false) }
    // 账号卡默认折叠；未配置时自动展开引导填写
    var credExpanded by remember { mutableStateOf(!settings.configured) }
    var monitoring by remember { mutableStateOf(settings.monitoring) }

    var showAway by remember { mutableStateOf(false) }
    val status by AppStatus.flow.collectAsState()

    fun refreshSettings() {
        settings = SettingsStore.load(context)
        monitoring = settings.monitoring
    }

    fun toggleMonitoring(on: Boolean) {
        if (on && !SettingsStore.load(context).configured) {
            toast(context, "请先展开账号卡填写并保存账号密码")
            return
        }
        SettingsStore.setMonitoring(context, on)
        if (on) {
            PortalMonitorService.start(context)
            toast(context, "后台监控已开启")
        } else {
            PortalMonitorService.stop(context)
            AppStatus.update(context, AppStatus.IDLE, "监控已停止")
            toast(context, "后台监控已关闭")
        }
        refreshSettings()
    }

    fun saveAll() {
        if (userId.isBlank() || passwd.isBlank()) {
            toast(context, "账号和密码不能为空")
            return
        }
        SettingsStore.saveCredentials(context, userId, passwd)
        SettingsStore.saveOptions(context, portalHost, settings.fallbackWhenSsidUnknown)
        refreshSettings()
        toast(context, "已保存")
    }

    fun manualCheck() {
        if (!SettingsStore.load(context).configured) {
            toast(context, "请先填写并保存账号密码")
            return
        }
        PortalMonitorService.checkNow(context)
        toast(context, "已开始检测，请查看下方状态")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题 + 监控开关
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "校园网自动登录",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text("后台监控", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Switch(checked = monitoring, onCheckedChange = { toggleMonitoring(it) })
        }
        Text(
            "连接到你配置的校园网 WiFi 时，自动检测是否被跳转到认证页并代你登录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ① 登录账号卡（默认折叠，含认证页地址）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { credExpanded = !credExpanded }
                ) {
                    Text("登录账号", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        if (settings.configured) "已配置" else "未配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (settings.configured) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (credExpanded) "▾" else "▸", fontWeight = FontWeight.Bold)
                }
                if (credExpanded) {
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = userId,
                            onValueChange = { userId = it },
                            label = { Text("账号（学号/工号）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = passwd,
                            onValueChange = { passwd = it },
                            label = { Text("密码") },
                            singleLine = true,
                            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showPass = !showPass }) {
                                    Text(if (showPass) "隐藏" else "显示")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = portalHost,
                            onValueChange = { portalHost = it },
                            label = { Text("认证页地址") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(onClick = { saveAll() }, modifier = Modifier.fillMaxWidth()) {
                            Text("保存设置")
                        }
                    }
                }
            }
        }

        // ② 当前校园网状态
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val (label, color) = stateInfo(status.state)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(
                        Modifier
                            .size(10.dp)
                            .background(color, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, fontWeight = FontWeight.Bold)
                    if (status.busy) {
                        Spacer(Modifier.width(8.dp))
                        Text("…", color = color)
                    }
                }
                if (status.detail.isNotBlank()) {
                    Text(status.detail, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "当前 WiFi：${status.ssid ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "更新时间：" + if (status.time == 0L) "—"
                    else SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(status.time)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onOpenHistory,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("查看登录历史 →")
                }
                // 非校园网认证页：提供手动打开按钮（B 方案，不发送任何账号信息）
                if (status.state == AppStatus.FAILED &&
                    status.detail.contains("不是校园网") &&
                    !status.portalUrl.isNullOrBlank()
                ) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(status.portalUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("打开认证页（浏览器）") }
                    Text(
                        "按钮只是用浏览器打开这个页面，不会发送校园网账号密码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 关键词规则没覆盖当前 WiFi：允许用户手动指认"这就是校园网"，
                // 写入 WiFi 记忆库后立即重新检测登录
                val noMatchSsid = status.ssid
                if (status.state == AppStatus.WIFI_NO_MATCH && !noMatchSsid.isNullOrBlank()) {
                    OutlinedButton(
                        onClick = {
                            SsidMemoryStore.remember(context, noMatchSsid, true)
                            toast(context, "已记住「$noMatchSsid」为校园网，正在重新检测")
                            PortalMonitorService.checkNow(context)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("这是校园网，记住它") }
                    Text(
                        "点「记住」后，这个 WiFi 会被写进校园网判断记忆（与自动学习共用），之后即使关键词规则没覆盖也能自动登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ③ 立即检测
        Button(onClick = { manualCheck() }, modifier = Modifier.fillMaxWidth()) {
            Text("立即检测并登录")
        }

        // ④ 设置入口
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("设置")
        }

        // ⑤ 离校模式快捷入口（放假/离校时一键停掉后台）
        OutlinedButton(
            onClick = { showAway = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("离校模式（不在学校时）") }

        if (showAway) {
            AlertDialog(
                onDismissRequest = { showAway = false },
                title = { Text("进入离校模式？") },
                text = {
                    Text(
                        "将立即停止后台监控服务、禁用开机自启并关闭本应用；\n下次打开本应用时自动恢复监控。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showAway = false
                        (context as? Activity)?.let { enterLeftCampusMode(it) }
                    }) { Text("确认") }
                },
                dismissButton = {
                    TextButton(onClick = { showAway = false }) { Text("取消") }
                }
            )
        }
    }
}

// ============================ 设置页 ============================

@Composable
private fun CategoryLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SettingsPage(
    onBack: () -> Unit,
    onShowPrivacy: () -> Unit,
    themeColor: Color,
    onThemeChange: (Color) -> Unit,
    locGranted: Boolean,
    locationOn: Boolean,
    notifGranted: Boolean,
    batteryIgnored: Boolean,
    batteryOverride: Boolean,
    onBatteryOverrideChange: (Boolean) -> Unit,
    onRequestLocation: () -> Unit,
    onRequestNotif: () -> Unit
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(SettingsStore.load(context)) }
    var fallback by remember { mutableStateOf(settings.fallbackWhenSsidUnknown) }
    var verboseLog by remember { mutableStateOf(settings.verboseLog) }
    var intervalText by remember(settings.checkIntervalSec) { mutableStateOf(settings.checkIntervalSec.toString()) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    var showAwayConfirm by remember { mutableStateOf(false) }

    fun refreshSettings() {
        settings = SettingsStore.load(context)
    }

    fun applyInterval() {
        val v = intervalText.trim().toIntOrNull()
        if (v == null || v < 15 || v > 3600) {
            toast(context, "请输入 15–3600 之间的秒数")
            return
        }
        if (v == SettingsStore.load(context).checkIntervalSec) return
        SettingsStore.setCheckInterval(context, v)
        refreshSettings()
        intervalText = v.toString()
        if (!SettingsStore.isIntervalExplained(context)) {
            SettingsStore.setIntervalExplained(context)
            showIntervalDialog = true
        } else {
            toast(context, "已保存：每 $v 秒自动检测一次（下一轮生效）")
        }
    }

    fun shareLog() {
        try {
            val file = AppLog.export(context)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".logprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "分享运行日志"))
        } catch (e: Exception) {
            toast(context, "导出失败：${e.message}")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("← 返回") }
            Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }

        // 有任何运行条件没满足时，"运行环境"提到最上面提醒用户；全满足则排最后
        val batteryEffectiveOk = batteryIgnored || batteryOverride
        val runEnvOk = locGranted && locationOn && notifGranted && batteryEffectiveOk
        val runEnvCard: @Composable () -> Unit = {
            RunEnvCard(
                locGranted = locGranted,
                locationOn = locationOn,
                notifGranted = notifGranted,
                batteryIgnored = batteryIgnored,
                batteryOverride = batteryOverride,
                onBatteryOverrideChange = onBatteryOverrideChange,
                onRequestLocation = onRequestLocation,
                onRequestNotif = onRequestNotif
            )
        }

        if (!runEnvOk) {
            CategoryLabel("运行环境")
            runEnvCard()
        }

        // ---- 检测 ----
        CategoryLabel("检测")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { s -> intervalText = s.filter { ch -> ch.isDigit() }.take(4) },
                        label = { Text("检测间隔（秒）") },
                        singleLine = true,
                        modifier = Modifier.width(150.dp)
                    )
                    TextButton(onClick = { applyInterval() }) { Text("保存间隔") }
                }
                Text(
                    "每轮自动检测联网状态的间隔（15–3600 秒）；首次连上校园网 WiFi 时始终立即检测一次，不受此间隔影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "无法读取 WiFi 名称时，遇认证页仍自动登录",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(checked = fallback, onCheckedChange = {
                        fallback = it
                        SettingsStore.saveOptions(context, settings.portalHost, it)
                        refreshSettings()
                    })
                }

                Text("SSID 关键词规则", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                if (settings.ssidRules.isEmpty()) {
                    Text(
                        "尚未添加规则：未配置规则时不会自动登录。添加你的校园网 WiFi 名称关键词（支持部分匹配，忽略大小写）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    settings.ssidRules.forEach { rule ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("· $rule", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                SettingsStore.setSsidRules(context, settings.ssidRules - rule)
                                refreshSettings()
                            }) { Text("删除") }
                        }
                    }
                }
                var newRule by remember { mutableStateOf("") }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newRule,
                        onValueChange = { s -> newRule = s.trim().take(32) },
                        label = { Text("添加 WiFi 名称关键词") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        if (newRule.isBlank()) {
                            toast(context, "关键词不能为空")
                            return@TextButton
                        }
                        SettingsStore.setSsidRules(context, settings.ssidRules + newRule)
                        newRule = ""
                        refreshSettings()
                        toast(context, "已添加：$newRule")
                    }) { Text("添加") }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "定位校验（默认关闭，登录前核对是否在校）",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(checked = settings.locationGuard, onCheckedChange = {
                        SettingsStore.setLocationGuard(context, it)
                        refreshSettings()
                    })
                }
                if (settings.locationGuard) {
                    when {
                        locating -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp))
                                Text("正在获取位置…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        settings.campusLocationSet -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "圆心：%.5f, %.5f".format(settings.campusLat, settings.campusLng),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(onClick = {
                                    SettingsStore.clearCampusLocation(context)
                                    refreshSettings()
                                    toast(context, "已清除校园位置")
                                }) { Text("清除") }
                            }
                        }
                        else -> {
                            OutlinedButton(
                                onClick = {
                                    locating = true
                                    scope.launch {
                                        val loc = CampusLocator.getCurrentLocation(context, 8000)
                                        locating = false
                                        if (loc != null) {
                                            SettingsStore.setCampusLocation(context, loc.first, loc.second)
                                            refreshSettings()
                                            toast(context, "已记住当前位置作为学校圆心")
                                        } else {
                                            toast(context, "获取位置失败，请检查系统定位后重试")
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("我在学校，点我记住这个位置") }
                        }
                    }
                    Text(
                        "开启后，自动登录前会取一次你的位置：距学校 500 米内才自动填表；定位失败时按原逻辑放行。位置仅本地比对、不上传；会增加少量耗电。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "以电脑版页面登录（双设备技巧）",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(checked = settings.desktopUA, onCheckedChange = {
                        SettingsStore.setDesktopUA(context, it)
                        refreshSettings()
                    })
                }
                Text(
                    "校园网按登录页面版本区分设备：手机版/电脑版各占一个名额。开启后本应用会模拟电脑浏览器完成登录，两台安卓设备可分别占用两种名额同时在线。是否有效取决于学校认证系统，建议实测。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "WiFi 名包含任一关键词即自动处理；不同 WiFi 的认证相互独立，切换后会各自重新认证。其他 WiFi 第一次遇到时会看一眼认证页内容自动记忆判断，之后重连直接查记忆。关键词没覆盖的 WiFi，也可以在主页状态卡点「这是校园网，记住它」手动指认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var memCount by remember { mutableStateOf(SsidMemoryStore.count(context)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已记住 ${memCount} 个 WiFi 的校园网判断",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = {
                        SsidMemoryStore.clear(context)
                        memCount = 0
                        toast(context, "已清除 WiFi 记忆")
                    }) { Text("清除记忆") }
                }
            }
        }

        // ---- 系统认证跳转 ----
        CategoryLabel("系统认证跳转")
        PortalJumpCard()

        // ---- 外观 ----
        CategoryLabel("外观")
        ThemeCard(themeColor = themeColor, onThemeChange = onThemeChange)

        // ---- 日志 ----
        CategoryLabel("日志")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "详细日志（排障时开启）",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(checked = verboseLog, onCheckedChange = {
                        verboseLog = it
                        AppLog.setVerbose(context, it)
                    })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { shareLog() },
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("导出日志") }
                }
                Text(
                    "日志保存在手机本地，不含账号密码；默认只记关键节点，开启详细后记录全过程。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 隐私 ----
        CategoryLabel("隐私")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                TextButton(onClick = onShowPrivacy, contentPadding = PaddingValues(0.dp)) {
                    Text("查看隐私声明")
                }
            }
        }

        // ---- 联系作者 ----
        CategoryLabel("联系作者")
        ContactCard()

        // ---- 运行环境 ----
        if (runEnvOk) {
            CategoryLabel("运行环境")
            runEnvCard()
        }


        // ---- 离校模式 ----
        CategoryLabel("离校模式（不在学校时）")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "放假离校时开启：立即停止后台监控服务、禁用开机自启，并关闭本应用；下次手动打开本应用时会自动退出离校模式并恢复监控。",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(
                    onClick = { showAwayConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("进入离校模式（关闭后台与自启）") }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (showAwayConfirm) {
        AlertDialog(
            onDismissRequest = { showAwayConfirm = false },
            title = { Text("进入离校模式？") },
            text = {
                Text(
                    "将立即：\n· 停止后台监控服务\n· 禁用开机自启\n· 关闭本应用\n\n下次打开本应用时会自动退出离校模式并恢复监控。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAwayConfirm = false
                    activity?.let { enterLeftCampusMode(it) }
                }) { Text("确认进入") }
            },
            dismissButton = {
                TextButton(onClick = { showAwayConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showIntervalDialog) {
        AlertDialog(
            onDismissRequest = { showIntervalDialog = false },
            title = { Text("关于检测间隔") },
            text = {
                Text(
                    "· 调低间隔（如 30–60 秒）：掉线后更快自动恢复，但更耗电、流量略增。\n\n" +
                        "· 调高间隔（如 10–30 分钟）：更省电，但掉线后最长要等一个周期才会被检测到并重新登录。\n\n" +
                        "首次连上校园网 WiFi 时始终会立即检测一次，不受此间隔影响。\n\n" +
                        "本说明仅在首次修改间隔时展示。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showIntervalDialog = false }) { Text("知道了") }
            }
        )
    }
}

// ============================ 首次引导 ============================

@Composable
private fun OnboardingPager(
    locGranted: Boolean,
    notifGranted: Boolean,
    locationOn: Boolean,
    onRequestLocation: () -> Unit,
    onRequestNotif: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            when (page) {
                0 -> OnboardingWelcome()
                1 -> OnboardingPermissions(
                    locGranted, notifGranted, locationOn, onRequestLocation, onRequestNotif
                )
                else -> OnboardingPrivacy()
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(3) { i ->
                Box(
                    Modifier
                        .padding(horizontal = 5.dp)
                        .size(if (pagerState.currentPage == i) 11.dp else 8.dp)
                        .background(
                            if (pagerState.currentPage == i) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                )
            }
        }

        Button(
            onClick = {
                if (pagerState.currentPage < 2) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                } else {
                    onFinish()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
        ) {
            Text(if (pagerState.currentPage == 2) "开始使用" else "下一步")
        }
        TextButton(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
            Text("跳过引导")
        }
    }
}

@Composable
private fun OnboardingWelcome() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("校园网自动登录", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Text(
            "连接校园网 WiFi 时，自动检测是否被跳转到认证页，并代你完成登录。",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(16.dp))
        Text("· 先在设置里添加你的校园网 WiFi 名称关键词", style = MaterialTheme.typography.bodyMedium)
        Text("· 连上对应 WiFi 后自动完成认证", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "登录成功保持静默，失败才发通知提醒你。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OnboardingPermissions(
    locGranted: Boolean,
    notifGranted: Boolean,
    locationOn: Boolean,
    onRequestLocation: () -> Unit,
    onRequestNotif: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text("需要两个权限", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "下面逐条说明用途，你可以现在授权，也可以以后在 设置→运行环境 里补。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("位置权限", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (locGranted) Text("✓ 已授权", color = Color(0xFF2E7D32))
                    else OutlinedButton(onClick = onRequestLocation) { Text("去授权") }
                }
                Text(
                    "安卓系统规定：读取 WiFi 名称必须授予位置权限。本应用只用它识别 WiFi 名字（与你配置的关键词比对），不会记录你的位置轨迹。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("系统定位开关", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (locationOn) Text("✓ 已开启", color = Color(0xFF2E7D32))
                    else OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    }) { Text("去打开") }
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("通知权限", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (notifGranted) Text("✓ 已授权", color = Color(0xFF2E7D32))
                        else OutlinedButton(onClick = onRequestNotif) { Text("去授权") }
                    }
                    Text(
                        "用于登录失败时提醒你（成功保持静默）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingPrivacy() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text("隐私说明", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        PrivacyContent()
    }
}

@Composable
private fun PrivacyContent() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrivacyItem("账号密码只存在你手机里", "使用安卓系统级加密（EncryptedSharedPreferences）保存，不上传到任何服务器。")
        PrivacyItem(
            "联网只做三件事",
            "1. 向公共探测点发极小请求，判断是否联网；\n" +
                "2. 在校园网认证页（1.1.1.1）完成登录；\n" +
                "3. 仅当你点「打开认证页」时，用浏览器打开当前 WiFi 的认证页（不发送校园网账号）。"
        )
        PrivacyItem("零统计零埋点", "不收集、不上报任何使用数据和个人信息。")
        PrivacyItem("日志只留本机", "运行日志保存在手机本地，仅当你主动点\"导出日志\"分享时才会离开手机，且不含密码。")
        PrivacyItem(
            "两个需要说明的能力",
            "1. 「一键禁用系统跳转」需要用电脑授予一次 WRITE_SECURE_SETTINGS 系统权限，仅在点击该按钮时使用，可随时在系统设置收回；\n" +
                "2. 「联系作者」在你点击时调起系统邮件应用发送反馈，或把邮箱地址复制到剪贴板。"
        )
    }
}

@Composable
private fun PrivacyItem(title: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PrivacyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("隐私说明") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PrivacyContent()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("我知道了") } }
    )
}

// ============================ 登录历史 ============================

@Composable
private fun HistoryPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(HistoryStore.load(context)) }
    val df = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("← 返回") }
            Text("登录历史", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                HistoryStore.clear(context)
                entries = HistoryStore.load(context)
                toast(context, "已清空")
            }) { Text("清空") }
        }

        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("还没有记录", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    "连接校园网 WiFi 后，检测和登录结果会自动记录在这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries) { e ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp)) {
                            Spacer(
                                Modifier
                                    .size(10.dp)
                                    .background(if (e.ok) Color(0xFF2E7D32) else Color(0xFFC62828), CircleShape)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(e.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                if (e.detail.isNotBlank()) {
                                    Text(e.detail, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    buildString {
                                        append(df.format(Date(e.time)))
                                        e.ssid?.let { append(" · ").append(it) }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================ 主题颜色 ============================

@Composable
private fun ThemeCard(themeColor: Color, onThemeChange: (Color) -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("主题颜色", fontWeight = FontWeight.Bold)

            // 预设色板
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                THEME_PRESETS.forEach { p ->
                    val selected = p == themeColor
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(p)
                            .border(
                                width = 3.dp,
                                color = if (selected) MaterialTheme.colorScheme.outline else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable { onThemeChange(p) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Text(
                                "✓",
                                color = if (p.luminance() > 0.55f) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(if (expanded) "收起自定义" else "自定义颜色（RGB / HEX）")
            }

            if (expanded) {
                var custom by remember { mutableStateOf(themeColor) }
                var hexText by remember { mutableStateOf(toHex6(themeColor)) }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(custom)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { s ->
                            hexText = s
                            parseHex(s)?.let { custom = it }
                        },
                        label = { Text("HEX（如 1565C0）") },
                        singleLine = true,
                        isError = parseHex(hexText) == null,
                        modifier = Modifier.weight(1f)
                    )
                }

                RgbSlider("红 R", custom.red) { r ->
                    custom = Color(r, custom.green, custom.blue)
                    hexText = toHex6(custom)
                }
                RgbSlider("绿 G", custom.green) { g ->
                    custom = Color(custom.red, g, custom.blue)
                    hexText = toHex6(custom)
                }
                RgbSlider("蓝 B", custom.blue) { b ->
                    custom = Color(custom.red, custom.green, b)
                    hexText = toHex6(custom)
                }

                Button(
                    onClick = {
                        parseHex(hexText)?.let {
                            custom = it
                            onThemeChange(it)
                            toast(context, "主题颜色已应用")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("使用此颜色") }
            }
        }
    }
}

@Composable
private fun RgbSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(40.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f)
        )
        Text("${(value * 255).roundToInt()}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(34.dp))
    }
}

// ============================ 通用 ============================

// ============================ 联系作者 / 系统跳转卡片 ============================

@Composable
private fun ContactCard() {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("遇到问题或想提新功能？欢迎邮件联系：", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { sendFeedbackEmail(context) }) { Text("邮件联系作者") }
                OutlinedButton(onClick = { copyText(context, "邮箱地址", CONTACT_EMAIL) }) { Text("复制邮箱") }
            }
            Text(
                CONTACT_EMAIL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                    } catch (e: Exception) {
                        copyText(context, "仓库地址", REPO_URL)
                    }
                },
                contentPadding = PaddingValues(0.dp)
            ) { Text("项目仓库：github.com/moqiuli-0/campus-autologin") }
        }
    }
}

@Composable
private fun PortalJumpCard() {
    val context = LocalContext.current
    var detectionOn by remember { mutableStateOf(portalDetectionEnabled(context)) }
    var showGrantDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "连接需要认证的 WiFi 时，系统会自动弹认证页。本应用会自己完成登录，弹窗纯属打扰，可按需禁用：",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("系统自动跳转：", style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (detectionOn) {
                        true -> "开启中（会弹认证页）"
                        false -> "已禁用 ✓"
                        null -> "未知"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = when (detectionOn) {
                        true -> Color(0xFFC62828)
                        false -> Color(0xFF2E7D32)
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (detectionOn != false) {
                Button(onClick = {
                    if (canWriteSecureSettings(context)) {
                        if (disablePortalDetection(context)) {
                            detectionOn = false
                            toast(context, "已禁用系统自动跳转")
                        } else {
                            toast(context, "设置失败，请稍后再试")
                        }
                    } else {
                        showGrantDialog = true
                    }
                }) { Text("一键禁用") }
                Text(
                    "说明：「一键禁用」需要用电脑授权一次（这种授权方式的学名叫 adb）。怕麻烦可以无视它——不开启也没关系，系统弹出认证页时手动点掉就行，本应用照样会在后台完成登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Button(onClick = {
                    if (canWriteSecureSettings(context)) {
                        if (restorePortalDetection(context)) {
                            detectionOn = true
                            toast(context, "已恢复系统自动跳转")
                        } else {
                            toast(context, "恢复失败，可重启手机恢复默认")
                        }
                    } else {
                        showGrantDialog = true
                    }
                }) { Text("后悔药：恢复系统自动跳转") }
                Text(
                    "反悔了随时点上面恢复；恢复后系统会重新自动弹认证页。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "禁用后：系统不再自动弹认证页，也不会因「无法上网」自动切流量；本应用的检测与登录不受任何影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showGrantDialog) {
        val cmd = "adb shell pm grant com.campusnet.autologin android.permission.WRITE_SECURE_SETTINGS"
        AlertDialog(
            onDismissRequest = { showGrantDialog = false },
            title = { Text("需要一次性授权") },
            text = {
                Text(
                    "系统不允许普通应用直接改这个开关，需用电脑授权一次（约 1 分钟）：\n\n" +
                        "1. 手机开启 USB 调试并连接电脑\n" +
                        "2. 执行下方命令（先点「复制命令」）\n" +
                        "3. 回到本页再点「一键禁用」\n\n" +
                        "该授权仅用于关闭系统认证跳转。不授权也没关系：系统弹出认证页时手动点掉即可，本应用照样会在后台完成登录。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    copyText(context, "授权命令", cmd)
                }) { Text("复制命令") }
            },
            dismissButton = {
                TextButton(onClick = { showGrantDialog = false }) { Text("知道了") }
            }
        )
    }
}

/** 尝试跳转到各品牌自启动/关联启动管理页；失败则退回应用详情页。返回是否打开了品牌专页。 */
private fun openAutoStartSettings(context: Context): Boolean {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val candidates = mutableListOf<String>()
    when {
        manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
            candidates += "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
            candidates += "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManager"
        }
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
            candidates += "com.miui.permcenter/com.miui.permcenter.autostart.AutoStartManagementActivity"
        manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
            candidates += "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity"
            candidates += "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity"
        }
        manufacturer.contains("huawei") || manufacturer.contains("honor") ->
            candidates += "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
    }
    for (spec in candidates) {
        try {
            val parts = spec.split("/", limit = 2)
            context.startActivity(Intent().setClassName(parts[0], parts[1]))
            return true
        } catch (_: Exception) {
        }
    }
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    }
    return false
}

/**
 * 按品牌跳转厂商的省电/后台管理页（白名单之外的独立开关）。
 * 每个 Intent 必须先用 resolveActivity 校验，不可直接 startActivity；
 * 品牌定制页全部失效时，兜底跳转应用详情页。返回是否打开了厂商专页。
 */
private fun openBrandBatterySettings(context: Context): Boolean {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val candidates = mutableListOf<Pair<String, String>>()
    when {
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
            candidates += "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"
            candidates += "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
        }

        manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
            // 鸿蒙 NEXT（5.0+）无法运行安卓组件，resolveActivity 会失败并走兜底
            candidates += "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        }

        manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") -> {
            candidates += "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity"
        }

        manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
            // Android 14+ 用 com.vivo.secure，老机型用 com.iqoo.secure
            if (Build.VERSION.SDK_INT >= 34) {
                candidates += "com.vivo.secure" to "com.vivo.secure.ui.phoneoptimize.AddWhiteListActivity"
            }
            candidates += "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        }

        manufacturer.contains("samsung") -> {
            candidates += "com.samsung.android.sm_cn" to "com.samsung.android.sm.ui.battery.BatteryActivity"
            candidates += "com.samsung.android.sm" to "com.samsung.android.sm.ui.battery.BatteryActivity"
        }
    }
    for ((pkg, cls) in candidates) {
        try {
            val intent = Intent().setClassName(pkg, cls)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return true
            }
        } catch (_: Exception) {
        }
    }
    // 兜底：应用详情页（用户可在里面找到电池/启动相关设置）
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    }
    return false
}

@Composable
private fun RunEnvCard(
    locGranted: Boolean,
    locationOn: Boolean,
    notifGranted: Boolean,
    batteryIgnored: Boolean,
    batteryOverride: Boolean,
    onBatteryOverrideChange: (Boolean) -> Unit,
    onRequestLocation: () -> Unit,
    onRequestNotif: () -> Unit
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CheckRow("位置权限（读取 WiFi 名称）", locGranted, onRequestLocation)
            CheckRow("系统定位开关", locationOn) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                CheckRow("通知权限", notifGranted, onRequestNotif)
            }
            // 忽略电池优化：行点击仅计数（连点 3 次解锁隐藏入口，行与"去处理"按钮都计数）；
            // 正式授权走"去处理"，行点击不再反复拉起系统授权弹窗
            var batteryTapCount by rememberSaveable { mutableIntStateOf(0) }
            val batteryEffectiveOk = batteryIgnored || batteryOverride

            fun countBatteryTap() {
                batteryTapCount++
                when (batteryTapCount) {
                    1 -> toast(context, "再连点 2 次可解锁隐藏选项")
                    2 -> toast(context, "再点 1 次即可解锁隐藏选项")
                    3 -> toast(context, "已解锁隐藏选项 ↓")
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { countBatteryTap() }
            ) {
                Text(
                    if (batteryEffectiveOk) "✓" else "✗",
                    color = if (batteryEffectiveOk) Color(0xFF2E7D32) else Color(0xFFC62828),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(24.dp)
                )
                Text(
                    if (batteryOverride) "忽略电池优化（已手动标记）" else "忽略电池优化（后台保活）",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!batteryIgnored) {
                    OutlinedButton(onClick = {
                        countBatteryTap()
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }) { Text("去处理") }
                }
            }
            // 连点 3 次解锁的隐藏入口
            if (batteryTapCount >= 3) {
                OutlinedButton(
                    onClick = {
                        if (!openBrandBatterySettings(context)) {
                            toast(context, "未找到厂商省电设置，已打开应用详情页")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("白名单已开却仍被后台清理？点这里进厂商省电管理") }
                Text(
                    "部分厂商在系统白名单之外，还有独立的省电/后台管理开关（如 vivo 的「后台高耗电」、小米的「无限制」），需要在那里也放行本应用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 手动屏蔽：部分系统/厂商的白名单状态无法被应用检测到，允许用户手动标记为已开启
                if (!batteryOverride) {
                    OutlinedButton(
                        onClick = {
                            onBatteryOverrideChange(true)
                            toast(context, "已手动屏蔽这条检查（标记为已开启）")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("检测不准？手动屏蔽这条检查（标记为已开启）") }
                } else {
                    TextButton(
                        onClick = {
                            onBatteryOverrideChange(false)
                            toast(context, "已撤销手动标记")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("撤销手动标记") }
                }
                Text(
                    "说明：部分系统/厂商的白名单状态无法被应用检测到（例如只在厂商设置里放行）。确认已在系统或厂商设置中放行后，" +
                        "可手动屏蔽这条检查；之后应用若真实检测到状态变化，仍会正常显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = {
                val opened = openAutoStartSettings(context)
                if (!opened) toast(context, "未找到自启动管理页，请在系统设置里搜索「自启动」")
            }, modifier = Modifier.fillMaxWidth()) {
                Text("去开启自启动 / 关联启动")
            }
            Text(
                "建议：把「自启动」和「关联启动」都打开（vivo 在 i管家 的 启动管理 里，两个开关并排），再配合上面的忽略电池优化，后台监控才最稳定。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, action: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (ok) "✓" else "✗",
            color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp)
        )
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!ok) {
            OutlinedButton(onClick = action) { Text("去处理") }
        }
    }
}

private fun stateInfo(state: String): Pair<String, Color> = when (state) {
    AppStatus.ONLINE -> "已联网" to Color(0xFF2E7D32)
    AppStatus.SUCCESS -> "自动登录成功" to Color(0xFF2E7D32)
    AppStatus.CHECKING, AppStatus.PORTAL, AppStatus.LOGGING_IN -> "处理中" to Color(0xFFEF6C00)
    AppStatus.FAILED -> "自动登录失败" to Color(0xFFC62828)
    AppStatus.NO_CRED -> "未配置账号" to Color(0xFFC62828)
    AppStatus.WIFI_NO_MATCH -> "当前 WiFi 未识别为校园网" to Color(0xFF546E7A)
    AppStatus.UNKNOWN -> "网络状态未知" to Color(0xFFEF6C00)
    else -> "待命" to Color(0xFF546E7A)
}
