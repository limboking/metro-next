# -*- coding: utf-8 -*-
"""
生成三个尺寸各自的桌面小部件预览图（android:previewImage）。

## 演进史（别走回头路）
1. drawable-nodpi 按 2x 画全尺寸 → 小米上 4×4 预览"看着很小"（比例不匹配被 fit-inside）。
2. drawable-xxxhdpi 4x 全尺寸 → 同样只有 4×4 异常。
3. 三尺寸共用一张 1-cell 缩略图（mdpi 70×70）→ 三张预览完全一样且比例全错
   （实测：小米 launcher 会把 previewImage 拉伸铺满 widget 的 footprint 区域，
    图片宽高比与 footprint 不符时必然变形）。

## 现行方案（v1.0.6 定尺寸 / v1.0.25 重绘内容）
实测结论：小米选择器的预览槽是**按高度截断的扁长区域**，previewImage 以
fit-center 显示（不拉伸到 footprint）——
- 竖高或正方形图（v1.0.3 4x / v1.0.5 1:1）一律被缩成「非常小一块」；
- 2×2 用 146dp 方图能完整显示（预览槽高约 2 格）；
- 4×2 / 4×4 统一 2:1 宽幅（294×146dp）：4×2 画 3 行列表，4×4 画 2 列 × 3 行网格。
previewLayout 已从 info xml 移除（MIUI 不使用，徒增歧义）。

v1.0.25：布局全面改版（小米小部件规范），预览图同步重绘：
- 圆角 16dp → 20dp，四周安全区 10/4dp → 统一 16dp；
- 内容区右侧额外让出 28dp（布局里 inner LinearLayout 的 paddingEnd），翻页列 24dp；
- 2×2 的「时刻 · 倒计时」由合并一行改为左右分列；新增「下一站」行；
- 字号对齐新布局（站名 15sp / 倒计时 17sp / 列表倒计时 18sp）。
"""
import math
import os
from PIL import Image, ImageDraw, ImageFont

OUT_DIR = r"D:/WorkBuddy_Save/手机小程序开发/android/app/src/main/res/drawable-nodpi"
D = 2  # 像素倍率：1dp = 2px
FONT_REG = "C:/Windows/Fonts/msyh.ttc"
FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"

BG = (255, 255, 255, 255)
TXT_PRIMARY = (0x16, 0x1A, 0x24, 255)
TXT_SECOND = (0x59, 0x61, 0x6F, 255)
TXT_TERT = (0x9A, 0xA1, 0xAF, 255)
ACCENT = (0x28, 0x50, 0xE6, 255)
DIVIDER = (0xF0, 0xF2, 0xF5, 255)

CORNER = 20 * D      # widget_bg.xml 圆角
PAD = 10 * D         # 根布局横向安全区（与 widget_*.xml 一致）
PAD_END_IN = 30 * D  # 内容列额外右侧内边距（给翻页列让位）
PAGER_W = 26 * D     # 翻页列宽（pager_box）

# 内容列右边界（模块级变量，各绘制函数引用）
W_RIGHT = 0


def f(size_sp, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT_REG, size_sp * D)


def trunc(draw, text, font, max_w):
    """按最大宽度截断（加 …）；预览图是静态位图，必须自己处理挤压"""
    if draw.textlength(text, font=font) <= max_w:
        return text
    while text and draw.textlength(text + "…", font=font) > max_w:
        text = text[:-1]
    return (text + "…") if text else ""


