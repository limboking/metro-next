package com.metronext.metro.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.metronext.metro.MainActivity
import com.metronext.metro.R
import java.util.Calendar

/**
 * 桌面小部件。
 *
 * 三个尺寸共用一套逻辑，区别只在「每页显示几个站点」与布局：
 *   2×2 → 每页 1 站   4×2 → 每页 3 站   4×4 → 每页 6 站
 *
 * 设计要点：
 * - 站点顺序跟随 App 内的收藏顺序（网页侧排序后导出快照），默认显示第一个。
 * - 翻页**循环**：第 1 页往上跳到末页，末页往下跳回第 1 页。
 * - 页码按 appWidgetId 独立存储，桌面上放多个小部件互不干扰。
 * - 点箭头翻页；点 ↻ 只刷新当前小部件；点其他区域打开 App（不做 deep link）。
 * - v1.0.19 起三个布局控件 id 完全一致（w_refresh + pager_*），render() 无分支。
 * - v1.0.24 起末页从队尾回退取满（无孤儿页）：收藏数非每页整数倍时，末页
 *   与前一页有少量重叠，但每页都是满行。
 * - v1.0.32 排版定稿（信息量跨设备/跨收藏数一致，只调排版不增减内容）：
 *   ① 行数恒等于 perPage，收藏不足一页时空行**保留占位**（不再 GONE）→ 每行恒占
 *      「卡高 ÷ perPage」一格，内容贴顶、留白集中在底部，行距不随收藏多少变化；
 *   ② 长文案由 TextFit 按 launcher 实测格子宽降字号（列表 10→9→8sp、2×2 站名 13→11sp），
 *      2×2 的「线路 · 开往 X」放不下就断在「开往」后换两行——不截断；
 *   ③ 2×2 班次行由合并单行改**两列**（左时刻 / 右倒计时，右对齐成列）。
 */
abstract class BaseWidget : AppWidgetProvider() {

    /** 每页显示几个站点 */
    protected abstract val perPage: Int

    /** 使用的布局：widget_small（2×2）/ widget_list3（4×2）/ widget_list6（4×4） */
    protected abstract fun layoutRes(): Int

    /**
     * 布局里写死了几行。必须与 layoutRes() 实际包含的行数严格一致：
     * RemoteViews 对不存在的 id 调 setXxx 不会立刻报错，但 updateAppWidget 应用布局时会抛异常，
     * 表现为「载入窗口小部件时出现问题」。
     * 2×2 走 bindSmall 分支，用不到这个字段。
     */
    protected open val rowCount: Int get() = perPage

    /**
     * 第 i 行的控件 id；必须与 layoutRes() 的布局对应（list 子类必须 override）。
     * 默认实现抛异常——WidgetSmall 走 bindSmall 不需要它，但 Kotlin 要求所有子类实现，
     * 已在 WidgetSmall 中 override 成占位返回值。
     */
    protected open fun rowIds(i: Int): RowIds = throw UnsupportedOperationException(
        "rowIds must be overridden by list-based widgets"
    )

    // ───────────────────── 文案自适应（v1.0.32） ─────────────────────

    /**
     * 按小部件**实际格子宽度**实测文本宽度，放不下就降字号——而不是截断。
     *
     * 为什么必须实测：格子宽由 launcher 决定，各机型/各网格不同（朋友机 2×2 只有 ~122dp，
     * 本机更宽）；CJK 1em、数字 0.5em 上下的 em 估算必然有偏差。Paint 实测与 TextView
     * 同字体、同 sp→px 换算，最准。
     *
     * 格子宽来源：launcher 通过 onAppWidgetOptionsChanged 给的 dp（缓存进 WidgetPrefs）；
     * 首次渲染还没拿到时用 info xml 声明的最小宽（2×2=125dp / 列表=250dp）——那是 launcher
     * 保证的下限，偏保守（字号可能偏小一档），拿到真实值后下次渲染即修正。
     */
    private inner class TextFit(private val ctx: Context, cellWDp: Int) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        /**
         * 内容列可用宽（dp）= 格子宽 − 根布局左右 padding − （内容列 paddingEnd，兼作翻页列位置）。
         * 翻页列落在 paddingEnd 让出的空间里、与文字左右相邻不重叠（2×2：20dp 列宽 = 20dp
         * paddingEnd；列表：26dp 列宽落在 30dp paddingEnd 内，余 4dp 间隙）。
         */
        val contentDp: Float = if (perPage == 1) {
            (cellWDp - 8 - 4 - 20).toFloat()      // 根 padStart8 + padEnd4 + 内容列 padEnd20
        } else {
            (cellWDp - 10 - 4 - 30).toFloat()     // 根 padStart10 + padEnd4 + 内容列 padEnd30
        }

        /**
         * 站名可用宽（再扣 4dp 色条 + 8dp 间距）。**仅 2×2 用**：
         * 列表的站名与「线路 · 方向」同处左侧文字列，可用宽 = 文字列宽（见 lineAvailDp）。
         */
        val nameDp: Float get() = contentDp - 12f

