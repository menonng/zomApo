"""좀비화 몹 디자인 4차(2026-11-21): 3차 위에 운영자 의견을 반영한다.

- 겹 구조(layered): 신체 내부(두개골·뇌·갈비뼈·빈 몸속·위)를 보여 줄 때는 칠한 그림이 아니라 '두 겹'으로 만든다.
  원종 큐브를 한 칸 안으로 줄여 속(뼈·장기)을 칠하고, 원래 자리에 살 껍질을 씌워 찢긴 구멍을 뚫는다.
  구멍 둘레에는 안쪽으로 들어가는 벽을 세워(build.pocket_walls) 비스듬히 봐도 깊이가 보이고 뒤가 비치지 않는다.
  겉모양(실루엣)은 바닐라 그대로다. 크리퍼 얼굴·가슴·등, 엔더맨 정수리·가슴, 가디언 옆구리, 가스트 배.
- 2배 텍스처: 모든 몹(build.HIRES = 2).
- 해골 계열(스켈레톤·스트레이·보그드)·마그마 큐브: 1차 디자인으로 되돌린다(1차 텍스처 좀비화 + 썩은 살점).
  해골은 B 방식이라 몸 텍스처를 못 바꾸므로, 1차 모습을 머리 덮개(머리 아이템)와 장비 레이어(몸통)에 옮겨 그린다.
- 스트레이·보그드는 바닐라 덧층(옷·덩굴) 텍스처를 함께 그린다. 3차에서 빠져 있어 얼굴이 이상해 보였다.
- 가스트: 앞면 아래 상처(불꽃 턱수염처럼 보이던 것)와 촉수 끝 덩어리를 뺐다. 배 아래에 움푹 들어간 구멍 속 화염구.
- 위더 스켈레톤: 붉은 덩어리(딸기잼처럼 보이던 것)를 빼고, 그을려 말라붙은 살가죽과 가는 진홍 균사.
- 피글린 브루트: 목의 붉은 얼룩을 빼고 바닐라 좀비 피글린 문법(드러난 두개골·갈비뼈·찢긴 귀)에 녹슨 금.
- 약탈자·소환사 머리띠는 머리 덮개에 딱 붙게, 변명자 도끼는 정수리에 박힌 각도로 고쳤다.
"""
import colorsys
import math
import os
import random
import sys

from PIL import Image

import designs as D
import designs3 as D3
import rot as R
from designs import A, bfaces, mat, chain
from paint import ramp, stain, put, get, lum, mix, speckle, threads, pick

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import zombify_textures as zt  # noqa: E402  1차 텍스처 좀비화

JAR = [None]   # build.py 가 정한다(바닐라 좀비 피글린 텍스처 등 참고용)


# ================================================================== 겹 구조 도구

class Hole:
    """껍질 면의 찢긴 구멍(면 정규 좌표 0~1). safe: 가장자리에서 이만큼 안쪽까지만 뚫는다(속 큐브 밖으로 새지 않게)."""

    def __init__(self, cx, cy, rx, ry, seed, jag=0.2, safe=(0.0, 0.0)):
        rr = random.Random(seed)
        self.cx, self.cy, self.rx, self.ry = cx, cy, rx, ry
        self.amps = [rr.uniform(-jag, jag) for _ in range(9)]
        self.su, self.sv = safe

    def _r(self, ang):
        n = len(self.amps)
        t = (ang + math.pi) / (2 * math.pi) * n
        i, f = int(t) % n, t - int(t)
        f = (1 - math.cos(f * math.pi)) / 2
        return 1 + self.amps[i] * (1 - f) + self.amps[(i + 1) % n] * f

    def dist(self, u, v):
        dx, dy = (u - self.cx) / self.rx, (v - self.cy) / self.ry
        return math.hypot(dx, dy) / self._r(math.atan2(dy, dx))

    def inside(self, u, v):
        if not (self.su <= u <= 1 - self.su and self.sv <= v <= 1 - self.sv):
            return False
        return self.dist(u, v) <= 1.0

    def box(self):
        m = 1 + max(abs(a) for a in self.amps)
        return (max(self.su, self.cx - self.rx * m), max(self.sv, self.cy - self.ry * m),
                min(1 - self.su, self.cx + self.rx * m), min(1 - self.sv, self.cy + self.ry * m))


def safe_of(size, face, dep):
    w, h, d = size
    dims = {'front': (w, h), 'back': (w, h), 'up': (w, d), 'down': (w, d), 'left': (d, h), 'right': (d, h)}[face]
    return (dep / dims[0] + 0.02, dep / dims[1] + 0.02)


TORN = ramp('#1A0705', '#2E0C08', '#4A160F', '#6A2419', '#8C3A2A', '#AE5A44')


