#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
北京地铁时刻表助手 — 数据构建脚本 (v2)
数据源: Mick235711/Beijing-Subway-Tools (MIT 许可，数据来自北京地铁官网等公开来源)

从该仓库 data/beijing/*.json5 解析各线路的 timetable（站 × 方向 × 日期组），
展开 delta 行程压缩格式为完整发车时刻，按"星期几 → 日期组"映射，生成站点索引，
内嵌进前端模板，输出单文件 beijing-metro.html。

用法:
    python build_timetable.py            # 用已缓存仓库构建(若无则克隆)
    python build_timetable.py --refresh  # 强制 git pull 更新仓库后构建

依赖: json5 (pip install json5), pypinyin (可选，用于拼音搜索索引)
"""
import json
import os
import re
import subprocess
import sys
import urllib.request

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_DIR = os.path.join(BASE, "data", "raw")
TPL = os.path.join(BASE, "scripts", "app_template.html")
OUT = os.path.join(BASE, "beijing-metro.html")

REPO_URL = "https://github.com/Mick235711/Beijing-Subway-Tools.git"
REPO_DIR = os.path.join(RAW_DIR, "bjst")
BEI_DIR = os.path.join(REPO_DIR, "data", "beijing")

# 旧数据源线路名 → 新名（供前端迁移用户已收藏的方向 key）
LINE_ALIASES = {
    "1号线八通线": "1号线",
    "4号线大兴线": "4号线",
}

DEFAULT_COLOR = "#9AA3B5"
TIME_RE = re.compile(r"(\d{1,2}):(\d{2})")
SKIP_FILES = ("carriage_types.json5", "fare_rules.json5", "metadata.json5")
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}


def log(msg):
    print("[build]", msg)


def sync_repo(force):
    if not os.path.isdir(os.path.join(REPO_DIR, ".git")):
        log("克隆数据仓库 Beijing-Subway-Tools ...")
        subprocess.run(["git", "clone", "--depth", "1", REPO_URL, REPO_DIR], check=True)
    elif force:
        log("更新数据仓库 (git pull) ...")
        subprocess.run(["git", "-C", REPO_DIR, "pull", "--depth", "1"], check=True)
    else:
        log("使用已缓存的数据仓库 (--refresh 强制更新)")
    sha = subprocess.check_output(["git", "-C", REPO_DIR, "rev-parse", "HEAD"]).decode().strip()
    date = subprocess.check_output(["git", "-C", REPO_DIR, "log", "-1", "--format=%ci"]).decode().strip()
    return sha, date


def to_min(hhmm):
    m = TIME_RE.search(str(hhmm))
    if not m:
        return None
    return int(m.group(1)) * 60 + int(m.group(2))


def expand_delta(arr):
    """delta 数组解码：[n, sub] 表示 sub 重复 n 次"""
    out = []
    for item in arr or []:
        if isinstance(item, int):
            out.append(item)
        elif isinstance(item, list) and len(item) == 2 and isinstance(item[0], int) and isinstance(item[1], list):
            n, sub = item
            for _ in range(n):
                out.extend(expand_delta(sub))
    return out


def expand_schedule(schedules):
    """展开 schedule 为排序去重的绝对分钟时刻列表（凌晨 <270 已 +1440）"""
    times = []
    for seg in (schedules or []):
        if not isinstance(seg, dict):
            continue
        if "trains" in seg:
            for t in seg.get("trains", []):
                m = to_min(t)
                if m is not None:
                    times.append(m)
        elif "first_train" in seg:
            cur = to_min(seg.get("first_train"))
            if cur is None:
                continue
            times.append(cur)
            for d in expand_delta(seg.get("delta")):
                cur += d
                times.append(cur)
    return sorted(set(m + 1440 if m < 270 else m for m in times))


def filter_time(hhmm):
    """filters 里的时刻（"HH:MM"）转绝对分钟，凌晨(<270) +1440 对齐 expand_schedule"""
    m = to_min(hhmm)
    if m is None:
        return None
    return m + 1440 if m < 270 else m


def filter_hits(times, f):
    """计算 filter 命中的时刻列表（绝对分钟）。trains 直接指定；否则 first_train+skip_trains/count/until。"""
    hits = []
    if isinstance(f.get("trains"), list):
        for t in f["trains"]:
            m = filter_time(t)
            if m is not None:
                hits.append(m)
        return hits
    ft = filter_time(f.get("first_train"))
    skip = f.get("skip_trains", 0) or 0
    step = skip + 1
    cnt = f.get("count")
    until = filter_time(f.get("until"))
    start = 0
    if ft is not None:
        try:
            start = times.index(ft)
        except ValueError:
            start = next((i for i, t in enumerate(times) if t >= ft), len(times))
    i = start
    while i < len(times):
        t = times[i]
        if until is not None and t > until:
            break
        hits.append(t)
        if cnt is not None and len(hits) >= cnt:
            break
        i += step
    return hits


def apply_filters(times, filters, routes, station):
    """按 schedule filter 语义标记班次属性，返回 (term_map, start_map, fast_map, exclude)。

    - term_map: {时刻: 终点站}（区间车/回库车，ends_with）
    - start_map: {时刻: 始发站}（出库车/始发空车，starts_with）
    - fast_map: {时刻: 快车名}（跳站快车，skip 列表非空，仅停靠站）
    - exclude:  [时刻]（被快车跳过的站的快车经过时刻，需从发车时刻排除）
    """
    term_map = {}
    start_map = {}
    fast_map = {}
    exclude = set()
    tset = set(times)
    for f in (filters or []):
        if not isinstance(f, dict):
            continue
        plan = f.get("plan")
        route = (routes or {}).get(plan) or {}
        term = route.get("ends_with")
        start = route.get("starts_with")
        skip_list = route.get("skip")
        is_fast = isinstance(skip_list, list) and bool(skip_list)
        for t in filter_hits(times, f):
            if t not in tset:
                continue
            if is_fast:
                if station in skip_list:
                    exclude.add(t)   # 被跳站：快车经过不停，排除
                    continue
                fast_map[t] = plan   # 停靠站：标记快车
            if start:
                start_map[t] = start
            if term:
                term_map[t] = term
    return term_map, start_map, fast_map, sorted(exclude)


def invert_map(m):
    """{时刻: 属性} → {属性: [时刻...]}（时刻排序）"""
    out = {}
    for t, v in (m or {}).items():
        out.setdefault(v, []).append(t)
    for v in out:
        out[v].sort()
    return out


def line_color(d):
    c = d.get("color")
    if isinstance(c, str) and c.strip():
        c = c.strip()
        if not c.startswith("#"):
            c = "#" + c
        return c
    return DEFAULT_COLOR


def line_terminal(train_routes, direction, station_names):
    """方向终点站名；环线返回空串。规则: reversed=False 终点=末站, True 终点=首站"""
    if not station_names:
        return ""
    routings = train_routes.get(direction) if isinstance(train_routes, dict) else None
    if isinstance(routings, dict):
        for rname in routings:
            if "环" in str(rname) or "loop" in str(rname).lower() or "circle" in str(rname).lower():
                return ""
        reversed_ = routings.get("reversed", False)
    else:
        reversed_ = False
    return station_names[0] if reversed_ else station_names[-1]


def line_sort_key(name):
    """线路排序：1号线~19号线按数字，其余市郊/机场线按固定顺序"""
    m = re.match(r"^(\d+)号线$", name)
    if m:
        return (0, int(m.group(1)), name)
    order = {"S1线": 1, "昌平线": 2, "房山线": 3, "燕房线": 4, "亦庄线": 5,
             "亦庄T1线": 6, "西郊线": 7, "首都机场线": 8, "大兴机场线": 9}
    return (1, order.get(name, 99), name)


def direction_meta(train_routes, direction, station_names, st_idx):
    """返回 (终点站 terminal, 下一站 next)。环线终点为空、下一站首尾循环。"""
    routings = train_routes.get(direction) if isinstance(train_routes, dict) else None
    is_loop = False
    reversed_ = False
    if isinstance(routings, dict):
        for rname in routings:
            if "环" in str(rname) or "loop" in str(rname).lower() or "circle" in str(rname).lower():
                is_loop = True
        reversed_ = routings.get("reversed", False)
    n = len(station_names)
    if is_loop:
        terminal = ""
        nxt = station_names[(st_idx + 1) % n] if not reversed_ else station_names[(st_idx - 1) % n]
    else:
        terminal = station_names[0] if reversed_ else station_names[-1]
        if reversed_:
            nxt = station_names[st_idx - 1] if st_idx > 0 else ""
        else:
            nxt = station_names[st_idx + 1] if st_idx < n - 1 else ""
    return terminal, nxt


def clean_name(name):
    """去掉站名尾部的线路后缀括号（如 '大钟寺(13号线)' -> '大钟寺'），合并换乘同名站"""
    return re.sub(r"[（(][^）)]*[）)]$", "", str(name)).strip()


def pinyin_initial(text):
    try:
        from pypinyin import lazy_pinyin
        return "".join([p[0] for p in lazy_pinyin(text) if p and p[0].isalpha()]).lower()
    except Exception:
        return ""


def validate(stations, lines):
    """全量数据校验：时刻非空/严格递增、首班≤末班、班次数合理、站名/颜色合法、无重复方向。"""
    total_recs = 0
    total_times = 0
    problems = []
    seen = set()
    color_re = re.compile(r"^#[0-9a-fA-F]{6}$")
    for st, recs in stations.items():
        if not st:
            problems.append("空站名")
        for rec in recs:
            total_recs += 1
            key = (st, rec.get("l"), rec.get("d"))
            if key in seen:
                problems.append("重复方向: %s %s %s" % key)
            seen.add(key)
            for gi, grp in enumerate(rec.get("g") or []):
                times = expand_schedule(grp)
                total_times += len(times)
                if not times:
                    problems.append("空时刻: %s %s %s 组%d" % (st, rec.get("l"), rec.get("d"), gi))
                    continue
                for i in range(1, len(times)):
                    if times[i] <= times[i - 1]:
                        problems.append("时刻非递增: %s %s %s 组%d" % (st, rec.get("l"), rec.get("d"), gi))
                        break
                if len(times) < 10 or len(times) > 600:
                    problems.append("班次数异常(%d): %s %s %s" % (len(times), st, rec.get("l"), rec.get("d")))
    for ln, c in lines.items():
        if not isinstance(c, str) or not color_re.match(c):
            problems.append("颜色非法: %s %r" % (ln, c))
    log("校验完成: %d 方向, %d 时刻, %d 问题" % (total_recs, total_times, len(problems)))
    for p in problems[:30]:
        log("  [校验] " + p)
    if len(problems) > 30:
        log("  ... 共 %d 个问题" % len(problems))
    return problems


def build_index():
    files = [f for f in os.listdir(BEI_DIR) if f.endswith(".json5") and f not in SKIP_FILES]
    lines = {}      # 线路名 -> 颜色
    stations = {}   # 站名 -> [{l,d,t,g,dow}, ...]
    fast_skip = {}  # {线路|方向|快车名: [跳过站列表]} 供前端展示快车跳站
    for f in sorted(files):
        try:
            import json5
            d = json5.load(open(os.path.join(BEI_DIR, f), encoding="utf-8"))
        except Exception as e:
            log("解析 %s 失败: %s" % (f, e))
            continue
        name = d.get("name") or f
        lines[name] = line_color(d)
        sn = [clean_name(s) for s in (d.get("station_names") or [s.get("name") for s in (d.get("stations") or [])])]
        dg = d.get("date_groups", {})
        tr = d.get("train_routes", {})
        # 收集快车跳站列表（全局）
        if isinstance(tr, dict):
            for direction, routings in tr.items():
                if not isinstance(routings, dict):
                    continue
                for rname, r in routings.items():
                    if isinstance(r, dict) and r.get("skip"):
                        fast_skip["%s|%s|%s" % (name, direction, rname)] = r["skip"]
        # timetable key 也归一化（合并同名换乘站）
        tt = {}
        for k, v in (d.get("timetable") or {}).items():
            tt[clean_name(k)] = v
        for st_idx, st in enumerate(sn):
            if st not in tt:
                continue
            for direction, groups in tt[st].items():
                if not isinstance(groups, dict):
                    continue
                routes = tr.get(direction) if isinstance(tr, dict) else None
                group_times = {}   # 展开结果（用于去重 key 和 dow 判断）
                group_raw = {}     # 原始 delta schedule（存储用，压缩体积）
                group_term = {}    # {gname: {时刻: 终点站}} 区间车
                group_start = {}   # {gname: {时刻: 始发站}} 始发车
                group_fast = {}    # {gname: {时刻: 快车名}} 快车
                group_excl = {}    # {gname: [排除时刻]} 被跳站快车经过时刻
                for gname, gd in groups.items():
                    if not isinstance(gd, dict):
                        continue
                    arr = expand_schedule(gd.get("schedule"))
                    if arr:
                        group_times[gname] = arr
                        group_raw[gname] = gd.get("schedule")
                        tm, sm, fm, ex = apply_filters(arr, gd.get("filters"), routes, st)
                        group_term[gname] = tm
                        group_start[gname] = sm
                        group_fast[gname] = fm
                        group_excl[gname] = ex
                if not group_times:
                    continue
                # 去重时刻数组 -> g 列表（gl 为对应日期组名标签）
                gnames = list(groups.keys())
                uniq, idx_map, gl = [], {}, []
                xt, xs, xf, xr = [], [], [], []
                for gname in gnames:
                    key = tuple(group_times.get(gname, ()))
                    if not key:
                        continue
                    if key not in idx_map:
                        idx_map[key] = len(uniq)
                        uniq.append(group_raw[gname])   # 存 delta（前端加载后展开）
                        gl.append(gname)
                        grp_xt = invert_map(group_term.get(gname))
                        grp_xs = invert_map(group_start.get(gname))
                        grp_xf = invert_map(group_fast.get(gname))
                        xt.append(grp_xt if grp_xt else None)
                        xs.append(grp_xs if grp_xs else None)
                        xf.append(grp_xf if grp_xf else None)
                        xr.append(group_excl.get(gname) or None)
                if not uniq:
                    continue
                # dow: 周一(1)..周日(7) 各指向 g 索引
                dow = ""
                for day in range(1, 8):
                    gi = 0
                    for gname in gnames:
                        gd2 = dg.get(gname)
                        wds = gd2.get("weekday", []) if isinstance(gd2, dict) else []
                        if day in wds:
                            gi = idx_map.get(tuple(group_times.get(gname, ())), 0)
                            break
                    dow += str(gi)
                terminal, nxt = direction_meta(tr, direction, sn, st_idx)
                rec = {"l": name, "d": direction, "t": terminal, "n": nxt, "g": uniq, "gl": gl, "dow": dow}
                if any(x is not None for x in xt):
                    rec["xt"] = xt
                if any(x is not None for x in xs):
                    rec["xs"] = xs
                if any(x is not None for x in xf):
                    rec["xf"] = xf
                if any(x is not None for x in xr):
                    rec["xr"] = xr
                recs = stations.setdefault(st, [])
                recs.append(rec)
    # 线路按号排序
    ordered_lines = {}
    for ln in sorted(lines.keys(), key=line_sort_key):
        ordered_lines[ln] = lines[ln]
    return ordered_lines, stations, fast_skip


def get_bj_commit():
    """通过 GitHub API 获取 data/beijing 目录最近一次提交的 sha 和日期。
    注意：不能用本地 git log -- data/beijing，因为浅克隆历史截断会误判（把 HEAD 当作路径最新提交）。"""
    url = "https://api.github.com/repos/Mick235711/Beijing-Subway-Tools/commits?path=data/beijing&per_page=1"
    req = urllib.request.Request(url, headers={**UA, "Accept": "application/vnd.github+json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            d = json.load(resp)
            c = d[0]
            return str(c["sha"]), str(c["commit"]["author"]["date"])[:10]
    except Exception as e:
        log("获取北京数据提交信息失败(使用 HEAD 兜底): %s" % e)
        return "", ""


def build():
    os.makedirs(RAW_DIR, exist_ok=True)
    force = "--refresh" in sys.argv

    if not os.path.isdir(BEI_DIR) or force:
        sha, date = sync_repo(force)
    else:
        sha = subprocess.check_output(["git", "-C", REPO_DIR, "rev-parse", "HEAD"]).decode().strip()
        date = subprocess.check_output(["git", "-C", REPO_DIR, "log", "-1", "--format=%ci"]).decode().strip()

    log("数据仓库 HEAD: %s (%s)" % (sha[:12], date))

    # 北京数据目录最近一次提交（精确检查更新用；浅克隆 git log 不可靠，走 GitHub API）
    bj_sha, bj_date = get_bj_commit()
    log("北京数据最近提交: %s (%s)" % (bj_sha[:12] if bj_sha else "-", bj_date or "-"))

    lines, stations, fast_skip = build_index()
    log("解析完成: %d 条线路, %d 个站点, %d 个快车交路" % (len(lines), len(stations), len(fast_skip)))

    # 全量数据校验（时刻非空/递增、班次数、站名、颜色、重复方向）
    validate(stations, lines)

    # 拼音首字母索引
    initial = {}
    for st in stations:
        ini = pinyin_initial(st)
        if ini:
            initial[st] = ini
    log("拼音索引覆盖 %d/%d 站" % (len(initial), len(stations)))

    meta = {
        "version": "3.1",
        "fmt": 2,
        "updated": bj_date or (date.split(" ")[0] if date else ""),
        "commitSha": sha,
        "bjSha": bj_sha,
        "lines": len(lines),
        "stations": len(stations),
        "source": "Beijing-Subway-Tools (MIT)",
        "lineAliases": LINE_ALIASES,
        "fastSkip": fast_skip,
    }

    payload = {"meta": meta, "lines": lines, "stations": stations, "initial": initial}
    json_str = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    json_str = json_str.replace("<", "\\u003c")

    log("数据 JSON 大小: %.2f MB" % (len(json_str.encode("utf-8")) / 1048576))

    tpl = open(TPL, encoding="utf-8").read()
    assert "__DATA__" in tpl, "模板缺少 __DATA__ 占位符"
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(tpl.replace("__DATA__", json_str))

    log("生成完成: %s (%.2f MB)" % (OUT, os.path.getsize(OUT) / 1048576))

    for sample in ("苹果园", "西直门", "国贸", "东四十条"):
        if sample in stations:
            rec = stations[sample][0]
            g0 = rec["g"][0] if rec.get("g") else []
            log("抽查 %s: %s %s | 首班 %s 末班 %s | 组%d 班%d" % (
                sample, rec["l"], rec["d"],
                _fmt(g0[0]) if g0 else "-", _fmt(g0[-1]) if g0 else "-",
                len(rec.get("g") or []), len(g0)))


def _fmt(m):
    m %= 1440
    return "%02d:%02d" % (m // 60, m % 60)


if __name__ == "__main__":
    build()
