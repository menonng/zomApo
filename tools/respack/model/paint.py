"""픽셀아트 칠하기 도구: 색 띠(어두움 → 밝음), 베이어 디더링, 면 방향 명암, 질감 무늬.

원칙
- 덧붙인 요소 하나에 색 한 가지가 아니라 **색 띠 5~6단계**를 쓴다(그림자·중간·밝음·하이라이트·포인트).
- 빛은 위·앞-왼쪽에서. 윗면이 가장 밝고 아랫면이 가장 어둡다. 옆면은 위에서 아래로 어두워지고 가장자리에 1px 그늘(앰비언트 오클루전).
- 색이 바뀌는 곳은 무작위 점이 아니라 4×4 베이어 디더링으로 섞는다.
- 무늬(균사·알·균열·따개비·녹청·잿불·성에 …)는 재료 위에 따로 그린다.
이 파일은 우리 그림 도구라 저장소에 넣는다.
"""
import math
import random

BAYER4 = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def hexc(s, a=255):
    s = s.lstrip('#')
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


def ramp(*hexes, a=255):
    return [hexc(h, a) for h in hexes]


def with_alpha(r, a):
    return [(c[0], c[1], c[2], a) for c in r]


def pick(rp, t, x, y):
    """t(0~1)에 맞는 색. 이웃 두 색 사이는 베이어 디더링."""
    n = len(rp) - 1
    t = min(0.9999, max(0.0, t))
    f = t * n
    i = int(f)
    frac = f - i
    thr = (BAYER4[y % 4][x % 4] + 0.5) / 16.0
    return rp[min(n, i + 1)] if frac > thr else rp[i]


class Noise:
    """값 노이즈(격자 보간). 시드 고정."""

    def __init__(self, seed, cell=3.0):
        self.r = random.Random(seed)
        self.cell = cell
        self.g = {}

    def _v(self, ix, iy):
        k = (ix, iy)
        if k not in self.g:
            self.g[k] = self.r.random()
        return self.g[k]

    def __call__(self, x, y):
        fx, fy = x / self.cell, y / self.cell
        x0, y0 = math.floor(fx), math.floor(fy)
        tx, ty = fx - x0, fy - y0
        tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
        a = self._v(x0, y0) * (1 - tx) + self._v(x0 + 1, y0) * tx
        b = self._v(x0, y0 + 1) * (1 - tx) + self._v(x0 + 1, y0 + 1) * tx
        return a * (1 - ty) + b * ty


def put(img, x, y, c):
    if c is None:
        return
    w, h = img.size
    if 0 <= x < w and 0 <= y < h:
        if len(c) == 4 and c[3] < 255:
            # 반투명은 그대로 덮어쓴다(엔티티 텍스처의 반투명 픽셀)
            img.putpixel((x, y), c)
        else:
            img.putpixel((x, y), c if len(c) == 4 else c + (255,))


def get(img, x, y):
    w, h = img.size
    if 0 <= x < w and 0 <= y < h:
        return img.getpixel((x, y))
    return (0, 0, 0, 0)


def mix(a, b, t):
    return tuple(int(round(a[i] * (1 - t) + b[i] * t)) for i in range(3)) + (a[3] if len(a) > 3 else 255,)


# ---------- 박스 UV 면 ----------

def box_faces(u, v, w, h, d):
    """박스 UV 의 면 사각형(x, y, 너비, 높이). 방향(26.3 ModelPart 기준으로 확인):
    up: 위 행 = 뒤, 아래 행 = 앞, 오른쪽 열 = 몹의 왼쪽 / 옆면들: 위 = 위, 밖에서 본 그대로(front 의 오른쪽 = 몹의 왼쪽)
    right = 몹의 오른쪽 옆면(오른쪽 열이 앞), left = 몹의 왼쪽 옆면(왼쪽 열이 앞), back = 뒷면."""
    return {
        'up': (u + d, v, w, d),
        'down': (u + d + w, v, w, d),
        'right': (u, v + d, d, h),
        'front': (u + d, v + d, w, h),
        'left': (u + d + w, v + d, d, h),
        'back': (u + 2 * d + w, v + d, w, h),
    }


FACE_BASE = {'up': 0.80, 'down': 0.26, 'front': 0.60, 'back': 0.56, 'right': 0.55, 'left': 0.58}


