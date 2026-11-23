"""좀비화 3차: 1차의 '좀비 느낌'(썩은 살빛·드러난 살과 뼈·병든 눈빛)을 2차 콘셉트 위에 얹는 공용 칠하기.

Mobestiary 해부도(크리퍼의 두개골·척추·화약, 엔더맨 단면의 뇌·핵, 가디언의 빈 몸속, 가스트의 화염구 위)를
그대로 따라 '안쪽'을 드러내는 데 쓴다. 모든 함수는 사각형(텍스처 픽셀) 기준이라 고해상도(2배) 바탕에서도 같이 쓴다.
"""
import colorsys
import math
import random

import paint as _paint
from paint import Noise, get, lum, mix, pick, put, lput, ramp

# 어두움 → 밝음
FLESH = ramp('#1C0806', '#38100C', '#5A1A14', '#7E2A22', '#A4433A', '#CC6E5C')
SKIN_LIP = ramp('#3A3A2C', '#5A5A44', '#7A7A5E')
BONE = ramp('#544D40', '#7C7462', '#A59B84', '#C9BFA8', '#E2DBC6', '#F6F1E4')
GUNPOWDER = ramp('#101010', '#1E1E1E', '#2E2E2E', '#424242', '#5A5A5A', '#767676')
SPARK = ramp('#7A2A00', '#C25A06', '#F59A1C', '#FFD45E', '#FFF6C4')
BRAIN = ramp('#2E0E26', '#4E1A42', '#76305F', '#A04E84', '#C878A8', '#EAB4D2')
VOIDCORE = ramp('#16061F', '#3A1260', '#6A26A0', '#A04EE0', '#D69AFF', '#FAEBFF')
HOLLOW = ramp('#120303', '#2A0808', '#481010', '#6A1A18', '#8E2A24', '#B4443A')
FIREBALL = ramp('#2A0A02', '#5E1A04', '#A23A08', '#E07018', '#FFB03A', '#FFE9A0')
ICHOR_WET = ramp('#3A3A08', '#6A6A12', '#9C9C22', '#CACA48', '#F0F08A')
ZSKIN = ramp('#24301E', '#34452A', '#4A5E38', '#617848', '#7B925C', '#97AD74')
SICK_EYE = ramp('#3C4A08', '#6E8410', '#A6C022', '#D6EC5A', '#F6FFB8')


def rot_skin(img, rect, amount=0.3, seed=0, mottle=0.12):
    """썩은 살빛: 채도를 빼고 누런 회녹색 쪽으로 돌린다. 군데군데 멍처럼 짙은 얼룩(노이즈)."""
    x0, y0, w, h = rect
    n = Noise(seed, 3.2)
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            c = get(img, x, y)
            if c[3] == 0:
                continue
            hh, s, v = colorsys.rgb_to_hsv(c[0] / 255, c[1] / 255, c[2] / 255)
            s *= 1 - amount
            hh = hh * (1 - amount * 0.45) + 0.2 * amount * 0.45
            m = n(x, y)
            v *= 1 - mottle * max(0.0, m - 0.45) * 2.2
            r, g, b = colorsys.hsv_to_rgb(hh % 1.0, max(0, min(1, s)), max(0, min(1, v)))
            put(img, x, y, (int(r * 255), int(g * 255), int(b * 255), c[3]))


def _blob(cx, cy, rx, ry, seed, jag=0.22):
    """가장자리가 들쭉날쭉한 타원 판정 함수(0 = 가운데, 1 = 가장자리)."""
    rr = random.Random(seed)
    k = [rr.uniform(-jag, jag) for _ in range(7)]

    def d(x, y):
        dx, dy = (x + 0.5 - cx) / max(0.5, rx), (y + 0.5 - cy) / max(0.5, ry)
        a = math.atan2(dy, dx)
        wob = 1 + k[0] * math.sin(3 * a + k[1] * 9) + k[2] * math.sin(5 * a + k[3] * 9) + k[4] * math.cos(2 * a + k[5] * 9)
        return math.hypot(dx, dy) / max(0.35, wob)
    return d