def shell_paint(holes, rim=TORN, strands=0, extra=None):
    """살 껍질: 원종 면을 옮겨 온 그림(build 가 미리 붙여 둠)에 구멍을 뚫고 찢긴 가장자리를 칠한다."""
    def painter(img, faces, seed):
        rr = random.Random(seed)
        for face, hs in holes.items():
            r = faces.get(face)
            if not r:
                continue
            x0, y0, w, h = r
            hole = set()
            for y in range(h):
                for x in range(w):
                    u, v = (x + 0.5) / w, (y + 0.5) / h
                    jit = (((x * 73856093) ^ (y * 19349663) ^ seed) % 1000) / 1000 * 0.24 - 0.12   # 가장자리 들쭉날쭉
                    if any(hh.su <= u <= 1 - hh.su and hh.sv <= v <= 1 - hh.sv and hh.dist(u, v) <= 1 + jit for hh in hs):
                        hole.add((x, y))
            dist = {p: 0 for p in hole}
            frontier = list(hole)
            for step in (1, 2, 3):
                nxt = []
                for (x, y) in frontier:
                    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        q = (x + dx, y + dy)
                        if 0 <= q[0] < w and 0 <= q[1] < h and q not in dist:
                            dist[q] = step
                            nxt.append(q)
                frontier = nxt
            # 가장자리: 칼로 그은 테두리처럼 보이지 않게, 그늘진 찢긴 단면(어두움)과 들린 살갗(살빛 그대로 약간 붉게)을 섞는다
            for (x, y), dd in dist.items():
                px, py = x0 + x, y0 + y
                c = get(img, px, py)
                if dd == 0:
                    put(img, px, py, (0, 0, 0, 0))
                elif dd == 1:
                    put(img, px, py, rim[rr.choice((0, 1, 1))] if rr.random() < 0.55 else mix(c, rim[1], 0.55))
                elif dd == 2:
                    if rr.random() < 0.5:
                        put(img, px, py, mix(c, rim[3], 0.3))
                elif dd == 3 and rr.random() < 0.25:
                    put(img, px, py, mix(c, (40, 30, 24, 255), 0.25))
            # 들린 살갗 조각: 가장자리에서 구멍 안으로 2~3칸 늘어진 살(바깥 살빛)
            edge = [p for p, dd in dist.items() if dd == 1]
            for _ in range(max(1, len(edge) // 14)):
                ex, ey = rr.choice(edge)
                inward = [(dx, dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (ex + dx, ey + dy) in hole]
                if not inward:
                    continue
                dx, dy = inward[0]
                col = get(img, x0 + ex - dx, y0 + ey - dy)
                for t in range(1, rr.randint(2, 3) + 1):
                    q = (ex + dx * t, ey + dy * t)
                    if q in hole:
                        put(img, x0 + q[0], y0 + q[1], mix(col, rim[4], 0.25 * t))
            # 구멍을 가로지르는 힘줄 몇 가닥(껍질과 같은 높이라 속과의 깊이 차이가 보인다)
            pts = sorted(hole)
            for _ in range(strands if len(pts) > 12 else 0):
                ax, ay = rr.choice(pts)
                ang = rr.uniform(0, math.pi)
                for t in range(-w, w):
                    x, y = int(round(ax + math.cos(ang) * t * 0.5)), int(round(ay + math.sin(ang) * t * 0.5))
                    if (x, y) in hole:
                        put(img, x0 + x, y0 + y, rim[4] if t % 3 else rim[5])
        if extra:
            extra(img, faces, seed)
    return painter


def wall_paint(rp=None):
    rp = rp or ramp('#120403', '#1E0806', '#2C0E0A', '#3E150F', '#52201A', '#682C22')
    return mat(rp, var=0.22, grad=0.3, cell=1.6)


def paint_inner(img, rect, dims, dep, fn):
    """속 큐브 면(rect, 텍스처 픽셀): 픽셀마다 '껍질 면 정규 좌표'(u, v)로 fn 을 불러 칠한다.
    속 큐브는 한 칸 작아 같은 텍스처가 더 좁게 그려지므로, 정면에서 껍질 구멍과 같은 자리가 맞도록 좌표를 바꾼다."""
    x0, y0, w, h = rect
    Wm, Hm = dims
    for y in range(h):
        for x in range(w):
            un, vn = (x + 0.5) / w, (y + 0.5) / h
            u = (dep + un * (Wm - 2 * dep)) / Wm
            v = (dep + vn * (Hm - 2 * dep)) / Hm
            c = fn(u, v, x0 + x, y0 + y)
            if c is not None:
                put(img, x0 + x, y0 + y, c)


def ell(u, v, cx, cy, rx, ry):
    return ((u - cx) / rx) ** 2 + ((v - cy) / ry) ** 2


def layer_spec(bone, size, dep, holes, tell=None, idx=0, strands=1, extra=None):
    """build.py 의 'layered' 항목: holes = {면: [Hole]}."""
    return dict(bone=bone, idx=idx, depth=dep, tell=tell, paint=shell_paint(holes, strands=strands, extra=extra),
                walls=[(face, hh.box()) for face, hs in holes.items() for hh in hs], wall_paint=wall_paint())


# ================================================================== 크리퍼: 살 속에 파인 두개골

CREEPER_SKULL = Hole(0.66, 0.54, 0.21, 0.32, seed=5, jag=0.3, safe=safe_of((8, 8, 8), 'front', 1.5))
CREEPER_CHEST = Hole(0.47, 0.45, 0.27, 0.24, seed=7, jag=0.32, safe=safe_of((8, 12, 4), 'front', 1.5))
CREEPER_SPINE = Hole(0.5, 0.6, 0.13, 0.27, seed=11, jag=0.3, safe=safe_of((8, 12, 4), 'back', 1.5))
CAV = ramp('#0A0303', '#160706', '#220C09', '#30120D', '#3E1912')


def creeper_inner(img, data):
    """속 큐브(한 칸 작은 머리·몸통): 두개골, 갈비뼈·척추·화약 심장, 등뼈 퓨즈."""
    head, body = bfaces(data, 'head'), bfaces(data, 'body')
    rr = random.Random(3)

    def skull(u, v, x, y):
        # 바닐라 크리퍼 오른쪽 눈(텍스처 5~6열, 2~3행) 자리에 눈구멍, 코 자리에 비강, 입 자리에 이빨
        e = ell(u, v, 0.74, 0.36, 0.12, 0.11)
        if e < 1:
            return CAV[0] if e < 0.45 else CAV[1] if e < 0.8 else R.BONE[0]
        if 0.47 < u < 0.58 and 0.5 < v < 0.62 and (u - 0.47) < (0.62 - v) * 1.1:
            return CAV[1]                                     # 비강(세모)
        if 0.69 < v < 0.79 and 0.48 < u < 0.84:
            col = int((u - 0.48) / 0.06)
            return R.BONE[5] if col % 2 == 0 and v < 0.77 else CAV[2]   # 이빨 줄
        t = 0.82 - 0.38 * v - (0.1 if u > 0.8 else 0) + rr.uniform(-0.05, 0.05)
        if 0.79 <= v < 0.86:
            t -= 0.15                                         # 턱뼈 아래 그늘
        return pick(R.BONE, t, x, y)
    paint_inner(img, head['front'], (8, 8), 1.5, skull)

    def chest(u, v, x, y):
        c = pick(CAV, 0.25 + 0.6 * (1 - CREEPER_CHEST.dist(u, v)) if CREEPER_CHEST.dist(u, v) < 1.4 else 0.2, x, y)
        if abs(u - 0.5) < 0.085:                              # 척추
            seg = int(v * 24) % 3
            return pick(R.BONE, 0.62 - 0.25 * (seg == 2) - 0.2 * abs(u - 0.5) / 0.085, x, y)
        for ry in (0.27, 0.37, 0.47, 0.57):                   # 갈비뼈: 척추에서 휘어 내려간다
            bend = ry + 0.05 * ((u - 0.5) / 0.4) ** 2
            if abs(v - bend) < 0.026 and 0.14 < u < 0.86:
                return pick(R.BONE, 0.7 - 0.25 * abs(u - 0.5), x, y)
        e = ell(u, v, 0.43, 0.52, 0.12, 0.09)                 # 화약 심장
        if e < 1:
            if rr.random() < 0.12:
                return R.SPARK[3 if e < 0.5 else 2]
            return R.GUNPOWDER[1 + int((1 - e) * 4) % 5]
        return c
    paint_inner(img, body['front'], (8, 12), 1.5, chest)

    def spine(u, v, x, y):
        if abs(u - 0.5) < 0.09:
            seg = int(v * 24) % 3
            if seg == 2:
                return R.SPARK[1] if rr.random() < 0.2 else CAV[1]   # 마디 사이 퓨즈 줄
            return pick(R.BONE, 0.6 - 0.2 * abs(u - 0.5) / 0.09, x, y)
        return pick(CAV, 0.4, x, y)
    paint_inner(img, body['back'], (8, 12), 1.5, spine)


def creeper_rot(img, data):
    """껍질이 될 바탕: 3차 부패 층(부패 살빛)과 다리 상처. 해부도는 칠하지 않는다(속 큐브 몫)."""
    head, body, leg = bfaces(data, 'head'), bfaces(data, 'body'), bfaces(data, 'right_front_leg')
    R.rot_skin(img, (0, 0, img.size[0], img.size[1]), amount=0.28, seed=3)


def creeper():
    d = D.creeper()
    d['base2'] = creeper_rot
    d['anatomy'] = creeper_inner
    d['adds'] = [a for i, a in enumerate(d['adds']) if i not in (8,)]
    d['layered'] = [
        layer_spec('head', (8, 8, 8), 1.5, {'front': [CREEPER_SKULL]}, strands=0),
        layer_spec('body', (8, 12, 4), 1.5, {'front': [CREEPER_CHEST], 'back': [CREEPER_SPINE]}, strands=1),
    ]
    d['concept'] = ('균사 크리퍼: 살이 찢긴 얼굴 반쪽 속에 한 겹 들어간 두개골, 가슴 구멍 속 갈비뼈·척추·화약 심장, '
                    '등의 척추 퓨즈(살 껍질 + 속 두 겹). 등의 포자 자루, 머리의 동충하초')
    return d


# ================================================================== 엔더맨: 정수리 뇌, 가슴 핵(두 겹)

ENDER_BRAIN = Hole(0.52, 0.5, 0.34, 0.33, seed=51, jag=0.26, safe=safe_of((8, 8, 8), 'up', 1))
ENDER_CHEST = Hole(0.5, 0.42, 0.3, 0.2, seed=53, jag=0.3, safe=safe_of((8, 12, 4), 'front', 1))


def enderman_inner(img, data):
    head, hat, body = bfaces(data, 'head'), bfaces(data, 'hat'), bfaces(data, 'body')
    rr = random.Random(52)

    def brain(u, v, x, y):
        e = ell(u, v, 0.5, 0.5, 0.42, 0.42)
        if e > 1:
            return R.FLESH[1]
        # 좌우 반구 사이 골, 구불구불한 주름
        if abs(u - 0.5) < 0.03:
            return R.BRAIN[0]
        w = math.sin(u * 31 + math.sin(v * 17) * 2.2) * math.cos(v * 27 + math.sin(u * 13) * 2)
        if w > 0.55:
            return R.BRAIN[1]
        return R.BRAIN[3 if w < -0.3 else 2] if rr.random() > 0.06 else R.BRAIN[5]
    # 정수리: 속 머리 윗면과, 그 안의 바닐라 '모자'(턱 부분, 반 칸 작음) 윗면 둘 다 뇌(모자 윗면이 먼저 보인다)
    paint_inner(img, head['up'], (8, 8), 1, brain)
    paint_inner(img, hat['up'], (8, 8), 0.5, brain)
    RIB = ramp('#4A4452', '#6A6476', '#8C8698', '#AEA8BA', '#CCC6D8', '#E6E0F0')
    VCAV = ramp('#050307', '#0E0714', '#170A22', '#22102F')

    def chest(u, v, x, y):
        dc = math.hypot((u - 0.5) / 0.16, (v - 0.42) / 0.11)
        if dc < 1:
            return R.VOIDCORE[5 if dc < 0.3 else 4 if dc < 0.65 else 3]
        for ry in (0.28, 0.37, 0.46, 0.55):
            if abs(v - ry) < 0.022 and 0.14 < u < 0.86 and abs(u - 0.5) > 0.17:
                return pick(RIB, 0.75 - 0.3 * abs(u - 0.5), x, y)
        glow = max(0.0, 1 - dc / 2.2)
        return pick(VCAV, 0.2 + 0.5 * glow, x, y) if glow < 0.6 else R.VOIDCORE[1]
    paint_inner(img, body['front'], (8, 12), 1.5, chest)


def enderman():
    d = D3.enderman()
    base2 = None
    d['base2'] = base2
    d['anatomy'] = enderman_inner
    d['layered'] = [
        layer_spec('head', (8, 8, 8), 1, {'up': [ENDER_BRAIN]}, strands=0),
        layer_spec('body', (8, 12, 4), 1, {'front': [ENDER_CHEST]}, strands=0),
    ]
    d['layered'][0]['wall_paint'] = wall_paint(ramp('#3A342A', '#5A5242', '#7C7462', '#A59B84', '#C9BFA8', '#E2DBC6'))   # 잘린 두개골 단면
    d['layered'][1]['wall_paint'] = wall_paint(ramp('#060408', '#0C0712', '#140A1E', '#1E0F2C', '#2A1440', '#3A1C58'))   # 공허빛 몸속
    d['emissive_inner'] = {'body': 12}
    # 공허 날개: 집중 때만 펼치는 모델 뼈대(평소 크기 0, 리그 EndermanRig). 등 위쪽 양옆에 붙은 찢긴 공허 막 세 갈래
    d['extra_bones'] = [dict(name='left_wing', parent='body', pivot=[1.5, 2.5, 2.1]), dict(name='right_wing', parent='body', pivot=[-1.5, 2.5, 2.1])]
    d['hidden_bones'] = ['left_wing', 'right_wing']
    d['adds'] = d['adds'] + [
        A('left_wing', [0.0, -1.3, 0.0], [12.0, 2.6, 0.0], void_wing),
        A('left_wing', [0.0, -5.6, 0.0], [10.0, 2.2, 0.0], void_wing, rot=('z', -30, [0.0, -4.5, 0.0])),
        A('left_wing', [0.0, 1.4, 0.0], [8.0, 2.0, 0.0], void_wing, rot=('z', 25, [0.0, 2.4, 0.0])),
        A('right_wing', [-12.0, -1.3, 0.0], [12.0, 2.6, 0.0], void_wing),
        A('right_wing', [-10.0, -5.6, 0.0], [10.0, 2.2, 0.0], void_wing, rot=('z', 30, [0.0, -4.5, 0.0])),
        A('right_wing', [-8.0, 1.4, 0.0], [8.0, 2.0, 0.0], void_wing, rot=('z', -25, [0.0, 2.4, 0.0])),
    ]   # 리그: 가슴 구멍 속 핵이 스스로 빛난다(속 큐브 전체가 빛나도 보이는 곳은 구멍뿐)
    d['view'] = (-30, -24)
    d['concept'] = '공허 붕괴체: 찢긴 정수리 속 한 겹 들어간 큰 뇌, 갈비뼈 너머 빛나는 보라 핵(두 겹), 보라 균열과 떠다니는 조각, 빠진 턱'
    return d


def void_wing(img, faces, seed):
    """공허 날개 막: 거의 검은 보라 막에 빛나는 보라 핏줄, 끝과 아래 가장자리가 찢겨 있다."""
    rr = random.Random(seed)
    MEM = ramp('#07040B', '#0E0716', '#170B24', '#221034', '#2E1646')
    VEIN = ramp('#5C1E85', '#9238C4', '#D07EFA')
    for face, (x0, y0, w, h) in faces.items():
        for y in range(h):
            for x in range(w):
                t = 0.25 + 0.5 * (1 - abs(y - h / 2) / max(1, h / 2)) - 0.25 * x / max(1, w)
                put(img, x0 + x, y0 + y, pick(MEM, t + rr.uniform(-0.08, 0.08), x, y))
        vy = h // 2
        for x in range(w):
            if rr.random() < 0.25:
                vy = max(0, min(h - 1, vy + rr.choice((-1, 1))))
            put(img, x0 + x, y0 + vy, VEIN[2 if x < w * 0.3 else 1 if x < w * 0.7 else 0])
        for x in range(w):   # 찢긴 아래 가장자리와 끝
            if rr.random() < 0.35 or x > w * 0.82 and rr.random() < 0.6:
                put(img, x0 + x, y0 + h - 1, (0, 0, 0, 0))
            if x > w * 0.9 and rr.random() < 0.5:
                put(img, x0 + x, y0, (0, 0, 0, 0))


# ================================================================== 가디언: 찢긴 옆구리 속 빈 몸(두 겹)

G_FRONT = Hole(0.7, 0.72, 0.19, 0.16, seed=63, jag=0.3, safe=safe_of((12, 12, 16), 'front', 1.5))
G_UP = Hole(0.4, 0.45, 0.2, 0.17, seed=64, jag=0.22, safe=safe_of((12, 12, 16), 'up', 1.5))
G_LEFT = Hole(0.55, 0.5, 0.2, 0.2, seed=65, jag=0.22, safe=safe_of((12, 12, 16), 'left', 1.5))


def guardian_inner(img, data, elder=False):
    head = bfaces(data, 'head', 0)
    SPINE = ramp('#5A3420', '#7C4A2C', '#9C6438', '#B07448')
    # 바닐라 가디언의 가운데 몸통은 앞·뒷면에만 그림이 있다(옆·위는 판 큐브가 덮음) → 구멍은 앞면에만
    for face, dims, hole in (('front', (12, 12), G_FRONT),):
        def fn(u, v, x, y, hole=hole):
            dd = hole.dist(u, v)
            # 가시 대 하나가 빈 몸속을 비스듬히 지난다
            if abs((u - hole.cx) - (v - hole.cy) * 0.8) < 0.03 and dd < 1.3:
                return SPINE[3] if (u - hole.cx) < 0 else SPINE[2]
            return pick(R.HOLLOW, 0.75 - 0.5 * min(1, dd), x, y)
        paint_inner(img, head[face], dims, 1.5, fn)


def _guardian_shell(img, data, elder=False):
    head = bfaces(data, 'head', 0)
    side_r = bfaces(data, 'head', 1)
    R.rot_skin(img, (0, 0, img.size[0], img.size[1]), amount=0.18 if not elder else 0.32, seed=61, mottle=0.16)
    if elder:
        from paint import crack
        for k2, rect in enumerate((head['front'], head['up'], head['left'], side_r['right'], head['back'])):
            crack(img, rect, 70 + k2, max(6, rect[2]), dark=[ramp('#3E3A33')[0], ramp('#5A554B')[0]])
            crack(img, rect, 80 + k2, max(4, rect[2] // 2), dark=[ramp('#3E3A33')[0], ramp('#5A554B')[0]])


def guardian():
    d = D.guardian()
    base = d['base']
    d['base'] = base
    d['base2'] = _guardian_shell
    d['anatomy'] = guardian_inner
    holes = {'front': [G_FRONT]}
    d['layered'] = [layer_spec('head', (12, 12, 16), 1.5, holes, strands=1)]
    d['adds'] = d['adds'][:-2]   # 꼬리 뒤에 떠 보이던 다시마 판
    d['concept'] = '익사체 지휘관(가디언): 따개비·죽은 산호, 찢긴 옆구리 속 한 겹 들어간 붉은 빈 몸속과 관통한 가시(두 겹), 백내장 눈'
    return d


def elder_guardian():
    d = D.guardian()
    base = d['base']
    d.update(layer='minecraft:elder_guardian#main', texture='guardian/guardian_elder.png', tone='pale', base=base,
             concept='썩은 장로(엘더 가디언): 바위처럼 갈라진 창백한 살갗, 찢긴 옆구리 속 붉은 빈 몸속(두 겹), 따개비와 다시마')
    d['base2'] = lambda img, data: _guardian_shell(img, data, elder=True)
    d['anatomy'] = lambda img, data: guardian_inner(img, data, elder=True)
    d['adds'] = d['adds'][:5]
    holes = {'front': [G_FRONT]}
    d['layered'] = [layer_spec('head', (12, 12, 16), 1.5, holes, strands=1)]
    return d


# ================================================================== 가스트: 움푹 들어간 배 속 화염구

GHAST_BELLY = Hole(0.5, 0.52, 0.3, 0.28, seed=97, jag=0.16, safe=safe_of((16, 16, 16), 'down', 2.5))
GHAST_SIDE = Hole(0.3, 0.8, 0.11, 0.09, seed=96, jag=0.2, safe=safe_of((16, 16, 16), 'left', 2.5))


def ghast_shell(img, data):
    """껍질: 흉터·울던 자국·부패 살빛(앞면 아래 상처는 없다)."""
    k = D.SCALE[0]
    body = bfaces(data, 'body')
    R.rot_skin(img, (0, 0, img.size[0], img.size[1]), amount=0.2, seed=91, mottle=0.1)
    paths = {
        'up': [[(0.1, 0.2), (0.45, 0.35), (0.8, 0.25)], [(0.3, 0.7), (0.62, 0.85), (0.9, 0.7)]],
        'left': [[(0.15, 0.15), (0.35, 0.55), (0.25, 0.7)], [(0.6, 0.2), (0.85, 0.45)]],
        'right': [[(0.8, 0.1), (0.55, 0.5), (0.7, 0.85)]],
        'back': [[(0.1, 0.3), (0.5, 0.42), (0.9, 0.3)], [(0.4, 0.6), (0.45, 0.95)]],
        'front': [[(0.05, 0.12), (0.22, 0.3)], [(0.78, 0.08), (0.95, 0.3)]],
    }
    for face, ps in paths.items():
        for i, pts in enumerate(ps):
            R.scar(img, body[face], pts, seed=93 + i, dark=ramp('#4A3A36')[0])
    f = body['front']
    TEAR = ramp('#4E5A60', '#6E7E86', '#9AAAB0', '#D4E2E6')
    for ex, L in ((0.3, 0.32), (0.66, 0.26)):
        R.tear(img, f[0] + int(f[2] * ex), f[1] + int(f[3] * 0.5), int(f[3] * L), TEAR, width=k)
    # 촉수 끝: 덩어리 대신 텍스처 아래쪽만 괴사색으로 물든다
    NECROTIC = ramp('#3A3430', '#4E4640', '#625850', '#786C62')
    for i in range(9):
        try:
            t = bfaces(data, f'tentacle{i}')
        except Exception:
            continue
        for face in ('front', 'back', 'left', 'right'):
            r = t[face]
            n = max(2, r[3] // 4)
            for y in range(r[3] - n, r[3]):
                for x in range(r[2]):
                    c = get(img, r[0] + x, r[1] + y)
                    if c[3]:
                        put(img, r[0] + x, r[1] + y, mix(c, pick(NECROTIC, 0.6 - 0.4 * (y - r[3] + n) / n, x, y), 0.65))


def ghast_inner(img, data):
    k = D.SCALE[0]
    body = bfaces(data, 'body')
    rr = random.Random(98)
    balls = [(0.5 + rr.uniform(-0.12, 0.12), 0.52 + rr.uniform(-0.1, 0.1), rr.uniform(0.07, 0.1)) for _ in range(5)]
    WALL = ramp('#1A0602', '#2E0C04', '#461506', '#5E1E08')

    def stomach(u, v, x, y):
        for bx, by, rad in balls:
            dd = math.hypot(u - bx, v - by) / rad
            if dd < 1:
                return R.FIREBALL[5 if dd < 0.35 else 4 if dd < 0.6 else 3 if dd < 0.85 else 2]
        return pick(WALL, 0.7 - 0.5 * min(1, GHAST_BELLY.dist(u, v)), x, y)
    paint_inner(img, body['down'], (16, 16), 2.5, stomach)

    def side(u, v, x, y):
        dd = math.hypot((u - 0.3) / 0.1, (v - 0.8) / 0.08)
        return R.FIREBALL[4 if dd < 0.5 else 3] if dd < 1 else pick(WALL, 0.5, x, y)
    paint_inner(img, body['left'], (16, 16), 2.5, side)


def ghast():
    d = D.ghast()
    d['base'] = None
    d['base2'] = ghast_shell
    d['anatomy'] = ghast_inner
    d['adds'] = []     # 촉수 끝 덩어리(장화처럼 보이던 것)와 벗겨진 살갗 판은 뺀다
    fire_wall = wall_paint(ramp('#160402', '#2A0A04', '#3E1206', '#561A08', '#702A0E', '#8E3E14'))
    sp = layer_spec('body', (16, 16, 16), 2.5, {'down': [GHAST_BELLY], 'left': [GHAST_SIDE]}, strands=2,
                    tell='찢긴 배 속 화염구: 죽을 때 위가 터져 작은 화염구 2~3개가 흩어진다')
    sp['wall_paint'] = fire_wall
    d['layered'] = [sp]
    d['view'] = (-35, 14)
    d['concept'] = '혼불 가스트: 흉터로 덮인 몸, 눈 아래 울던 자국, 배 아래 움푹 찢긴 구멍 속 화염구(두 겹, Mobestiary), 옆구리에도 작게 비치는 불'
    return d


# ================================================================== 마그마 큐브: 1차 디자인

P1 = {
    'flesh': ((112, 52, 44), (150, 84, 70), (78, 92, 52)),
    'bone': ((214, 204, 176), (238, 232, 212), (150, 140, 116)),
}


def p1_mat(name):
    """1차 parts.fill 과 같은 무늬(1배 픽셀 크기로 칠해 2배로 키운다)."""
    base, hi, dark = P1[name]

    def painter(img, faces, seed):
        rng = random.Random(seed)
        for face, (x0, y0, w, h) in faces.items():
            k = 2 if w % 2 == 0 and h % 2 == 0 else 1
            gw, gh = max(1, w // k), max(1, h // k)
            g = [[rng.random() for _ in range(gw // 2 + 3)] for _ in range(gh // 2 + 3)]
            for y in range(gh):
                for x in range(gw):
                    fx, fy = x / 2, y / 2
                    xi, yi = int(fx), int(fy)
                    tx, ty = fx - xi, fy - yi
                    a = g[yi][xi] * (1 - tx) + g[yi][xi + 1] * tx
                    b = g[yi + 1][xi] * (1 - tx) + g[yi + 1][xi + 1] * tx
                    n = a * (1 - ty) + b * ty
                    c = mix((*base, 255), (*hi, 255), max(0.0, (n - 0.5) * 1.6)) if n > 0.5 else mix((*base, 255), (*dark, 255), (0.5 - n) * 1.2)
                    if name == 'bone' and rng.random() < 0.08:
                        c = (*dark, 255)
                    if name == 'flesh' and rng.random() < 0.05:
                        c = (96, 150, 70, 255)
                    for yy in range(k):
                        for xx in range(k):
                            put(img, x0 + x * k + xx, y0 + y * k + yy, c)
    return painter


def zombify_1x(img, style, seed, rect=None):
    """1차 텍스처 좀비화를 1배 픽셀 크기로(2배 바탕이면 줄여서 칠한 뒤 다시 키운다)."""
    x0, y0, w, h = rect or (0, 0, img.size[0], img.size[1])
    crop = img.crop((x0, y0, x0 + w, y0 + h))
    k = 2 if w % 2 == 0 and h % 2 == 0 and w >= 4 else 1
    small = crop.resize((w // k, h // k), Image.NEAREST) if k > 1 else crop
    z = zt.zombify(small, style, seed)
    if k > 1:
        z = z.resize((w, h), Image.NEAREST)
    img.paste(z, (x0, y0))


def magma_cube():
    flesh, bone = p1_mat('flesh'), p1_mat('bone')

    def base(img, data):
        zombify_1x(img, 'warm', seed=hash('slime/magmacube.png') & 0xffff)
        # 속: 층 사이로 보이는 핵 = 숯이 된 심장(갈라진 틈마다 불씨). 껍질 층 안쪽 면은 그을린 살
        core = bfaces(data, 'inside_cube')
        CHAR = ramp('#120806', '#22100A', '#341810', '#4A2216')
        rr = random.Random(71)
        for face, r in core.items():
            for y in range(r[3]):
                for x in range(r[2]):
                    put(img, r[0] + x, r[1] + y, pick(CHAR, 0.3 + rr.uniform(0, 0.5), x, y))
            from paint import crack
            crack(img, r, 72 + len(face), max(4, r[2]), dark=[R.FIREBALL[3], R.FIREBALL[4]], light=R.FIREBALL[5], branch=0.3)
    return dict(
        method='E', layer='minecraft:magma_cube#main', texture='slime/magmacube.png', base=base, view=(-35, 22),
        inner_pose={f'cube{i}': (0, -(4 - i) * 1.7, 0, 0, 0, 0) for i in range(8)},
        concept='부패 마그마 큐브(1차 디자인): 그을음과 상처로 탁해진 껍질 층, 층 사이로 삐져나온 탄 뼈와 살점, 속에는 숯이 된 심장(갈라진 틈마다 불씨)',
        adds=[
            A('cube3', [3.6, 19.0, -1.2], [1.6, 1.0, 2.4], bone, tell='불씨 착지: 착지 직전 층 사이가 밝아지고 탄 뼈 사이로 불씨가 샌다'),
            A('cube5', [-5.2, 21.0, 0.4], [1.6, 1.0, 1.8], bone),
            A('cube1', [-1.0, 16.6, -5.0], [2.0, 1.4, 1.2], bone),
            A('cube7', [-1.5, 23.6, -1.0], [3.0, 0.8, 2.0], flesh),
        ])


# ================================================================== 해골 계열: 1차 디자인을 머리 덮개 + 몸통 장비 레이어로

HEAD_BOX = (0, 0, 8, 8, 8)
BODY_BOX = (16, 16, 8, 12, 4)


def _box_rects(u, v, w, h, d, k):
    from paint import box_faces
    return {f: (r[0] * k, r[1] * k, r[2] * k, r[3] * k) for f, r in box_faces(u, v, w, h, d).items()}


def rot_patches(img, rect, seed, density=1.0, wound=True, rp=None):
    """1차 humanoid_overlay 의 '썩은 살점' 무늬(올리브·갈색 덩어리, 가끔 상처, 아래로 흐른 줄)를 한 면에."""
    x0, y0, w, h = rect
    rng = random.Random(seed)
    rotten = rp or ((74, 52, 40), (104, 78, 56), (70, 84, 46))
    k = 2
    gw, gh = max(1, w // k), max(1, h // k)
    cell = 3
    g = [[rng.random() for _ in range(gw // cell + 3)] for _ in range(gh // cell + 3)]
    for y in range(gh):
        for x in range(gw):
            fx, fy = x / cell, y / cell
            xi, yi = int(fx), int(fy)
            tx, ty = fx - xi, fy - yi
            a = g[yi][xi] * (1 - tx) + g[yi][xi + 1] * tx
            b = g[yi + 1][xi] * (1 - tx) + g[yi + 1][xi + 1] * tx
            t = (a * (1 - ty) + b * ty)
            drip = (x * 7 + seed) % 6 == 0 and y > 3 and t > 0.55
            if t > 0.72 - 0.1 * (density - 1) or drip:
                c = mix((*rotten[0], 255), (*rotten[1], 255), min(1.0, (t - 0.55) * 2))
                if rng.random() < 0.18:
                    c = (*rotten[2], 255)
                if wound and t > 0.86:
                    c = (96, 22, 22, 255)
                for yy in range(k):
                    for xx in range(k):
                        put(img, x0 + x * k + xx, y0 + y * k + yy, c)


def mycelium(img, rect, seed, n=2, rp=None):
    """균사(다시 일어서는 해골의 신호): 흰 실 몇 가닥."""
    threads(img, rect, seed, n, rp or D.MYC, length=max(6, rect[3] // 2))


def skeleton_family(name, texture, layer, outer=None, outer_tex=None, style='bone', extra_head=None, extra_body=None,
                    concept='', tell=None, adds=None, gaps=0.3, patches=None, wound=True):
    """해골 계열 공통(1차 디자인): 머리 덮개 = 바닐라 두개골을 1차 방식으로 좀비화 + 썩은 살점,
    몸통 = 레깅스 장비 레이어(몸통보다 0.5 큼)에 바닐라 갈비뼈를 같은 방식으로 좀비화해 옮기고 갈비뼈 틈 일부를 마른 살로 메운다.
    팔다리는 B 방식 한계로 바닐라 그대로(장비 팔다리는 해골 팔다리보다 두 배 굵어 떠 보인다)."""
    seed = hash(texture) & 0xffff

    def cover(img, faces, s):
        for face, r in faces.items():
            zombify_1x(img, style, seed + len(face), r)
            if face in ('left', 'right', 'up', 'back'):
                rot_patches(img, r, seed + 7 * len(face), density=1.0, rp=patches, wound=wound)
            elif face == 'front':
                # 얼굴은 눈구멍·코·이가 보이게 아래쪽 한 뺨에만 살점
                x0, y0, w, h = r
                rot_patches(img, (x0 + (w // 2 if seed % 2 else 0), y0 + h // 2, w // 2, h // 2), seed + 5, density=1.1, rp=patches, wound=wound)
        if extra_head:
            extra_head(img, faces, s)

    def overlay(base, outer_img):
        k = 2
        img = Image.new('RGBA', (64 * k, 32 * k), (0, 0, 0, 0))
        rects = _box_rects(*BODY_BOX, 1)
        rng = random.Random(seed + 3)
        for face, r in rects.items():
            crop = base.crop((r[0], r[1], r[0] + r[2], r[1] + r[3]))
            if outer_img is not None:
                crop.alpha_composite(outer_img.crop((r[0], r[1], r[0] + r[2], r[1] + r[3])))
            dst = (r[0] * k, r[1] * k, r[2] * k, r[3] * k)
            img.paste(crop.resize((dst[2], dst[3]), Image.NEAREST), dst[:2])
            zombify_1x(img, style, seed + 11 + len(face), dst)
            # 갈비뼈 틈: 절반쯤은 말라붙은 살·힘줄로 메운다(나머지는 비어 바닐라 뼈가 보인다)
            if face in ('front', 'back'):
                for y in range(0, dst[3], 2):
                    for x in range(0, dst[2], 2):
                        if get(img, dst[0] + x, dst[1] + y)[3] == 0 and rng.random() < gaps:
                            c = (58 + rng.randrange(20), 32 + rng.randrange(12), 26, 255) if rng.random() < 0.7 else (70, 84, 46, 255)
                            for yy in range(2):
                                for xx in range(2):
                                    put(img, dst[0] + x + xx, dst[1] + y + yy, c)
            if face in ('front', 'back', 'left', 'right'):
                rot_patches(img, dst, seed + 13 * len(face), density=1.0, rp=patches, wound=wound)
        if extra_body:
            extra_body(img, {f: (r[0] * k, r[1] * k, r[2] * k, r[3] * k) for f, r in rects.items()})
        return img

    copy = [('head', 0)]
    if outer:
        copy.append(('outer_head', 0))
    d = dict(method='B', layer=layer, texture=texture, outer=outer, outer_tex=outer_tex,
             overlay=[layer.split('#')[0] + '#leggings'], overlay_bones=('body',), overlay_fn2=overlay,
             concept=concept,
             adds=[dict(bone='head', origin=[-4.0, -8.0, -4.0], size=[8, 8, 8], paint=cover, copy=copy, grow=[0.3, 0.3, 0.3],
                        tell=tell, glow=False, rot=None, name='zombie_skull')] + (adds or []))
    return d


def _flesh_flap():
    return p1_mat('flesh')


def skeleton():
    def body(img, rects):
        for f in ('front', 'back'):
            mycelium(img, rects[f], 301 + len(f), n=3)
    return skeleton_family(
        'skeleton', 'skeleton/skeleton.png', 'minecraft:skeleton#main', extra_body=body,
        concept='묘지 해골(1차 디자인): 살점이 썩어 붙은 누런 뼈, 갈비뼈 틈의 말라붙은 살, 뼈를 다시 잇는 흰 균사',
        tell='균사로 이은 뼈: 쓰러지면 30% 확률로 8~14초 뒤 다시 일어선다',
        adds=[A('head', [2.4, -1.4, -4.7], [1.6, 2.6, 1.0], _flesh_flap())])


def stray():
    FROSTED = ramp('#9DB4C8', '#C8DCEA', '#EEF7FC', '#FFFFFF')

    def body(img, rects):
        speckle(img, rects['front'], 341, 0.03, [FROSTED[1], FROSTED[2]])
        speckle(img, rects['up'], 342, 0.12, [FROSTED[0], FROSTED[1]])

    def head(img, faces, s):
        speckle(img, faces['up'], 343, 0.07, [FROSTED[0], FROSTED[1]])
    return skeleton_family(
        'stray', 'skeleton/stray.png', 'minecraft:stray#main', outer='minecraft:stray#outer', outer_tex='skeleton/stray_overlay.png',
        extra_body=body, extra_head=head,
        concept='동상 해골(1차 디자인): 해진 옷 아래 썩어 얼어붙은 살점, 갈비뼈와 정수리에 낀 서리',
        tell='갈비뼈의 서리: 화살이 얼음을 쌓는다')


def bogged():
    return skeleton_family(
        'bogged', 'skeleton/bogged.png', 'minecraft:bogged#main', outer='minecraft:bogged#outer', outer_tex='skeleton/bogged_overlay.png',
        concept='포자 해골(1차 디자인): 늪 덩굴(바닐라 덧층) 아래 썩은 살점, 머리의 바닐라 버섯',
        tell='포자 터짐: 버섯 갓이 부풀며 빛난다(가위로 깎으면 잠잠)')


# ---------------------------------------------------------------- 위더 스켈레톤: 그을린 살가죽 + 가는 진홍 균사

HYPHA = ramp('#3A0A0C', '#5A1014', '#7E1A1C', '#A42A24', '#E07A3A')
CHAR = ((34, 26, 22), (58, 44, 36), (30, 34, 26))


def _hyphae(img, rect, seed, n):
    """가는 진홍 균사(1픽셀 선이 갈라지며 뻗는다). 덩어리로 칠하지 않는다."""
    from paint import crack
    crack(img, rect, seed, max(6, rect[3] // 2), dark=[HYPHA[1], HYPHA[2]], light=HYPHA[3], branch=0.3, wander=0.7)
    if n > 1:
        crack(img, rect, seed + 1, max(4, rect[3] // 3), dark=[HYPHA[1], HYPHA[2]], branch=0.25)


def wither_skeleton():
    def head(img, faces, s):
        _hyphae(img, faces['up'], 401, 2)
        _hyphae(img, faces['back'], 402, 1)
        r = faces['front']   # 왼쪽 눈구멍에서 균사가 기어 나온다(끝에 불씨 한 점)
        from paint import crack
        crack(img, r, 403, r[3] // 2, dark=[HYPHA[1], HYPHA[2]], light=HYPHA[3], start=(r[0] + r[2] * 0.3, r[1] + r[3] * 0.45), heading=-1.2, branch=0.2)
        put(img, int(r[0] + r[2] * 0.22), int(r[1] + r[3] * 0.2), HYPHA[4])

    def body(img, rects):
        for f in ('front', 'back'):
            _hyphae(img, rects[f], 410 + len(f), 2)
    return skeleton_family(
        'wither_skeleton', 'skeleton/wither_skeleton.png', 'minecraft:wither_skeleton#main', style='dark', patches=CHAR, gaps=0.25, wound=False,
        extra_head=head, extra_body=body,
        concept='진홍 해골: 그을려 말라붙은 살가죽, 눈구멍과 뼈 틈을 기어 다니는 가는 진홍 균사(붉은 덩어리는 없다)',
        tell='진홍 균사: 쓰러지면 균사가 뼈를 다시 엮어 일으킨다(그 동안 균사 끝이 불씨처럼 빛난다)')


# ---------------------------------------------------------------- 피글린 브루트: 좀비 피글린 문법 + 녹슨 금

def _is_skin(c):
    if c[3] == 0:
        return False
    h, s, v = colorsys.rgb_to_hsv(c[0] / 255, c[1] / 255, c[2] / 255)
    return (h < 0.06 or h > 0.94) and s > 0.2 and v > 0.45


def _is_gold(c):
    if c[3] == 0:
        return False
    h, s, v = colorsys.rgb_to_hsv(c[0] / 255, c[1] / 255, c[2] / 255)
    return 0.09 < h < 0.18 and s > 0.4 and v > 0.45


def _tarnish(c):
    h, s, v = colorsys.rgb_to_hsv(c[0] / 255, c[1] / 255, c[2] / 255)
    r, g, b = colorsys.hsv_to_rgb(0.16, s * 0.55, v * 0.72)
    return (int(r * 255), int(g * 255), int(b * 255), c[3])


def _zp_texture():
    if JAR[0] is None:
        return None
    from zmodel import jar_texture
    return jar_texture(JAR[0], 'piglin/zombified_piglin.png')


def piglin_brute():
    zp = _zp_texture()

    def head_cover(img, faces, s):
        # 바닐라 좀비 피글린 머리 면을 그대로(좀비 짝 문법). build 가 먼저 브루트 면을 붙였으므로 그 위에 덮는다
        if zp is None:
            return
        from paint import box_faces
        for face, r in faces.items():
            src = box_faces(0, 0, 10, 8, 8)[face]
            crop = zp.crop((src[0], src[1], src[0] + src[2], src[1] + src[3])).resize((r[2], r[3]), Image.NEAREST)
            img.paste(crop, (r[0], r[1]))

    def snout_cover(img, faces, s):
        if zp is None:
            return
        from paint import box_faces
        for face, r in faces.items():
            src = box_faces(31, 1, 4, 4, 1)[face]
            crop = zp.crop((src[0], src[1], src[0] + src[2], src[1] + src[3])).resize((r[2], r[3]), Image.NEAREST)
            img.paste(crop, (r[0], r[1]))

    def ear_cover(u, v):
        def f(img, faces, s):
            if zp is None:
                return
            from paint import box_faces
            for face, r in faces.items():
                src = box_faces(u, v, 1, 5, 4)[face]
                crop = zp.crop((src[0], src[1], src[0] + src[2], src[1] + src[3])).resize((r[2], r[3]), Image.NEAREST)
                img.paste(crop, (r[0], r[1]))
        return f

    def overlay(base, outer_img):
        k = 2
        img = Image.new('RGBA', (64 * k, 32 * k), (0, 0, 0, 0))
        rects = _box_rects(*BODY_BOX, 1)
        for face, r in rects.items():
            crop = base.crop((r[0], r[1], r[0] + r[2], r[1] + r[3]))
            jacket = base.crop((r[0], r[1] + 16, r[0] + r[2], r[1] + 16 + r[3]))   # 브루트 조끼 겉층(16, 32)
            crop.alpha_composite(jacket)
            zcrop = zp.crop((r[0], r[1], r[0] + r[2], r[1] + r[3])) if zp is not None else None
            out = Image.new('RGBA', crop.size, (0, 0, 0, 0))
            for y in range(crop.size[1]):
                for x in range(crop.size[0]):
                    c = crop.getpixel((x, y))
                    if _is_skin(c) and zcrop is not None:
                        out.putpixel((x, y), zcrop.getpixel((x, y)))   # 맨살 자리 = 좀비 피글린의 썩은 살·갈비뼈
                    elif _is_gold(c):
                        out.putpixel((x, y), _tarnish(c))               # 녹슨 금
                    elif c[3]:
                        out.putpixel((x, y), c)
            dst = (r[0] * k, r[1] * k, r[2] * k, r[3] * k)
            img.paste(out.resize((dst[2], dst[3]), Image.NEAREST), dst[:2])
        return img

    return dict(
        method='B', layer='minecraft:piglin_brute#main', texture='piglin/piglin_brute.png',
        overlay=['minecraft:piglin_brute#leggings'], overlay_bones=('body',), overlay_fn2=overlay,
        concept='진홍 브루트: 바닐라 좀비 피글린처럼 드러난 두개골·뜯긴 귀(머리 덮개), 조끼 사이 맨살 자리는 썩은 살과 갈비뼈, 녹슨 금 띠',
        adds=[
            dict(bone='head', origin=[-5.0, -8.0, -4.0], size=[10, 8, 8], paint=head_cover, copy=[('head', 0)], grow=[0.3, 0.3, 0.3],
                 tell='찢는 도끼: 맞으면 늘 출혈, 체력 절반 아래 광폭(눈이 붉게 번진다)', glow=False, rot=None, name='zombie_head'),
            dict(bone='head', origin=[-2.0, -4.0, -5.0], size=[4, 4, 1], paint=snout_cover, copy=[('head', 1)], grow=[0.25, 0.25, 0.25],
                 tell=None, glow=False, rot=None, name='zombie_snout'),
            dict(bone='right_ear', origin=[-1.0, 0.0, -2.0], size=[1, 5, 4], paint=ear_cover(39, 6), copy=[('right_ear', 0)], grow=[0.3, 0.3, 0.3],
                 tell=None, glow=False, rot=None, name='zombie_ear'),
            dict(bone='left_ear', origin=[0.0, 0.0, -2.0], size=[1, 5, 4], paint=ear_cover(51, 6), copy=[('left_ear', 0)], grow=[0.3, 0.3, 0.3],
                 tell=None, glow=False, rot=None, name='zombie_ear'),
        ])


# ================================================================== 우민: 머리띠·도끼 손질

BAND = ramp('#1C1A15', '#2A2620', '#3A352C', '#4C4639', '#5E5746')


def _band_paint(rp=BAND, gem=None):
    def f(img, faces, seed):
        rr = random.Random(seed)
        for face, r in faces.items():
            for y in range(r[3]):
                for x in range(r[2]):
                    t = 0.62 - 0.4 * y / max(1, r[3] - 1) + rr.uniform(-0.06, 0.06)
                    if face in ('up', 'down'):
                        t = 0.5
                    put(img, r[0] + x, r[1] + y, pick(rp, t, x, y))
            if face in ('front', 'back', 'left', 'right') and r[3] >= 3:
                for x in range(r[2]):     # 해진 아래 가장자리: 몇 칸 뜯김
                    if rr.random() < 0.18:
                        put(img, r[0] + x, r[1] + r[3] - 1, (0, 0, 0, 0))
                for x in range(0, r[2], 3):   # 바느질 땀
                    put(img, r[0] + x, r[1] + r[3] // 2, rp[-1])
        if gem:
            r = faces['front']
            cx, cy = r[0] + r[2] // 2, r[1] + r[3] // 2
            for dx in (-1, 0):
                for dy in (-1, 0):
                    put(img, cx + dx, cy + dy, gem[-1] if (dx, dy) == (-1, -1) else gem[-2])
            for dx, dy in ((-2, -1), (-2, 0), (1, -1), (1, 0), (-1, -2), (0, -2), (-1, 1), (0, 1)):
                put(img, cx + dx, cy + dy, D.GOLD[3])
    return f


def _knot_paint():
    return mat(BAND, var=0.12, grad=0.25)


def pillager():
    """약탈자: 3차 그대로에서 두건 띠만 뺀다(운영자 요청). 머리를 꿰뚫은 화살은 둔다."""
    d = D3._with_zhead(D.pillager, 'left')()
    zh = d['adds'][:2]
    arrow = [a for a in d['adds'][2:] if a['size'] not in ([9.8, 2.0, 9.8], [6.0, 1.2, 1.4])]
    d['adds'] = zh + arrow
    d['concept'] = '약탈자: 머리를 꿰뚫은 화살(표식 화살) · 좀비 주민처럼 썩은 얼굴(머리 덮개)'
    return d


def evoker():
    """소환사: 3차 그대로에서 룬 띠만 뺀다(운영자 요청). 송곳니 왕관은 둔다."""
    d = D3._with_zhead(D.evoker, 'left')()
    zh = d['adds'][:2]
    fangs = [a for a in d['adds'][2:] if a['size'] != [8.8, 1.2, 8.8]]
    d['adds'] = zh + fangs
    d['concept'] = '소환사: 송곳니로 된 왕관 · 좀비 주민처럼 썩은 얼굴(머리 덮개)'
    return d


def vindicator():
    d = D3._with_zhead(D.vindicator, 'right')()
    zh = d['adds'][:2]
    moss = [a for a in d['adds'][2:] if a['size'] == [4.2, 1.0, 4.2]]
    blade_p = mat(D.IRON, var=0.08, extra=lambda img, faces, seed: [put(img, faces['front'][0] + x, faces['front'][1] + faces['front'][3] - 1, D.IRON[-1]) for x in range(faces['front'][2])])
    handle_p = mat(D.WOOD, var=0.08)
    gash = mat(R.FLESH, var=0.2)
    # 도끼: 뒤에서 내려찍어 날이 정수리에 1.5칸 박혔다. 날 끝선은 앞뒤 방향, 자루는 뒤쪽 위로 비스듬히(약 28도)
    piv = [0.8, -10.3, -0.4]
    rot = ('x', 28, piv)
    axe = [
        A('head', [0.3, -13.9, -2.6], [1.0, 5.1, 4.2], blade_p, rot=rot, tell='박힌 도끼: 문을 빨리 부순다'),
        A('head', [0.15, -15.1, -3.4], [1.3, 1.3, 12.0], handle_p, rot=rot),
        A('head', [-0.2, -10.45, -2.4], [2.0, 0.2, 3.8], gash),   # 날이 박힌 자리의 갈라진 살
    ]
    d['adds'] = zh + axe + moss
    d['concept'] = '변명자: 정수리에 박힌 도끼(자루는 뒤로 비스듬히), 저택 숲의 이끼 · 좀비 주민처럼 썩은 얼굴(머리 덮개)'
    return d


# ================================================================== 파괴수: 다시 만듦(두 겹)

RAV_FACE = Hole(0.74, 0.56, 0.18, 0.22, seed=101, jag=0.28, safe=safe_of((16, 20, 16), 'front', 2))
RAV_L = Hole(0.45, 0.7, 0.3, 0.16, seed=107, jag=0.3, safe=safe_of((14, 16, 20), 'left', 2))
RAV_R = Hole(0.55, 0.7, 0.3, 0.16, seed=108, jag=0.3, safe=safe_of((14, 16, 20), 'right', 2))


def ravager_shell(img, data):
    head, mouth, body = bfaces(data, 'head', 0), bfaces(data, 'mouth'), bfaces(data, 'body', 0)
    R.rot_skin(img, (0, 0, img.size[0], img.size[1]), amount=0.4, seed=101, mottle=0.24)
    # 아래턱: 입술 살이 썩어 내려앉아 아랫니와 턱뼈가 드러났다(그물 무늬 대신 또렷한 이 줄과 턱뼈 띠)
    f = mouth['front']
    k = D.SCALE[0]
    for x in range(f[2]):
        tooth = (x // (2 * k)) % 2 == 0
        for y in range(f[3]):
            if y < 2 * k:
                put(img, f[0] + x, f[1] + y, R.BONE[5 - (y >= k)] if tooth else R.FLESH[0])
            elif y < 3 * k:
                put(img, f[0] + x, f[1] + y, R.FLESH[2] if (x + y) % 3 else R.FLESH[1])
    for face in ('left', 'right'):
        r = mouth[face]
        for x in range(r[2]):
            for y in range(k, 2 * k):
                put(img, r[0] + x, r[1] + y, pick(R.BONE, 0.62 - 0.2 * (x / max(1, r[2])), x, y))
    for rect, seed in ((head['up'], 109), (head['back'], 110), (body['up'], 111)):
        stain(img, rect, seed, D.MOSS, coverage=0.45, cell=2.2 * k, edge=D.MOSS[1])


def ravager_inner(img, data):
    head, body = bfaces(data, 'head', 0), bfaces(data, 'body', 0)
    rr = random.Random(104)

    def skull(u, v, x, y):
        e = ell(u, v, 0.75, 0.6, 0.1, 0.075)
        if e < 1:
            return CAV[0] if e < 0.5 else CAV[1]          # 바닐라 오른눈 자리의 눈구멍
        if 0.57 < u < 0.66 and 0.66 < v < 0.73:
            return CAV[1]                                  # 콧구멍 가장자리
        if 0.72 < v < 0.78 and 0.58 < u < 0.9:
            return R.BONE[5] if int((u - 0.58) / 0.05) % 2 == 0 and v < 0.765 else CAV[2]   # 윗니
        if abs(v - 0.51) < 0.018:
            return pick(R.BONE, 0.35, x, y)               # 눈썹뼈 그늘
        return pick(R.BONE, 0.78 - 0.4 * v + rr.uniform(-0.05, 0.05), x, y)
    paint_inner(img, head['front'], (16, 20), 2, skull)

    def ribs(hole):
        def fn(u, v, x, y):
            dd = hole.dist(u, v)
            for rv in (0.58, 0.67, 0.76, 0.85):          # 갈비뼈: 몸 길이 방향으로 늘어선 세로 뼈대(다리에 안 가리는 뒤쪽)
                if abs(v - rv) < 0.028:
                    return pick(R.BONE, 0.85 - 0.3 * abs(u - 0.45), x, y)
            return pick(CAV, 0.15 + 0.6 * max(0.0, 1 - dd), x, y)
        return fn
    paint_inner(img, body['left'], (20, 16), 2, ribs(RAV_L))
    paint_inner(img, body['right'], (20, 16), 2, ribs(RAV_R))


def ravager():
    return dict(
        method='E', layer='minecraft:ravager#main', texture='illager/ravager.png', tone='murk', base2=ravager_shell, anatomy=ravager_inner,
        view=(-35, -14),
        layered=[layer_spec('head', (16, 20, 16), 2, {'front': [RAV_FACE]}, strands=0),
                 layer_spec('body', (14, 16, 20), 2, {'left': [RAV_L], 'right': [RAV_R]}, strands=1)],
        concept='썩은 파괴수: 살이 썩어 떨어진 뺨 속 눈구멍과 두개골, 내려앉은 입술 아래 아랫니와 턱뼈, 옆구리 구멍 속 갈비뼈(두 겹), 머리 위와 등의 이끼',
        adds=[])


# ================================================================== 목록

DESIGNS = dict(D3.DESIGNS)
DESIGNS.update({
    'creeper': creeper, 'enderman': enderman, 'guardian': guardian, 'elder_guardian': elder_guardian, 'ghast': ghast,
    'magma_cube': magma_cube, 'skeleton': skeleton, 'stray': stray, 'bogged': bogged, 'wither_skeleton': wither_skeleton,
    'piglin_brute': piglin_brute, 'pillager': pillager, 'evoker': evoker, 'vindicator': vindicator, 'ravager': ravager,
})
_shulker3 = D3.DESIGNS['shulker']


def shulker_inner(img, data):
    """속(뚜껑이 열리면 보이는 곳): 아랫판 윗면·뚜껑 아랫면은 썩은 속살과 고름(가장자리는 껍데기 단면), 머리는 좀비처럼."""
    lid, base, head = bfaces(data, 'lid'), bfaces(data, 'base'), bfaces(data, 'head')
    FLESHIN = ramp('#2A1618', '#4A2428', '#6E3A3A', '#8E5250', '#B0706A')
    SHELL = ramp('#2E1C34', '#47304F', '#62466C')
    PUS = ramp('#4E5A18', '#7A8A22', '#B4C24A')
    rr = random.Random(531)
    for rect in (base['up'], lid['down']):
        x0, y0, w, h = rect
        for y in range(h):
            for x in range(w):
                edge = min(x, y, w - 1 - x, h - 1 - y)
                if edge < 1:
                    put(img, x0 + x, y0 + y, pick(SHELL, 0.5, x, y))
                else:
                    t = 0.35 + 0.35 * (1 - abs(x - w / 2) / (w / 2)) * (1 - abs(y - h / 2) / (h / 2)) + rr.uniform(-0.1, 0.1)
                    put(img, x0 + x, y0 + y, pick(FLESHIN, t, x, y))
        for _ in range(5):
            cx, cy = x0 + rr.randrange(2, w - 2), y0 + rr.randrange(2, h - 2)
            put(img, cx, cy, PUS[2]); put(img, cx + 1, cy, PUS[1]); put(img, cx, cy + 1, PUS[0])
        for _ in range(3):
            put(img, x0 + rr.randrange(2, w - 2), y0 + rr.randrange(2, h - 2), FLESHIN[0])
    # 머리: 썩은 회녹색 살, 왼쪽 병든 눈, 오른쪽 아래 드러난 턱뼈
    SKIN = ramp('#2E3428', '#454E3A', '#5E6A50', '#7A8668')
    for face, r in head.items():
        for y in range(r[3]):
            for x in range(r[2]):
                put(img, r[0] + x, r[1] + y, pick(SKIN, 0.35 + rr.uniform(0, 0.4), x, y))
    f = head['front']
    put(img, f[0] + 1, f[1] + 2, R.SICK_EYE[3]); put(img, f[0] + 2, f[1] + 2, R.SICK_EYE[2])
    put(img, f[0] + 4, f[1] + 2, (0x14, 0x10, 0x10, 255))
    for x in range(3, 6):
        put(img, f[0] + x, f[1] + 4, R.BONE[4] if x % 2 else R.BONE[2])
    put(img, f[0] + 4, f[1] + 5, R.BONE[3])


def shulker():
    """셜커: 3차 그대로(1배)에 속(열리면 보이는 속살·좀비 머리)을 그린다. 2배로 칠하면 지의류 무늬가 잘게 부서져 3차가 더 낫다."""
    d = _shulker3()
    d['hires'] = 1
    base = d['base']
    d['base'] = lambda img, data: (base(img, data), shulker_inner(img, data))
    d['inner_pose'] = {'lid': (0, -9, 0, 0, 22.5, 0)}
    d['inner_view_pitch'] = 30
    d['concept'] = d['concept'].replace('중력 셜커', '가시 셜커') + ' · 뚜껑이 열리면 썩은 속살과 고름, 좀비처럼 썩은 머리'
    d['tell'] = '가시 껍데기: 닫혀 있을 때 근접으로 치면 껍데기 틈의 뼈 가시에 찔린다'
    return d


DESIGNS['shulker'] = shulker
_KEEP_TELL = {'shulker', 'skeleton', 'stray', 'bogged', 'wither_skeleton', 'piglin_brute', 'magma_cube', 'ghast'}
DESIGNS = {n: (f if n in _KEEP_TELL else D3._retell(n, f)) for n, f in DESIGNS.items()}
