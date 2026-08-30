# Metro Next 4 候选选型表（app-icon-gen 矢量版）
from PIL import Image, ImageDraw, ImageFont
import os

SRC = r"D:/WorkBuddy_Save/手机小程序开发/.workbuddy/icon_preview/skill"
FONT = r"C:/Windows/Fonts/msyh.ttc"
OUT = r"D:/WorkBuddy_Save/手机小程序开发/.workbuddy/icon_preview/skill_grid.png"

ITEMS = [
    ("C1", "OrbitRing",   "环线轨道 + 橙列车点",  "极简 premium，但稍像耳机"),
    ("C2", "RouteBadge",  "站牌 + 蓝折线 + 橙点",  "⭐ 最推荐 · 地铁工具感"),
    ("C3", "NextArrow",   "轨道 + 出发箭头",       "通用，地铁感弱"),
    ("C4", "MetroM",      "粗圆角字母 M",         "品牌感强，但像 Gmail"),
]

THUMB = 480
PAD = 28
LABEL_H = 96
TOP = 140
SIDE = 36
COLS, ROWS = 4, 1
CELL_W = THUMB + PAD * 2
CELL_H = THUMB + LABEL_H
W = SIDE * 2 + CELL_W * COLS
H = TOP + CELL_H * ROWS + SIDE

img = Image.new("RGB", (W, H), "#FFFFFF")
d = ImageDraw.Draw(img)
d.text((SIDE, 32), "Metro Next 图标选型（app-icon-gen 矢量版）", font=ImageFont.truetype(FONT, 50), fill="#16315C")
d.text((SIDE, 98), "语义分层 SVG · 同色系深渐变质感 · 橙色点睛 · 选定后出 mipmap 全套尺寸", font=ImageFont.truetype(FONT, 26), fill="#5A6B85")

for i, (code, name, brief, note) in enumerate(ITEMS):
    x = SIDE + i * CELL_W
    y = TOP
    d.rounded_rectangle([x + 8, y + 8, x + CELL_W - 8, y + CELL_H - 8], radius=20, fill="#F4F7FB")
    p = os.path.join(SRC, name, f"{name.lower()}.preview.png")
    icon = Image.open(p).convert("RGBA").resize((THUMB, THUMB), Image.LANCZOS)
    img.paste(icon, (x + PAD, y + PAD), icon)
    if "⭐" in note:
        d.text((x + PAD + THUMB - 60, y + PAD - 6), "★", font=ImageFont.truetype(FONT, 56), fill="#F5B942")
    d.text((x + PAD + 6, y + PAD + THUMB + 14), code, font=ImageFont.truetype(FONT, 32), fill="#16315C")
    d.text((x + PAD + 70, y + PAD + THUMB + 20), f"{name} · {brief}", font=ImageFont.truetype(FONT, 22), fill="#5A6B85")
    d.text((x + PAD + 70, y + PAD + THUMB + 48), note, font=ImageFont.truetype(FONT, 22), fill="#5A6B85")

img.save(OUT)
print("saved", OUT, img.size)