def shade_face(img, rect, rp, face, seed=0, var=0.16, grad=0.14, base=None, ao=0.12, rim=0.08, alpha=None, cell=2.5):
    """재료 바탕: 면 방향 밝기 + 세로 그러데이션 + 노이즈 + 가장자리 그늘 + 윗가장자리 반사광."""
    x0, y0, w, h = rect
    n = Noise(seed, cell)
    b = FACE_BASE[face] if base is None else base
    for y in range(h):
        for x in range(w):
            t = b + (n(x, y) - 0.5) * 2 * var
            if face not in ('up', 'down'):
                t += grad * (0.5 - (y + 0.5) / max(1, h)) * 2
                if y == h - 1:
                    t -= ao
                if y == 0:
                    t += rim
                if x == 0 or x == w - 1:
                    t -= ao * 0.5
            else:
                if x == 0 or y == 0 or x == w - 1 or y == h - 1:
                    t -= ao * 0.6
            c = pick(rp, t, x0 + x, y0 + y)
            if alpha is not None:
                c = (c[0], c[1], c[2], alpha)
            put(img, x0 + x, y0 + y, c)


def paint_box(img, u, v, w, h, d, painter):
    """박스 UV 한 덩이의 면마다 painter(face, rect) 를 부른다."""
    for face, rect in box_faces(u, v, w, h, d).items():
        if rect[2] > 0 and rect[3] > 0:
            painter(face, rect)


# ---------- 무늬 ----------

def disc(img, cx, cy, r, rp, seed=0, spec=True, alpha=None):
    """둥근 덩이(알·버섯 점·포자 혹): 왼쪽 위가 밝고 오른쪽 아래가 어둡게, 1px 반사광."""
    for y in range(int(cy - r - 1), int(cy + r + 2)):
        for x in range(int(cx - r - 1), int(cx + r + 2)):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            dd = math.hypot(dx, dy)
            if dd <= r:
                t = 0.62 - 0.55 * (dx + dy) / (2 * r + 0.01) - 0.25 * (dd / (r + 0.01)) ** 2
                c = pick(rp, t, x, y)
                if alpha is not None:
                    c = (c[0], c[1], c[2], alpha)
                put(img, x, y, c)
    if spec and r >= 1.2:
        put(img, int(cx - r * 0.45), int(cy - r * 0.45), rp[-1])