def rounded_rect_alpha(size, radius, color):
    img = Image.new("RGBA", size, (0, 0, 0, 0))
    ImageDraw.Draw(img).rounded_rectangle(
        [0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=color
    )
    return img


def chevron(draw, cx, cy, up=True, size=7 * D, width=2 * D):
    if up:
        pts = [(cx - size, cy + size * 0.5), (cx, cy - size * 0.5), (cx + size, cy + size * 0.5)]
    else:
        pts = [(cx - size, cy - size * 0.5), (cx, cy + size * 0.5), (cx + size, cy - size * 0.5)]
    draw.line(pts, fill=TXT_TERT, width=width, joint="curve")


def refresh_icon(draw, cx, cy, r=6 * D, width=2 * D):
    """环形刷新箭头（与 ic_refresh.xml 同构）：圆弧留右上缺口 + 切向箭头"""
    start, end = -20, 250   # PIL 角度：0°=3 点钟方向，顺时针
    draw.arc([cx - r, cy - r, cx + r, cy + r], start=start, end=end,
             fill=TXT_TERT, width=width)
    th = math.radians(end)
    px, py = cx + r * math.cos(th), cy + r * math.sin(th)
    dx, dy = -math.sin(th), math.cos(th)      # 顺时针切线方向
    L = 4 * D
    for ang in (25, -25):
        a = math.radians(ang)
        bx = dx * math.cos(a) - dy * math.sin(a)
        by = dx * math.sin(a) + dy * math.cos(a)
        draw.line([(px, py), (px - bx * L, py - by * L)], fill=TXT_TERT, width=width)


def draw_pager(draw, W, H, page_text):
    """右侧竖排翻页列：刷新(26dp)/上箭头(26dp)/页码(16dp)/下箭头(26dp)，整体垂直居中。
    列宽 24dp，右边缘与内容区右内边距对齐（根布局 paddingEnd=16dp）。"""
    box_h = (26 + 26 + 16 + 26) * D
    top = (H - box_h) // 2
    cx = W - PAD - PAGER_W // 2
    refresh_icon(draw, cx, top + 13 * D)
    chevron(draw, cx, top + 26 * D + 13 * D, up=True)
    draw.text((cx, top + 52 * D + 8 * D), page_text, font=f(11),
              fill=TXT_TERT, anchor="mm")
    chevron(draw, cx, top + (52 + 16) * D + 13 * D, up=False)


def draw_list_row(draw, ry, row_h, name, line, cd, hhmm, color):
    """一行（与 widget_list3.xml 一致）：[4dp 色条] 站名14sp粗+线路11sp灰 │ 倒计时18sp+时刻11sp"""
    global W_RIGHT
    bar_w, bar_h = 4 * D, 18 * D
    by = ry + (row_h - bar_h) // 2
    draw.rounded_rectangle([PAD, by, PAD + bar_w, by + bar_h],
                           radius=bar_w // 2, fill=color)
    tx = PAD + bar_w + 8 * D
    cy = ry + row_h // 2
    gap = 1 * D
    nh, lh = 14 * D, 11 * D
    ty = cy - (nh + gap + lh) // 2
    draw.text((tx, ty + nh // 2), name, font=f(13, bold=True), fill=TXT_PRIMARY, anchor="lm")
    draw.text((tx, ty + nh + gap + lh // 2), line, font=f(10), fill=TXT_TERT, anchor="lm")

    mh, th = 18 * D, 11 * D
    ty2 = cy - (mh + gap + th) // 2
    draw.text((W_RIGHT, ty2 + mh // 2), cd, font=f(17, bold=True), fill=ACCENT, anchor="rm")
    draw.text((W_RIGHT, ty2 + mh + gap + th // 2), hhmm, font=f(10), fill=TXT_TERT, anchor="rm")


def gen_list_preview(path, H_dp, rows, page_text):
    """4×2 列表预览：rows = [(站名, 线路·方向, 倒计时, 时刻, 线路色)]，行高均分"""
    global W_RIGHT
    W, H = 294 * D, H_dp * D
    W_RIGHT = W - PAD - PAD_END_IN
    img = rounded_rect_alpha((W, H), CORNER, BG)
    draw = ImageDraw.Draw(img)

    n = len(rows)
    row_h = (H - 2 * PAD - (n - 1) * 1 * D) // n      # 行均分（weight=1 效果）
    for i, (name, line, cd, hhmm, color) in enumerate(rows):
        ry = PAD + i * (row_h + 1 * D)
        draw_list_row(draw, ry, row_h, name, line, cd, hhmm, color)
        if i < n - 1:
            dy = ry + row_h
            draw.rectangle([PAD, dy, W_RIGHT, dy + 1 * D - 1], fill=DIVIDER)

    draw_pager(draw, W, H, page_text)
    img.save(path)


def gen_small_preview(path):
    """2×2 预览：160dp 方图（320×320px）。
    画布取真机 2×2 的常见实际占位（约 160dp，大于 minWidth=110dp）——
    用 146dp 画会因为内容区被安全区+翻页列挤压而截断，与真机表现不符。
    内容与 widget_small.xml 一致：
      色条+站名 / 线路·方向 / 下一站 / 分割线 / 最近 3 班（「时刻 · 距发车」合并单行，
      首班强调色、后两班次级灰——2×2 宽度放不下分列，v1.0.25 实测回退）。"""
    W = H = 160 * D
    img = rounded_rect_alpha((W, H), CORNER, BG)
    draw = ImageDraw.Draw(img)
    x = PAD
    color = (0xF5, 0xD8, 0x00, 255)

    # ① 色条 + 站名（色条 4×18dp，站名 15sp 粗，marginStart 8dp）
    y = PAD
    draw.rounded_rectangle([x, y, x + 4 * D, y + 18 * D], radius=2 * D, fill=color)
    draw.text((x + 4 * D + 8 * D, y + 9 * D), "呼家楼",
              font=f(14, bold=True), fill=TXT_PRIMARY, anchor="lm")
    # ② 线路 · 方向（11.5sp，marginTop 3dp）
    y += 18 * D + 3 * D
    draw.text((x, y + 6 * D), "6号线 · 开往 潞城", font=f(10), fill=TXT_TERT, anchor="lm")
    # ③ 下一站（11.5sp，marginTop 2dp）
    y += 12 * D + 2 * D
    draw.text((x, y + 6 * D), "下一站 十里堡", font=f(10), fill=TXT_TERT, anchor="lm")
    # ④ 分割线（marginTop 8dp）
    y += 12 * D + 8 * D
    draw.rectangle([x, y, W - PAD - PAGER_W - 4 * D, y + 1 * D - 1], fill=DIVIDER)

    # ⑤ 最近 3 班：三行均分剩余高度，合并单行（与布局一致，超出翻页列宽度则截断）
    top = y + 1 * D
    seg = (H - PAD - top) // 3
    text_right = W - PAD - PAGER_W - 2 * D          # 内容可用右界（给翻页列让位）
    rows = [("15:04 · 3 分钟", 16, True, ACCENT),
            ("15:11 · 10 分钟", 12, False, TXT_SECOND),
            ("15:19 · 18 分钟", 12, False, TXT_SECOND)]
    for i, (txt, fs, bold, col) in enumerate(rows):
        ft = f(fs, bold=bold)
        draw.text((x, top + i * seg + seg // 2),
                  trunc(draw, txt, ft, text_right - x), font=ft, fill=col, anchor="lm")

    draw_pager(draw, W, H, "1/7")
    img.save(path)


def gen_large_preview(path):
    """4×4 预览：2:1 宽幅 + 2 列 × 3 行网格（6 站）。
    关键：不能用 1:1 方图——小米预览槽按高度截断，方图必被缩小。"""
    W, H = 294 * D, 146 * D
    img = rounded_rect_alpha((W, H), CORNER, BG)
    draw = ImageDraw.Draw(img)

    stations = [
        ("西二旗", "13号线 · 开往 西土城", "3 分钟", YELLOW),
        ("国贸", "10号线 · 开往 车道沟", "6 分钟", BLUE),
        ("西直门", "2号线 · 内环", "9 分钟", NAVY),
        ("东单", "1号线 · 环球度假区", "12 分钟", RED),
        ("北土城", "8号线 · 开往 朱辛庄", "15 分钟", GREEN),
        ("望京东", "15号线 · 开往 俸伯", "18 分钟", PURPLE),
    ]
    cols, rows_n = 2, 3
    pad_x, pad_y = PAD, 6 * D
    col_gap = 24 * D
    grid_right = W - PAD - PAD_END_IN                 # 网格区右边界（给翻页列让位）
    cell_w = (grid_right - pad_x - col_gap) // 2
    cell_h = (H - 2 * pad_y) // rows_n

    for idx, (name, line, cd, color) in enumerate(stations):
        r, c = idx // cols, idx % cols
        x0 = pad_x + c * (cell_w + col_gap)
        y0 = pad_y + r * cell_h
        # 色条（两行文字块左侧）
        bar_w, bar_h = 4 * D, 22 * D
        by = y0 + (cell_h - bar_h) // 2
        draw.rounded_rectangle([x0, by, x0 + bar_w, by + bar_h], radius=bar_w // 2, fill=color)
        tx = x0 + bar_w + 7 * D
        # 第一行：站名（左） + 倒计时（右）
        ny = y0 + cell_h // 2 - 8 * D
        draw.text((tx, ny), name, font=f(12, bold=True), fill=TXT_PRIMARY, anchor="lm")
        draw.text((x0 + cell_w - 4 * D, ny), cd, font=f(12, bold=True), fill=ACCENT, anchor="rm")
        # 第二行：线路 · 方向
        draw.text((tx, ny + 13 * D), line, font=f(9), fill=TXT_TERT, anchor="lm")

    for r in range(rows_n - 1):
        dy = pad_y + (r + 1) * cell_h
        draw.rectangle([pad_x, dy, grid_right, dy + D - 1], fill=DIVIDER)
    # 列间竖分割线（加粗到 2px，强化「双列网格」骨架，与 4×2 单列列表一眼区分）
    vx = pad_x + cell_w + col_gap // 2
    draw.rectangle([vx, pad_y + 4 * D, vx + 2 * D - 1, H - pad_y - 4 * D], fill=DIVIDER)

    draw_pager(draw, W, H, "1/2")
    img.save(path)


YELLOW = (0xF5, 0xD8, 0x00, 255)   # 13号线
BLUE = (0x00, 0x9A, 0xD8, 255)     # 10号线 (示意)
NAVY = (0x00, 0x60, 0xA8, 255)     # 2号线 (示意)
RED = (0xC2, 0x3A, 0x30, 255)      # 1号线 (示意)
GREEN = (0x00, 0x9E, 0x60, 255)    # 8号线 (示意)
PURPLE = (0x60, 0x2D, 0x86, 255)   # 15号线 (示意)

if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)

    # 2×2 预览（146dp 方图，单站卡片）
    p = os.path.join(OUT_DIR, "widget_preview_small.png")
    gen_small_preview(p)
    print("small ", Image.open(p).size, os.path.getsize(p), "bytes")

    # 4×2 预览（2:1，3 行列表）
    p = os.path.join(OUT_DIR, "widget_preview_medium.png")
    gen_list_preview(p, 146, [
        ("西二旗", "13号线 · 开往 西土城", "3 分钟", "14:32", YELLOW),
        ("国贸", "10号线 · 开往 车道沟", "6 分钟", "14:35", BLUE),
        ("西直门", "2号线 · 内环", "9 分钟", "14:38", NAVY),
    ], "1/3")
    print("medium", Image.open(p).size, os.path.getsize(p), "bytes")

    # 4×4 预览（2:1，2 列 × 3 行网格 = 6 站）
    p = os.path.join(OUT_DIR, "widget_preview_large.png")
    gen_large_preview(p)
    print("large ", Image.open(p).size, os.path.getsize(p), "bytes")