        /**
         * 文本在 sp 字号下的宽度（px）。
         * 用 TypedValue.applyDimension 而不用 scaledDensity：前者与 TextView 内部
         * 解析 sp 的路径完全一致（大字号缩放下 scaledDensity 是非线性近似，会偏差）。
         */
        fun widthPx(text: String, sp: Float): Float {
            paint.textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sp, ctx.resources.displayMetrics
            )
            return paint.measureText(text)
        }

        fun dpOf(px: Float): Float = px / ctx.resources.displayMetrics.density

        /**
         * 文本（可含 \n，按最长一行判定）超过可用宽时逐级降 1sp，最低 minSp；返回最终字号。
         *
         * 阈值取 100%（不留安全余量）而非常见的 0.9x：留余量的代价是「本可放下的文案被
         * 无谓缩小一档」（实测 6 字站名 78dp 恰好等于 2×2 可用宽，按 0.92 会掉到 11sp）。
         * 万一真机字体比实测略宽，兜底是**换行或 ellipsize，而不是丢内容**——上层每个
         * 超长文案都有两行/降号兜底（见 line()）。
         */
        fun sp(text: String, availDp: Float, startSp: Float, minSp: Float): Float {
            if (text.isEmpty() || availDp <= 0f) return startSp
            val lines = text.split('\n')
            var sp = startSp
            while (sp > minSp && lines.any { dpOf(widthPx(it, sp)) > availDp }) sp -= 1f
            return sp
        }

        /**
         * 「线路 · 开往 X」专用（2×2 与列表共用）：一行放得下就用一行；放不下且含「开往」
         * 就断在「开往」后换两行（两行分别判定，各自降字号）；连断行也放不下才整串降字号。
         * 返回 (最终文本, 字号)。
         *
         * 用「断行」而不是「降号」优先：换行不丢任何信息，降号会连带把同页其它行一起缩小。
         * 传入 startSp == minSp 时即为「在固定字号下只决定断不断行」，供列表统一字号后再定版式。
         */
        fun line(text: String, availDp: Float, startSp: Float, minSp: Float): Pair<String, Float> {
            if (text.isEmpty()) return text to startSp
            if (dpOf(widthPx(text, startSp)) <= availDp) return text to startSp
            val brk = when {
                text.contains("开往 ") -> text.replace("开往 ", "开往\n")
                text.contains("开往") -> text.replace("开往", "开往\n")
                else -> null
            }
            if (brk != null) return brk to sp(brk, availDp, startSp, minSp)
            return text to sp(text, availDp, startSp, minSp)
        }
    }

    /**
     * 文本适配上下文：格子宽（launcher 实测优先，未知用声明下限）+ 2×2 的可用宽。
     */
    private fun textFit(ctx: Context, id: Int): TextFit {
        val declared = if (perPage == 1) SMALL_MIN_W_DP else LIST_MIN_W_DP
        val cellW = WidgetPrefs.getCellW(ctx, id).takeIf { it > 0 } ?: declared
        return TextFit(ctx, cellW)
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val startMs = System.currentTimeMillis()
            try {
                WidgetLog.append(
                    ctx,
                    "onUpdate 开始: id=$id, size=${this::class.java.simpleName}, " +
                        "perPage=$perPage, layout=${layoutRes()}"
                )
                render(ctx, mgr, id)
                WidgetLog.append(ctx, "onUpdate 成功: id=$id, ${System.currentTimeMillis() - startMs}ms")
            } catch (e: Throwable) {
                WidgetLog.append(ctx, "onUpdate 异常: id=$id, ${System.currentTimeMillis() - startMs}ms", e)
                fallbackRender(ctx, mgr, id, e)
            }
        }
        try {
            WidgetTick.schedule(ctx)
        } catch (e: Throwable) {
            WidgetLog.append(ctx, "WidgetTick.schedule 异常", e)
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            // ★ 小米小部件「曝光刷新」：用户滑到小部件所在页面时，系统主动拉起
            // :widgetProvider 进程并发此广播——这是 HyperOS 上唯一能绕开
            // 「对齐唤醒推迟闹钟」的刷新时机，因此优先处理。
            // super.onReceive 只认原生 ACTION_APPWIDGET_UPDATE，必须在这里显式分发。
            if (intent.action == ACTION_MIUI_EXPOSURE) {
                val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                if (ids != null && ids.isNotEmpty()) {
                    WidgetLog.append(ctx, "曝光刷新: ${ids.contentToString()}")
                    onUpdate(ctx, AppWidgetManager.getInstance(ctx), ids)
                    return
                }
            }
            super.onReceive(ctx, intent)
            if (intent.action == ACTION_PAGE) {
                val id = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                val delta = intent.getIntExtra(EXTRA_DELTA, 0)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID && delta != 0) {
                    WidgetPrefs.shiftPage(ctx, id, delta, pageCount(ctx))
                    render(ctx, AppWidgetManager.getInstance(ctx), id)
                    WidgetTick.schedule(ctx)
                }
            } else if (intent.action == ACTION_REFRESH) {
                // v1.0.17：手动刷新按钮 → 只重渲染当前小部件（重读最新快照 +
                // 按当前时间重算倒计时），桌面上的其他小部件不受影响。
                val id = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetLog.append(ctx, "手动刷新: id=$id")
                    render(ctx, AppWidgetManager.getInstance(ctx), id)
                    WidgetTick.schedule(ctx)
                }
            }
        } catch (e: Throwable) {
            WidgetLog.append(ctx, "onReceive 异常: action=${intent.action}", e)
        }
    }

    /**
     * 正常渲染失败的兜底：改用已验证可用的 2×2 布局（widget_small）渲染，
     * 把错误信息显示在卡片上，而不是让 launcher 报「载入窗口小部件时出现问题」。
     * 这样 widget 一定能上桌面，同时 widget_debug.log 里留有真实原因。
     */
    private fun fallbackRender(ctx: Context, mgr: AppWidgetManager, id: Int, e: Throwable) {
        try {
            val v = RemoteViews(ctx.packageName, R.layout.widget_small)
            v.setTextViewText(R.id.w_name, "渲染失败，详见日志")
            v.setTextViewText(R.id.w_line, "widget_debug.log")
            v.setTextViewText(R.id.w_up1, e.message ?: e.javaClass.simpleName)
            v.setTextViewText(R.id.w_up2, "")
            v.setTextViewText(R.id.w_up3, "")
            // 紧急态副本在布局里默认 gone，但上一次成功渲染可能把它置成 VISIBLE（≤1 分钟时），
            // 而兜底分支不走 setCountdown → 必须显式隐藏，否则错误文案与「已进站」重影。
            v.setViewVisibility(R.id.w_up1_d, View.GONE)
            // 时刻列/下一站清空：兜底卡片只留错误信息，不留上一次预览数据
            v.setTextViewText(R.id.w_t1, "")
            v.setTextViewText(R.id.w_t2, "")
            v.setTextViewText(R.id.w_t3, "")
            v.setTextViewText(R.id.w_next, "")
            v.setViewVisibility(R.id.pager_box, View.GONE)
            v.setOnClickPendingIntent(R.id.widget_root, openAppIntent(ctx))
            mgr.updateAppWidget(id, v)
            WidgetLog.append(ctx, "fallback 渲染成功: id=$id")
        } catch (e2: Throwable) {
            WidgetLog.append(ctx, "fallback 也失败: id=$id", e2)
        }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        for (id in ids) WidgetPrefs.clear(ctx, id)
    }

    /**
     * 记录 launcher 实际分配给小部件的尺寸（dp），并重渲染。
     * 用于验证「4×2/4×4 载入失败 = 内容高度超过 launcher 分配高度」的假设：
     * 若日志里 4×2 的 minHeight 明显小于布局内容高（2×52dp+1dp≈105dp），即可实锤。
     */
    override fun onAppWidgetOptionsChanged(
        ctx: Context,
        mgr: AppWidgetManager,
        id: Int,
        newOptions: android.os.Bundle
    ) {
        try {
            super.onAppWidgetOptionsChanged(ctx, mgr, id, newOptions)
            val minW = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minH = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val maxW = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            val maxH = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            val rowsNow = WidgetData.rows(ctx)
            // v1.0.32：把 launcher 给的格子宽存下来 —— 文案降字号按它算可用宽
            // （以前只写日志，布局全靠弹性装，窄格子上长文案必截断）。
            if (minW > 0) WidgetPrefs.setCellSize(ctx, id, minW, minH)
            WidgetLog.append(
                ctx,
                "尺寸变化: id=$id, size=${this::class.java.simpleName}, " +
                    "min=${minW}x${minH}dp, max=${maxW}x${maxH}dp, " +
                    "rows=${rowsNow?.size ?: "null(占位单行)"}"
            )
            render(ctx, mgr, id)
        } catch (e: Throwable) {
            WidgetLog.append(ctx, "onAppWidgetOptionsChanged 异常: id=$id", e)
        }
    }

    private fun pageCount(ctx: Context): Int {
        val total = WidgetData.rows(ctx)?.size ?: 0
        return if (total == 0) 1 else (total + perPage - 1) / perPage
    }

    protected fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(ctx.packageName, layoutRes())
        val rows = WidgetData.rows(ctx)
        val pages = pageCount(ctx)

        // 诊断日志：每行实际使用的线路色（排查「色条消失/颜色不对」——
        // 若日志里颜色正常而桌面异常，则可确认是 launcher 渲染问题）
        if (!rows.isNullOrEmpty()) {
            WidgetLog.append(
                ctx,
                "rows: " + rows.joinToString { "${it.name}=#${"%06X".format(it.color and 0xFFFFFF)}" }
            )
        }

        // 收藏变少导致页码越界时收敛到最后一页
        val page = WidgetPrefs.getPage(ctx, id).coerceIn(0, pages - 1)
        WidgetPrefs.setPage(ctx, id, page)

        val list = if (rows.isNullOrEmpty()) listOf(emptyRow(ctx)) else rows
        // v1.0.24 末页补齐：最后一页从队尾回退取满 perPage 行。此前 8 个收藏 +
        // 每页 6 站时末页只剩 2 行（weight 均分后各占半屏，中间大片留白），
        // 被用户当成「站点丢了」。代价是与前一页有少量重叠（收藏数非 perPage
        // 整数倍时），但任何一页都是满行，且收藏较少（≤perPage）时无变化。
        val start = minOf(page * perPage, (list.size - perPage).coerceAtLeast(0))

        val fit = textFit(ctx, id)

        if (perPage == 1) {
            val cur = list.getOrNull(start)
            if (cur != null) {
                WidgetLog.append(
                    ctx,
                    "2×2 排版: id=$id, content=${fit.contentDp}dp, " +
                        "name=${fit.sp(cur.name, fit.nameDp, 13f, 11f)}sp, " +
                        "line=${fit.line(cur.lineWithMark(), fit.contentDp, 10f, 8f).second}sp"
                )
            }
            bindSmall(views, cur, fit)
        } else {
            // 一屏内字号必须统一：同一页里某行缩小、别的行不缩，看着像错版。
            // 用「全部收藏里最挤的那一条」定字号，整页共用（翻页时字号也稳定）。
            val maxRightDp = list
                .filter { it.countdown.isNotEmpty() || it.hhmm.isNotEmpty() }
                .maxOfOrNull { rightColumnDp(fit, it) } ?: 0f
            // 左侧文字列可用宽 = 内容列 − 20（色条4 + 名间距8 + 右列间距8）− 右列宽
            val lineAvailDp = fit.contentDp - 20f - maxRightDp
            var nameSp = 13f
            var lineSp = 10f
            for (r in list) {
                nameSp = minOf(nameSp, fit.sp(r.name, lineAvailDp, 13f, 11f))
                lineSp = minOf(lineSp, fit.line(r.lineWithMark(), lineAvailDp, 10f, 8f).second)
            }
            WidgetLog.append(
                ctx,
                "列表排版: id=$id, cell=${fit.contentDp}dp, 右列=${maxRightDp}dp, " +
                    "文字列=${lineAvailDp}dp, name=${nameSp}sp, line=${lineSp}sp"
            )

            // 行数恒等于 perPage：没数据的行不再隐藏，保留空行占位（见 bindListRow）
            for (i in 0 until rowCount) {
                val row = if (i < perPage) list.getOrNull(start + i) else null
                val hasNext = i < perPage && list.getOrNull(start + i + 1) != null
                // 断行按**共享字号**定版（startSp == minSp 即「字号已定，只决定断不断行」）——
                // 若按各自的 10sp 断行，某行降到 9sp 后仍留着不必要的一行，行高会不一致
                val lineText = row?.let {
                    fit.line(it.lineWithMark(), lineAvailDp, lineSp, lineSp).first
                }
                bindListRow(views, i, row, hasNext, nameSp, lineSp, lineText)
            }
        }

        // v1.0.19 起三个布局（widget_small/list3/list6）控件 id 完全一致：
        // w_refresh + pager_prev/num/next。翻页控件只有一页时隐藏，刷新按钮常显。
        val showPager = pages > 1
        views.setViewVisibility(R.id.pager_prev, if (showPager) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.pager_num, if (showPager) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.pager_next, if (showPager) View.VISIBLE else View.GONE)
        if (showPager) {
            views.setTextViewText(R.id.pager_num, "${page + 1}/$pages")
            views.setOnClickPendingIntent(R.id.pager_prev, pageIntent(ctx, id, -1))
            views.setOnClickPendingIntent(R.id.pager_next, pageIntent(ctx, id, +1))
        }
        views.setOnClickPendingIntent(R.id.w_refresh, refreshIntent(ctx, id))

        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(ctx))
        mgr.updateAppWidget(id, views)
    }

    /** 无收藏 / 无快照时的占位行（规范要求：无内容场景要有占位与说明文字） */
    private fun emptyRow(ctx: Context) = WidgetData.Row(
        name = ctx.getString(R.string.widget_no_fav),
        color = Color.LTGRAY,
        lineDesc = ctx.getString(R.string.widget_open_hint),
        mark = "",
        hhmm = "",
        countdown = "",
        urgent = false,
        absMin = Int.MAX_VALUE
    )

    /** 「线路 · 方向」+ 班次标记（始发/快车/区间），与网页 fav-lines + fc 两行合并展示 */
    private fun WidgetData.Row.lineWithMark(): String = when {
        mark.isEmpty() -> lineDesc
        lineDesc.isEmpty() -> mark
        else -> "$lineDesc · $mark"
    }

    // ───────────────── 2×2（widget_small.xml，控件 id 为 w_*） ─────────────────
    // v1.0.15：倒计时回到纯文本（Chronometer 在小米 launcher 上时间基准异常，已废弃），
    // 实时性由 WidgetTick 分钟对齐闹钟保证。
    // v1.0.25：三班合并单行「时刻 · 距发车」。
    // v1.0.32：改**两列**（左时刻 / 右倒计时），字号按实测格子宽动态下发；班次不足三班
    //          时清空文本保留空行（与其它尺寸信息量一致、行距恒定）。

    private fun bindSmall(views: RemoteViews, row: WidgetData.Row?, fit: TextFit) {
        val r = row ?: return

        // 「线路 · 开往 X」：一行优先，放不下就断在「开往」后换两行——窄卡片上
        // 「15号线 · 开往 清华东路西口」一行需 124dp，而内容列只有 90dp 上下。
        val (lineText, lineSp) = fit.line(r.lineWithMark(), fit.contentDp, 10f, 8f)
        val nextText = if (r.nextStation.isEmpty()) "" else "下一站 ${r.nextStation}"
        val nextSp = fit.sp(nextText, fit.contentDp, 10f, 8f)

        views.setInt(R.id.w_bar, "setBackgroundColor", r.color)
        views.setTextViewTextSize(
            R.id.w_name, TypedValue.COMPLEX_UNIT_SP, fit.sp(r.name, fit.nameDp, 13f, 11f)
        )
        views.setTextViewText(R.id.w_name, r.name)
        views.setTextViewTextSize(R.id.w_line, TypedValue.COMPLEX_UNIT_SP, lineSp)
        views.setTextViewText(R.id.w_line, lineText)
        // 下一站：数据缺失（环线/未知）时清空文本而不是隐藏整行——空行占位让行距恒定
        views.setViewVisibility(R.id.w_next, View.VISIBLE)
        views.setTextViewTextSize(R.id.w_next, TypedValue.COMPLEX_UNIT_SP, nextSp)
        views.setTextViewText(R.id.w_next, nextText)

        val ups = r.upcoming
        val now = nowOperatingMinute()
        for (k in 0 until SMALL_SLOTS) {
            val up = ups.getOrNull(k)
            val timeText: String
            val cdText: String
            val urgent: Boolean
            when {
                up != null -> {
                    timeText = up.hhmm
                    cdText = compactWaitText(up.absMin - now)
                    urgent = up.absMin - now <= 1
                }
                k == 0 -> {
                    // 收班/无班次：首行显示静态文案，信息不丢
                    timeText = r.hhmm
                    cdText = r.countdown
                    urgent = false
                }
                else -> {
                    timeText = ""
                    cdText = ""
                    urgent = false
                }
            }
            views.setTextViewText(SMALL_TIME_IDS[k], timeText)
            // 右列（倒计时）不能被左列挤掉：可用宽 = 内容列 − 时刻实测宽 − 6dp 间距
            val cdAvail = fit.contentDp - fit.dpOf(fit.widthPx(timeText, SMALL_TIME_SP)) - 6f
            val cdSp = fit.sp(
                cdText, cdAvail,
                if (k == 0) SMALL_CD_SP else SMALL_CD_SP2, 11f
            )
            val cdId = SMALL_CD_IDS[k]
            val cdDId = SMALL_CD_DANGER_IDS[k]
            views.setTextViewTextSize(cdId, TypedValue.COMPLEX_UNIT_SP, cdSp)
            views.setTextViewText(cdId, cdText)
            if (cdDId != 0) {
                views.setTextViewTextSize(cdDId, TypedValue.COMPLEX_UNIT_SP, cdSp)
                views.setTextViewText(cdDId, cdText)
                setCountdownVisible(views, cdId, cdDId, urgent)
            }
        }
    }

    /**
     * 叠放的两个倒计时控件：同一份文案，按 urgent 决定显示哪一个。
     * 这是「深色模式只能 XML 静态适配」约束下的紧急态方案——
     * 一旦用 setTextColor 动态设色，小米切换深色模式时用缓存 RemoteViews 重建，
     * 颜色就再也不会更新。
     */
    private fun setCountdown(
        views: RemoteViews, idNormal: Int, idDanger: Int,
        text: String, urgent: Boolean
    ) {
        views.setTextViewText(idNormal, text)
        views.setTextViewText(idDanger, text)
        setCountdownVisible(views, idNormal, idDanger, urgent)
    }

    private fun setCountdownVisible(views: RemoteViews, idNormal: Int, idDanger: Int, urgent: Boolean) {
        views.setViewVisibility(idNormal, if (urgent) View.GONE else View.VISIBLE)
        views.setViewVisibility(idDanger, if (urgent) View.VISIBLE else View.GONE)
    }

    // ───────────── 倒计时文案（v1.0.15：纯文本，分钟粒度） ─────────────

    /**
     * 2×2 紧凑倒计时：≥60 分钟用「X时Y分」（≈3.7em，13sp 下 48dp），
     * 而不是列表用的「X 小时 Y 分」（≈5.6em，2×2 的右列放不下）。
     */
    private fun compactWaitText(waitMin: Int): String = when {
        waitMin <= 0 -> "已进站"
        waitMin >= 60 -> "${waitMin / 60}时${waitMin % 60}分"
        else -> "$waitMin 分钟"
    }

    /** 列表行右侧「倒计时 + 时刻」两行取较宽者（dp）→ 用于反推左侧文字列的可用宽 */
    private fun rightColumnDp(fit: TextFit, r: WidgetData.Row): Float = maxOf(
        fit.dpOf(fit.widthPx(r.countdown, LIST_CD_SP)),
        fit.dpOf(fit.widthPx(r.hhmm, LIST_TIME_SP))
    )

    // ───────────── 4×2 / 4×4（widget_list*.xml，控件 id 为 row*_line / time / cd） ─────────────

    private fun bindListRow(
        views: RemoteViews, i: Int, row: WidgetData.Row?,
        hasNext: Boolean, nameSp: Float, lineSp: Float, lineText: String?
    ) {
        val ids = rowIds(i)
        if (row == null) {
            // v1.0.32：空行**保留占位**（不再 GONE）——每行恒占「卡高 ÷ perPage」一格，
            // 收藏不足一页时内容贴顶、留白集中在底部。旧版把空行 GONE 掉，剩下的行把整卡
            // 高度均分 → 收藏少时行距被拉开（朋友机 4×4 只有 4 站，行间大片留白）。
            views.setViewVisibility(ids.row, View.VISIBLE)
            if (ids.div != 0) views.setViewVisibility(ids.div, View.GONE)
            views.setInt(ids.bar, "setBackgroundColor", Color.TRANSPARENT)
            views.setTextViewText(ids.name, "")
            views.setTextViewText(ids.line, "")
            views.setTextViewText(ids.time, "")
            views.setTextViewText(ids.cd, "")
            views.setTextViewText(ids.cdD, "")
            return
        }
        views.setViewVisibility(ids.row, View.VISIBLE)
        // 分割线只在「本行有内容且下一行也有内容」时显示
        if (ids.div != 0) views.setViewVisibility(ids.div, if (hasNext) View.VISIBLE else View.GONE)

        // 色条：TextView + setBackgroundColor（RemoteViews 反射方法）。
        // 不用 setImageViewBitmap——小米 launcher 翻页重渲染时部分位图不显示（v1.0.7 实测）。
        views.setInt(ids.bar, "setBackgroundColor", row.color)
        // 站名 / 线路·方向：字号由 render 按实测可用宽统一下发（整页一致，超宽断行/降号不截断）
        views.setTextViewTextSize(ids.name, TypedValue.COMPLEX_UNIT_SP, nameSp)
        views.setTextViewText(ids.name, row.name)
        views.setTextViewTextSize(ids.line, TypedValue.COMPLEX_UNIT_SP, lineSp)
        // 线路 · 方向 · 标记（与网页 fav-lines 一致）；lineText 可能含「开往」后的换行
        views.setTextViewText(ids.line, lineText ?: row.lineWithMark())
        // 时刻小字（次要信息）
        views.setTextViewText(ids.time, row.hhmm)
        // 倒计时大字：WidgetData 已按当前时间算好的文案（X 分钟 / X 小时 Y 分 / 已进站）。
        // 颜色由布局 XML 决定（accent / danger 两个叠放控件按 urgent 切显隐）。
        setCountdown(views, ids.cd, ids.cdD, row.countdown, row.urgent)
    }

    // 倒计时文案：列表行直接用 WidgetData 算好的 row.countdown（「X 分钟 / X 小时 Y 分 /
    // 已进站 / 次日」），2×2 用 compactWaitText（紧凑式）。原 waitText/upcomingText
    // 在 v1.0.32 改两列后已无调用点，删除避免死代码。

    /** 当前运营日分钟数（凌晨 4:30 前归前一运营日，与网页版/WidgetData 一致） */
    private fun nowOperatingMinute(): Int {
        val c = Calendar.getInstance()
        var m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        if (m < 270) m += 1440
        return m
    }

    private fun pageIntent(ctx: Context, id: Int, delta: Int): PendingIntent {
        val i = Intent(ctx, this::class.java).apply {
            action = ACTION_PAGE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(EXTRA_DELTA, delta)
        }
        // requestCode 需区分 widgetId 与方向，否则多个小部件会共用同一个 PendingIntent
        val code = id * 10 + if (delta > 0) 1 else 2
        return PendingIntent.getBroadcast(
            ctx, code, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun refreshIntent(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, this::class.java).apply {
            action = ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
        // requestCode 与 pageIntent 的 +1/+2 区分（+3），多个小部件互不共用
        return PendingIntent.getBroadcast(
            ctx, id * 10 + 3, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun openAppIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, 0, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        const val ACTION_PAGE = "com.metronext.metro.widget.ACTION_PAGE"
        const val ACTION_REFRESH = "com.metronext.metro.widget.ACTION_REFRESH"
        const val EXTRA_DELTA = "delta"
        /** 小米小部件曝光刷新（Manifest 里已声明该 action） */
        const val ACTION_MIUI_EXPOSURE = "miui.appwidget.action.APPWIDGET_UPDATE"

        /** info xml 里声明的 minWidth——launcher 保证的下限，未知真实格子宽时用它（2×2=125dp） */
        const val SMALL_MIN_W_DP = 125

        /** 列表（4×2 / 4×4）声明的 minWidth = 250dp */
        const val LIST_MIN_W_DP = 250
    }
}

/** 2×2：每页 1 站 */
class WidgetSmall : BaseWidget() {
    override val perPage = 1
    override fun layoutRes() = R.layout.widget_small
    override fun rowIds(i: Int) = IDS_3[0]   // 占位：WidgetSmall 走 bindSmall，永远不会调用 rowIds
}

/** 4×2：每页 3 站（用户反馈 2 行留白太多，从 2 扩到 3） */
class WidgetMedium : BaseWidget() {
    override val perPage = 3
    override fun layoutRes() = R.layout.widget_list3
    override val rowCount = 3
    override fun rowIds(i: Int) = IDS_3[i]
}

/** 4×4：每页 6 站（用户反馈 4 行留白太多，从 4 扩到 6） */
class WidgetLarge : BaseWidget() {
    override val perPage = 6
    override fun layoutRes() = R.layout.widget_list6
    override val rowCount = 6
    override fun rowIds(i: Int) = IDS_6[i]
}

/**
 * 一行站点对应的控件 id（div 为 0 表示该行是最后一行、其后没有分割线）。
 * 必须是 public（默认可见性）：BaseWidget.rowIds() 是 protected open，
 * 返回类型若是 private-in-file / internal 会触发 EXPOSED_FUNCTION_RETURN_TYPE。
 *
 * cdD：与 cd 叠放的「紧急色」副本（默认 GONE）。深色模式只能 XML 静态适配，
 * 不能用 setTextColor 切换颜色，所以紧急态改成切这两个控件的显隐。
 */
class RowIds(
    val row: Int, val bar: Int, val name: Int,
    val line: Int, val time: Int, val cd: Int, val div: Int, val cdD: Int
)

/**
 * 列表布局的 id 表——必须与布局文件严格一一对应。
 * RemoteViews 对不存在的 id 调 setXxx 不会立刻报错，但 updateAppWidget 应用布局时会抛异常
 * → launcher 显示「载入窗口小部件时出现问题」。
 */
// ───────────────────── 文案自适应口径（v1.0.32） ─────────────────────
//
// 「放不下」的处置顺序：① 断行（断在「开往」后，两行都不丢字）→ ② 降字号（最低 8sp）→
// ③ ellipsize（仅在①②都无解时的最后兜底，例如「X 小时 Y 分」这类宽倒计时把左列挤到
//    极窄、且线路名还带「快车·跳N站」标记的极罕见组合）。
// 降号阈值取 100%（不留安全余量）：留余量会把本可放下的文案无谓缩小一档；真机若比实测
// 略宽，后果是断行而不是截断，不丢信息。

// ───────────────────── 2×2 班次行的控件 id / 字号（v1.0.32 两列式） ─────────────────────
//
// 三行班次固定占位（左「时刻」+ 右「倒计时」）；班次不足三班时清空文本、保留空行。
// 首行另有「紧急态」叠放副本（cd_danger），按 urgent 切显隐。

/** 左列「时刻」10sp 次级灰 */
private const val SMALL_TIME_SP = 10f

/** 右列「倒计时」：首行 13sp 强调色，后两班 12sp 次级色 */
private const val SMALL_CD_SP = 13f
private const val SMALL_CD_SP2 = 12f

/** 2×2 班次行数（与 widget_small.xml 的班次行数严格一致） */
private const val SMALL_SLOTS = 3

/** 列表右列字号：倒计时 17sp、时刻 10sp（WidgetData 已算好文案，无需换算） */
private const val LIST_CD_SP = 17f
private const val LIST_TIME_SP = 10f

private val SMALL_TIME_IDS = intArrayOf(R.id.w_t1, R.id.w_t2, R.id.w_t3)
private val SMALL_CD_IDS = intArrayOf(R.id.w_up1, R.id.w_up2, R.id.w_up3)
/** 紧急态叠放副本；0 = 该行没有副本 */
private val SMALL_CD_DANGER_IDS = intArrayOf(R.id.w_up1_d, 0, 0)

// 注意：widget_list6.xml 由 scripts/gen_widget_list6.py 从 widget_list3.xml 生成，
// 两侧行结构与 id 命名必须一致，否则会出现只在 4×4 复现的渲染问题。
private val IDS_3 = arrayOf(                       // widget_list3.xml：3 行 + div1/div2
    RowIds(R.id.row1, R.id.row1_bar, R.id.row1_name, R.id.row1_line, R.id.row1_time, R.id.row1_cd, R.id.div1, R.id.row1_cd_d),
    RowIds(R.id.row2, R.id.row2_bar, R.id.row2_name, R.id.row2_line, R.id.row2_time, R.id.row2_cd, R.id.div2, R.id.row2_cd_d),
    RowIds(R.id.row3, R.id.row3_bar, R.id.row3_name, R.id.row3_line, R.id.row3_time, R.id.row3_cd, 0, R.id.row3_cd_d)
)

private val IDS_6 = arrayOf(                       // widget_list6.xml：6 行 + div1~div5
    RowIds(R.id.row1, R.id.row1_bar, R.id.row1_name, R.id.row1_line, R.id.row1_time, R.id.row1_cd, R.id.div1, R.id.row1_cd_d),
    RowIds(R.id.row2, R.id.row2_bar, R.id.row2_name, R.id.row2_line, R.id.row2_time, R.id.row2_cd, R.id.div2, R.id.row2_cd_d),
    RowIds(R.id.row3, R.id.row3_bar, R.id.row3_name, R.id.row3_line, R.id.row3_time, R.id.row3_cd, R.id.div3, R.id.row3_cd_d),
    RowIds(R.id.row4, R.id.row4_bar, R.id.row4_name, R.id.row4_line, R.id.row4_time, R.id.row4_cd, R.id.div4, R.id.row4_cd_d),
    RowIds(R.id.row5, R.id.row5_bar, R.id.row5_name, R.id.row5_line, R.id.row5_time, R.id.row5_cd, R.id.div5, R.id.row5_cd_d),
    RowIds(R.id.row6, R.id.row6_bar, R.id.row6_name, R.id.row6_line, R.id.row6_time, R.id.row6_cd, 0, R.id.row6_cd_d)
)

/** 页码持久化：按 appWidgetId 独立存储 */
private object WidgetPrefs {
    private const val PREFS = "metro_widget_pages"
    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getPage(ctx: Context, id: Int): Int = prefs(ctx).getInt("page_$id", 0)

    fun setPage(ctx: Context, id: Int, page: Int) {
        prefs(ctx).edit().putInt("page_$id", page).apply()
    }

    /** 循环翻页：越界取模回到另一端 */
    fun shiftPage(ctx: Context, id: Int, delta: Int, pages: Int) {
        if (pages <= 0) return
        val cur = getPage(ctx, id).coerceIn(0, pages - 1)
        setPage(ctx, id, ((cur + delta) % pages + pages) % pages)
    }

    /**
     * launcher 最近一次告知的格子尺寸（dp）。v1.0.32 起用于文案自适应：
     * 只写日志不落库的旧行为无法按实际格子宽决定字号（窄格子上长文案必截断）。
     * 返回 0 表示未知，调用方回退到 info xml 声明的 minWidth。
     */
    fun getCellW(ctx: Context, id: Int): Int = prefs(ctx).getInt("w_$id", 0)

    fun setCellSize(ctx: Context, id: Int, w: Int, h: Int) {
        prefs(ctx).edit().putInt("w_$id", w).putInt("h_$id", h).apply()
    }

    fun clear(ctx: Context, id: Int) {
        prefs(ctx).edit().remove("page_$id").remove("w_$id").remove("h_$id").apply()
    }
}

/** 刷新桌面上所有本应用的小部件（App 切后台 / 开机恢复 / 快照更新时调用） */
fun refreshAllWidgets(ctx: Context) {
    val mgr = AppWidgetManager.getInstance(ctx)
    val pkg = ctx.packageName
    for (cls in listOf(WidgetSmall::class.java, WidgetMedium::class.java, WidgetLarge::class.java)) {
        val ids = mgr.getAppWidgetIds(ComponentName(pkg, cls.name))
        if (ids.isNotEmpty()) {
            ctx.sendBroadcast(Intent(ctx, cls).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                // 关键：必须带 EXTRA_APPWIDGET_IDS！AppWidgetProvider.onReceive 只在
                // 广播包含该 extra 时才调用 onUpdate，否则整个广播是空操作——
                // v1.0.11 的每分钟闹钟因此从未真正重渲染过小部件（自刷失效的元凶之一）。
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}

/** 桌面上是否还有本应用的小部件 */
fun hasAnyWidget(ctx: Context): Boolean {
    val mgr = AppWidgetManager.getInstance(ctx)
    val pkg = ctx.packageName
    for (cls in listOf(WidgetSmall::class.java, WidgetMedium::class.java, WidgetLarge::class.java)) {
        if (mgr.getAppWidgetIds(ComponentName(pkg, cls.name)).isNotEmpty()) return true
    }
    return false
}

/**
 * 刷新时机（v1.0.13 定稿）：倒计时由 Chronometer 在 launcher 进程实时渲染
 * （零闹钟零耗电，解锁看到的必然是实时值），闹钟只负责「有车开走、切换下一班」
 * 这一数据变化时刻 → 每天几十次。
 *
 * 沿革：v1.0.10 及以前用精确闹钟（Android 14 上权限默认拒绝，失效）；
 * v1.0.11/12 用每分钟自续闹钟链（亮屏可用，但息屏/空闲时被系统合并推迟，
 * 解锁看到的是旧画面）；v1.0.13 起实时性由 Chronometer 承担，闹钟只挂发车时刻。
 * 桌面上没有小部件时不排闹钟（完全静默）；30 分钟 updatePeriodMillis 兜底。
 */
object WidgetTick {

    /** 无班次（收班后）时的兜底补刷间隔（30 分钟） */
    private const val IDLE_CATCHUP_MS = 30 * 60_000L

    fun schedule(ctx: Context) {
        if (!hasAnyWidget(ctx)) {
            WidgetLog.append(ctx, "tick 跳过: 桌面上无本应用小部件")
            return
        }
        // 每分钟对齐闹钟链（v1.0.15 定稿，用户明确接受「每分钟刷新」）：
        //   - 运营时段：nextTick = min(下一分钟边界, 下一班发车) → 每分钟重渲染，
        //     倒计时逐分钟走字，发车瞬间切下一班；系统推迟时补发即刷新。
        //   - 收班后：nextTick = min(次日 4:30, now + 30 分钟) → 每 30 分钟更新
        //     「X 小时 Y 分」静态文案，整夜不逐分钟唤醒。
        // 沿革：Chronometer 实时秒级（v1.0.13/14）在小米 launcher 上时间基准异常
        // （实测 496579:35:38 假值），已废弃回退纯文本。
        val now = System.currentTimeMillis()
        // v1.0.26：回到纯分钟对齐。此前为小米降频到 15 分钟，前提是「曝光刷新」
        // 能覆盖实时场景；但曝光刷新属于小米小部件专属通道，未送审的自用包声明
        // miuiWidget 会导致小部件直接载入失败（v1.0.25 实锤），故已回退原生通道。
        val nextMinute = (now / 60000L + 1) * 60000L
        val departure = nextDepartureMillis(WidgetData.rows(ctx))
        val at = if (departure != null) {
            minOf(nextMinute, departure)
        } else {
            minOf(nextDayStartMillis(), now + IDLE_CATCHUP_MS)
        }
        val pi = PendingIntent.getBroadcast(
            ctx, 1000, Intent(ctx, WidgetTickReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        TickState.markScheduled(ctx, at)
        WidgetLog.append(
            ctx,
            "tick 调度: at=$at (" +
                (if (departure != null && at == departure) "发车时刻" else "分钟/补刷") + ")"
        )
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            // 理论上不会发生（该 API 不需要精确闹钟权限），防御性兜底
            WidgetLog.append(ctx, "setAndAllowWhileIdle 被拒，退回粗略闹钟", e)
            try {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: Exception) {
            }
        }
    }

    /** 最近的发车时刻（毫秒）；无班次时返回 null */
    private fun nextDepartureMillis(rows: List<WidgetData.Row>?): Long? {
        if (rows.isNullOrEmpty()) return null
        val now = System.currentTimeMillis()
        var best: Long? = null
        for (r in rows) {
            if (r.absMin == Int.MAX_VALUE) continue
            val t = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, r.absMin / 1440)
                add(Calendar.MINUTE, r.absMin % 1440)
            }.timeInMillis
            // 至少 1 秒之后，避免与当前时刻重合导致连续触发
            if (t > now + 1000 && (best == null || t < best!!)) best = t
        }
        return best
    }

    /** 次日 4:30：运营日切换点，需重算工作日 / 双休日对应的班次 */
    private fun nextDayStartMillis(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 4)
        set(Calendar.MINUTE, 30)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis
}

/** 收到发车时刻闹钟 → 刷新全部小部件 */
class WidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        // 记录实际触发时刻：与 TickState 记录的调度时刻对比，可判断闹钟是否被
        // 系统推迟（小米对非白名单应用的「对齐唤醒」会推迟到 App 运行时才补发）
        TickState.markFired(ctx)
        WidgetLog.append(ctx, "tick 触发: action=${intent?.action ?: "(闹钟)"}")
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            // 开机：闹钟全部被系统清空，重排并刷新一次（保证「下一班」不长期停更）
            WidgetTick.schedule(ctx)
            refreshAllWidgets(ctx)
            return
        }
        refreshAllWidgets(ctx)
        WidgetTick.schedule(ctx)
    }
}

/**
 * 闹钟调度/触发时刻记录（v1.0.16）：用于「小部件诊断日志」面板顶部的后台刷新自检。
 * 判定逻辑：调度后迟迟不触发 → 系统正在推迟闹钟（MIUI 省电策略），
 * 只有把应用设为「无限制 + 允许自启动」才能让小部件自主刷新。
 */
object TickState {
    private const val PREFS = "metro_tick_state"
    private const val K_SCHED_AT = "sched_at"   // 计划触发时刻（毫秒）
    private const val K_SCHED_TS = "sched_ts"   // 计划时刻的记录时间
    private const val K_FIRE_TS = "fire_ts"     // 实际触发时间

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markScheduled(ctx: Context, at: Long) {
        prefs(ctx).edit()
            .putLong(K_SCHED_AT, at)
            .putLong(K_SCHED_TS, System.currentTimeMillis())
            .apply()
    }

    fun markFired(ctx: Context) {
        prefs(ctx).edit().putLong(K_FIRE_TS, System.currentTimeMillis()).apply()
    }

    private fun fmt(ts: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(ts))

    /** 供 App 内诊断面板展示的后台刷新自检结论 */
    fun summary(ctx: Context): String {
        val p = prefs(ctx)
        val schedAt = p.getLong(K_SCHED_AT, 0L)
        val schedTs = p.getLong(K_SCHED_TS, 0L)
        val fireTs = p.getLong(K_FIRE_TS, 0L)
        val now = System.currentTimeMillis()
        val head = "[后台刷新自检] 系统 Android ${android.os.Build.VERSION.RELEASE}(SDK " +
            "${android.os.Build.VERSION.SDK_INT})"
        return when {
            schedAt == 0L -> "$head\n尚未排过闹钟（请先放置小部件并打开一次 App）"
            fireTs == 0L -> "[后台刷新自检] 已排闹钟（计划 ${fmt(schedAt)}）但从未触发 → 系统正在推迟闹钟\n" + HINT
            fireTs < schedTs -> "[后台刷新自检] 已排闹钟（计划 ${fmt(schedAt)}）未按时触发 → 系统正在推迟闹钟\n" + HINT
            else -> {
                val delaySec = (fireTs - schedAt) / 1000
                val verdict = if (delaySec <= 60) "正常" else "被系统推迟（延迟 ${delaySec} 秒）\n$HINT"
                "[后台刷新自检] 最近一次：计划 ${fmt(schedAt)} → 实际 ${fmt(fireTs)}（延迟 ${delaySec} 秒）→ $verdict" +
                    "\n距上次触发已 ${(now - fireTs) / 1000} 秒"
            }
        }
    }

    private const val HINT =
        "→ 小米（澎湃OS）会推迟非白名单应用的闹钟：请到「设置 → 应用设置 → 应用管理 → Metro Next」\n" +
            "   把「省电策略」设为「无限制」并开启「自启动」，小部件即可自主刷新。"
}
