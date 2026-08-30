package com.metronext.metro

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

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
        }

        setContentView(web)

        if (savedInstanceState == null) {
            web.loadUrl("https://$APP_DOMAIN/assets/public/metro.html")
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

    private companion object {
        const val APP_DOMAIN = "appassets.androidplatform.net"
    }
}
