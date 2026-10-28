# 生成胶囊钱包 launcher 图标：紫色渐变底 + 白色胶囊钱包（翻盖线 + 卡扣点）
from PIL import Image, ImageDraw
import os

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app", "src", "main", "res")

TOP = (167, 139, 250)    # #a78bfa
BOT = (108, 99, 255)     # #6c63ff
FLAP = (108, 99, 255)    # 翻盖线/卡扣用主紫


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def make_icon(size, round_variant):
    S = size * 4  # 4x 超采样抗锯齿
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    # 背景：垂直渐变
    grad = Image.new("RGBA", (S, S))
    gd = ImageDraw.Draw(grad)
    for y in range(S):
        gd.line([(0, y), (S, y)], fill=lerp(TOP, BOT, y / S) + (255,))

    # 形状遮罩：圆 = 整圆；方 = 圆角矩形
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    pad = int(S * 0.02)
    if round_variant:
        md.ellipse([pad, pad, S - pad, S - pad], fill=255)
    else:
        md.rounded_rectangle([pad, pad, S - pad, S - pad], radius=int(S * 0.24), fill=255)
    img.paste(grad, (0, 0), mask)

    d = ImageDraw.Draw(img)

    # 白色胶囊钱包本体
    cw, ch = S * 0.62, S * 0.30
    x0, y0 = (S - cw) / 2, (S - ch) / 2
    x1, y1 = x0 + cw, y0 + ch
    d.rounded_rectangle([x0, y0, x1, y1], radius=ch / 2, fill=(255, 255, 255, 255))

    # 翻盖分割线（紫色竖线，位于胶囊约 68% 处）
    lx = x0 + cw * 0.68
    lw = S * 0.012
    inset = ch * 0.16
    d.rounded_rectangle([lx - lw / 2, y0 + inset, lx + lw / 2, y1 - inset],
                        radius=lw / 2, fill=FLAP + (255,))

    # 卡扣圆点（翻盖区中央）
    r = ch * 0.11
    cx = (lx + x1) / 2 + r * 0.3
    cy = S / 2
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=FLAP + (255,))

    return img.resize((size, size), Image.LANCZOS)


for name, px in DENSITIES.items():
    out_dir = os.path.join(RES, f"mipmap-{name}")
    os.makedirs(out_dir, exist_ok=True)
    make_icon(px, False).save(os.path.join(out_dir, "ic_launcher.png"))
    make_icon(px, True).save(os.path.join(out_dir, "ic_launcher_round.png"))
    print(f"{name}: {px}px done")

# 预览图
make_icon(192, False).save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon_preview.png"))
print("preview saved")
