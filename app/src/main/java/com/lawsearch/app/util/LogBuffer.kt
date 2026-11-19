package com.lawsearch.app.util

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量日志缓冲。
 *
 * 为什么不用 logcat：普通 App 无法读取其他进程日志，且 `READ_LOGS` 是系统权限，
 * 用户手机上没有。所以这里自己维护一个环形缓冲，记录：
 *  - App 内部关键事件（导入进度、搜索耗时、异常）
 *  - 未捕获异常（全局 handler 兜底）
 *
 * 崩溃时也要能留下证据，所以**同步写文件**（小容量、追加式、超过阈值轮转）。
 */
object LogBuffer {

    private const val MAX_LINES = 2000
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val FILE_NAME = "lawsearch.log"

    private val entries = ArrayDeque<LogEntry>(MAX_LINES)
    private val lock = Any()

    private var logFile: File? = null
    private var installed = false

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    data class LogEntry(
        val time: Long,
        /** D/I/W/E */
        val level: Char,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
    ) {
        val isError: Boolean get() = level == 'E' || throwable != null

        fun format(): String {
            val sb = StringBuilder()
            sb.append(timeFmt.format(Date(time)))
                .append(" [").append(level).append("] ")
                .append(tag).append(": ").append(message)
            if (throwable != null) {
                sb.append('\n').append(throwable.stackTraceToString())
            }
            return sb.toString()
        }
    }

    /** 在 Application.onCreate 里调用一次 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        logFile = File(context.filesDir, FILE_NAME)

        // 全局异常兜底：先落盘，再交给系统默认处理（保留系统崩溃对话框）
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                log('E', "Crash", "线程 ${thread.name} 未捕获异常", throwable)
                flushToFile()
            }
            prev?.uncaughtException(thread, throwable)
        }

        log('I', "App", "启动 v${appVersionName(context)} (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE})")
    }

    private fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    fun d(tag: String, message: String) = log('D', tag, message)
    fun i(tag: String, message: String) = log('I', tag, message)
    fun w(tag: String, message: String, t: Throwable? = null) = log('W', tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = log('E', tag, message, t)

    private fun log(level: Char, tag: String, message: String, throwable: Throwable? = null) {
        synchronized(lock) {
            if (entries.size >= MAX_LINES) entries.removeFirst()
            entries.addLast(LogEntry(System.currentTimeMillis(), level, tag, message, throwable))
        }
    }

    fun snapshot(): List<LogEntry> = synchronized(lock) { entries.toList() }

    fun errors(): List<LogEntry> = snapshot().filter { it.isError }

    fun clear() {
        synchronized(lock) { entries.clear() }
        runCatching { logFile?.delete() }
    }

    /**
     * 生成给用户复制的完整报告。
     *
     * 结构：先给「错误摘要」（开发者先看这段），再给完整日志。
     */
    fun buildReport(context: Context, includeFull: Boolean = true): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.appendLine("=== LawSearch 反馈报告 ===")
        sb.appendLine("导出时间: $now")
        sb.appendLine("版本: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName} (code ${appVersion(context)})")
        sb.appendLine("包名: ${context.packageName}")
        sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}  (${Build.BRAND} / ${Build.DEVICE})")
        sb.appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        // 屏幕信息：排查布局问题（截断/遮挡/错位）必须要有
        sb.appendLine("屏幕: ${screenInfo(context)}")
        sb.appendLine("字体缩放: ${fontScale(context)}")
        sb.appendLine("系统语言: ${Locale.getDefault()}")
        sb.appendLine("深色模式: ${if (isDarkMode(context)) "是" else "否"}")
        sb.appendLine()

        val errs = errors()
        sb.appendLine("--------- 错误摘要（共 ${errs.size} 条）---------")
        if (errs.isEmpty()) {
            sb.appendLine("（没有检测到错误）")
        } else {
            errs.takeLast(200).forEach { sb.appendLine(it.format()) }
        }
        sb.appendLine()

        if (includeFull) {
            sb.appendLine("--------- 完整日志（共 ${snapshot().size} 条）---------")
            snapshot().forEach { sb.appendLine(it.format()) }
        }
        sb.appendLine("=== 报告结束 ===")
        return sb.toString()
    }

    private fun appVersion(context: Context): Int = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    }.getOrDefault(0)

    /**
     * 屏幕信息：分辨率、密度、dp 尺寸。
     *
     * 排查布局问题（文字截断、控件被挡、间距异常）时必须知道这些 ——
     * 同一个 bug 在 1080×2400@440dpi 和 1440×3200@560dpi 上表现完全不同。
     */
    private fun screenInfo(context: Context): String = runCatching {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels
        val h = dm.heightPixels
        val dpi = dm.densityDpi
        val dpW = (w / dm.density).toInt()
        val dpH = (h / dm.density).toInt()
        "${w}×${h}px  ${dpi}dpi  ${dpW}×${dpH}dp"
    }.getOrDefault("未知")

    private fun fontScale(context: Context): String = runCatching {
        val scale = context.resources.configuration.fontScale
        "$scale（" + when {
            scale >= 1.3f -> "很大"
            scale >= 1.15f -> "较大"
            scale <= 0.9f -> "较小"
            else -> "默认"
        } + "）"
    }.getOrDefault("默认")

    private fun isDarkMode(context: Context): Boolean = runCatching {
        (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }.getOrDefault(false)

    /** 追加到文件（崩溃兜底用） */
    fun flushToFile() {
        val f = logFile ?: return
        runCatching {
            if (f.length() > MAX_FILE_BYTES) {
                val backup = File(f.parentFile, "$FILE_NAME.1")
                runCatching { backup.delete() }
                f.renameTo(backup)
            }
            f.appendText(buildReportString())
        }
    }

    private fun buildReportString(): String {
        val sb = StringBuilder()
        snapshot().takeLast(200).forEach { sb.appendLine(it.format()) }
        return sb.toString()
    }
}
