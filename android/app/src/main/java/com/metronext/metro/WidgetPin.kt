package com.metronext.metro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.metronext.metro.widget.WidgetLarge
import com.metronext.metro.widget.WidgetLog
import com.metronext.metro.widget.WidgetMedium
import com.metronext.metro.widget.WidgetSmall
import com.metronext.metro.widget.WidgetTick
import com.metronext.metro.widget.refreshAllWidgets

/**
 * 「添加到桌面」链路（v1.0.25）。
 *
 * App 内一键添加 = AppWidgetManager.requestPinAppWidget()：
 *   - 标准 API，无需任何权限，也无需送审 —— 自用的 debug 包同样可用；
 *   - 小米/Redmi 上不会弹二次确认框，直接落到桌面（其它品牌会弹系统确认框）；
 *   - 结果通过 [WidgetPinResultReceiver] 回调，但部分机型不回调 →
 *     MainActivity 另外做延时比对 getAppWidgetIds 的兜底判定。
 *
 * 只有结果回调在本进程（主进程），小部件本体仍跑在 :widgetProvider 进程，
 * 因此这里的 listener 不会被跨进程问题影响。
 */
object WidgetPin {

    const val ACTION = "com.metronext.metro.PIN_RESULT"

    /** 请求码：与 WidgetTick 的 1000、翻页的 id*10+n 不冲突即可 */
    const val REQ_CODE = 2001

    /**
     * 结果回调（唯一消费者是 MainActivity，注册后必须在使用完置空）。
     * ok=false 时 reason 为 canceled / unsupported / bad_size / unknown。
     */
    @Volatile
    var listener: ((ok: Boolean, reason: String) -> Unit)? = null

    /** 尺寸 key → 小部件 Provider 类 */
    fun clsOf(size: String): Class<*>? = when (size) {
        "small" -> WidgetSmall::class.java
        "medium" -> WidgetMedium::class.java
        "large" -> WidgetLarge::class.java
        else -> null
    }

    /** 构造回调用的 PendingIntent（显式广播，receiver 无需 intent-filter） */
    fun callbackIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        REQ_CODE,
        Intent(ctx, WidgetPinResultReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** 桌面上是否已有指定尺寸的小部件（网页侧用来打勾提示） */
    fun hasSize(ctx: Context, size: String): Boolean {
        val cls = clsOf(size) ?: return false
        return AppWidgetManager.getInstance(ctx)
            .getAppWidgetIds(ComponentName(ctx, cls))
            .isNotEmpty()
    }

    /**
     * 「桌面快捷方式」权限是否被明确拒绝。
     * 小米把 requestPinAppWidget 映射到 AppOps 的 install_shortcut：
     * 权限为「拒绝」时 AppOps 返回 IGNORED/ERRORED，桌面会**静默忽略**请求。
     * 返回 true = 明确拒绝（应弹引导）；false = 允许/默认/未知（放行，交给延时比对兜底）。
     */
    fun isShortcutDenied(ctx: Context): Boolean {
        val mode = shortcutOpMode(ctx)
        return mode == android.app.AppOpsManager.MODE_IGNORED ||
            mode == android.app.AppOpsManager.MODE_ERRORED
    }

    /**
     * 读取 install_shortcut 的 AppOps 原始模式值（仅诊断用，写进 widget_debug.log）。
     * 0=允许 1=忽略 2=出错 3=默认；查询失败返回 -1。
     */
    fun shortcutOpMode(ctx: Context): Int = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE)
            as android.app.AppOpsManager
        ops.checkOpNoThrow(
            "android:install_shortcut",
            android.os.Process.myUid(),
            ctx.packageName
        )
    } catch (_: Throwable) {
        -1
    }

    /** 是否小米系设备（Redmi/POCO 同源，同样有「桌面快捷方式」权限门控） */
    fun isXiaomiLike(): Boolean {
        val m = "${android.os.Build.MANUFACTURER} ${android.os.Build.BRAND}"
        return m.contains("xiaomi", true) || m.contains("redmi", true) ||
            m.contains("poco", true)
    }

    /**
     * 跳转「桌面快捷方式」权限的具体页面：
     * 优先小米安全中心的单应用权限编辑器（列表第一页即「桌面快捷方式」开关），
     * 跳转失败（非小米/安全中心被裁剪）退回系统应用详情页。
     */
    fun openPermEditor(ctx: Context) {
        try {
            ctx.startActivity(
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity"
                    )
                    .putExtra("extra_pkgname", ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        } catch (_: Throwable) {
        }
        try {
            ctx.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", ctx.packageName, null)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }
    }
}

/** requestPinAppWidget 的结果回调（仅在部分机型触发，其余靠延时比对兜底） */
class WidgetPinResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val id = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val ok = id != AppWidgetManager.INVALID_APPWIDGET_ID
        WidgetLog.append(ctx, "pin 回调: ok=$ok, id=$id, action=${intent?.action}")
        if (ok) {
            // 刚落地的小部件是空布局（尚未渲染过一次），立刻补渲染并排闹钟
            refreshAllWidgets(ctx)
            try {
                WidgetTick.schedule(ctx)
            } catch (_: Throwable) {
            }
        }
        WidgetPin.listener?.invoke(ok, if (ok) "ok" else "canceled")
        WidgetPin.listener = null
    }
}
