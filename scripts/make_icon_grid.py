# Metro Next 图标选型表拼接
# 3x3 网格 + 编号 + 方向/配色标注
from PIL import Image, ImageDraw, ImageFont
import os

SRC = r"D:/WorkBuddy_Save/手机小程序开发/.workbuddy/icon_preview"
FONT = r"C:/Windows/Fonts/msyh.ttc"

# 顺序与 render_icons.js 一致
CELLS = [
    ("1A", "车头 + 指针", "深蓝渐变"),
    ("1B", "车头 + 指针", "地铁红渐变"),
    ("1C", "车头 + 指针", "墨蓝 + 金"),
    ("2A", "表盘 + 轨道", "深蓝渐变"),
    ("2B", "表盘 + 轨道", "浅底深表"),
    ("2C", "表盘 + 轨道", "地铁红渐变"),
    ("3A", "字母 M 变形", "深蓝 + 金箭"),
    ("3B", "字母 M 变形", "地铁红 + 米箭"),
    ("3C", "字母 M 变形", "浅底蓝 M 红箭"),
]

PAD = 28          # 格内边距
LABEL_H = 86      # 底部标签区
TOP = 130         # 顶部标题区
SIDE = 36
CELL_W = 512 + PAD * 2
CELL_H = 512 + LABEL_H
COLS = ROWS = 3
W = SIDE * 2 + CELL_W * COLS
H = TOP + CELL_H * ROWS + SIDE

img = Image.new("RGB", (W, H), "#FFFFFF")
d = ImageDraw.Draw(img)

def font(sz):
    return ImageFont.truetype(FONT, sz)

# 顶部标题 + 副标题
d.text((SIDE, 34), "Metro Next 图标选型", font=font(52), fill="#16315C")
d.text((SIDE, 100), "A: 车头+指针  B: 表盘+轨道  C: 字母M变形    （每方向 3 个配色，选定后出全套尺寸）",
       font=font(26), fill="#5A6B85")

for i, (code, name, color) in enumerate(CELLS):
    r, c = divmod(i, COLS)
    x = SIDE + c * CELL_W
    y = TOP + r * CELL_H
    # 格底
    d.rounded_rectangle([x + 8, y + 8, x + CELL_W - 8, y + CELL_H - 8], radius=20, fill="#F4F7FB")
    # 图标
    p = os.path.join(SRC, f"{code.lower()}-*.png")
    import glob
    f = glob.glob(os.path.join(SRC, f"{code.lower()}-*.png"))[0]
    icon = Image.open(f).resize((512, 512), Image.LANCZOS)
    img.paste(icon, (x + PAD, y + PAD), icon)
    # 标签
    d.text((x + PAD + 6, y + PAD + 512 + 14), code, font=font(34), fill="#16315C")
    d.text((x + PAD + 84, y + PAD + 512 + 20), f"{name} · {color}", font=font(26), fill="#5A6B85")

out = os.path.join(SRC, "grid.png")
img.save(out)
print("saved", out, img.size)
