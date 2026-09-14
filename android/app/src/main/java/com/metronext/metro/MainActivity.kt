package com.metronext.metro

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.metronext.metro.widget.TickState
import com.metronext.metro.widget.WidgetData
import com.metronext.metro.widget.WidgetLog
import com.metronext.metro.widget.WidgetTick
import com.metronext.metro.widget.refreshAllWidgets

/**
 * Metro Next —— 网页版同源安卓壳。
 *
 * 渲染完全交由 beijing-metro.html 承担，因此 App 与网页版天然一致（含时钟、线路选择器、
 * 站点详情、全部班次平日/双休分组、收藏、更新提示）。
 *
 * 关键点：用 WebViewAssetLoader 以正规 https origin（appassets.androidplatform.net）提供
 * 本地 assets，而非 file://。因为 file:// 是 opaque origin，会导致：
 *   1) localStorage 不可用 → 收藏无法持久化；
 *   2) 跨域 fetch 被拦截 → 「更新数据」失效。
 * 换成正规 origin 后，两者行为与网页版完全一致。
 *
 * 外部请求（如 GitHub 拉取新版时刻表）不拦截，交由 WebView 正常走网络。
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    /** 跨线程回主线程（添加到桌面结果回传网页 / 兜底判定轮询） */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 崩溃捕获：写 filesDir/crash_log.txt（无 adb 时定位闪退，同早期 debug 版机制）
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e ->
                try {
                    val f = java.io.File(filesDir, "crash_log.txt")
                    val ts = java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA
                    ).format(java.util.Date())
                    f.writeText("$ts\n${android.util.Log.getStackTraceString(e)}")
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(APP_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true          // 收藏依赖 localStorage
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                @Suppress("OVERRIDE_DEPRECATION")
                override fun shouldInterceptRequest(
                    view: WebView,
                    url: String
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(Uri.parse(url))
            }

            webChromeClient = WebChromeClient()
            setBackgroundColor(Color.WHITE)

            // 网页侧收藏变化时通过这个接口把精简快照交给原生，供桌面小部件读取。
            // 注意此处在 WebView.apply{} 内，this 指向 WebView（它不是 Context），故需限定为 Activity
            addJavascriptInterface(WidgetBridge(this@MainActivity), "MetroWidget")
        }

        setContentView(web)

        if (savedInstanceState == null) {
            // v1.0.20：仅 debug 构建给网页带 ?debug=1（显示「小部件诊断日志」入口）；
            // release 版不带任何调试功能。用 FLAG_DEBUGGABLE 判断，免开 buildConfig 特性。
            val isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            web.loadUrl("https://$APP_DOMAIN/assets/public/metro.html" + if (isDebug) "?debug=1" else "")
        }
    }

    override fun onStop() {
        super.onStop()
        // 切到后台/返回桌面：立即刷新小部件，保证「回桌面就看到最新班次」
        try {
            refreshAllWidgets(this)
        } catch (_: Exception) {
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (this::web.isInitialized) web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (this::web.isInitialized) web.restoreState(savedInstanceState)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }

    override fun onPause() {
        if (this::web.isInitialized) web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (this::web.isInitialized) web.onResume()
    }

    // ───────────── 网页「添加到桌面」：requestPinAppWidget 链路 ─────────────

    /** 兜底判定：小米部分机型不回调结果，改为延时比对 appWidgetId 数量 */
    private val pinDelays = longArrayOf(700L, 1500L, 3000L)

    /** size: small / medium / large */
    private fun requestPin(size: String) {
        val cls = WidgetPin.clsOf(size)
        if (cls == null) {
            jsPinResult(false, "bad_size")
            return
        }
        val mgr = AppWidgetManager.getInstance(this)
        val supported = try {
            mgr.isRequestPinAppWidgetSupported
        } catch (t: Throwable) {
            WidgetLog.append(this, "isRequestPinAppWidgetSupported 异常", t)
            false
        }
        if (!supported) {
            jsPinResult(false, "unsupported")
            return
        }
        // 小米「桌面快捷方式」权限被明确拒绝时，requestPinAppWidget 会静默失败
        // （无弹窗、无回调、不落桌面）——先弹引导，确认后直达权限页
        if (WidgetPin.isShortcutDenied(this)) {
            WidgetLog.append(this, "桌面快捷方式权限被拒绝(AppOps mode=${WidgetPin.shortcutOpMode(this)}) → 弹引导")
            jsPinResult(false, "no_perm")
            showPermDialog(
                "未开启「桌面快捷方式」权限，添加会静默失败。\n\n去开启后请回到本页重新点添加。"
            )
            return
        }
        val cn = ComponentName(this, cls)
        val before = mgr.getAppWidgetIds(cn).size
        WidgetPin.listener = { ok, reason -> jsPinResult(ok, reason) }
        val started = try {
            mgr.requestPinAppWidget(cn, null, WidgetPin.callbackIntent(this))
        } catch (t: Throwable) {
            WidgetLog.append(this, "requestPinAppWidget 异常", t)
            false
        }
        if (!started) {
            WidgetPin.listener = null
            jsPinResult(false, "unsupported")
            return
        }
        WidgetLog.append(this, "requestPin 已发起: size=$size, 已有 $before 个")
        pinWatch(cls, before, 0)
    }

    private fun pinWatch(cls: Class<*>, before: Int, attempt: Int) {
        mainHandler.postDelayed({
            if (WidgetPin.listener == null) return@postDelayed   // 已被回调处理
            val now = try {
                AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, cls)).size
            } catch (_: Throwable) {
                before
            }
            when {
                now > before -> {
                    WidgetPin.listener?.invoke(true, "ok")
                    WidgetPin.listener = null
                }
                attempt < pinDelays.lastIndex -> pinWatch(cls, before, attempt + 1)
                else -> {
                    // 超时不判失败：用户可能还停留在系统确认框，网页侧给中性提示。
                    // 小米系设备没有系统确认框（要么直接落桌面要么静默忽略），
                    // 超时 ≈ 被静默忽略 → 弹「桌面快捷方式」权限引导。
                    WidgetPin.listener?.invoke(false, "unknown")
                    WidgetPin.listener = null
                    if (WidgetPin.isXiaomiLike()) {
                        showPermDialog(
                            "桌面没有响应添加请求，很可能是未开启「桌面快捷方式」权限。\n\n去开启后请回到本页重新点添加；也可以回到桌面长按空白处手动添加。"
                        )
                    }
                }
            }
        }, pinDelays[attempt])
    }

    /** 「桌面快捷方式」权限引导弹窗：确认后直达小米安全中心的本应用权限页 */
    private fun showPermDialog(msg: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("添加到桌面")
            .setMessage(msg)
            .setPositiveButton("去开启") { _, _ -> WidgetPin.openPermEditor(this) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 把结果回传给网页：window.MetroPinResult(ok, reason) */
    private fun jsPinResult(ok: Boolean, reason: String) {
        if (ok) {
            try {
                refreshAllWidgets(this)
            } catch (_: Throwable) {
            }
        }
        mainHandler.post {
            if (!this::web.isInitialized) return@post
            // reason 全为内部固定 ASCII 常量，直接拼接安全
            web.evaluateJavascript(
                "window.MetroPinResult && window.MetroPinResult($ok, \"$reason\")",
                null
            )
        }
    }

    override fun onDestroy() {
        WidgetPin.listener = null
        if (this::web.isInitialized) {
            web.stopLoading()
            web.webChromeClient = null
            web.destroy()
        }
        super.onDestroy()
    }

    /**
     * 网页 → 原生的桥接：接收收藏站点的精简快照，供桌面小部件读取。
     * 小部件不能用 WebView，也不适合读 1.1MB 全量表，所以只传收藏相关的班次（通常几十 KB）。
     */
    private class WidgetBridge(private val act: MainActivity) {
        private val ctx: Context get() = act

        @JavascriptInterface
        fun onSnapshot(json: String) {
            WidgetData.save(ctx, json)
            WidgetTick.schedule(ctx)
            // 收藏/数据变了 → 桌面小部件立即重渲染（此前只等下次闹钟，会滞后）
            try {
                refreshAllWidgets(ctx)
            } catch (_: Exception) {
            }
        }

        /**
         * 网页「添加到桌面」：size = small / medium / large。
         * 走标准 requestPinAppWidget，无需权限、无需送审；小米上直接落到桌面。
         * 结果异步回传 window.MetroPinResult(ok, reason)。
         */
        @JavascriptInterface
        fun requestPinWidget(size: String) {
            act.mainHandler.post { act.requestPin(size) }
        }

        /** 桌面上是否已有该尺寸的小部件（网页侧用于打勾提示） */
        @JavascriptInterface
        fun hasWidget(size: String): Boolean = try {
            WidgetPin.hasSize(ctx, size)
        } catch (_: Throwable) {
            false
        }

        /** 当前桌面是否支持一键添加（不支持时网页应隐藏该入口） */
        @JavascriptInterface
        fun canPinWidget(): Boolean = try {
            AppWidgetManager.getInstance(ctx).isRequestPinAppWidgetSupported
        } catch (_: Throwable) {
            false
        }

        /**
         * 跳转到系统「应用详情」页。
         * 小米/红米上 requestPinAppWidget 依赖「桌面快捷方式」特殊权限：
         * 设置 → 应用设置 → 应用管理 → Metro Next → 桌面快捷方式（允许）。
         * 未授权时 requestPinAppWidget 会静默失败（无弹窗、无回调、不落桌面）。
         */
        @JavascriptInterface
        fun openAppSettings() {
            try {
                val i = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", ctx.packageName, null)
                )
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (_: Throwable) {
            }
        }

        /**
         * 网页「小部件诊断日志」入口读取日志文件（无 adb 时定位载入失败）。
         * v1.0.16：顶部附「后台刷新自检」结论，判断闹钟是否被系统推迟。
         */
        @JavascriptInterface
        fun getWidgetLog(): String {
            return try {
                val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                val body = java.io.File(dir, "widget_debug.log").takeIf { it.exists() }
                    ?.readText() ?: "(无 widget_debug.log)"
                TickState.summary(ctx) + "\n\n" + body
            } catch (e: Exception) {
                "读取日志失败: ${e.message}"
            }
        }

        /** 清空小部件诊断日志（诊断面板「清空日志」按钮） */
        @JavascriptInterface
        fun clearWidgetLog() {
            WidgetLog.clear(ctx)
        }
    }

    private companion object {
        const val APP_DOMAIN = "appassets.androidplatform.net"
    }
}
