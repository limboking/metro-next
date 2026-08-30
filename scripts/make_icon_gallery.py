# Metro Next 图标选型画廊（单文件 HTML，图片 base64 内嵌）
import base64, io, os, re, glob
from PIL import Image

AI2  = r"D:/WorkBuddy_Save/手机小程序开发/.workbuddy/icon_preview/ai2"
AI3  = r"D:/WorkBuddy_Save/手机小程序开发/.workbuddy/icon_preview/ai3"
SKILL = r"D:/WorkBuddy_Save/手机小程序开发/.workbuddy/icon_preview/skill"
OUT = r"D:/WorkBuddy_Save/手机小程序开发/.workbuddy/icon_preview/metro_icons_gallery.html"

# ---------- B 组（AI 质感 12 张），按文件名时间戳排序对应生成顺序 ----------
def ts(p):
    m = re.search(r"(\d{2}-\d{2}-\d{2})", p)
    return m.group(1) if m else ""

b_files = sorted(glob.glob(os.path.join(AI2, "*.png")), key=ts)
B_META = [
    ("B1", "液态玻璃",     "深蓝底 + 玻璃面板内白色列车，Liquid Glass 风"),
    ("B2", "软胶 3D 列车",  "奶油蓝底 + 圆润硅胶质感列车车头"),
    ("B3", "金属拉丝 M",    "炭黑底 + 拉丝金属 3D 字母 M"),
    ("B4", "硅胶环 + 橙点", "深蓝底 + 3D 硅胶环 + 橙色列车点"),
    ("B5", "霓虹轨道环",    "近黑底 + 发光蓝色轨道环"),
    ("B6", "毛玻璃卡片",    "蓝紫渐变 + 悬浮毛玻璃卡片"),
    ("B7", "浅色极简环点",  "浅灰白底 + 深蓝圆环圆点，浅色主题"),
    ("B8", "蓝橙渐变列车",  "蓝橙渐变底 + 白色列车剪影 + 光晕"),
    ("B9", "霓虹发光 M",    "深蓝底 + 蓝紫霓虹 3D 字母 M"),
    ("B10", "玻璃站牌",     "蓝渐变 + 3D 玻璃站牌 + 白色路线"),
    ("B11", "线稿极简",     "粉蓝渐变 + 细线条轨道列车"),
    ("B12", "银灰黑环橙点", "银灰渐变 + 哑光黑环 + 橙点"),
]
assert len(b_files) == 12, f"expect 12 ai2 files, got {len(b_files)}"

# ---------- C 组（矢量扁平 4 张） ----------
C_META = [
    ("C1", "环线轨道",     "OrbitRing · 白环 + 橙列车点", "orbitring.preview.png"),
    ("C2", "站牌路线",     "RouteBadge · 站牌 + 蓝折线",  "routebadge.preview.png"),
    ("C3", "轨道箭头",     "NextArrow · 轨道 + 出发箭头",  "nextarrow.preview.png"),
    ("C4", "字母 M",       "MetroM · 粗圆角 M",          "metrom.preview.png"),
]

# ---------- D 组（地铁+时间 融合矢量扁平 8 张） ----------
D_META = [
    ("D1", "列车车窗表盘",  "侧视列车，车窗即表盘"),
    ("D2", "轨道贯穿表盘",  "时钟夹在上下轨道之间"),
    ("D3", "环线表盘",      "刻度环=地铁环线 + 橙列车点"),
    ("D4", "M 头顶表盘",    "字母 M + 时钟"),
    ("D5", "列车时刻点",    "时刻点序列，橙点=下一班"),
    ("D6", "站台时钟",      "站台上的时刻表"),
    ("D7", "车头仪表盘",    "正视车头 + 胸前表盘 + 车灯"),
    ("D8", "轨道指针",      "极简：轨道 + 时针 + 橙点"),
]

# ---------- E 组（D5 下部图形变体 5 张，保留上方时刻点序列） ----------
E_FILES = {
    "E1": "e1-clock.png", "E2": "e2-train.png", "E3": "e3-track.png",
    "E4": "e4-front.png", "E5": "e5-loop.png",
}
E_META = [
    ("E1", "下部改·时钟",     "时刻点序列 + 表盘"),
    ("E2", "下部改·侧视列车", "时刻点序列 + 列车(车窗/轮/灯)"),
    ("E3", "下部改·铁轨枕木", "时刻点序列 + 轨道枕木"),
    ("E4", "下部改·正视车头", "时刻点序列 + 车头(挡风/灯)"),
    ("E5", "下部改·环线轨道", "时刻点序列 + 小环线+橙点"),
]

def b64_jpeg(p, size=460, q=82):
    im = Image.open(p).convert("RGB")
    im.thumbnail((size, size), Image.LANCZOS)
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=q)
    return base64.b64encode(buf.getvalue()).decode()

def card(code, name, tag, src_b64):
    return f'''
    <div class="card" onclick="openLight(this)">
      <div class="imgwrap"><img src="data:image/jpeg;base64,{src_b64}" alt="{code}"/></div>
      <div class="meta">
        <span class="code">{code}</span>
        <span class="name">{name}</span>
        <span class="tag">{tag}</span>
      </div>
    </div>'''

cards_b = "".join(card(c, n, t, b64_jpeg(b_files[i])) for i, (c, n, t) in enumerate(B_META))
cards_c = "".join(
    card(c, n, t, b64_jpeg(os.path.join(SKILL, name, fn)))
    for c, n, t, name, fn in [(*m, m[1].lower() if False else "", "") for m in []]
)  # placeholder

