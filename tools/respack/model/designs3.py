"""좀비화 몹 디자인 3차(2026-11-21): 2차 콘셉트(서식지·종족·기믹) + 1차의 좀비 느낌 + Mobestiary 해부도.

운영자 의견: "2차는 새 변종이나 그래픽 개선으로만 보인다. 1차는 좀비 느낌이 강했다. 합치면 될 듯."
→ 2차의 콘셉트·명암은 두고, 모든 리그 몹에 공통 '부패 층'(rot.py: 썩은 살빛, 찢긴 살, 드러난 뼈, 병든 눈빛)을 얹는다.
→ Mobestiary 도서의 해부도를 그대로 따라 '안쪽'을 드러낸다:
   크리퍼 = 반쪽 두개골·척추 퓨즈·화약 심장, 엔더맨 = 큰 뇌·가슴의 보라 핵, 가디언 = 붉은 벽의 빈 몸속과 관통한 가시,
   엘더 가디언 = 바위처럼 갈라진 창백한 살갗, 가스트 = 흉터·눈물 자국·화염구로 찬 위, 마그마 큐브 = 층 사이로 보이는 핵.
→ 작은 몹(거미, 동굴 거미, 팬텀)은 텍스처를 2배(64×64 급)로 그려 세부가 보이게 한다. 나머지는 1배(무게).
→ C 방식(우민·마녀)은 바닐라 머리 면을 옮겨 좀비 주민처럼 다시 칠한 '머리 덮개'를 씌운다(바닐라 좀비 짝: 주민 ↔ 좀비 주민).
"""
import random

import designs as D
import rot as R
from designs import A, bfaces, chain, mat, part, x_sphere
from paint import ramp, stain, put, get, recolor, lum, mix, with_alpha, crack, speckle, sphere_faces

# ------------------------------------------------------------------ 크리퍼


