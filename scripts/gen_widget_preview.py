# -*- coding: utf-8 -*-
"""
生成三个尺寸各自的桌面小部件预览图（android:previewImage）。

## 演进史（别走回头路）
1. drawable-nodpi 按 2x 画全尺寸 → 小米上 4×4 预览"看着很小"（比例不匹配被 fit-inside）。
2. drawable-xxxhdpi 4x 全尺寸 → 同样只有 4×4 异常。
3. 三尺寸共用一张 1-cell 缩略图（mdpi 70×70）→ 三张预览完全一样且比例全错
   （实测：小米 launcher 会把 previewImage 拉伸铺满 widget 的 footprint 区域，
    图片宽高比与 footprint 不符时必然变形）。

## 现行方案（v1.0.6）
四轮实测结论：小米选择器的预览槽是**按高度截断的扁长区域**，previewImage 以
fit-center 显示（不拉伸到 footprint）——
- 竖高或正方形图（v1.0.3 4x / v1.0.5 1:1）一律被缩成「非常小一块」；
- 2:1 宽图（v1.0.5 的 4×2 预览）能铺满显示、效果正常。
因此 **所有预览图统一 2:1 宽幅**（588×292 px，drawable-nodpi）：
  small  → 单站卡片居中
  medium → 3 行列表
  large  → 2 列 × 3 行网格（展示 6 站容量）
previewLayout 已从 info xml 移除（MIUI 不使用，徒增歧义）。
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

CORNER = 16 * D
PAGER_W = 26 * D        # 与新布局一致：翻页列 26dp
PAD_START = 10 * D      # 根布局 paddingStart 10dp
PAD_END = 4 * D


def f(size_sp, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT_REG, size_sp * D)


def rounded_rect_alpha(size, radius, color):
    img = Image.new("RGBA", size, (0, 0, 0, 0))
    ImageDraw.Draw(img).rounded_rectangle(
        [0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=color
    )
    return img


def chevron(draw, cx, cy, up=True, size=8 * D, width=2 * D):
    if up:
        pts = [(cx - size, cy + size * 0.5), (cx, cy - size * 0.5), (cx + size, cy + size * 0.5)]
    else:
        pts = [(cx - size, cy - size * 0.5), (cx, cy + size * 0.5), (cx + size, cy - 0 + size * 0.5)]
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


def draw_pager(draw, W, H, page_text, refresh=True):
    """右侧竖排：v1.0.19 起三尺寸统一为 刷新(30dp)/上箭头(30dp)/页码(16dp)/下箭头(30dp)。
    refresh=False 保留兼容（旧预览形态）。整体垂直居中（与布局一致）。"""
    if refresh:
        box_h = (30 + 30 + 16 + 30) * D
        top = (H - box_h) // 2
        cx = W - PAGER_W // 2
        refresh_icon(draw, cx, top + 15 * D)
        chevron(draw, cx, top + 30 * D + 15 * D, up=True)
        draw.text((cx, top + 60 * D + 16 * D // 2), page_text, font=f(10),
                  fill=TXT_TERT, anchor="mm")
        chevron(draw, cx, top + (60 + 16) * D + 30 * D // 2, up=False)
    else:
        box_h = (30 + 16 + 30) * D
        top = (H - box_h) // 2
        cx = W - PAGER_W // 2
        chevron(draw, cx, top + 30 * D // 2, up=True)
        draw.text((cx, top + 30 * D + 16 * D // 2), page_text, font=f(10),
                  fill=TXT_TERT, anchor="mm")
        chevron(draw, cx, top + (30 + 16) * D + 30 * D // 2, up=False)


def draw_list_row(draw, ry, row_h, name, line, cd, hhmm, color):
    """一行（与 widget_list*.xml 样式一致）：[4dp 色条] 站名13sp粗+线路10sp灰 | 倒计时17sp+时刻10sp"""
    bar_w, bar_h = 4 * D, 26 * D
    by = ry + (row_h - bar_h) // 2
    draw.rounded_rectangle([PAD_START, by, PAD_START + bar_w, by + bar_h],
                           radius=bar_w // 2, fill=color)
    tx = PAD_START + bar_w + 8 * D
    cy = ry + row_h // 2
    gap = 1 * D
    nh, lh = 13 * D, 10 * D
    ty = cy - (nh + gap + lh) // 2
    draw.text((tx, ty + nh // 2), name, font=f(13, bold=True), fill=TXT_PRIMARY, anchor="lm")
    draw.text((tx, ty + nh + gap + lh // 2), line, font=f(10), fill=TXT_TERT, anchor="lm")

    rx = W_RIGHT
    mh, th = 17 * D, 10 * D
    ty2 = cy - (mh + gap + th) // 2
    draw.text((rx, ty2 + mh // 2), cd, font=f(17, bold=True), fill=ACCENT, anchor="rm")
    draw.text((rx, ty2 + mh + gap + th // 2), hhmm, font=f(10), fill=TXT_TERT, anchor="rm")


# 内容列右边界 = 画布宽 - 翻页列 - paddingEnd（模块级变量，draw_list_row 引用）
W_RIGHT = 0


def gen_list_preview(path, H_dp, rows, page_text):
    """列表类预览：rows = [(站名, 线路·方向, 倒计时, 时刻, 线路色)]，行高均分（与 v1.0.5 布局一致）"""
    global W_RIGHT
    W, H = 294 * D, H_dp * D
    W_RIGHT = W - PAGER_W - PAD_END
    img = rounded_rect_alpha((W, H), CORNER, BG)
    draw = ImageDraw.Draw(img)

    n = len(rows)
    content_w = W - PAGER_W - PAD_START - PAD_END
    row_h = (H - (n - 1) * 1 * D) // n          # 行均分（weight=1 效果）
    for i, (name, line, cd, hhmm, color) in enumerate(rows):
        ry = i * (row_h + 1 * D)
        draw_list_row(draw, ry, row_h, name, line, cd, hhmm, color)
        if i < n - 1:
            dy = ry + row_h
            draw.rectangle([PAD_START, dy, W_RIGHT, dy + 1 * D - 1], fill=DIVIDER)

    draw_pager(draw, W, H, page_text, refresh=True)
    img.save(path)


def gen_small_preview(path):
    """2×2 预览：1:1 方图（292×292）。
    v1.0.5/v1.0.7 实测：预览槽高约 2 格（≈146dp），146dp 方图能完整显示（只有
    294dp 的 4×4 方图会被截半）——所以 2×2 用方图、仅 4×4 需要用 2:1 宽图。
    内容与 v1.0.9 新版 2×2 排版一致：站名 / 线路·方向 / 下一站 / 最近 3 班。"""
    W = H = 146 * D
    img = rounded_rect_alpha((W, H), CORNER, BG)
    draw = ImageDraw.Draw(img)
    x = PAD_START
    color = (0xF5, 0xD8, 0x00, 255)

    # 顶：色条 + 站名
    draw.rounded_rectangle([x, 8 * D, x + 4 * D, 8 * D + 14 * D], radius=2 * D, fill=color)
    draw.text((x + 4 * D + 6 * D, 8 * D + 7 * D), "呼家楼",
              font=f(14, bold=True), fill=TXT_PRIMARY, anchor="lm")
    # 线路 · 方向
    draw.text((x, 29 * D), "6号线 · 开往 潞阳", font=f(10), fill=TXT_TERT, anchor="lm")
    # 下一站
    draw.text((x, 42 * D), "下一站 十里堡", font=f(10), fill=TXT_TERT, anchor="lm")

    # 下部 3 段：最近 3 班（均分，与 v1.0.9 布局一致）
    top = 56 * D
    seg = (H - top) // 3
    draw.text((x, top + seg // 2), "15:04 · 3 分钟", font=f(16, bold=True), fill=ACCENT, anchor="lm")
    draw.text((x, top + seg + seg // 2), "15:11 · 10 分钟", font=f(12), fill=TXT_SECOND, anchor="lm")
    draw.text((x, top + 2 * seg + seg // 2), "15:19 · 18 分钟", font=f(12), fill=TXT_SECOND, anchor="lm")

    draw_pager(draw, W, H, "1/7", refresh=True)
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
    pad_x, pad_y = 12 * D, 6 * D
    col_gap = 24 * D
    grid_right = W - PAGER_W - 8 * D                     # 网格区右边界（给翻页列让位）
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
        # 行分割线（横向贯穿两列，最后一行不画）
        if r < rows_n - 1 and c == 0:
            pass
    for r in range(rows_n - 1):
        dy = pad_y + (r + 1) * cell_h
        draw.rectangle([pad_x, dy, grid_right, dy + D - 1], fill=DIVIDER)
    # 列间竖分割线（加粗到 2px，强化「双列网格」骨架，与 4×2 单列列表一眼区分）
    vx = pad_x + cell_w + col_gap // 2
    draw.rectangle([vx, pad_y + 4 * D, vx + 2 * D - 1, H - pad_y - 4 * D], fill=DIVIDER)

    draw_pager(draw, W, H, "1/2", refresh=True)
    img.save(path)


YELLOW = (0xF5, 0xD8, 0x00, 255)   # 13号线
BLUE = (0x00, 0x9A, 0xD8, 255)     # 10号线 (示意)
NAVY = (0x00, 0x60, 0xA8, 255)     # 2号线 (示意)
RED = (0xC2, 0x3A, 0x30, 255)      # 1号线 (示意)
GREEN = (0x00, 0x9E, 0x60, 255)    # 8号线 (示意)
PURPLE = (0x60, 0x2D, 0x86, 255)   # 15号线 (示意)

if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)

    # 2×2 预览（2:1 宽幅，单站卡片）
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
