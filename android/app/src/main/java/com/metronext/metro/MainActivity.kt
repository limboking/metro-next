package com.metronext.metro

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
            web.loadUrl("https://$APP_DOMAIN/assets/public/metro.html")
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

    override fun onDestroy() {
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
    private class WidgetBridge(private val ctx: Context) {
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