def wound(img, rect, cx, cy, rx, ry, seed=0, inner=FLESH, lip=True, wet=2, drip=0, drip_rp=None):
    """찢긴 상처: 안쪽은 깊을수록 어둡고, 가장자리엔 괴사 띠와 들린 살갗(위쪽 밝은 테), 젖은 반사 몇 점."""
    x0, y0, w, h = rect
    d = _blob(cx, cy, rx, ry, seed)
    rr = random.Random(seed + 7)
    inside = []
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            if get(img, x, y)[3] == 0:
                continue
            t = d(x, y)
            if t < 1.0:
                depth = 1 - t
                idx = 4.2 - depth * 3.6 + (0.35 if (x + y) % 2 else 0) * (depth > 0.3)
                put(img, x, y, inner[max(0, min(len(inner) - 1, int(idx)))])
                inside.append((x, y, t))
            elif t < 1.0 + 1.6 / max(rx, ry, 1):
                above = d(x, y + 1) < 1.0
                if lip and above:
                    put(img, x, y, mix(get(img, x, y), SKIN_LIP[2], 0.55))  # 들린 살갗(위)
                else:
                    put(img, x, y, mix(get(img, x, y), inner[0], 0.7))     # 괴사 띠
    for _ in range(wet):
        if inside:
            x, y, t = rr.choice([p for p in inside if p[2] > 0.25] or inside)
            put(img, x, y, inner[-1])
    for _ in range(drip):
        if inside:
            x, y, _t = max(inside, key=lambda p: p[1] + rr.random() * 2)
            col = drip_rp or inner
            for i in range(rr.randint(2, 4)):
                if y + 1 + i < y0 + h and get(img, x, y + 1 + i)[3]:
                    put(img, x, y + 1 + i, col[2] if i < 2 else col[1])
    return inside


def cavity(img, rect, cx, cy, rx, ry, seed=0, rp=HOLLOW, rim=FLESH):
    """몸속이 드러난 구멍: 가장자리에서 안쪽으로 어두워진다(빈 몸속·가슴 속)."""
    x0, y0, w, h = rect
    d = _blob(cx, cy, rx, ry, seed, jag=0.16)
    cells = []
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            if get(img, x, y)[3] == 0:
                continue
            t = d(x, y)
            if t < 1.0:
                put(img, x, y, rp[max(0, min(len(rp) - 1, int(t * (len(rp) - 1) + ((x * 7 + y * 3) % 3) * 0.2)))])
                cells.append((x, y))
            elif t < 1.0 + 1.4 / max(rx, ry, 1):
                put(img, x, y, rim[2] if d(x, y + 1) < 1.0 else rim[1])
    return d


def bone_bar(img, x0, y0, w, h, horizontal=True, rp=BONE):
    """뼈 막대 한 토막: 위(왼쪽)가 밝고 아래가 어둡다, 끝은 둥글게."""
    for j in range(h if horizontal else w):
        t = j / max(1, (h if horizontal else w) - 1)
        col = rp[4] if t < 0.34 else rp[3] if t < 0.67 else rp[1]
        for i in range(w if horizontal else h):
            end = i == 0 or i == (w if horizontal else h) - 1
            c = rp[2] if end and col == rp[4] else col
            if horizontal:
                put(img, x0 + i, y0 + j, c)
            else:
                put(img, x0 + j, y0 + i, c)


def vertebrae(img, x, y0, y1, width=2, rp=BONE, gap=None):
    """세로 척추: 두 칸짜리 마디와 어두운 틈이 번갈아."""
    gap = gap or FLESH[1]
    y = y0
    k = 0
    while y < y1:
        seg = 2 if k % 2 == 0 else 1
        for yy in range(y, min(y1, y + seg)):
            for xx in range(width):
                if k % 2 == 0:
                    put(img, x + xx, yy, rp[4] if xx == 0 else rp[2])
                else:
                    put(img, x + xx, yy, gap)
        y += seg
        k += 1


