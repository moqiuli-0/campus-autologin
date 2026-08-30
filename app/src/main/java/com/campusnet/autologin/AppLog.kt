package com.campusnet.autologin

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用日志：双档位。
 * - info：关键节点，永远记录（检查开始/结果、认证页、登录结论）
 * - verbose：细节过程（探测每个地址的响应、WebView 每轮轮询），仅在"详细日志"开关打开时记录
 * 同时写入内存环形缓冲与文件（filesDir/logs/app.log，超 256KB 自动清空重写），
 * 支持一键导出为可分享的文本文件。日志不含账号密码。
 */
object AppLog {
    private const val TAG = "CampusNet"
    private const val MAX_MEM_LINES = 500
    private const val MAX_FILE_BYTES = 256 * 1024L

    @Volatile
    var verbose: Boolean = false
        private set

    private val mem = ArrayDeque<String>()
    private var logFile: File? = null

    fun init(context: Context, verboseEnabled: Boolean) {
        verbose = verboseEnabled
        if (logFile == null) {
            synchronized(this) {
                if (logFile == null) {
                    val dir = File(context.applicationContext.filesDir, "logs")
                    dir.mkdirs()
                    logFile = File(dir, "app.log")
                }
            }
        }
    }

    fun setVerbose(context: Context, enabled: Boolean) {
        verbose = enabled
        SettingsStore.setVerboseLog(context, enabled)
        info(if (enabled) "详细日志已开启" else "详细日志已关闭（仅记录关键节点）")
    }

    fun info(msg: String) = write("I", msg)

    fun verbose(msg: String) {
        if (verbose) write("V", msg)
    }

    private fun write(level: String, msg: String) {
        Log.i(TAG, msg)
        // SimpleDateFormat 非线程安全，且本方法会被多线程并发调用，故每次新建
        val line = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
            .format(Date()) + " $level $msg"
        synchronized(mem) {
            mem.addLast(line)
            while (mem.size > MAX_MEM_LINES) mem.removeFirst()
        }
        appendFile(line)
    }

    private fun appendFile(line: String) {
        val f = logFile ?: return
        try {
            synchronized(this) {
                if (f.length() > MAX_FILE_BYTES) f.writeText("")
                f.appendText(line + "\n")
            }
        } catch (_: Exception) {
        }
    }

    /** 生成可分享的日志文件，返回文件路径。优先导出 app.log（含全部历史），仅无文件时用内存缓冲。 */
    fun export(context: Context): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "campusnet-log-${System.currentTimeMillis()}.txt")
        val head = buildString {
            appendLine("==== 校园网自动登录 运行日志 ====")
            appendLine("导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("APP 版本：${try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "?" }}")
            appendLine("Android 版本：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("详细日志模式：${if (verbose) "开" else "关"}")
            appendLine("================================")
            appendLine()
        }
        val fileText = try {
            logFile?.takeIf { it.exists() && it.length() > 0 }?.readText()
        } catch (_: Exception) {
            null
        }
        val body = fileText ?: synchronized(mem) { mem.joinToString("\n") }
        out.writeText(head + body + "\n")
        return out
    }

    fun clear() {
        synchronized(mem) { mem.clear() }
        try { logFile?.writeText("") } catch (_: Exception) {}
    }
}
