"""자체 부품 텍스처(살점·포자·뼈·상처·이끼·버섯·썩은 심·핵·턱·해골). 우리 그림이라 저장소에 넣을 수 있다.

덧붙인 큐브마다 박스 UV 자리(가로 2(w+d) × 세로 d+h)를 아틀라스에 잡고, 재료 무늬로 채운다. 시드 고정.
"""
import math
import random

from PIL import Image

MAT = {
    'flesh': ((112, 52, 44), (150, 84, 70), (78, 92, 52)),
    'spore': ((96, 150, 70), (196, 236, 120), (52, 80, 40)),
    'bone': ((214, 204, 176), (238, 232, 212), (150, 140, 116)),
    'wound': ((96, 22, 22), (150, 40, 34), (44, 12, 12)),
    'moss': ((70, 96, 44), (110, 140, 62), (44, 60, 30)),
    'fungus': ((120, 84, 56), (170, 60, 48), (230, 214, 190)),
    'rot': ((70, 54, 40), (104, 76, 50), (120, 150, 80)),
    'core': ((150, 230, 90), (236, 255, 190), (70, 130, 50)),
    'jaw': ((112, 52, 44), (232, 226, 200), (60, 20, 20)),
    'skull': ((214, 204, 176), (238, 232, 212), (40, 34, 30)),
}


def _noise(rng, w, h, cell):
    gw, gh = w // cell + 2, h // cell + 2
    g = [[rng.random() for _ in range(gw)] for _ in range(gh)]
    out = [[0.0] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            fx, fy = x / cell, y / cell
            x0, y0 = int(fx), int(fy)
            tx, ty = fx - x0, fy - y0
            a = g[y0][x0] * (1 - tx) + g[y0][x0 + 1] * tx
            b = g[y0 + 1][x0] * (1 - tx) + g[y0 + 1][x0 + 1] * tx
            out[y][x] = a * (1 - ty) + b * ty
    return out


def _mix(a, b, t):
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def fill(img, x0, y0, w, h, d, mat, seed):
    """박스 UV 한 덩이를 재료로 칠한다. w,h,d 는 정수(올림)."""
    base, hi, dark = MAT[mat]
    rng = random.Random(seed)
    W, H = 2 * (w + d), d + h
    n = _noise(rng, W, H, 2)
    px = img.load()
    for y in range(H):
        for x in range(W):
            if (y < d and (x < d or x >= d + 2 * w)):
                continue  # 박스 UV 의 빈 모서리
            v = n[y][x]
            c = _mix(base, hi, max(0.0, (v - 0.5) * 1.6)) if v > 0.5 else _mix(base, dark, (0.5 - v) * 1.2)
            if mat == 'spore' and rng.random() < 0.12:
                c = hi
            if mat == 'flesh' and rng.random() < 0.05:
                c = MAT['spore'][0]
            if mat == 'fungus' and y < d and rng.random() < 0.25:
                c = MAT['fungus'][2]
            if mat == 'core' and rng.random() < 0.15:
                c = (255, 255, 230)
            if mat == 'bone' and rng.random() < 0.08:
                c = dark
            px[x0 + x, y0 + y] = (*c, 255)
    # 재료별 무늬
    if mat == 'jaw':  # 앞면 위쪽에 이빨 줄
        for x in range(d, d + w):
            if (x - d) % 2 == 0:
                px[x0 + x, y0 + d] = (*MAT['bone'][1], 255)
    if mat == 'skull':  # 앞면에 눈구멍 둘
        fx, fy = x0 + d, y0 + d
        for (ex, ey) in [(max(0, w // 4 - 1 + 1), h // 3), (max(0, 3 * w // 4 - 1), h // 3)]:
            if 0 <= ex < w and 0 <= ey < h:
                px[fx + ex, fy + ey] = (30, 26, 22, 255)
    if mat == 'wound':  # 가운데 더 짙게
        cx, cy = x0 + d + w // 2, y0 + d + h // 2
        px[cx, cy] = (30, 8, 8, 255)


def build_atlas(adds, size=64):
    """adds: 덧붙임 목록(dict). 각 항목에 'uv'(아틀라스 좌표)와 'tex_size' 를 채우고 아틀라스 이미지를 돌려준다."""
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    x = y = row = 0
    for i, a in enumerate(adds):
        w, h, d = (max(1, math.ceil(v)) for v in a['size'])
        W, H = 2 * (w + d), d + h
        if x + W > size:
            x, y, row = 0, y + row, 0
        if y + H > size:
            return build_atlas(adds, size * 2)
        fill(img, x, y, w, h, d, a['mat'], seed=1000 + i * 7919)
        a['uv'] = [x, y]
        a['tex_size'] = (size, size)
        x += W
        row = max(row, H)
    return img


def humanoid_overlay(seed=77, w=64, h=32):
    """사람형 장비 레이어(64×32) 자리에 '썩은 살갗'. 머리·몸통 자리에만 드문드문(팔·다리 자리는 투명:
    장비 모델은 해골 팔다리보다 굵어서 살점이 떠 보인다). 어두운 갈색·올리브 위주, 상처는 아주 조금."""
    rng = random.Random(seed)
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    px = img.load()
    rotten = ((74, 52, 40), (104, 78, 56), (70, 84, 46))
    for (u, v, bw, bh, bd) in [(0, 0, 8, 8, 8), (16, 16, 8, 12, 4)]:  # 머리, 몸통
        W, H = 2 * (bw + bd), bd + bh
        n = _noise(rng, W, H, 3)
        for y in range(H):
            for x in range(W):
                if y < bd and (x < bd or x >= bd + 2 * bw):
                    continue
                t = n[y][x]
                drip = (x * 7 + seed) % 6 == 0 and y > bd + 3 and t > 0.55
                if t > 0.7 or drip:
                    c = _mix(rotten[0], rotten[1], min(1.0, (t - 0.55) * 2))
                    if rng.random() < 0.18:
                        c = rotten[2]
                    if t > 0.86:
                        c = MAT['wound'][0]
                    px[u + x, v + y] = (*c, 255)
    return img