def creeper_anatomy(img, data):
    k = D.SCALE[0]
    head, body, leg = bfaces(data, 'head'), bfaces(data, 'body'), bfaces(data, 'right_front_leg')
    R.rot_skin(img, (0, 0, img.size[0], img.size[1]), amount=0.28, seed=3)
    # 얼굴 왼쪽 절반(텍스처 오른쪽 열)이 썩어 떨어져 두개골이 드러났다(Mobestiary 크리퍼 해부도)
    f = head['front']
    x0, y0, w, h = f
    half = x0 + w // 2
    rr = random.Random(5)
    for y in range(y0, y0 + h):
        edge = half + (1 if rr.random() < 0.45 else 0) - (1 if rr.random() < 0.2 else 0)
        for x in range(edge, x0 + w):
            c = get(img, x, y)
            if lum(c) < 0.16:
                put(img, x, y, R.FLESH[0])          # 눈구멍·입은 더 깊은 구멍
            else:
                t = 0.78 - 0.32 * (y - y0) / max(1, h - 1) - (0.12 if x == x0 + w - 1 else 0)
                put(img, x, y, D.pick(R.BONE, t, x, y))
        put(img, edge - 1, y, R.FLESH[3] if rr.random() < 0.6 else R.FLESH[2])   # 찢긴 살 가장자리
    # 두개골 쪽 입: 이빨 몇 개
    for y in range(y0 + h * 5 // 8, y0 + h * 7 // 8):
        for x in range(half, x0 + w - 1):
            if get(img, x, y) == R.FLESH[0] and (x + y) % 2 == 0:
                put(img, x, y, R.BONE[5])
    # 가슴: 찢긴 구멍 속 갈비뼈·척추·화약 심장
    b = body['front']
    cx, cy = b[0] + b[2] * 0.5, b[1] + b[3] * 0.5
    R.cavity(img, b, cx, cy, b[2] * 0.36, b[3] * 0.34, seed=7, rp=ramp('#0C0505', '#1E0B09', '#2E120E', '#40180F'))
    R.vertebrae(img, int(cx) - k, int(b[1] + b[3] * 0.18), int(b[1] + b[3] * 0.84), width=2 * k)
    core = R.ellipse_cells(b, cx + 0.2, cy + 0.6 * k, b[2] * 0.2, b[3] * 0.13)
    R.granules(img, core, 9, sparks=2 + k)
    for i, fy in enumerate((0.3, 0.44, 0.7)):
        R.bone_bar(img, int(b[0] + b[2] * 0.2), int(b[1] + b[3] * fy), max(2, int(b[2] * 0.6)), k)
    # 등: 척추 퓨즈가 드러나고(포자 자루 사이) 다리 아래는 뼈
    bk = body['back']
    R.wound(img, bk, bk[0] + bk[2] * 0.5, bk[1] + bk[3] * 0.62, bk[2] * 0.16, bk[3] * 0.3, seed=11, wet=1)
    R.vertebrae(img, int(bk[0] + bk[2] * 0.5) - k, int(bk[1] + bk[3] * 0.38), int(bk[1] + bk[3] * 0.9), width=2 * k)
    for rect in (leg['front'], leg['left']):
        R.wound(img, rect, rect[0] + rect[2] * 0.5, rect[1] + rect[3] * 0.62, rect[2] * 0.3, rect[3] * 0.16, seed=13 + rect[0], wet=1)


def creeper():
    d = D.creeper()
    d['base2'] = creeper_anatomy
    d['adds'] = [a for i, a in enumerate(d['adds']) if i not in (8,)]   # 선반 버섯 하나 빼서 덜 붐비게
    d['concept'] = ('균사 크리퍼: 썩어 떨어진 얼굴 반쪽에 두개골, 찢긴 가슴 속 갈비뼈·척추 퓨즈·화약 심장(Mobestiary 해부도). '
                    '등의 포자 자루, 머리의 동충하초')
    return d


# ------------------------------------------------------------------ 거미 계열(2배)

def spider_rot(img, data, venom=False):
    k = D.SCALE[0]
    ab, hd, leg = bfaces(data, 'body1'), bfaces(data, 'head'), bfaces(data, 'right_front_leg')
    R.rot_skin(img, (0, 0, img.size[0], img.size[1]), amount=0.22, seed=21, mottle=0.18)
    drip = D.VENOM if venom else R.ICHOR_WET
    # 배 양옆: 찢긴 살 + 흘러내리는 체액
    for face, fx, seed in (('left', 0.42, 23), ('right', 0.6, 24)):
        r = ab[face]
        R.wound(img, r, r[0] + r[2] * fx, r[1] + r[3] * 0.5, r[2] * 0.2, r[3] * 0.26, seed=seed, wet=2, drip=2, drip_rp=drip)
    r = ab['back']
    R.wound(img, r, r[0] + r[2] * 0.35, r[1] + r[3] * 0.4, r[2] * 0.14, r[3] * 0.18, seed=25, wet=1, drip=1, drip_rp=drip)
    # 털: 2배 해상도에서만 보이는 가는 센털
    for rect in (ab['up'], ab['left'], ab['right'], ab['back'], hd['up'], leg['front'], leg['up']):
        speckle(img, rect, 30 + rect[0], 0.05, [D.CHITIN[4], D.CHITIN[5]])
    # 머리: 송곳니 아래 썩은 살
    r = hd['front']
    R.wound(img, r, r[0] + r[2] * 0.5, r[1] + r[3] * 0.86, r[2] * 0.12, r[3] * 0.08, seed=27, wet=1)
    # 배 등딱지: 2배 해상도에서만 보이는 마디 골과 가장자리 반사
    r = ab['up']
    for i in range(1, 5):
        y = r[1] + r[3] * i // 5
        for x in range(r[0], r[0] + r[2]):
            c = get(img, x, y)
            if c[3] and lum(c) < 0.3:
                put(img, x, y, D.CHITIN[0])
                put(img, x, y - 1, mix(get(img, x, y - 1), D.CHITIN[5], 0.35))


def spider_eyes3(img, venom=False):
    """눈 여덟 개 모두 병든 빛(1차의 좀비 눈). 독샘 거미는 청록이 섞인다."""
    rp = R.SICK_EYE if not venom else ramp('#0E3A2E', '#1E6A50', '#3AA878', '#8AE8B0', '#E2FFEE')
    w, h = img.size
    for y in range(h):
        for x in range(w):
            c = img.getpixel((x, y))
            if c[3]:
                put(img, x, y, D.pick(rp, 0.5 + lum(c) * 0.9, x, y))


def spider():
    d = D.spider()
    d['hires'] = 2
    d['parts_scale'] = 2
    d['base2'] = spider_rot
    d['eyes_fn'] = spider_eyes3
    d['concept'] = '알집 거미: 배 위 기생 알 무더기, 찢긴 배에서 흘러내리는 누런 체액, 병든 빛의 큰 눈 두 개(텍스처 2배)'
    return d


def cave_spider():
    d = D.cave_spider()
    d['hires'] = 2
    d['parts_scale'] = 2
    d['base2'] = lambda img, data: spider_rot(img, data, venom=True)
    d['eyes_fn'] = lambda img: spider_eyes3(img, venom=True)
    d['concept'] = '독샘 거미: 부풀어 빛나는 청록 독샘, 찢긴 배에서 새는 독, 폐광 먼지·거미줄·박힌 갱목(텍스처 2배)'
    return d


# ------------------------------------------------------------------ 팬텀(2배)

def phantom_rot(img, data):
    body, head = bfaces(data, 'body'), bfaces(data, 'head')
    R.rot_skin(img, (0, 0, img.size[0], img.size[1]), amount=0.3, seed=41, mottle=0.2)
    for face, seed in (('left', 43), ('right', 44)):
        r = body[face]
        R.wound(img, r, r[0] + r[2] * 0.45, r[1] + r[3] * 0.5, r[2] * 0.16, r[3] * 0.34, seed=seed, wet=2)
        R.ribs(img, int(r[0] + r[2] * 0.33), int(r[1] + r[3] * 0.3), max(2, int(r[2] * 0.25)), int(r[3] * 0.4), 3)
    r = body['down']
    R.wound(img, r, r[0] + r[2] * 0.5, r[1] + r[3] * 0.55, r[2] * 0.3, r[3] * 0.18, seed=45, wet=2, drip=0)
    r = head['front']
    R.wound(img, r, r[0] + r[2] * 0.7, r[1] + r[3] * 0.7, r[2] * 0.14, r[3] * 0.18, seed=46, wet=1)


def phantom():
    d = D.phantom()
    d['hires'] = 2
    d['parts_scale'] = 2
    d['base2'] = phantom_rot
    d['concept'] = '시체 운반자(팬텀): 구멍 나고 해진 날개막 사이 날개뼈, 등뼈와 옆구리 갈비뼈가 드러난 썩은 몸(텍스처 2배)'
    return d


# ------------------------------------------------------------------ 엔더맨

def enderman_anatomy(img, data):
    k = D.SCALE[0]
    head, body = bfaces(data, 'head'), bfaces(data, 'body')
    # 정수리: 큰 뇌가 드러난 단면(Mobestiary Fig. XIII "Note the large brain"). 머리 겉층(hat)이 위를 덮으므로 겉층 윗면에도 그린다
    for r in (head['up'], bfaces(data, 'hat')['up']):
        cells = R.ellipse_cells(r, r[0] + r[2] * 0.5, r[1] + r[3] * 0.5, r[2] * 0.42, r[3] * 0.42)
        cs = set(cells)
        rr = random.Random(51)
        for (x, y) in cells:
            put(img, x, y, R.BRAIN[2 + ((x * 3 + y * 5) % 3 == 0)])
        for _ in range(5 * k):  # 주름: 짧게 구불구불한 어두운 골
            x, y = rr.choice(cells)
            for _s in range(4 * k):
                if (x, y) in cs:
                    put(img, x, y, R.BRAIN[0])
                x += rr.choice((-1, 0, 1))
                y += rr.choice((-1, 0, 1))
        for _ in range(4 * k):
            x, y = rr.choice(cells)
            put(img, x, y, R.BRAIN[5])
        for (x, y) in cells:  # 뇌 둘레 찢긴 살
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if (x + dx, y + dy) not in cs and r[0] <= x + dx < r[0] + r[2] and r[1] <= y + dy < r[1] + r[3]:
                    put(img, x + dx, y + dy, R.FLESH[2])
    # 가슴: 좌우 갈비뼈 사이로 빛나는 보라 핵(공허 에너지의 심장)
    b = body['front']
    cx, cy = b[0] + b[2] * 0.5, b[1] + b[3] * 0.42
    R.cavity(img, b, cx, cy, b[2] * 0.38, b[3] * 0.24, seed=53, rp=ramp('#050307', '#0E0714', '#170A22', '#22102F'))
    RIB = ramp('#4A4452', '#6A6476', '#8C8698', '#AEA8BA', '#CCC6D8', '#E6E0F0')
    for fy in (0.27, 0.37, 0.47, 0.57):
        y = int(b[1] + b[3] * fy)
        R.bone_bar(img, int(b[0] + b[2] * 0.14), y, max(1, int(b[2] * 0.24)), k, rp=RIB)
        R.bone_bar(img, int(b[0] + b[2] * 0.62), y, max(1, int(b[2] * 0.24)), k, rp=RIB)
    core = R.ellipse_cells(b, cx, cy, b[2] * 0.16, b[3] * 0.14)
    for (x, y) in core:
        dist = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
        put(img, x, y, R.VOIDCORE[5 if dist < 0.5 * k else 4 if dist < 1.3 * k else 3])


def enderman():
    d = D.enderman()
    d['base2'] = enderman_anatomy
    d['view'] = (-30, -24)
    d['concept'] = '공허 붕괴체: 단면처럼 드러난 큰 뇌, 갈비뼈 너머 빛나는 보라 핵(Mobestiary 단면도), 보라 균열과 떠다니는 조각, 빠진 턱'
    return d


# ------------------------------------------------------------------ 가디언·엘더 가디언

def guardian_hollow(img, data, elder=False):
    head = bfaces(data, 'head', 0)
    side_r, side_l = bfaces(data, 'head', 1), bfaces(data, 'head', 2)
    R.rot_skin(img, (0, 0, img.size[0], img.size[1]), amount=0.18 if not elder else 0.32, seed=61, mottle=0.16)
    # 옆구리가 찢겨 빈 몸속이 보인다: 붉은 안쪽 벽, 그 속을 지나는 가시 대(Mobestiary: 속은 비었고 가시가 관통한다)
    for rect, seed in ((head['front'], 63), (head['up'], 64), (side_l['left'], 65)):
        cx, cy = rect[0] + rect[2] * (0.72 if seed == 63 else 0.4), rect[1] + rect[3] * (0.74 if seed == 63 else 0.45)
        R.cavity(img, rect, cx, cy, rect[2] * 0.2, rect[3] * 0.18, seed=seed, rp=R.HOLLOW[::-1], rim=R.FLESH)
        for i in range(int(rect[3] * 0.5)):
            x = int(cx - rect[2] * 0.16 + i * 0.9)
            y = int(cy - rect[3] * 0.14 + i * 0.75)
            if get(img, x, y) in R.HOLLOW:
                put(img, x, y, ramp('#7C4A2C')[0])
                put(img, x, y - 1, ramp('#B07448')[0])
    if elder:
        # 바위를 깎은 듯 갈라진 창백한 살갗(Mobestiary: "cracked, as if carved from ancient rock")
        for k2, rect in enumerate((head['front'], head['up'], head['left'], side_r['right'], head['back'])):
            crack(img, rect, 70 + k2, max(6, rect[2]), dark=[ramp('#3E3A33')[0], ramp('#5A554B')[0]])
            crack(img, rect, 80 + k2, max(4, rect[2] // 2), dark=[ramp('#3E3A33')[0], ramp('#5A554B')[0]])


def guardian():
    d = D.guardian()
    base = d['base']
    d['base'] = lambda img, data: (base(img, data), guardian_hollow(img, data))
    d['concept'] = '익사체 지휘관(가디언): 따개비·죽은 산호, 찢긴 옆구리로 보이는 붉은 빈 몸속과 관통한 가시(Mobestiary), 백내장 눈'
    return d


def elder_guardian():
    d = D.guardian()
    base = d['base']
    d.update(layer='minecraft:elder_guardian#main', texture='guardian/guardian_elder.png', tone='pale',
             base=lambda img, data: (base(img, data), guardian_hollow(img, data, elder=True)),
             concept='썩은 장로(엘더 가디언): 바위처럼 갈라진 창백한 살갗, 찢긴 옆구리 속 붉은 빈 몸속, 따개비와 다시마')
    d['adds'] = d['adds'][:5] + d['adds'][-2:]   # 산호 가시는 빼고 따개비·다시마만
    return d


# ------------------------------------------------------------------ 가스트

def ghast_anatomy(img, data):
    k = D.SCALE[0]
    body = bfaces(data, 'body')
    R.rot_skin(img, (0, 0, img.size[0], img.size[1]), amount=0.2, seed=91, mottle=0.1)
    # 흉터로 덮인 몸(Mobestiary: "covered with scars")
    paths = {
        'up': [[(0.1, 0.2), (0.45, 0.35), (0.8, 0.25)], [(0.3, 0.7), (0.62, 0.85), (0.9, 0.7)]],
        'left': [[(0.15, 0.15), (0.35, 0.55), (0.3, 0.9)], [(0.6, 0.2), (0.85, 0.45)]],
        'right': [[(0.8, 0.1), (0.55, 0.5), (0.7, 0.85)]],
        'back': [[(0.1, 0.3), (0.5, 0.42), (0.9, 0.3)], [(0.4, 0.6), (0.45, 0.95)]],
        'front': [[(0.05, 0.12), (0.22, 0.3)], [(0.78, 0.08), (0.95, 0.3)]],
    }
    for face, ps in paths.items():
        for i, pts in enumerate(ps):
            R.scar(img, body[face], pts, seed=93 + i, dark=ramp('#4A3A36')[0])
    # 울던 자국: 눈 아래로 젖은 줄(Mobestiary: "the tracks of tears once shed are visible")
    f = body['front']
    TEAR = ramp('#4E5A60', '#6E7E86', '#9AAAB0', '#D4E2E6')
    for ex, L in ((0.3, 0.32), (0.66, 0.26)):
        R.tear(img, f[0] + int(f[2] * ex), f[1] + int(f[3] * 0.5), int(f[3] * L), TEAR, width=k)
    # 배: 찢긴 틈으로 화염구가 가득한 위(Mobestiary: "stomach is largely filled with fireballs")
    dn = body['down']
    cx, cy = dn[0] + dn[2] * 0.48, dn[1] + dn[3] * 0.55
    R.cavity(img, dn, cx, cy, dn[2] * 0.3, dn[3] * 0.26, seed=97, rp=ramp('#1A0602', '#2E0C04', '#461506', '#5E1E08'))
    rr = random.Random(98)
    for i in range(4):
        bx = cx + rr.uniform(-dn[2] * 0.16, dn[2] * 0.16)
        by = cy + rr.uniform(-dn[3] * 0.12, dn[3] * 0.12)
        rad = rr.uniform(1.1, 1.8) * k
        for (x, y) in R.ellipse_cells(dn, bx, by, rad, rad):
            dist = ((x + 0.5 - bx) ** 2 + (y + 0.5 - by) ** 2) ** 0.5 / rad
            put(img, x, y, R.FIREBALL[5 if dist < 0.35 else 4 if dist < 0.6 else 3 if dist < 0.85 else 2])
    # 앞면 아래쪽까지 이어진 찢김: 정면에서도 위 속 불빛이 살짝 보인다
    R.wound(img, f, f[0] + f[2] * 0.5, f[1] + f[3] * 0.96, f[2] * 0.2, f[3] * 0.08, seed=99, inner=R.FIREBALL[:4] + [R.FIREBALL[4], R.FIREBALL[5]], wet=0)


def ghast():
    d = D.ghast()
    d['base'] = ghast_anatomy
    # 2차의 벗겨진 살갗 조각(떠 보이던 판)은 뺀다. 시든 촉수 끝만 남기고, 끝을 썩은 살빛으로
    NECROTIC = ramp('#1E1A18', '#2E2824', '#3E3530', '#4E423A', '#5E3A30', '#7A4A3A')
    tip = mat(NECROTIC, extra=lambda img, faces, seed: speckle(img, faces['down'], seed, 0.35, [R.FLESH[3], R.BONE[3]]))
    d['adds'] = [A(a['bone'], a['origin'], a['size'], tip, tell=a.get('tell')) for a in d['adds'] if a['bone'].startswith('tentacle')]
    d['adds'][0]['tell'] = '찢긴 배 속 화염구: 죽을 때 위가 터져 작은 화염구 2~3개가 흩어진다'
    d['concept'] = '혼불 가스트: 흉터로 덮인 몸, 눈 아래 울던 자국, 찢긴 배 속에 가득한 화염구(Mobestiary 해부도), 끝이 썩은 촉수'
    return d


# ------------------------------------------------------------------ 슬라임·마그마 큐브

def slime():
    d = D.slime()
    flesh = mat(R.FLESH, var=0.2, extra=lambda img, faces, seed: speckle(img, faces.get('up', (0, 0, 0, 0)), seed, 0.3, [R.FLESH[5]]))
    old = d['adds'][0]['paint']

    def rotten_skull(img, faces, seed):
        old(img, faces, seed)
        for face in ('front', 'left', 'right'):
            r = faces.get(face)
            if r and r[2] >= 3:
                R.wound(img, r, r[0] + r[2] * (0.25 if face != 'right' else 0.7), r[1] + r[3] * 0.7, r[2] * 0.22, r[3] * 0.2, seed=seed + len(face), wet=1)
    d['adds'][0]['paint'] = rotten_skull
    d['adds'] += [A('cube', [1.4, 21.0, -1.6], [1.2, 1.0, 1.4], flesh), A('cube', [-2.6, 18.8, 1.6], [1.0, 0.8, 1.0], flesh)]
    d['concept'] = '늪 찌꺼기: 탁한 젤리 껍질(Mobestiary: 투명한 젤리 층) 속에 썩은 살이 붙은 해골·살점·연잎·기포'
    return d


def magma_cube():
    d = D.magma_cube()
    d['adds'] = [a for a in d['adds'] if a['size'][1] != 1.2 and a['size'][1] != 1 and a['size'][2] != 2.2 and a['size'][1] != 1.8]
    base = d['base']

    def embers(img, data):
        base(img, data)
        rr = random.Random(77)
        for bone in ('cube1', 'cube3', 'cube4'):
            F = bfaces(data, bone)
            for face in ('front', 'left', 'right', 'back'):
                r = F[face]
                for _ in range(2):
                    put(img, r[0] + rr.randrange(max(1, r[2])), r[1] + rr.randrange(max(1, r[3])), D.SOUL[5])
    d['base'] = embers
    if d['adds']:
        d['adds'][0]['tell'] = '층 사이 틈: 착지 직전 푸르게 빛나고 껍질 틈으로 혼불 불씨가 튄다'
    d['concept'] = '혼불 큐브: 흑암 껍데기 층 사이로 영혼 불 핵이 비치고(Mobestiary: 층이 용수철처럼 벌어지며 핵이 보인다), 틈으로 불씨'
    return d


# ------------------------------------------------------------------ 우민·마녀: 좀비 주민식 머리 덮개

def _eye_white(c):
    return c[3] and lum(c) > 0.8


def zhead_adds(seed_side='left', skin=None, nose=True):
    skin = skin or ramp('#2B3326', '#3D4836', '#53614A', '#6B7A5F', '#869478', '#A2AE93')
    face = lambda img, faces, seed: R.zombie_face(img, faces, seed, skin=skin, wound_side=seed_side, eye_test=_eye_white)
    nose_face = lambda img, faces, seed: R.zombie_face(img, faces, seed, skin=skin, eye_test=None, wounds=False)
    out = [dict(bone='head', origin=[-4.0, -10.0, -4.0], size=[8, 10, 8], paint=face, copy=('head', 0), grow=[0.3, 0.3, 0.3],
                tell=None, glow=False, rot=None, name='zombie_head')]
    if nose:
        out.append(dict(bone='nose', origin=[-1.0, -1.0, -6.0], size=[2, 4, 2], paint=nose_face, copy=('nose', 0), grow=[0.25, 0.25, 0.25],
                        tell=None, glow=False, rot=None, name='zombie_nose'))
    return out


def _with_zhead(fn, side='left'):
    def g():
        d = fn()
        d['adds'] = zhead_adds(side) + d['adds']
        d['concept'] = d['concept'] + ' · 좀비 주민처럼 썩은 얼굴(머리 덮개)'
        return d
    return g


# ------------------------------------------------------------------ 파괴수(새로)

def ravager_base(img, data):
    k = D.SCALE[0]
    head, mouth, body = bfaces(data, 'head', 0), bfaces(data, 'mouth'), bfaces(data, 'body', 0)
    R.rot_skin(img, (0, 0, img.size[0], img.size[1]), amount=0.4, seed=101, mottle=0.24)
    # 아래턱: 살이 떨어져 턱뼈와 이빨이 드러남
    for face in ('front', 'left', 'right', 'down'):
        r = mouth[face]
        for y in range(r[1], r[1] + r[3]):
            for x in range(r[0], r[0] + r[2]):
                if (x * 5 + y * 3) % 9 < 7:
                    put(img, x, y, D.pick(R.BONE, 0.78 - 0.3 * (y - r[1]) / max(1, r[3] - 1), x, y))
        if face == 'front':
            for x in range(r[0] + 1, r[0] + r[2] - 1, 2):
                put(img, x, r[1], R.BONE[5])
    # 얼굴 왼쪽 아래 살이 떨어져 광대뼈·눈구멍(크리퍼와 같은 '드러난 두개골')
    f = head['front']
    x0, y0, w, h = f
    for y in range(y0 + h // 2, y0 + h):
        edge = x0 + w // 2 + (1 if (y * 7) % 3 == 0 else 0)
        for x in range(edge, x0 + w):
            c = get(img, x, y)
            put(img, x, y, R.FLESH[0] if lum(c) < 0.12 else D.pick(R.BONE, 0.72 - 0.3 * (y - y0 - h // 2) / max(1, h // 2), x, y))
        put(img, edge - 1, y, R.FLESH[3])
    R.wound(img, f, f[0] + f[2] * 0.22, f[1] + f[3] * 0.78, f[2] * 0.1, f[3] * 0.08, seed=105, wet=2)
    # 옆구리: 갈비뼈가 드러난 큰 상처
    for face, seed in (('left', 107), ('right', 108)):
        r = body[face]
        R.cavity(img, r, r[0] + r[2] * 0.5, r[1] + r[3] * 0.45, r[2] * 0.26, r[3] * 0.32, seed=seed)
        R.ribs(img, int(r[0] + r[2] * 0.3), int(r[1] + r[3] * 0.24), int(r[2] * 0.4), int(r[3] * 0.44), 4)
    # 머리 위·뒤와 등: 이끼
    for rect, seed in ((head['up'], 109), (head['back'], 110), (body['up'], 111)):
        stain(img, rect, seed, D.MOSS, coverage=0.45, cell=2.2, edge=D.MOSS[1])


def ravager():
    return dict(
        method='E', layer='minecraft:ravager#main', texture='illager/ravager.png', tone='murk', base=ravager_base, view=(-35, -14),
        concept='썩은 파괴수: 살이 떨어져 드러난 턱뼈·광대뼈와 이빨, 갈비뼈가 보이는 옆구리, 머리 위와 등의 이끼',
        adds=[])


# 3차 기믹(문서 '몹 좀비화' 3절)에 맞춘 신호 문구. 디자인의 첫 덧붙임에 붙는다
TELLS = {
    'creaking': '송진 균열: 시선이 끊길 때마다 더 밝아지고 빨라진다',
    'phantom': '등에 탄 승객: 대상 가까이 급강하하며 내려놓는다',
    'guardian': '지휘 광선: 광선이 잡은 대상을 근처 드라운드가 노린다',
    'elder_guardian': '썩은 저주: 저주 유령이 초록빛, 근처 물속에 드라운드',
    'breeze': '흡입 돌풍: 거꾸로 도는 바람 입자를 끌고 나는 돌풍구',
    'skeleton': '균사로 이은 뼈: 쓰러지면 30% 확률로 8~14초 뒤 다시 일어선다',
    'stray': '갈비뼈의 서리: 화살이 얼음을 쌓는다',
    'bogged': '포자 터짐: 버섯 갓이 부풀며 빛난다(가위로 깎으면 잠잠)',
    'wither_skeleton': '진홍 균사: 쓰러져도 다시 일어선다',
    'piglin_brute': '찢는 도끼: 맞으면 늘 출혈, 체력 절반 아래 광폭',
    'vindicator': '도끼 투척: 도끼를 머리 뒤로 젖히는 0.5초',
    'evoker': '강령술: 팔을 들 때 초록 영혼 입자, 시체가 먼저 들썩인다',
    'illusioner': '거울 교대: 자리를 바꿀 때 거울 깨지는 소리',
    'witch': '언데드 치유: 가장 다친 좀비에게 물약, 회복된 좀비는 초록 입자',
    'shulker': '중력 교란: 공중부양 중 또 맞으면 아래로 내리꽂힌다',
    'ravager': '부르는 포효: 주변 좀비가 몰려온다',
    'blaze': '숯 껍데기: 화염구 2연발, 죽을 때 재 구름',
    'magma_cube': '층 사이 틈: 착지 직전 푸르게 빛나고 혼불 불씨가 튄다',
}
NAMES = {'shulker': '중력 셜커', 'skeleton': '묘지 해골'}


def _retell(name, fn):
    def g():
        d = fn()
        tell = TELLS.get(name)
        if tell:
            if d.get('adds'):
                for a in d['adds']:
                    a['tell'] = None
                d['adds'][0]['tell'] = tell
            else:
                d['tell'] = tell
        if name == 'shulker':
            d['concept'] = d['concept'].replace('부패 셜커', '중력 셜커')
        return d
    return g


DESIGNS = dict(D.DESIGNS)
DESIGNS.update({
    'creeper': creeper, 'spider': spider, 'cave_spider': cave_spider, 'phantom': phantom, 'enderman': enderman,
    'guardian': guardian, 'elder_guardian': elder_guardian, 'ghast': ghast, 'slime': slime, 'magma_cube': magma_cube,
    'pillager': _with_zhead(D.pillager, 'left'), 'vindicator': _with_zhead(D.vindicator, 'right'),
    'evoker': _with_zhead(D.evoker, 'left'), 'illusioner': _with_zhead(D.illusioner, 'right'),
    'witch': _with_zhead(D.witch, 'left'), 'ravager': ravager,
})
DESIGNS = {n: _retell(n, f) for n, f in DESIGNS.items()}
