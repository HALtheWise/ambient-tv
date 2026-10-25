#!/usr/bin/env python3
"""Render the home screen shelf banners (320x180) with the same algorithms as the scenes.

    python3 tools/banners.py        (needs numpy and Pillow)
"""
import os
import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "launcher", "res", "drawable-xhdpi")
W, H = 320, 180
rng = np.random.default_rng(146)


def save(name, rgb):
    Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8)).save(os.path.join(OUT, name + ".png"))
    print("wrote", name)


def fire():
    pal = [0x070707, 0x1F0707, 0x2F0F07, 0x470F07, 0x571707, 0x671F07, 0x771F07, 0x8F2707, 0x9F2F07, 0xAF3F07,
           0xBF4707, 0xC74707, 0xDF4F07, 0xDF5707, 0xDF5707, 0xD75F07, 0xD75F07, 0xD7670F, 0xCF6F0F, 0xCF770F,
           0xCF7F0F, 0xCF8717, 0xC78717, 0xC78F17, 0xC7971F, 0xBF9F1F, 0xBF9F1F, 0xBFA727, 0xBFA727, 0xBFAF2F,
           0xB7AF2F, 0xB7B72F, 0xB7B737, 0xCFCF6F, 0xDFDF9F, 0xEFEFC7, 0xFFFFFF]
    pal = np.array([[(c >> 16) & 255, (c >> 8) & 255, c & 255] for c in pal])
    w, h = 160, 90
    f = np.zeros((h, w), int)
    x = np.arange(w)
    d = np.abs(x - w / 2) / (w * 0.3)
    f[h - 1] = np.where(d < 1, np.round(36 * (1 - d * d * 0.3)), 0)
    for _ in range(140):
        for y in range(1, h):
            r = rng.integers(0, 4, w)
            dst = np.clip(x - r + 1, 0, w - 1)
            cool = (r & 1) * (rng.random(w) < 0.75)
            f[y - 1, dst] = np.maximum(0, f[y, x] - cool)
    img = Image.fromarray(pal[f].astype(np.uint8)).resize((W, H), Image.BILINEAR)
    save("banner_fire", np.array(img))


def fractal():
    ys, xs = np.mgrid[0:H, 0:W]
    scale = 0.02
    cx, cy = -0.7436, 0.1318
    c = (cx + (xs - W / 2) / H * 2 * scale) + 1j * (cy - (ys - H / 2) / H * 2 * scale)
    z = np.zeros_like(c)
    dz = np.zeros_like(c)
    n = np.zeros(c.shape)
    zf = np.zeros_like(c)
    dzf = np.zeros_like(c)
    alive = np.ones(c.shape, bool)
    for i in range(600):
        dz[alive] = 2 * z[alive] * dz[alive] + 1
        z[alive] = z[alive] ** 2 + c[alive]
        esc = alive & (np.abs(z) > 256)
        n[esc] = i + 1 - np.log2(np.log2(np.abs(z[esc])))
        zf[esc], dzf[esc] = z[esc], dz[esc]
        alive &= ~esc
    t = n * 0.02
    col = (0.5 + 0.5 * np.cos(2 * np.pi * (t[..., None] + np.array([0.0, 0.12, 0.25])))) ** 1.4
    r = np.abs(zf)
    dist = np.where(alive, 0, r * np.log(np.maximum(r, 1.0001)) / np.maximum(np.abs(dzf), 1e-30))
    pixels = dist / (2 * scale / H)
    glow = np.minimum(1, np.exp(-pixels * 0.12) + 0.06 * np.exp(-pixels * 0.01))
    col *= glow[..., None] * 0.95
    col[alive] = 0
    save("banner_fractal", col * 255)


