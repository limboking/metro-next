package com.metronext.metro.widget

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小部件调试日志。
 *
 * 背景：用户手机没有 adb，launcher 报「载入窗口小部件时出现问题」时看不到任何堆栈。
 * 此工具把所有关键路径的异常（含完整 stack trace）与关键步骤写入文件，
 * 用户可在文件管理器里直接打开（Android/data/com.metronext.metro/files/widget_debug.log）。
 *
 * 写入位置优先级：getExternalFilesDir（无需权限、文件管理器可见）→ 失败则 filesDir。
 */
object WidgetLog {

    private const val MAX_SIZE = 512 * 1024

    @Synchronized
    fun append(context: Context, msg: String) {
        // release 构建不写任何日志（v1.0.21）：诊断日志仅 debug 构建使用。
        // 判定方式与 MainActivity 一致（项目未开 buildConfig 特性，无 BuildConfig 类）
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val f = File(dir, "widget_debug.log")
            if (f.exists() && f.length() > MAX_SIZE) {
                val old = File(dir, "widget_debug.old.log")
                try {
                    if (old.exists()) old.delete()
                    if (f.renameTo(old)) f.delete()
                } catch (_: Exception) {
                }
            }
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date())
            f.appendText("[$ts] $msg\n")
        } catch (_: Exception) {
            // 日志失败不影响主功能
        }
    }

    /** 异常快捷写入 */
    fun append(context: Context, tag: String, e: Throwable) {
        val stack = android.util.Log.getStackTraceString(e)
        append(context, "$tag: ${e.javaClass.name}: ${e.message}\n$stack")
    }

    /** 清空日志（App 内「小部件诊断日志」面板的「清空日志」按钮调用） */
    fun clear(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, "widget_debug.log").delete()
            File(dir, "widget_debug.old.log").delete()
        } catch (_: Exception) {
        }
    }
}
