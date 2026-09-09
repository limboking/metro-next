# 生成 Metro Next 图标候选 spec（app-icon-gen skill 格式）
import json, os

BASE = r"D:/WorkBuddy_Save/手机小程序开发/.workbuddy/icon_preview/skill"

NAVY_BG = ("#1C3559", "#0C1626")
BLUE_BG = ("#2E7BE6", "#1B62D9")
INK_BG  = ("#131F33", "#0A101C")
ORANGE  = "#F5A623"

def srgba(hexs):
    return ",".join(f"{int(hexs[i:i+2],16)/255:.5f}" for i in (1,3,5)) + ",1.00000"

def grad_fill(top, bottom):
    return {
        "linear-gradient": [f"extended-srgb:{srgba(top)}", f"extended-srgb:{srgba(bottom)}"],
        "orientation": {"start": {"x": 0, "y": 0}, "stop": {"x": 0, "y": 1}},
    }

def bg_svg(top, bottom):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">'
            f'<defs><linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">'
            f'<stop offset="0" stop-color="{top}"/><stop offset="1" stop-color="{bottom}"/>'
            f'</linearGradient></defs>'
            f'<rect width="1024" height="1024" fill="url(#bg)"/></svg>')

def spec(name, brief, top, bottom, layers):
    return {
        "name": name,
        "source_reference": f"synthetic Metro Next icon concept: {brief}",
        "brief": brief,
        "fill": grad_fill(top, bottom),
        "layers": [
            {"name": "background", "svg": bg_svg(top, bottom), "composer": {"include": False}},
        ] + layers,
    }

def layer(name, svg):
    return {"name": name, "svg": svg, "composer": {"format": "svg"}}

# ---------- C1 环线轨道 + 列车点 ----------
# 白色粗圆环（地铁环线），橙色圆点（列车当前位置）
c1 = spec(
    "OrbitRing", "subway loop line ring with train position dot, premium minimal",
    *NAVY_BG,
    [
        layer("loop ring", '<path d="M512 152 A360 360 0 1 1 511 152 Z M512 332 A180 180 0 1 0 513 332 Z" fill="#FFFFFF" fill-rule="evenodd"/>'),
        layer("train dot", f'<circle cx="512" cy="286" r="74" fill="{ORANGE}"/>'),
    ],
)

# ---------- C2 站牌 + 路线折线 ----------
# 白色圆角站牌，蓝色折线路线穿过，橙色列车点
c2 = spec(
    "RouteBadge", "station badge with crossing route line and train dot",
    *BLUE_BG,
    [
        layer("badge", '<rect x="262" y="262" width="500" height="500" rx="150" fill="#FFFFFF"/>'),
        layer("route cut", '<path d="M318 706 L450 574 L574 574 L706 442" stroke="#1B62D9" stroke-width="84" stroke-linecap="round" stroke-linejoin="round" fill="none"/>'),
        layer("train dot", f'<circle cx="512" cy="574" r="42" fill="{ORANGE}"/>'),
    ],
)

# ---------- C3 轨道 + 出发箭头 + 列车 ----------
# 白色水平轨道线，白色向上出发箭头，橙色列车点
c3 = spec(
    "NextArrow", "track line with depart arrow and train dot, kinetic minimal",
    *NAVY_BG,
    [
        layer("track", '<rect x="160" y="560" width="704" height="64" rx="32" fill="#FFFFFF"/>'),
        layer("depart arrow", '<path d="M466 560 L466 400 L398 400 L512 268 L626 400 L558 400 L558 560 Z" fill="#FFFFFF"/>'),
        layer("train dot", f'<circle cx="790" cy="592" r="40" fill="{ORANGE}"/>'),
    ],
)

# ---------- C4 字母 M ----------
# 白色粗圆角 M（Metro Next 首字母）
c4 = spec(
    "MetroM", "bold rounded letter M monogram",
    *INK_BG,
    [
        layer("letter M", '<path d="M220 700 L220 336 M220 336 L512 566 M512 566 L804 336 M804 336 L804 700" stroke="#FFFFFF" stroke-width="140" stroke-linecap="round" stroke-linejoin="round" fill="none"/>'),
    ],
)

for s in (c1, c2, c3, c4):
    d = os.path.join(BASE, s["name"], "tmp", "work")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "spec.json"), "w", encoding="utf-8") as f:
        json.dump(s, f, ensure_ascii=False, indent=2)
    print("spec written:", s["name"])