def reaction_diffusion():
    w, h = 160, 90
    a = np.ones((h, w))
    b = np.zeros((h, w))
    for _ in range(25):
        y, x = rng.integers(0, h), rng.integers(0, w)
        b[max(0, y - 3):y + 3, max(0, x - 3):x + 3] = 0.6
    f, k = 0.0545, 0.062

    def lap(m):
        return (-m + 0.2 * (np.roll(m, 1, 0) + np.roll(m, -1, 0) + np.roll(m, 1, 1) + np.roll(m, -1, 1))
                + 0.05 * (np.roll(np.roll(m, 1, 0), 1, 1) + np.roll(np.roll(m, 1, 0), -1, 1)
                          + np.roll(np.roll(m, -1, 0), 1, 1) + np.roll(np.roll(m, -1, 0), -1, 1)))
    for _ in range(6000):
        abb = a * b * b
        a, b = a + lap(a) - abb + f * (1 - a), b + 0.5 * lap(b) + abb - (k + f) * b
    v = np.clip((b - 0.08) / 0.27, 0, 1)
    v = v * v * (3 - 2 * v)
    ink, deep = np.array([0.94, 0.64, 0.35]), np.array([0.06, 0.03, 0.02])
    gy, gx = np.gradient(b)
    light = np.clip((0.4 * gx * 20 - 0.6 * gy * 20 + 0.7) / 1.2, 0, 1)
    col = (deep + (ink - deep) * v[..., None]) * (0.55 + 0.6 * light[..., None])
    img = Image.fromarray(np.clip(col * 255, 0, 255).astype(np.uint8)).resize((W, H), Image.BICUBIC)
    save("banner_rd", np.array(img))


def ant():
    w, h = 80, 45
    g = np.zeros((h, w), int)
    x, y, d = w // 2 - 6, h // 2 + 6, 0
    for _ in range(11500):
        d = (d + (1 if g[y, x] == 0 else 3)) & 3
        g[y, x] ^= 1
        if d == 0: y = (y - 1) % h
        elif d == 1: x = (x + 1) % w
        elif d == 2: y = (y + 1) % h
        else: x = (x - 1) % w
    cell = 4
    img = np.zeros((h * cell, w * cell, 3))
    warm = np.array([0.94, 0.64, 0.35]) * 0.8 * 255
    for j in range(h):
        for i in range(w):
            if g[j, i]:
                img[j * cell:(j + 1) * cell - 1, i * cell:(i + 1) * cell - 1] = warm
    img[y * cell:(y + 1) * cell - 1, x * cell:(x + 1) * cell - 1] = 255
    save("banner_ant", img)


def life():
    """Gosper's glider gun mid-volley, drawn like the home screen: amber cells fading with age."""
    rle = ("24bo$22bobo$12b2o6b2o12b2o$11bo3bo4b2o12b2o$2o8bo5bo3b2o$2o8bo3bob2o4bobo$"
           "10bo5bo7bo$11bo3bo$12b2o!")
    cells, x, y, n = set(), 0, 0, ""
    for ch in rle:
        if ch.isdigit():
            n += ch
            continue
        k = int(n or 1)
        n = ""
        if ch == "b": x += k
        elif ch == "o":
            for i in range(k): cells.add((x + i, y))
            x += k
        elif ch == "$": y, x = y + k, 0
    age = {c: 1 for c in cells}
    ghosts = {}
    for _ in range(96):
        counts = {}
        for (cx, cy) in cells:
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    if dx or dy:
                        counts[(cx + dx, cy + dy)] = counts.get((cx + dx, cy + dy), 0) + 1
        nxt = {c for c, k in counts.items() if k == 3 or (k == 2 and c in cells)}
        for c in cells - nxt: ghosts[c] = 0
        ghosts = {c: g + 1 for c, g in ghosts.items() if g < 6 and c not in nxt}
        age = {c: age.get(c, 0) + 1 for c in nxt}
        cells = nxt
    cell = 7
    img = Image.new("RGB", (W, H), (7, 7, 8))
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)
    ox, oy = 18, 14
    amber = np.array([240, 164, 88])
    def put(c, a):
        px, py = ox + c[0] * cell, oy + c[1] * cell
        if -cell < px < W and -cell < py < H:
            col = tuple(int(v) for v in amber * a + np.array([7, 7, 8]) * (1 - a))
            d.rounded_rectangle([px, py, px + cell - 2, py + cell - 2], radius=2, fill=col)
    for c, g in ghosts.items(): put(c, 0.1 * (1 - g / 6))
    for c, a in age.items(): put(c, min(1, 0.22 + 0.6 * np.exp(-(a - 1) / 6) + 0.18))
    save("banner_life", np.array(img))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    life()
    fire()
    fractal()
    reaction_diffusion()
    ant()
