package com.metronext.metro.widget

import android.content.Context
import android.graphics.Color
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * 桌面小部件的数据层。
 *
 * 背景：小部件由 RemoteViews 渲染，既不能用 WebView，也不适合每次解析 1.1MB 全量时刻表。
 * 因此网页侧（app_template.html 的 buildWidgetSnapshot）把「收藏站点的班次」导出成精简快照，
 * App 通过 JS 接口收到后写入内部存储，小部件只读这份小文件。
 *
 * 快照只含静态班次；「下一班」随当前时间变化，由小部件本地计算，
 * 计算逻辑与网页版 calcNext / curGi 逐行保持一致，避免两端结果不同。
 */
object WidgetData {

    private const val FILE_NAME = "widget_snapshot.json"

    /**
     * 快照格式版本。v2（2026-08-31）：新增 mk 标记（始发/快车/区间）。
     * 低于该版本的旧快照直接弃用——旧缓存缺 dirDesc/mk，会造成
     * 「环线不显示方向、始发不显示」，用户需打开一次 App 重新推送。
     */
    private const val SNAPSHOT_VERSION = 2

    /** 最近班次中的一班：hhmm 静态显示，absMin 供 Chronometer 换算实时倒计时基点 */
    class Upcoming(val hhmm: String, val absMin: Int)

    /** 小部件一行要展示的内容 */
    class Row(
        val name: String,          // 站名
        val color: Int,            // 线路色
        val lineDesc: String,      // 「线路名 · 方向」摘要，如 "10号线 · 开往 涵阳" / "2号线 · 内环"
        val mark: String,          // 该班次标记（"XX始发" / "快车·跳N站" / "区间·YY"），无则空串
        val hhmm: String,          // 时刻，如 14:32（小字）
        val countdown: String,     // 静态回退文案（"暂无班次"/"已进站"/"次日"）
        val urgent: Boolean,       // 是否即将发车（<=1 分钟），用于标红
        val absMin: Int,           // 绝对分钟数（可能为 1440+ 表示次日），用于精确刷新与倒计时基点
        val upcoming: List<Upcoming> = emptyList(), // 最近 3 班（2×2 展示用）
        val nextStation: String = ""               // 下一站（沿行驶方向邻站；环线/未知为空）
    )

    /**
     * 某一线路某一方向的班次组。
     * terminal 仍是终点站名（环线为空串）；dirDesc 是网页侧拼接好的「线路 · 方向」摘要，
     * 直接交给 widget 渲染，省去在原生侧再次拼装的负担，也避免环线方向丢失。
     * dirDesc 缺失时（异常快照）用 l/t/d 兜底重建。
     */
    private class Item(
        val color: Int,
        val lineId: String,            // 线路名，如 "10号线"（dirDesc 兜底重建用）
        val terminal: String,
        val loopDir: String,           // 环线方向（内环/外环），非环线为空串
        val dirDesc: String,           // 网页侧给的 widget 显示文本
        val dow: String,               // 周一~周日各指向 groups 的下标
        val groups: List<IntArray>,    // 去重后的班次组，单位：分钟
        val marks: List<Map<Int, String>?>,  // 与 groups 平行；每组的 {分钟: 标记文本}
        val nextStation: String        // 下一站（网页侧沿站序算好；环线/未知为空串）
    ) {
        /** 兜底重建「线路 · 方向」摘要 */
        val fallbackDesc: String
            get() = when {
                terminal.isNotEmpty() -> "$lineId · 开往 $terminal"
                loopDir.isNotEmpty() -> "$lineId · $loopDir"
                else -> lineId
            }
    }

    private class Fav(val name: String, val items: List<Item>)

    /** 上一次成功解析的快照备份：主快照损坏/写一半时兜底，避免渲染出单行占位 */
    private const val GOOD_NAME = "widget_snapshot_good.json"

