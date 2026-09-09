# Metro Next 定稿图标全套资源生成（Android mipmap + 自适应 + 商店）
import os, math
from PIL import Image, ImageDraw

SRC = r"D:/WorkBuddy_Save/手机小程序开发/.workbuddy/icon_preview/final"
RES = r"D:/WorkBuddy_Save/手机小程序开发/android/app/src/main/res"

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

def mkdirs(p):
    os.makedirs(p, exist_ok=True)

full = Image.open(os.path.join(SRC, "full_1024.png")).convert("RGBA")

def circle_mask(size):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    d.ellipse((2, 2, size - 2, size - 2), fill=255)
    return m

# 1) 传统 launcher 图标（各 dpi）
for dpi, px in SIZES.items():
    d = os.path.join(RES, f"mipmap-{dpi}")
    mkdirs(d)
    full.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))
    # 圆形版本
    r = full.resize((px, px), Image.LANCZOS)
    r.putalpha(circle_mask(px))
    r.save(os.path.join(d, "ic_launcher_round.png"))
    print("mipmap", dpi, px)

# 2) 自适应前景（内容控制在 66% 安全区内：1.08x 微放大 → 内容 ~58%）
mkdirs(os.path.join(RES, "drawable"))
fg = Image.open(os.path.join(SRC, "fg_1024.png")).convert("RGBA")
fg_zoom = fg.resize((int(1024 * 1.08), int(1024 * 1.08)), Image.LANCZOS)
x0 = (fg_zoom.width - 1024) // 2
y0 = (fg_zoom.height - 1024) // 2
fg_crop = fg_zoom.crop((x0, y0, x0 + 1024, y0 + 1024))
fg_crop.resize((432, 432), Image.LANCZOS).save(os.path.join(RES, "drawable", "ic_launcher_foreground.png"))
print("adaptive foreground 432 (1.08x, within 66% safe zone)")

# 3) 背景渐变 drawable
BG_XML = """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <gradient
        android:angle="270"
        android:startColor="#1C3559"
        android:endColor="#0C1626" />
</shape>
"""
mkdirs(os.path.join(RES, "drawable"))
with open(os.path.join(RES, "drawable", "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
    f.write(BG_XML)

# 4) 自适应图标 xml
ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
"""
mkdirs(os.path.join(RES, "mipmap-anydpi-v26"))
for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    with open(os.path.join(RES, "mipmap-anydpi-v26", name), "w", encoding="utf-8") as f:
        f.write(ADAPTIVE)
print("adaptive xml done")

# 5) 商店图标 512
mkdirs(os.path.join(RES, "..", "store"))
full.resize((512, 512), Image.LANCZOS).save(os.path.join(RES, "..", "store", "metro_next_512.png"))
print("store icon 512")

print("ALL DONE ->", RES)