def ribs(img, x0, y0, w, h, n, rp=BONE, gap=None):
    """갈비뼈 n 줄(위아래로 고르게)."""
    step = max(2, h // max(1, n))
    for i in range(n):
        y = y0 + i * step
        if y < y0 + h:
            bone_bar(img, x0, y, w, 1, True, rp)


def granules(img, cells, seed, rp=GUNPOWDER, sparks=3, spark_rp=SPARK):
    """화약 알갱이: 짙은 회색 점묘 + 불꽃 몇 점."""
    rr = random.Random(seed)
    for (x, y) in cells:
        put(img, x, y, rp[rr.choice((1, 2, 2, 3, 3, 4))])
    for _ in range(sparks):
        if cells:
            x, y = rr.choice(cells)
            put(img, x, y, spark_rp[rr.choice((3, 4))])


def ellipse_cells(rect, cx, cy, rx, ry):
    x0, y0, w, h = rect
    out = []
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            if ((x + 0.5 - cx) / max(0.5, rx)) ** 2 + ((y + 0.5 - cy) / max(0.5, ry)) ** 2 < 1.0:
                out.append((x, y))
    return out


def scar(img, rect, pts, seed=0, dark=None, light=None, stitches=True):
    """아문 흉터: 가운데 어두운 골 + 양옆 도드라진 밝은 살 + 꿰맨 자국."""
    x0, y0, w, h = rect
    dark = dark or FLESH[1]
    P = [(x0 + px * (w - 1), y0 + py * (h - 1)) for px, py in pts]
    k = 0
    for (ax, ay), (bx, by) in zip(P, P[1:]):
        n = int(max(abs(bx - ax), abs(by - ay))) + 1
        for i in range(n):
            t = i / max(1, n - 1)
            x, y = int(round(ax + (bx - ax) * t)), int(round(ay + (by - ay) * t))
            base = get(img, x, y)
            if base[3] == 0:
                continue
            lput(img, x, y, dark)
            lt = light or mix(base, (255, 245, 230, 255), 0.28)
            if abs(bx - ax) >= abs(by - ay):
                lput(img, x, y - _paint.LINE_GRID, lt)
                if stitches and k % 3 == 0:
                    lput(img, x, y + _paint.LINE_GRID, mix(base, dark, 0.6))
                    lput(img, x, y - _paint.LINE_GRID, mix(base, dark, 0.6))
            else:
                lput(img, x - _paint.LINE_GRID, y, lt)
                if stitches and k % 3 == 0:
                    lput(img, x + _paint.LINE_GRID, y, mix(base, dark, 0.6))
                    lput(img, x - _paint.LINE_GRID, y, mix(base, dark, 0.6))
            k += 1


def tear(img, x, y0, length, rp, width=1):
    """눈물 자국: 위는 젖어 밝고 아래로 갈수록 말라 옅어진다."""
    for i in range(length):
        for j in range(width):
            base = get(img, x + j, y0 + i)
            if base[3] == 0:
                continue
            t = i / max(1, length - 1)
            put(img, x + j, y0 + i, mix(base, rp[1] if i else rp[3], 0.75 - 0.45 * t))


def sick_eyes(img, rect, test, rp=SICK_EYE):
    """눈 픽셀(test 가 True)을 병든 빛으로: 가운데 밝게."""
    x0, y0, w, h = rect
    pts = [(x, y) for y in range(y0, y0 + h) for x in range(x0, x0 + w) if get(img, x, y)[3] and test(get(img, x, y))]
    for (x, y) in pts:
        c = get(img, x, y)
        put(img, x, y, pick(rp, 0.55 + lum(c) * 0.5, x, y))


def zombie_face(img, faces, seed, skin=None, wound_side='left', eye_test=None, wounds=True):
    """C 방식 머리 덮개: 바닐라 머리 면을 그대로 옮긴 뒤 좀비 주민처럼 다시 칠한다.
    픽셀마다 명암(밝기)은 그대로 두고 색만 좀비 녹색 쪽으로 돌린다. 디더링을 쓰지 않아 바닐라 얼굴의 눈·눈썹·입이 또렷하게 남는다.
    눈 흰자는 병든 빛, 머리 덮개에만 한쪽 뺨 상처(코에는 그리지 않음)."""
    for face, (x0, y0, w, h) in faces.items():
        for y in range(y0, y0 + h):
            for x in range(x0, x0 + w):
                c = get(img, x, y)
                if c[3] == 0:
                    continue
                if eye_test and face == 'front' and eye_test(c):
                    put(img, x, y, SICK_EYE[3])
                    continue
                hh, s, v = colorsys.rgb_to_hsv(c[0] / 255, c[1] / 255, c[2] / 255)
                hh = 0.25                                  # 누런 녹색(좀비 주민 살빛 쪽)
                s = min(0.55, 0.22 + s * 0.6)
                v = v * 0.86
                r, g, b = colorsys.hsv_to_rgb(hh, s, v)
                put(img, x, y, (int(r * 255), int(g * 255), int(b * 255), c[3]))
    if not wounds:
        return
    f = faces.get('front')
    if f and f[2] >= 4 and f[3] >= 4:
        cx = f[0] + (f[2] * 0.28 if wound_side == 'left' else f[2] * 0.72)
        wound(img, f, cx, f[1] + f[3] * 0.72, max(1.0, f[2] * 0.16), max(1.0, f[3] * 0.12), seed, wet=1)
    side = faces.get('right' if wound_side == 'left' else 'left')
    if side and side[2] >= 4:
        wound(img, side, side[0] + side[2] * 0.5, side[1] + side[3] * 0.4, max(1.0, side[2] * 0.14), max(1.0, side[3] * 0.1), seed + 3, wet=1)
