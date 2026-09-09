# -*- coding: utf-8 -*-
"""
生成 widget_list6.xml（4×4，六行）。

4×2 与 4×4 的行结构必须逐字段一致，否则会出现「4×2 正常、4×4 异常」这类
只在一个尺寸复现的 bug。所以 4×4 不由手工维护，而是从 widget_list3.xml
复制 row1 块重复 6 次生成。

改行结构时：改 widget_list3.xml → 跑本脚本 → 4×4 自动同步。

用法：
    python scripts/gen_widget_list6.py
"""
import io
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "android", "app", "src", "main", "res", "layout", "widget_list3.xml")
DST = os.path.join(ROOT, "android", "app", "src", "main", "res", "layout", "widget_list6.xml")

src = io.open(SRC, encoding="utf-8").read()

# 行块：从 row1 的 <LinearLayout> 起，按嵌套深度取到它闭合为止。
# 不能用缩进判断——内层列的结束标签缩进与外层相同。
anchor = '        <LinearLayout\n            android:id="@+id/row1"'
start = src.find(anchor)
if start < 0:
    raise SystemExit("row1 anchor not found in widget_list3.xml")

depth = 0
acc = []
for ln in src[start:].split("\n"):
    acc.append(ln)
    depth += ln.count("<LinearLayout") - ln.count("</LinearLayout>") + \
        ln.count("<FrameLayout") - ln.count("</FrameLayout>")
    if depth == 0 and len(acc) > 1:
        break
row1 = "\n".join(acc) + "\n"

# 分割线块
dk = src.find('android:id="@+id/div1"')
if dk < 0:
    raise SystemExit("div1 anchor not found in widget_list3.xml")
# 块起点：id 锚点前最近的空行（布局中块之间以空行分隔），
# 不再假定分割线元素标签名（曾用 <View>，RemoteViews 白名单不允许，已改 <LinearLayout>）
ds = src.rfind("\n\n", 0, dk) + 2
de = src.find("/>", dk) + 3
div1 = src[ds:de]

# 预览用示例数据（运行时会被真实收藏覆盖）
SAMPLE = [
    ("西二旗", "13号线 · 开往 西土城", "3 分钟", "14:32"),
    ("国贸", "10号线 · 内环", "6 分钟", "14:35"),
    ("西直门", "2号线 · 内环", "9 分钟", "14:38"),
    ("东单", "1号线 · 开往 环球度假区", "12 分钟", "14:41"),
    ("北土城", "8号线 · 开往 朱辛庄", "15 分钟", "14:44"),
    ("望京东", "15号线 · 开往 俸伯", "18 分钟", "14:47"),
]


def row_for(i):
    name, line, cd, hm = SAMPLE[i - 1]
    r = row1.replace("row1", "row%d" % i)
    r = r.replace('android:text="西二旗"', 'android:text="%s"' % name)
    r = r.replace('android:text="13号线 · 开往 西土城"', 'android:text="%s"' % line)
    r = r.replace('android:text="3 分钟"', 'android:text="%s"' % cd)
    r = r.replace('android:text="14:32"', 'android:text="%s"' % hm)
    return r


body = ""
for i in range(1, 7):
    body += row_for(i)
    if i < 6:
        body += div1.replace("div1", "div%d" % i)

pager_start = src.index('    <LinearLayout\n        android:id="@+id/pager_box"')
header = src[:start]
# 内容列的闭合标签：pager_box 之前最后一个「    </LinearLayout>」就是
# 包住所有行的内层内容列的收尾——header 只到 row1 之前，body 只有行与分割线，
# 这一层闭合必须单独补上（v1.0.25 实测漏掉会导致 list6 XML 解析失败、4×4 无法载入）。
close_start = src.rfind("    </LinearLayout>", 0, pager_start)
if close_start < 0:
    raise SystemExit("content-column closer not found in widget_list3.xml")
closer = src[close_start:pager_start]
pager = src[pager_start:]

header = header.replace(
    "  4×2 三站小部件（v1.0.25 HyperOS 规范版）",
    "  4×4 六站小部件（v1.0.25 HyperOS 规范版）",
)
header = header.replace(
    "不足一页时运行时 GONE）。",
    "不足一页时运行时 GONE）。\n"
    "  本文件由 scripts/gen_widget_list6.py 生成，行结构与 widget_list3.xml 必须一致——\n"
    "  改行结构请改 4×2 布局后重跑脚本，不要手改本文件。",
)
pager = pager.replace('android:text="1/3"', 'android:text="1/2"')

out = header + body + closer + pager

# 写盘前自检：开闭标签数量必须配平，防止再生成结构损坏的布局。
# 注意：自闭合标签（如 1dp 分割线 <LinearLayout ... />）既算开也算闭，
# 否则自 v1.0.28 改用 LinearLayout 分割线起会误报 unbalanced。
import re as _re


def _opens(s, tag):
    all_open = s.count("<" + tag)
    selfclosing = len(_re.findall(r"<%s\b[^>]*?/>" % tag, s, _re.S))
    return all_open - selfclosing


opens = _opens(out, "LinearLayout") + _opens(out, "FrameLayout")
closes = out.count("</LinearLayout>") + out.count("</FrameLayout>")
if opens != closes:
    raise SystemExit("layout unbalanced: %d opens vs %d closes" % (opens, closes))

io.open(DST, "w", encoding="utf-8").write(out)

print("written:", DST)
print("rows:", out.count('android:id="@+id/row'), "divs:", out.count('android:id="@+id/div'),
      "cd_d:", out.count("_cd_d"))
