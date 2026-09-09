# Metro Next AI 图标选型表
# 4x2 网格 + 编号 + 风格标签 + 推荐标记
from PIL import Image, ImageDraw, ImageFont
import os, glob

SRC = r"D:/WorkBuddy_Save/手机小程序开发/.workbuddy/icon_preview/ai"
FONT = r"C:/Windows/Fonts/msyh.ttc"
OUT = r"D:/WorkBuddy_Save/手机小程序开发/.workbuddy/icon_preview/ai_grid.png"

# 顺序（按生成文件读取，保证对应风格）
# 顺序按 glob 字母序（实际文件读取顺序），与下方 META 索引对齐
files = sorted(glob.glob(os.path.join(SRC, "*.png")))
META = [
    ("A1", "3D 蓝紫列车出隧道",  "⭐ 推荐 · 商店级质感"),
    ("A2", "清新蓝白车头+云",    "清爽，地铁感弱"),
    ("A3", "暖色插画车头",        "偏玩具风，专业感不足"),
    ("A4", "极简扁平+金箭",      "简洁但偏空"),
    ("A5", "3D 卡通车头+时钟",   "可爱，元素稍满"),
    ("A6", "蓝橙 M 彩带+铁轨",   "撞色稍杂"),
    ("A7", "玻璃拟态 M",          "⭐ 推荐 · 现代专业"),
    ("A8", "深色霓虹隧道+列车",  "⭐ 推荐 · 冲击强"),
]

THUMB = 460
PAD = 30
LABEL_H = 90
TOP = 150
SIDE = 36
COLS, ROWS = 4, 2
CELL_W = THUMB + PAD * 2
CELL_H = THUMB + LABEL_H
W = SIDE * 2 + CELL_W * COLS
H = TOP + CELL_H * ROWS + SIDE

img = Image.new("RGB", (W, H), "#FFFFFF")
d = ImageDraw.Draw(img)
f_title = ImageFont.truetype(FONT, 50)
f_sub   = ImageFont.truetype(FONT, 26)
f_code  = ImageFont.truetype(FONT, 32)
f_desc  = ImageFont.truetype(FONT, 24)

d.text((SIDE, 32), "Metro Next AI 图标选型", font=f_title, fill="#16315C")
d.text((SIDE, 98), "8 张 AI 生图候选 · 选定后裁水印 + 出 mipmap 全套尺寸", font=f_sub, fill="#5A6B85")

for i, fp in enumerate(files[:8]):
    code, name, desc = META[i]
    r, c = divmod(i, COLS)
    x = SIDE + c * CELL_W
    y = TOP + r * CELL_H
    d.rounded_rectangle([x + 8, y + 8, x + CELL_W - 8, y + CELL_H - 8], radius=20, fill="#F4F7FB")
    icon = Image.open(fp).convert("RGBA").resize((THUMB, THUMB), Image.LANCZOS)
    img.paste(icon, (x + PAD, y + PAD), icon)
    # 推荐标记（金色星）
    if "⭐" in desc:
        d.text((x + PAD + THUMB - 60, y + PAD - 6), "★", font=ImageFont.truetype(FONT, 56), fill="#F5B942")
    d.text((x + PAD + 6, y + PAD + THUMB + 14), code, font=f_code, fill="#16315C")
    d.text((x + PAD + 70, y + PAD + THUMB + 20), f"{name} · {desc}", font=f_desc, fill="#5A6B85")

img.save(OUT)
print("saved", OUT, img.size)