cards_c = "".join(
    card(c, n, t, b64_jpeg(os.path.join(SKILL, {"C1":"OrbitRing","C2":"RouteBadge","C3":"NextArrow","C4":"MetroM"}[c], fn)))
    for c, n, t, fn in [m[0:3] + (m[3],) for m in C_META]
)

D_FILES = {
    "D1": "d1-metroclock.png", "D2": "d2-trackclock.png", "D3": "d3-loopdial.png",
    "D4": "d4-mclock.png", "D5": "d5-nextpoints.png", "D6": "d6-stationdial.png",
    "D7": "d7-frontclock.png", "D8": "d8-handtrack.png",
}
cards_d = "".join(
    card(c, n, t, b64_jpeg(os.path.join(AI3, D_FILES[c])))
    for c, n, t in D_META
)

cards_e = "".join(
    card(c, n, t, b64_jpeg(os.path.join(AI3, E_FILES[c])))
    for c, n, t in E_META
)

html = f'''<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>Metro Next 图标选型</title>
<style>
  * {{ box-sizing: border-box; margin: 0; padding: 0; }}
  body {{ background: #F5F7FB; color: #1A2740; font-family: "Microsoft YaHei", system-ui, sans-serif; padding: 28px 20px 60px; }}
  h1 {{ font-size: 26px; color: #16315C; margin-bottom: 6px; }}
  .sub {{ color: #5A6B85; font-size: 14px; margin-bottom: 22px; }}
  .sec {{ font-size: 17px; font-weight: 700; color: #16315C; margin: 26px 0 14px; padding-left: 10px; border-left: 4px solid #2E7BE6; }}
  .grid {{ display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 16px; }}
  .card {{ background: #fff; border-radius: 14px; padding: 12px; cursor: pointer; box-shadow: 0 1px 4px rgba(22,49,92,.08); transition: transform .15s, box-shadow .15s; }}
  .card:hover {{ transform: translateY(-3px); box-shadow: 0 6px 18px rgba(22,49,92,.14); }}
  .imgwrap {{ border-radius: 10px; overflow: hidden; background: #EEF2F8; }}
  .imgwrap img {{ width: 100%; display: block; }}
  .meta {{ display: flex; align-items: baseline; gap: 8px; margin-top: 10px; flex-wrap: wrap; }}
  .code {{ font-weight: 800; color: #16315C; font-size: 15px; }}
  .name {{ font-size: 14px; color: #1A2740; font-weight: 600; }}
  .tag {{ font-size: 12px; color: #5A6B85; }}
  .hint {{ margin-top: 24px; padding: 14px 18px; background: #EAF2FF; border-radius: 10px; color: #16315C; font-size: 14px; line-height: 1.7; }}
  .lightbox {{ display: none; position: fixed; inset: 0; background: rgba(10,16,28,.85); z-index: 99; align-items: center; justify-content: center; flex-direction: column; }}
  .lightbox.open {{ display: flex; }}
  .lightbox img {{ max-width: 82vw; max-height: 76vh; border-radius: 14px; box-shadow: 0 10px 40px rgba(0,0,0,.5); }}
  .lightbox .lb-meta {{ color: #fff; margin-top: 14px; font-size: 16px; }}
  .lightbox .close {{ position: absolute; top: 18px; right: 26px; color: #fff; font-size: 34px; cursor: pointer; }}
</style></head><body>
<h1>Metro Next 图标选型</h1>
<div class="sub">29 个候选 · 点击卡片放大对比 · 告诉我编号（如 E2）即可定稿 → 出全套 mipmap + 自适应图标</div>

<div class="sec">E 组 · D5 下部图形变体（上方时刻点序列保留）5 张</div>
<div class="grid">{cards_e}</div>

<div class="sec">D 组 · 地铁 + 时间 融合设计（矢量扁平）8 张</div>
<div class="grid">{cards_d}</div>

<div class="sec">B 组 · AI 质感系列（极简 × 现代材质）12 张</div>
<div class="grid">{cards_b}</div>

<div class="sec">C 组 · 矢量扁平系列（app-icon-gen 语义分层）4 张</div>
<div class="grid">{cards_c}</div>

<div class="hint">💡 D 组按你的偏好回归矢量扁平风格，重点打磨"地铁+时间"的融合图形：
D1 车窗=表盘（语义最直接）、D3 环线=表盘刻度（地铁环线概念）、D6 站台时钟（通勤场景感）、D7 车头仪表盘（识别度最高）。
<br/>如想调整某个图形的细节（指针位置、元素比例、配色），直接说编号 + 修改点，我重出该张。</div>

<div class="lightbox" id="lb" onclick="this.classList.remove('open')">
  <span class="close">&times;</span>
  <img id="lbImg" src=""/>
  <div class="lb-meta" id="lbMeta"></div>
</div>
<script>
function openLight(card) {{
  const img = card.querySelector('img');
  const meta = card.querySelector('.meta').innerText;
  document.getElementById('lbImg').src = img.src;
  document.getElementById('lbMeta').textContent = meta;
  document.getElementById('lb').classList.add('open');
}}
</script>
</body></html>'''

with open(OUT, "w", encoding="utf-8") as f:
    f.write(html)
print("saved", OUT, f"{os.path.getsize(OUT)/1024/1024:.1f} MB")
