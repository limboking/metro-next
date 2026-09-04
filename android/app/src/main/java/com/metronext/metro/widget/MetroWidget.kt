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

        if (perPage == 1) {
            bindSmall(ctx, views, list.getOrNull(start))
        } else {
            // 只遍历布局真实存在的行；没数据的行（收藏不足一页）必须显式隐藏。
            for (i in 0 until rowCount) {
                val row = if (i < perPage) list.getOrNull(start + i) else null
                val hasNext = i < perPage && list.getOrNull(start + i + 1) != null
                bindListRow(ctx, views, i, row, hasNext)
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

    private fun emptyRow(ctx: Context) = WidgetData.Row(
        name = ctx.getString(R.string.widget_no_fav),
        color = Color.LTGRAY,
        lineDesc = "",
        mark = "",
        hhmm = "--:--",
        countdown = ctx.getString(R.string.widget_open_hint),
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
    // v1.0.15：倒计时回到纯文本「时刻 · 距发车」（Chronometer 在小米 launcher 上
    // 时间基准异常，已废弃），实时性由 WidgetTick 分钟对齐闹钟保证。
    // 下一站/第二三班无数据时动态 GONE，不留空位。

    private fun bindSmall(ctx: Context, views: RemoteViews, row: WidgetData.Row?) {
        val r = row ?: return
        views.setInt(R.id.w_bar, "setBackgroundColor", r.color)
        views.setTextViewText(R.id.w_name, r.name)
        views.setTextViewText(R.id.w_line, r.lineWithMark())

        // 下一站：环线/数据缺失时隐藏整行，不留空位
        if (r.nextStation.isEmpty()) {
            views.setViewVisibility(R.id.w_next, View.GONE)
        } else {
            views.setViewVisibility(R.id.w_next, View.VISIBLE)
            views.setTextViewText(R.id.w_next, "下一站 ${r.nextStation}")
        }

        val ups = r.upcoming
        // 首班：无班次时退回静态文案；其余班次：无则 GONE 不留空位
        val danger = ctx.getColor(R.color.widget_danger)
        views.setTextViewText(R.id.w_up1, if (ups.isEmpty()) r.countdown else upcomingText(ups[0]))
        views.setViewVisibility(R.id.w_up2, if (ups.size >= 2) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.w_up3, if (ups.size >= 3) View.VISIBLE else View.GONE)
        if (ups.size >= 2) views.setTextViewText(R.id.w_up2, upcomingText(ups[1]))
        if (ups.size >= 3) views.setTextViewText(R.id.w_up3, upcomingText(ups[2]))

        // 首班 = 主色（紧急变红）；其余班次 = 次要灰
        views.setTextColor(R.id.w_up1, if (r.urgent) danger else ctx.getColor(R.color.widget_accent))
        views.setTextColor(R.id.w_up2, ctx.getColor(R.color.widget_text_secondary))
        views.setTextColor(R.id.w_up3, ctx.getColor(R.color.widget_text_secondary))
    }

    // ───────────── 4×2 / 4×4（widget_list*.xml，控件 id 为 row*_line / time / cd） ─────────────

    private fun bindListRow(ctx: Context, views: RemoteViews, i: Int, row: WidgetData.Row?, hasNext: Boolean) {
        val ids = rowIds(i)
        if (row == null) {
            views.setViewVisibility(ids.row, View.GONE)
            if (ids.div != 0) views.setViewVisibility(ids.div, View.GONE)
            return
        }
        views.setViewVisibility(ids.row, View.VISIBLE)
        // 分割线只在「本行有内容且下一行也有内容」时显示
        if (ids.div != 0) views.setViewVisibility(ids.div, if (hasNext) View.VISIBLE else View.GONE)

        // 色条：TextView + setBackgroundColor（RemoteViews 反射方法）。
        // 不用 setImageViewBitmap——小米 launcher 翻页重渲染时部分位图不显示（v1.0.7 实测）。
        views.setInt(ids.bar, "setBackgroundColor", row.color)
        views.setTextViewText(ids.name, row.name)
        // 线路 · 方向 · 标记（与网页 fav-lines 一致）
        views.setTextViewText(ids.line, row.lineWithMark())
        // 时刻小字（次要信息）
        views.setTextViewText(ids.time, row.hhmm)
        // 倒计时大字：WidgetData 已按当前时间算好的文案（X 分钟 / X 小时 Y 分 / 已进站）
        views.setTextViewText(ids.cd, row.countdown)

        val danger = ctx.getColor(R.color.widget_danger)
        views.setTextColor(ids.cd, if (row.urgent) danger else ctx.getColor(R.color.widget_accent))
        views.setTextColor(ids.time, ctx.getColor(R.color.widget_text_tertiary))
    }

    // ───────────── 倒计时文案（v1.0.15：纯文本，分钟粒度） ─────────────

    /**
     * 距发车分钟数 → 文案（与网页版一致）：<60 分「X 分钟」；≥60 分「X 小时 Y 分」；
     * ≤0 或异常「已进站」。
     */
    private fun waitText(waitMin: Int): String = when {
        waitMin <= 0 -> "已进站"
        waitMin >= 60 -> "${waitMin / 60} 小时 ${waitMin % 60} 分"
        else -> "$waitMin 分钟"
    }

    /** 当前运营日分钟数（凌晨 4:30 前归前一运营日，与网页版/WidgetData 一致） */
    private fun nowOperatingMinute(): Int {
        val c = Calendar.getInstance()
        var m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        if (m < 270) m += 1440
        return m
    }

    /**
     * 2×2 三行：显示「时刻 · 距发车」文本，如 "15:04 · 3 分钟"。
     */
    private fun upcomingText(up: WidgetData.Upcoming): String {
        if (up.absMin == Int.MAX_VALUE) return up.hhmm
        return "${up.hhmm} · ${waitText(up.absMin - nowOperatingMinute())}"
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
 */
class RowIds(
    val row: Int, val bar: Int, val name: Int,
    val line: Int, val time: Int, val cd: Int, val div: Int
)

/**
 * 列表布局的 id 表——必须与布局文件严格一一对应。
 * RemoteViews 对不存在的 id 调 setXxx 不会立刻报错，但 updateAppWidget 应用布局时会抛异常
 * → launcher 显示「载入窗口小部件时出现问题」。
 */
private val IDS_3 = arrayOf(                       // widget_list3.xml：3 行 + div1/div2
    RowIds(R.id.row1, R.id.row1_bar, R.id.row1_name, R.id.row1_line, R.id.row1_time, R.id.row1_cd, R.id.div1),
    RowIds(R.id.row2, R.id.row2_bar, R.id.row2_name, R.id.row2_line, R.id.row2_time, R.id.row2_cd, R.id.div2),
    RowIds(R.id.row3, R.id.row3_bar, R.id.row3_name, R.id.row3_line, R.id.row3_time, R.id.row3_cd, 0)
)

private val IDS_6 = arrayOf(                       // widget_list6.xml：6 行 + div1~div5
    RowIds(R.id.row1, R.id.row1_bar, R.id.row1_name, R.id.row1_line, R.id.row1_time, R.id.row1_cd, R.id.div1),
    RowIds(R.id.row2, R.id.row2_bar, R.id.row2_name, R.id.row2_line, R.id.row2_time, R.id.row2_cd, R.id.div2),
    RowIds(R.id.row3, R.id.row3_bar, R.id.row3_name, R.id.row3_line, R.id.row3_time, R.id.row3_cd, R.id.div3),
    RowIds(R.id.row4, R.id.row4_bar, R.id.row4_name, R.id.row4_line, R.id.row4_time, R.id.row4_cd, R.id.div4),
    RowIds(R.id.row5, R.id.row5_bar, R.id.row5_name, R.id.row5_line, R.id.row5_time, R.id.row5_cd, R.id.div5),
    RowIds(R.id.row6, R.id.row6_bar, R.id.row6_name, R.id.row6_line, R.id.row6_time, R.id.row6_cd, 0)
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

    fun clear(ctx: Context, id: Int) {
        prefs(ctx).edit().remove("page_$id").apply()
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

    /** 有班次时的兜底补刷间隔（10 分钟） */
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