def crack(img, rect, seed, length, dark, light=None, glow=None, start=None, heading=None, wander=0.6, branch=0.15):
    """균열: 어두운 선 + 한쪽 1px 밝은 모서리(깎인 느낌), glow 를 주면 가운데가 빛나는 틈."""
    x0, y0, w, h = rect
    r = random.Random(seed)
    x, y = start if start else (x0 + r.randrange(max(1, w)), y0 + r.randrange(max(1, h)))
    a = heading if heading is not None else r.uniform(0, 2 * math.pi)
    stack = [(x, y, a, length)]
    while stack:
        x, y, a, L = stack.pop()
        fx, fy = float(x), float(y)
        for _ in range(L):
            a += r.uniform(-wander, wander)
            fx += math.cos(a)
            fy += math.sin(a)
            ix, iy = int(round(fx)), int(round(fy))
            if not (x0 <= ix < x0 + w and y0 <= iy < y0 + h):
                break
            if glow:
                put(img, ix, iy, glow[-1] if r.random() < 0.55 else glow[-2])
                for ox, oy in ((1, 0), (0, 1), (-1, 0), (0, -1)):
                    px, py = ix + ox, iy + oy
                    if x0 <= px < x0 + w and y0 <= py < y0 + h and get(img, px, py)[:3] not in [g[:3] for g in glow[-2:]]:
                        if r.random() < 0.55:
                            put(img, px, py, glow[1] if r.random() < 0.6 else glow[2])
            else:
                put(img, ix, iy, dark[0] if r.random() < 0.6 else dark[1])
                if light is not None:
                    px, py = ix + (1 if math.sin(a) > 0 else 0), iy + (1 if math.cos(a) > 0 else 0)
                    if (px, py) != (ix, iy) and x0 <= px < x0 + w and y0 <= py < y0 + h:
                        put(img, px, py, light)
            if r.random() < branch and L > 3:
                stack.append((ix, iy, a + r.choice([-1, 1]) * r.uniform(0.6, 1.2), L // 2))


def threads(img, rect, seed, n, rp, origin=None, length=8, shadow=None, branch=0.22):
    """균사·실: 가는 선이 갈라지며 뻗는다. 아래쪽에 1px 그림자."""
    x0, y0, w, h = rect
    r = random.Random(seed)
    for k in range(n):
        if origin:
            x, y = origin
        else:
            x, y = x0 + r.randrange(max(1, w)), y0 + r.randrange(max(1, h))
        a = r.uniform(0, 2 * math.pi)
        stack = [(x, y, a, length)]
        while stack:
            x, y, a, L = stack.pop()
            fx, fy = float(x), float(y)
            for i in range(L):
                a += r.uniform(-0.7, 0.7)
                fx += math.cos(a)
                fy += math.sin(a)
                ix, iy = int(round(fx)), int(round(fy))
                if not (x0 <= ix < x0 + w and y0 <= iy < y0 + h):
                    break
                put(img, ix, iy, rp[-1] if i < L * 0.4 else rp[-2])
                if shadow is not None and y0 <= iy + 1 < y0 + h and get(img, ix, iy + 1)[:3] not in [c[:3] for c in rp]:
                    put(img, ix, iy + 1, shadow)
                if r.random() < branch:
                    stack.append((ix, iy, a + r.choice([-1, 1]) * r.uniform(0.7, 1.4), max(2, L // 2)))


def stain(img, rect, seed, rp, coverage=0.4, cell=3.0, edge=None, keep=None, on_empty=False):
    """번짐: 노이즈 문턱 위만 칠한다. 경계에는 edge 색(번진 테두리). keep(c)->bool 이면 그 픽셀은 건드리지 않음."""
    x0, y0, w, h = rect
    n = Noise(seed, cell)
    thr = 1.0 - coverage
    for y in range(h):
        for x in range(w):
            v = n(x, y)
            px, py = x0 + x, y0 + y
            old = get(img, px, py)
            if (old[3] == 0 and not on_empty) or (keep and keep(old)):
                continue
            if v > thr:
                t = (v - thr) / max(0.01, 1 - thr)
                put(img, px, py, pick(rp, 0.25 + 0.6 * t, px, py))
            elif edge is not None and v > thr - 0.06 and (old[3] or on_empty):
                put(img, px, py, edge)


def speckle(img, rect, seed, density, colors, keep=None, on_empty=False):
    x0, y0, w, h = rect
    r = random.Random(seed)
    for y in range(h):
        for x in range(w):
            if r.random() < density:
                px, py = x0 + x, y0 + y
                old = get(img, px, py)
                if (old[3] == 0 and not on_empty) or (keep and keep(old)):
                    continue
                put(img, px, py, r.choice(colors))


def drips(img, rect, seed, n, rp, max_len=None, top=None):
    """흘러내림: 위에서 아래로. 왼쪽 밝음, 오른쪽 어둠, 끝에 방울."""
    x0, y0, w, h = rect
    r = random.Random(seed)
    for _ in range(n):
        x = x0 + r.randrange(max(1, w))
        L = r.randint(2, max(2, (max_len or h) - 1))
        ys = top if top is not None else y0
        for i in range(L):
            y = ys + i
            if y >= y0 + h:
                break
            put(img, x, y, pick(rp, 0.75 - 0.4 * i / L, x, y))
        yb = ys + L
        if yb < y0 + h:
            put(img, x, yb, rp[1])
            put(img, x, min(y0 + h - 1, yb - 1), rp[-1])


def ring(img, cx, cy, rp, center, size=3):
    """따개비·구멍: 둥근 테두리(위 밝게) + 어두운 가운데."""
    if size <= 2:
        put(img, cx, cy, center)
        put(img, cx - 1, cy, rp[-2])
        put(img, cx, cy - 1, rp[-1])
        put(img, cx + 1, cy, rp[1])
        put(img, cx, cy + 1, rp[0])
        return
    for y in range(-1, 2):
        for x in range(-1, 2):
            if x == 0 and y == 0:
                put(img, cx, cy, center)
            else:
                t = 0.75 - 0.25 * (x + y)
                put(img, cx + x, cy + y, pick(rp, t, cx + x, cy + y))
    put(img, cx - 1, cy - 1, rp[-1])


def plank_strands(img, rect, seed, rp, density=0.55, alpha=255, gap_alpha=0):
    """늘어진 가닥(이끼·다시마·거미줄 띠): 세로 가닥, 끝이 들쭉날쭉, 사이는 투명."""
    x0, y0, w, h = rect
    r = random.Random(seed)
    for x in range(w):
        if r.random() > density:
            for y in range(h):
                put(img, x0 + x, y0 + y, (0, 0, 0, gap_alpha))
            continue
        L = r.randint(max(1, h // 2), h)
        for y in range(h):
            if y < L:
                t = 0.8 - 0.55 * y / max(1, L) + r.uniform(-0.1, 0.1)
                c = pick(rp, t, x0 + x, y0 + y)
                put(img, x0 + x, y0 + y, (c[0], c[1], c[2], alpha))
            else:
                put(img, x0 + x, y0 + y, (0, 0, 0, 0))


def web(img, rect, seed, col=(240, 240, 240, 190), col2=(205, 205, 205, 140)):
    """거미줄: 방사선 + 동심선, 나머지 투명."""
    x0, y0, w, h = rect
    for y in range(h):
        for x in range(w):
            put(img, x0 + x, y0 + y, (0, 0, 0, 0))
    r = random.Random(seed)
    cx, cy = x0 + w * r.uniform(0.3, 0.7), y0 + h * r.uniform(0.2, 0.5)
    for k in range(6):
        a = k * math.pi / 3 + r.uniform(-0.2, 0.2)
        for s in range(max(w, h)):
            put(img, int(cx + math.cos(a) * s), int(cy + math.sin(a) * s), col)
    for rr in (2, 4, 6):
        for k in range(24):
            a = k * 2 * math.pi / 24
            if r.random() < 0.8:
                put(img, int(cx + math.cos(a) * rr), int(cy + math.sin(a) * rr * 0.9), col2)


def peel(img, rect, seed, n, light, shadow, size=(2, 4)):
    """벗겨지는 살갗·껍질: 밝은 조각 + 아래·오른쪽 1px 그림자."""
    x0, y0, w, h = rect
    r = random.Random(seed)
    for _ in range(n):
        pw, ph = r.randint(*size), r.randint(1, max(1, size[1] - 1))
        px, py = x0 + r.randrange(max(1, w - pw)), y0 + r.randrange(max(1, h - ph))
        for y in range(ph):
            for x in range(pw):
                if r.random() < 0.85:
                    put(img, px + x, py + y, light[0] if (x + y) % 3 else light[1])
        for x in range(pw + 1):
            put(img, px + x, py + ph, shadow)
        for y in range(ph):
            put(img, px + pw, py + y, shadow)


def frost_edge(img, rect, seed, rp, depth=2):
    """성에: 위쪽 가장자리에 흰빛 결정, 아래로 삐죽."""
    x0, y0, w, h = rect
    r = random.Random(seed)
    for x in range(w):
        L = r.randint(1, depth + 1)
        for y in range(min(h, L)):
            put(img, x0 + x, y0 + y, rp[-1] if y == 0 else pick(rp, 0.7 - 0.2 * y, x0 + x, y0 + y))


def rivets(img, points, rp):
    for (x, y) in points:
        put(img, x, y, rp[-1])
        put(img, x + 1, y + 1, rp[0])


def glow_dot(img, cx, cy, rp):
    put(img, cx, cy, rp[-1])
    for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        if get(img, cx + ox, cy + oy)[3]:
            put(img, cx + ox, cy + oy, rp[-3])


def recolor(img, rect, fn):
    x0, y0, w, h = rect
    for y in range(h):
        for x in range(w):
            c = get(img, x0 + x, y0 + y)
            if c[3]:
                nc = fn(c, x, y)
                if nc is not None:
                    put(img, x0 + x, y0 + y, nc)


def lum(c):
    return (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) / 255.0


def to_ramp(img, rect, rp, lo=0.0, hi=1.0, keep_alpha=True, gamma=1.0):
    """영역의 밝기를 색 띠로 다시 칠한다(원래 명암은 지키고 색만 바꾼다)."""
    def f(c, x, y):
        t = (lum(c) - lo) / max(0.01, hi - lo)
        t = max(0.0, min(1.0, t)) ** gamma
        nc = pick(rp, t, rect[0] + x, rect[1] + y)
        return (nc[0], nc[1], nc[2], c[3] if keep_alpha else nc[3])
    recolor(img, rect, f)


# ---------- 2차: 형태가 읽히는 무늬 ----------

def sphere_faces(img, faces, rp, alpha=None, spec=True, rim=0.18, strength=0.42):
    """둥근 덩이처럼 보이게: 면마다 왼쪽 위로 치우친 빛 중심의 방사형 그러데이션 + 어두운 테두리 + 반사광."""
    for face, (x0, y0, w, h) in faces.items():
        if w <= 0 or h <= 0:
            continue
        b = FACE_BASE[face]
        cx, cy = (w - 1) * 0.35, (h - 1) * 0.35
        R = max(1.0, math.hypot(w, h) * 0.62)
        for y in range(h):
            for x in range(w):
                dd = math.hypot(x - cx, y - cy) / R
                t = b + strength * (0.55 - dd)
                if x == 0 or y == 0 or x == w - 1 or y == h - 1:
                    t -= rim * (0.6 if face == 'up' else 1.0)
                c = pick(rp, t, x0 + x, y0 + y)
                if alpha is not None:
                    c = (c[0], c[1], c[2], alpha)
                put(img, x0 + x, y0 + y, c)
        if spec and face in ('up', 'front', 'right', 'left') and w >= 2 and h >= 2:
            put(img, x0 + int(cx), y0 + int(cy), rp[-1] if alpha is None else (rp[-1][0], rp[-1][1], rp[-1][2], min(255, (alpha or 255) + 20)))


def path_points(rect, pts):
    x0, y0, w, h = rect
    return [(x0 + p[0] * (w - 1), y0 + p[1] * (h - 1)) for p in pts]


def stroke(img, rect, pts, rp, width=1, shade=None, glow=False, clip=True, core=-2, halo=2, halo_p=0.5, seed=0):
    """정해 둔 경로(사각형 안 비율 좌표)를 따라 선을 긋는다.
    보통 선: 가운데 rp[-2], width 2 면 아래·오른쪽 한 줄을 그늘색. glow: 가운데 rp[core], 둘레 일부 rp[halo](은은한 빛)."""
    x0, y0, w, h = rect
    r = random.Random(seed)
    P = path_points(rect, pts)
    done = set()
    for (ax, ay), (bx, by) in zip(P, P[1:]):
        n = int(max(abs(bx - ax), abs(by - ay))) + 1
        for i in range(n + 1):
            t = i / max(1, n)
            x, y = int(round(ax + (bx - ax) * t)), int(round(ay + (by - ay) * t))
            if clip and not (x0 <= x < x0 + w and y0 <= y < y0 + h):
                continue
            done.add((x, y))
            put(img, x, y, rp[core] if glow else rp[-2])
            if width >= 2 and not glow:
                sx = x + 1
                if (not clip) or (x0 <= sx < x0 + w):
                    put(img, sx, y, shade if shade else rp[1])
    if glow:
        for (x, y) in list(done):
            for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                px, py = x + ox, y + oy
                if (px, py) in done or not (x0 <= px < x0 + w and y0 <= py < y0 + h):
                    continue
                if r.random() < halo_p:
                    put(img, px, py, rp[halo])


def colony(img, cx, cy, r, rp):
    """곰팡이 군락: 바깥 테 밝게, 안쪽 어둡게, 가운데 점."""
    for y in range(int(cy - r - 1), int(cy + r + 2)):
        for x in range(int(cx - r - 1), int(cx + r + 2)):
            dd = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if dd <= r:
                t = 0.35 + 0.5 * (dd / max(0.5, r)) ** 2
                put(img, x, y, pick(rp, t, x, y))
    put(img, int(cx), int(cy), rp[0])


def chip_edges(img, rect, seed, n, col):
    """가장자리 몇 픽셀을 떨어져 나간 듯(다른 색)으로."""
    x0, y0, w, h = rect
    r = random.Random(seed)
    for _ in range(n):
        side = r.randrange(4)
        if side == 0:
            x, y = x0 + r.randrange(w), y0
        elif side == 1:
            x, y = x0 + r.randrange(w), y0 + h - 1
        elif side == 2:
            x, y = x0, y0 + r.randrange(h)
        else:
            x, y = x0 + w - 1, y0 + r.randrange(h)
        put(img, x, y, col)