    /** 原子写入：先写临时文件再 rename。writeText 是「清空+写入」，渲染线程若在
     *  写入中途读文件会拿到半截 JSON → rows() 返回 null → 小部件退化成单行占位
     *  （用户反馈「长按 4×2 后每页只剩一个站点」，即此竞态）。 */
    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            // rename 失败（目标被占用等罕见场景）→ 退回直接覆盖
            f.writeText(text)
            tmp.delete()
        }
    }

    /** 解析并校验一份快照文本；失败/版本旧返回 null */
    private fun readFavs(text: String): List<Fav>? = try {
        val root = JSONObject(text)
        // 旧版快照缺 mk/dirDesc，直接弃用，等用户打开 App 推送 v2 快照
        if (root.optInt("v", 0) < SNAPSHOT_VERSION) null else parse(root)
    } catch (_: Exception) {
        null
    }

    /** 保存网页侧推送来的快照 */
    fun save(context: Context, json: String) {
        try {
            atomicWrite(File(context.filesDir, FILE_NAME), json)
        } catch (_: Exception) {
            // 写失败不影响主功能，小部件下次刷新仍会读到旧快照
        }
    }

    /** 读取快照并计算每个收藏站的下一班；无有效快照时返回 null（区别于「有快照但没收藏」） */
    fun rows(context: Context): List<Row>? {
        val mainText = try {
            File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()
        } catch (_: Exception) {
            null
        } ?: return null
        var favs = readFavs(mainText)
        if (favs == null) {
            // 主快照不可用（半截 JSON / 版本旧）→ 尝试上次成功解析的备份
            val goodText = try {
                File(context.filesDir, GOOD_NAME).takeIf { it.exists() }?.readText()
            } catch (_: Exception) {
                null
            }
            favs = goodText?.let { readFavs(it) }
            if (favs == null) return null
            WidgetLog.append(context, "主快照不可用，已回退到备份快照")
        } else {
            // 解析成功 → 落一份备份（原子写），供下次损坏时兜底
            try {
                atomicWrite(File(context.filesDir, GOOD_NAME), mainText)
            } catch (_: Exception) {
            }
        }
        if (favs.isEmpty()) return emptyList()

        val cal = Calendar.getInstance()
        val dayIdx = dayIndex(cal)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        // 与网页版 calcFavNext 一致：凌晨 4:30 前算作前一个运营日
        var base = hour * 60 + minute
        if (base < 270) base += 1440

        val out = ArrayList<Row>(favs.size)
        for (fav in favs) {
            var bestAbs: Int? = null
            var bestItem: Item? = null
            var bestGi = 0
            var bestNextDay = false
            for (item in fav.items) {
                val r = nextDeparture(item, dayIdx, base) ?: continue
                if (bestAbs == null || r.first < bestAbs!!) {
                    bestAbs = r.first
                    bestNextDay = r.second
                    bestItem = item
                    bestGi = r.third
                }
            }
            val item = bestItem
            val abs = bestAbs
            if (item == null || abs == null) {
                out.add(Row(fav.name, Color.GRAY, "", "", "--:--", "暂无班次", false, Int.MAX_VALUE))
            } else {
                val wait = abs - base
                // 下一班的标记（始发/快车/区间）：按「当日组 + 发车分钟」查表
                val mark = item.marks.getOrNull(bestGi)?.get(if (bestNextDay) abs - 1440 else abs) ?: ""
                out.add(
                    Row(
                        name = fav.name,
                        color = item.color,
                        lineDesc = item.dirDesc.ifEmpty { item.fallbackDesc },
                        mark = mark,
                        hhmm = formatHm(abs),
                        countdown = when {
                            bestNextDay -> "次日"
                            wait <= 0 -> "已进站"
                            wait >= 60 -> "${wait / 60} 小时 ${wait % 60} 分"
                            else -> "$wait 分钟"
                        },
                        urgent = !bestNextDay && wait <= 1,
                        absMin = abs,
                        upcoming = upcoming3(item, bestGi, base),
                        nextStation = item.nextStation
                    )
                )
            }
        }
        return out
    }

    // ───────────────────────── 解析 ─────────────────────────

    private fun parse(root: JSONObject): List<Fav> {
        val arr = root.optJSONArray("f") ?: return emptyList()
        val favs = ArrayList<Fav>(arr.length())
        for (i in 0 until arr.length()) {
            val fo = arr.optJSONObject(i) ?: continue
            val name = fo.optString("n", "")
            if (name.isEmpty()) continue
            val ia = fo.optJSONArray("it") ?: continue
            val items = ArrayList<Item>(ia.length())
            for (j in 0 until ia.length()) {
                val io = ia.optJSONObject(j) ?: continue
                val ga = io.optJSONArray("g") ?: continue
                val groups = ArrayList<IntArray>(ga.length())
                for (k in 0 until ga.length()) {
                    val ta = ga.optJSONArray(k) ?: continue
                    val times = IntArray(ta.length())
                    for (t in 0 until ta.length()) times[t] = ta.optInt(t, -1)
                    groups.add(times)
                }
                if (groups.isEmpty()) continue
                // mk：与 groups 平行的标记数组，mk[gi] = {分钟: "XX始发"}；旧快照/无标记时缺省
                val marks = ArrayList<Map<Int, String>?>(groups.size)
                val mka = io.optJSONArray("mk")
                for (k2 in 0 until groups.size) {
                    val mo = mka?.optJSONObject(k2)
                    if (mo == null) {
                        marks.add(null)
                        continue
                    }
                    val m = HashMap<Int, String>(mo.length())
                    for (key in mo.keys()) {
                        val v = mo.optString(key, "")
                        if (v.isNotEmpty()) m[key.toIntOrNull() ?: continue] = v
                    }
                    marks.add(if (m.isEmpty()) null else m)
                }
                items.add(
                    Item(
                        color = parseColor(io.optString("c", "#9AA1AF")),
                        lineId = io.optString("l", ""),
                        terminal = io.optString("t", ""),
                        loopDir = io.optString("d", ""),
                        dirDesc = io.optString("dirDesc", ""),
                        dow = io.optString("dow", ""),
                        groups = groups,
                        marks = marks,
                        nextStation = io.optString("ns", "")
                    )
                )
            }
            if (items.isNotEmpty()) favs.add(Fav(name, items))
        }
        return favs
    }

    /**
     * 颜色解析。兜底色用中灰 0xFF6C7686（此前用 Color.GRAY 太浅，白底上几乎不可见，
     * 曾被误判为「色条消失」）；解析失败记录原始值便于排查。
     */
    private fun parseColor(s: String): Int = try {
        Color.parseColor(s)
    } catch (_: Exception) {
        0xFF6C7686.toInt()
    }

    // ───────────────────── 时刻计算（镜像网页版） ─────────────────────

    /**
     * 与网页版 curGi 一致：凌晨 0:00-4:30 归入昨天，再换算成「周一=0 … 周日=6」。
     */
    private fun dayIndex(now: Calendar): Int {
        val cal = now.clone() as Calendar
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        if (h < 4 || (h == 4 && m < 30)) cal.add(Calendar.DAY_OF_MONTH, -1)
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            else -> 6 // 周日
        }
    }

    /**
     * 与网页版 dirTimes + calcNext 一致。
     * @return Triple(绝对分钟数, 是否为次日首班, 命中的日期组下标 gi)
     */
    private fun nextDeparture(item: Item, dayIdx: Int, base: Int): Triple<Int, Boolean, Int>? {
        val gi = if (item.dow.length > dayIdx && item.dow[dayIdx] in '0'..'9') {
            item.dow[dayIdx] - '0'
        } else 0
        val times = item.groups.getOrElse(gi) { item.groups[0] }
        if (times.isEmpty()) return null
        for (t in times) if (t > base) return Triple(t, false, gi)
        // 末班已过 → 次日首班（与网页版 calcNext 的 idx=-1 分支一致）
        if (base >= times[times.size - 1]) return Triple(times[0] + 1440, true, gi)
        return null
    }

    private fun formatHm(absMin: Int): String {
        val v = ((absMin % 1440) + 1440) % 1440
        return String.format("%02d:%02d", v / 60, v % 60)
    }

    /**
     * 最近 3 班（与下一班同一线路方向的当日时刻组）。
     * absMin 为发车绝对分钟（>1440 表示次日），供 Chronometer 计算实时倒计时基点。
     * 当日不足 3 班时用次日首班补齐（hhmm 加"次日"前缀）。
     */
    private fun upcoming3(item: Item, gi: Int, base: Int): List<Upcoming> {
        val times = item.groups.getOrElse(gi) { item.groups[0] }
        if (times.isEmpty()) return emptyList()

        val out = ArrayList<Upcoming>(3)
        for (t in times) {
            if (t > base) {
                out.add(Upcoming(formatHm(t), t))
                if (out.size == 3) return out
            }
        }
        var i = 0
        while (out.size < 3 && i < times.size) {
            out.add(Upcoming("次日 " + formatHm(times[i]), times[i] + 1440))
            i++
        }
        return out
    }
}
