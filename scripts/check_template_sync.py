#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构建前一致性校验：网页模板 ↔ 产物 ↔ 原生快照契约。

背景（v1.0.30 事故复盘）：`scripts/app_template.html` 一度是旧副本，而
`beijing-metro.html` 由管线从该模板重建，于是 v1.0.20~v1.0.24 的修复被静默回退——
其中「小部件 v2 快照构建器」丢失后，原生侧按 v<2 弃用快照，小部件永远空态。
这类"模板/产物漂移"没有任何编译期报错，必须在此显式拦截。

校验项：
  1. 模板去掉 __DATA__ / 产物去掉内嵌数据后，其余内容必须逐字一致
  2. 产物中的快照版本号必须 == 原生 WidgetData.SNAPSHOT_VERSION
     （网页产出低于原生要求 → 快照被丢弃 → 小部件空态）
用法：python scripts/check_template_sync.py   （退出码 0=通过，1=不一致）
"""
import os
import re
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TPL = os.path.join(BASE, "scripts", "app_template.html")
ART = os.path.join(BASE, "beijing-metro.html")
KT = os.path.join(BASE, "android", "app", "src", "main", "java",
                  "com", "metronext", "metro", "widget", "WidgetData.kt")

DATA_RE = re.compile(r'<script id="metro-data" type="application/json">.*?</script>', re.S)


def strip_data(text):
    return DATA_RE.sub("<DATA/>", text)


def main():
    ok = True
    tpl = open(TPL, encoding="utf-8").read()
    art = open(ART, encoding="utf-8").read()

    # 1) 模板（含 __DATA__ 占位）↔ 产物（含真实数据）
    a = strip_data(tpl).replace("__DATA__", "<DATA/>")
    b = strip_data(art)
    if a != b:
        ok = False
        print("[check] ✗ 模板与产物不一致：scripts/app_template.html ≠ beijing-metro.html")
        import difflib
        diff = [l for l in difflib.unified_diff(a.splitlines(), b.splitlines(),
                                                "模板", "产物", lineterm="", n=0)][2:]
        print("[check]   差异行数 %d（前 10 行）：" % len(diff))
        for l in diff[:10]:
            print("[check]   " + l[:120])
        print("[check]   → 修正：python scripts/build_timetable.py  或修复模板后重跑")
    else:
        print("[check] ✓ 模板与产物一致（除内嵌数据）")

    # 2) 快照版本契约：网页产出版本 ≥ 原生要求版本
    m_tpl = re.search(r'var snap = \{ v: (\d+)', art)
    m_kt = re.search(r'SNAPSHOT_VERSION\s*=\s*(\d+)', open(KT, encoding="utf-8").read())
    web_ver = int(m_tpl.group(1)) if m_tpl else None
    native_ver = int(m_kt.group(1)) if m_kt else None
    if web_ver is None or native_ver is None:
        ok = False
        print("[check] ✗ 无法读取快照版本：网页=%s 原生=%s" % (web_ver, native_ver))
    elif web_ver != native_ver:
        ok = False
        print("[check] ✗ 快照版本不匹配：网页产出 v%s ≠ 原生要求 v%s"
              "（原生会弃用快照 → 小部件空态）" % (web_ver, native_ver))
    else:
        print("[check] ✓ 快照版本一致：v%s（网页产出 = 原生要求）" % web_ver)

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
