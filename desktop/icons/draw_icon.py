"""Draws the Self-Healing app icon: a cracked phone patched with a band-aid, and a green "fixed" badge.

    python3 desktop/icons/draw_icon.py desktop/icons/icon.png
"""
import math
import random
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter

S = 4  # supersampling factor; drawn at 4096 px and scaled down
N = 1024 * S
random.seed(7)


def sc(v):
    return int(round(v * S))


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def gradient(size, stops, angle=90):
    """Linear gradient through (position, rgb) stops; angle 90 = top→bottom."""
    w, h = size
    line = Image.new("RGB", (1, 512))
    for y in range(512):
        t = y / 511
        for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
            if p0 <= t <= p1:
                line.putpixel((0, y), lerp(c0, c1, (t - p0) / max(p1 - p0, 1e-6)))
                break
    diag = int(math.hypot(w, h)) + 2
    g = line.resize((diag, diag)).rotate(90 - angle, resample=Image.BICUBIC)
    return g.crop(((diag - w) // 2, (diag - h) // 2, (diag - w) // 2 + w, (diag - h) // 2 + h)).convert("RGBA")


def rrect_mask(box, r):
    m = Image.new("L", (N, N), 0)
    ImageDraw.Draw(m).rounded_rectangle(box, r, fill=255)
    return m


def shadow(mask, offset, blur, alpha):
    sh = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    a = mask.point(lambda v: v * alpha // 255)
    sh.putalpha(ImageChops.offset(a, offset[0], offset[1]))
    return sh.filter(ImageFilter.GaussianBlur(blur))


def fill(mask, layer):
    out = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    out.paste(layer, (0, 0), mask)
    return out


img = Image.new("RGBA", (N, N), (0, 0, 0, 0))

# ── Tile ───────────────────────────────────────────────────────────────────
tile = (sc(100), sc(100), sc(924), sc(924))
tile_mask = rrect_mask(tile, sc(185))
img.alpha_composite(shadow(tile_mask, (0, sc(12)), sc(18), 120))
img.alpha_composite(fill(tile_mask, gradient((N, N), [(0, (30, 74, 128)), (0.55, (14, 34, 66)), (1, (6, 14, 30))])))
glow = Image.new("RGBA", (N, N), (0, 0, 0, 0))
ImageDraw.Draw(glow).ellipse((sc(250), sc(200), sc(780), sc(760)), fill=(70, 170, 255, 60))
img.alpha_composite(Image.composite(glow.filter(ImageFilter.GaussianBlur(sc(80))), Image.new("RGBA", (N, N)), tile_mask))

# ── Phone ──────────────────────────────────────────────────────────────────
px0, py0, px1, py1 = 322, 196, 702, 828
frame = (sc(px0), sc(py0), sc(px1), sc(py1))
d = ImageDraw.Draw(img)
# Side buttons (behind the frame).
btn = gradient((N, N), [(0, (200, 205, 212)), (1, (110, 116, 126))], angle=0)
for box in [(px0 - 7, 330, px0 + 6, 372), (px0 - 7, 392, px0 + 6, 452), (px0 - 7, 466, px0 + 6, 526), (px1 - 6, 400, px1 + 7, 500)]:
    img.alpha_composite(fill(rrect_mask(tuple(sc(v) for v in box), sc(5)), btn))
frame_mask = rrect_mask(frame, sc(74))
img.alpha_composite(shadow(frame_mask, (sc(6), sc(22)), sc(22), 150))
# Titanium frame: brushed diagonal gradient with a bright rim.
img.alpha_composite(fill(frame_mask, gradient((N, N), [(0, (232, 236, 241)), (0.35, (150, 156, 166)), (0.6, (205, 210, 217)), (1, (96, 102, 112))], angle=60)))
inner = (frame[0] + sc(9), frame[1] + sc(9), frame[2] - sc(9), frame[3] - sc(9))
img.alpha_composite(fill(rrect_mask(inner, sc(66)), Image.new("RGBA", (N, N), (8, 9, 12, 255))))
screen = (inner[0] + sc(12), inner[1] + sc(12), inner[2] - sc(12), inner[3] - sc(12))
screen_mask = rrect_mask(screen, sc(54))

# Lock-screen wallpaper: deep gradient with soft colour blobs.
wall = gradient((N, N), [(0, (34, 46, 98)), (0.5, (52, 36, 102)), (1, (16, 22, 52))], angle=75)
blobs = Image.new("RGBA", (N, N), (0, 0, 0, 0))
bd = ImageDraw.Draw(blobs)
bd.ellipse((sc(300), sc(560), sc(640), sc(900)), fill=(255, 120, 90, 150))
bd.ellipse((sc(470), sc(220), sc(780), sc(520)), fill=(80, 200, 255, 140))
bd.ellipse((sc(280), sc(300), sc(520), sc(540)), fill=(170, 110, 255, 120))
wall.alpha_composite(blobs.filter(ImageFilter.GaussianBlur(sc(70))))
img.alpha_composite(fill(screen_mask, wall))

# ── Shattered glass ────────────────────────────────────────────────────────
cx, cy = 512, 512  # impact point, under the band-aid
cracks = Image.new("RGBA", (N, N), (0, 0, 0, 0))
cd = ImageDraw.Draw(cracks)


def crack_line(x, y, angle, length, width, depth=0):
    pts = [(x, y)]
    step = 18
    for _ in range(int(length / step)):
        angle += random.uniform(-0.35, 0.35)
        x += math.cos(angle) * step
        y += math.sin(angle) * step
        pts.append((x, y))
        if depth < 2 and random.random() < 0.12:
            crack_line(x, y, angle + random.choice((-1, 1)) * random.uniform(0.5, 1.0), length * 0.35, max(width - 1, 1), depth + 1)
    shade = [(sc(a) + sc(2), sc(b) + sc(2)) for a, b in pts]
    cd.line(shade, fill=(0, 0, 0, 110), width=sc(width + 1), joint="curve")
    cd.line([(sc(a), sc(b)) for a, b in pts], fill=(235, 243, 255, 225), width=sc(width), joint="curve")


for i in range(11):
    a = i / 11 * 2 * math.pi + random.uniform(-0.2, 0.2)
    crack_line(cx, cy, a, random.uniform(170, 330), 3)
# Concentric fractures around the impact.
for r in (46, 92):
    pts = []
    for k in range(0, 360, 12):
        rr = r + random.uniform(-8, 8)
        pts.append((sc(cx + rr * math.cos(math.radians(k))), sc(cy + rr * math.sin(math.radians(k)))))
    for k in range(0, len(pts) - 1, 2):
        cd.line([pts[k], pts[k + 1]], fill=(235, 243, 255, 170), width=sc(2))
img.alpha_composite(Image.composite(cracks, Image.new("RGBA", (N, N)), screen_mask))

d = ImageDraw.Draw(img)
d.rounded_rectangle((sc(462), sc(232), sc(562), sc(262)), sc(15), fill=(0, 0, 0, 255))  # Dynamic Island (above the cracks)
d.ellipse((sc(538), sc(240), sc(552), sc(254)), fill=(22, 26, 40, 255))

# Glass reflection.
gloss = Image.new("RGBA", (N, N), (0, 0, 0, 0))
ImageDraw.Draw(gloss).polygon([(sc(330), sc(200)), (sc(560), sc(200)), (sc(330), sc(520))], fill=(255, 255, 255, 34))
img.alpha_composite(Image.composite(gloss.filter(ImageFilter.GaussianBlur(sc(6))), Image.new("RGBA", (N, N)), screen_mask))

# ── Band-aid ───────────────────────────────────────────────────────────────
band = Image.new("RGBA", (N, N), (0, 0, 0, 0))
bw, bh = sc(500), sc(156)
bx0, by0 = N // 2 - bw // 2, sc(cy) - bh // 2
body_box = (bx0, by0, bx0 + bw, by0 + bh)
body_mask = rrect_mask(body_box, bh // 2)
# Cylindrical shading: light top, darker bottom edge.
skin = gradient((N, N), [(0, (250, 214, 168)), (0.45, (238, 190, 138)), (1, (196, 138, 88))])
skin_crop = skin.crop((0, by0, N, by0 + bh)).resize((N, bh))
body = Image.new("RGBA", (N, N), (0, 0, 0, 0))
body.paste(skin_crop, (0, by0))
body = Image.composite(body, Image.new("RGBA", (N, N)), body_mask)
# Woven fabric texture.
noise = Image.effect_noise((N, N), 40).filter(ImageFilter.GaussianBlur(S * 0.6))
weave = Image.new("L", (N, N), 128)
wd = ImageDraw.Draw(weave)
for x in range(0, N, sc(6)):
    wd.line([(x, 0), (x, N)], fill=110, width=S)
texture = ImageChops.blend(noise, weave, 0.5)
tex = Image.merge("RGBA", (texture, texture, texture, body_mask.point(lambda v: v * 40 // 255)))
band.alpha_composite(body)
band = Image.composite(Image.alpha_composite(band, Image.composite(tex, Image.new("RGBA", (N, N)), body_mask)), Image.new("RGBA", (N, N)), body_mask)
bdr = ImageDraw.Draw(band)
# Breathing holes on both wings.
for side in (-1, 1):
    for i, dx in enumerate((150, 190, 225)):
        for dy in ((-34, 0, 34) if i % 2 == 0 else (-17, 17)):
            x, y = N // 2 + side * sc(dx), sc(cy + dy)
            bdr.ellipse((x - sc(6), y - sc(6), x + sc(6), y + sc(6)), fill=(170, 112, 70, 200))
            bdr.ellipse((x - sc(6), y - sc(4), x + sc(6), y + sc(7)), fill=(244, 206, 160, 120))
# Gauze pad with mesh and an inset shadow.
pw, ph = sc(156), sc(112)
pad = (N // 2 - pw // 2, sc(cy) - ph // 2, N // 2 + pw // 2, sc(cy) + ph // 2)
pad_mask = rrect_mask(pad, sc(18))
band.alpha_composite(shadow(pad_mask, (0, sc(3)), sc(4), 90))
pad_layer = gradient((N, N), [(0, (252, 244, 232)), (1, (226, 210, 190))])
mesh = Image.new("RGBA", (N, N), (0, 0, 0, 0))
md = ImageDraw.Draw(mesh)
for k in range(-N, N, sc(10)):
    md.line([(k, 0), (k + N, N)], fill=(190, 168, 140, 90), width=S)
    md.line([(k + N, 0), (k, N)], fill=(190, 168, 140, 90), width=S)
pad_layer.alpha_composite(mesh)
band.alpha_composite(fill(pad_mask, pad_layer))
# Top highlight along the edge for volume.
hl = Image.new("RGBA", (N, N), (0, 0, 0, 0))
ImageDraw.Draw(hl).rounded_rectangle((bx0 + sc(30), by0 + sc(10), bx0 + bw - sc(30), by0 + sc(30)), sc(12), fill=(255, 240, 220, 90))
band.alpha_composite(Image.composite(hl.filter(ImageFilter.GaussianBlur(sc(6))), Image.new("RGBA", (N, N)), body_mask))

band = band.rotate(38, resample=Image.BICUBIC, center=(N // 2, sc(cy)))
img.alpha_composite(shadow(band.split()[3], (sc(4), sc(14)), sc(14), 140))
img.alpha_composite(band)

# ── "Fixed" badge ──────────────────────────────────────────────────────────
bx, by, br = sc(698), sc(760), sc(118)
badge_mask = Image.new("L", (N, N), 0)
ImageDraw.Draw(badge_mask).ellipse((bx - br, by - br, bx + br, by + br), fill=255)
img.alpha_composite(shadow(badge_mask, (0, sc(12)), sc(14), 140))
img.alpha_composite(fill(badge_mask, gradient((N, N), [(0, (255, 255, 255)), (1, (214, 222, 230))])))
r2 = br - sc(14)
inner_mask = Image.new("L", (N, N), 0)
ImageDraw.Draw(inner_mask).ellipse((bx - r2, by - r2, bx + r2, by + r2), fill=255)
img.alpha_composite(fill(inner_mask, gradient((N, N), [(0, (74, 222, 128)), (0.5, (34, 170, 90)), (1, (18, 120, 62))])))
spec = Image.new("RGBA", (N, N), (0, 0, 0, 0))
ImageDraw.Draw(spec).ellipse((bx - r2 + sc(18), by - r2 + sc(8), bx + r2 - sc(18), by - sc(10)), fill=(255, 255, 255, 60))
img.alpha_composite(Image.composite(spec.filter(ImageFilter.GaussianBlur(sc(6))), Image.new("RGBA", (N, N)), inner_mask))
d = ImageDraw.Draw(img)
check = [(bx - sc(48), by + sc(2)), (bx - sc(12), by + sc(40)), (bx + sc(54), by - sc(38))]
d.line([(x, y + sc(4)) for x, y in check], fill=(10, 80, 40, 120), width=sc(30), joint="curve")
d.line(check, fill=(255, 255, 255, 255), width=sc(30), joint="curve")
for x, y in check:
    d.ellipse((x - sc(15), y - sc(15), x + sc(15), y + sc(15)), fill=(255, 255, 255, 255))

img.resize((1024, 1024), Image.LANCZOS).save(sys.argv[1] if len(sys.argv) > 1 else "icon.png")
