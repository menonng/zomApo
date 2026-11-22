"""좀비화 몹 디자인(2차, 2026-11-21). 몹마다 서식지·종족·기믹에서 콘셉트를 잡는다. '좀비화 = 피'가 아니다.

| 몹 | 콘셉트 | 근거 |
|---|---|---|
| 크리퍼 | 균사 크리퍼: 동충하초처럼 균류가 몸을 먹어 들어감, 등에 포자 자루 | 풀·잎 같은 식물성 몸, 폭발 → 포자 구름 |
| 거미 | 알집 거미: 배 위에 기생 알 무더기, 노란 체액, 거미줄 | 절지동물(피 대신 체액), 동굴, 거미줄 침 |
| 동굴 거미 | 독샘 거미: 부푼 청록 독샘, 독이 떨어지는 송곳니, 갱도 거미줄 | 폐광 서식, 독 |
| 엔더맨 | 공허 붕괴체: 몸이 조각나 떠다니고 보라 균열, 빠진 턱 속 공허, 후렴 열매 | 엔드, 순간이동, 블록 들기 |
| 크리킹 | 창백한 썩음: 창백한 이끼 늘어짐, 송진 흘러내림, 선반 버섯, 머리에 눈꽃, 발 뿌리 | 창백한 정원, 크리킹 심장(송진), 뿌리 기믹 |
| 슬라임 | 늪 찌꺼기: 탁한 껍질 속에 해골·뼈·연잎·기포 | 늪, 재결합 |
| 마그마 큐브 | 혼불 큐브: 용암이 영혼 불로 식고 흑암 껍데기, 네더 화석 뼈 | 영혼 모래 골짜기, 갈라질 때 뼛조각 |
| 팬텀 | 누더기 팬텀: 구멍 난 날개막, 드러난 날개뼈·척추, 곰팡이 | 원래 언데드 비행체, 화살에 떨어짐 |
| 가디언 | 따개비 가디언: 따개비, 하얗게 죽은 산호 가시, 다시마, 백내장 눈 | 해저 신전, 탁한 피 |
| 브리즈 | 녹슨 바람: 녹청 슨 구리판·리벳, 피리 구멍, 바람 나팔 | 시련의 방(구리·응회암), 부르는 돌풍 |
| 가스트 | 혼불 가스트: 벗겨지는 살갗, 눈·입의 영혼 불빛, 그을린 눈물, 시든 촉수 | 네더, 우는 몹, 울부짖음 |
| 블레이즈 | 잿불 블레이즈: 숯 껍데기와 잿불 균열, 굴뚝, 타 버린 막대 | 요새, 꺼져 가는 불, 재 구름 |
| 스켈레톤 | 묘지 해골: 흙·이끼·노란 지의류, 뼈를 다시 잇는 균사, 머리의 버섯·뿌리 | 지상 묘지, 다시 일어서는 해골 |
| 스트레이 | 동상 해골: 성에, 고드름, 얼음 결정 | 눈 덮인 땅 |
| 보그드 | 포자 해골: 늪 점액, 빨강·갈색 버섯 무더기 | 늪, 원래 버섯 |
| 위더 스켈레톤 | 진홍 균류 해골: 진홍 균류·네더 사마귀 | 네더 요새 |
| 브루트 | 진홍 브루트: 진홍 균류, 녹슨 금 띠 | 보루 |
| 약탈자 | 화살이 박힌 머리, 해진 두건 | 순찰대, 표식 화살 |
| 변명자 | 머리에 박힌 도끼, 이끼 | 저택, 문 부수기 |
| 소환사 | 송곳니 왕관, 에메랄드 룬 띠 | 소환 마법 |
| 환술사 | 박힌 거울 조각 | 환영 |
| 마녀 | 모자에 버섯, 띠에 꽂힌 물약병 | 늪 오두막, 언데드 치유사 |
| 셜커 | 엔드석 지의류와 썩은 퍼퍼 | 엔드 시티 |

방식: E(리그)·D(셜커 텍스처)는 몸 텍스처까지 새로 칠한다. B(사람형 장비 덧씌움)·C(머리 아이템)는 **바닐라 몸 텍스처를 바꿀 수 없으므로**
몸은 그대로 두고 덧씌움(장비 레이어 64×32)과 머리 아이템만 그린다.
"""
import colorsys
import math
import random

from paint import (FACE_BASE, Noise, box_faces, chip_edges, colony, crack, disc, drips, frost_edge, get, glow_dot, hexc, lum,
                   mix, peel, pick, plank_strands, put, ramp, recolor, ring, rivets, shade_face, speckle, sphere_faces,
                   stain, stroke, threads, to_ramp, web, with_alpha)

# ---------------- 색 띠 (어두움 → 밝음) ----------------
FUNGUS = ramp('#3E352A', '#5E5242', '#857760', '#AE9F84', '#D3C6A9', '#F1EADA')
MYC = ramp('#8A806C', '#B4AA94', '#D9D1BE', '#F3EFE3')
SPORE = ramp('#34501E', '#4F732B', '#73983A', '#9DBE4C', '#C9E06E', '#F1F8B8')
NECRO = ramp('#1F2116', '#2E3020', '#40422A', '#555633')
CORDY = ramp('#4A2410', '#74391A', '#A25624', '#C97A36', '#E9A65E', '#F8D29A')
STEM = ramp('#6E6656', '#948B79', '#B9B09D', '#D8D1C0', '#EEE9DE')
DIRT = ramp('#2A1E14', '#3F2D1D', '#574028', '#6E5334', '#866943')
EGG = ramp('#6A5638', '#9A8259', '#C4AE82', '#E2D2AA', '#F4EACC', '#FFFEF4')
ICHOR = ramp('#2F4012', '#4D661C', '#72902A', '#9CB83A', '#C7DD5E', '#EEF7A6')
CHITIN = ramp('#100E0C', '#1B1815', '#27231E', '#353029', '#463F36', '#5A5146')
FANG = ramp('#4E4638', '#7A705C', '#A89D85', '#CFC5AE', '#EAE3D2', '#FBF8F0')
VENOM = ramp('#0C3238', '#145860', '#1E868E', '#35B6B4', '#82E8D8', '#DDFFF6')
VOID = ramp('#16061F', '#32104D', '#5C1E85', '#9238C4', '#D07EFA', '#F7DEFF')
ENDER = ramp('#08080A', '#111115', '#1A1A20', '#25252D', '#33333E')
CHORUS = ramp('#352240', '#553A62', '#7A5888', '#A07EAE', '#C9AED3', '#EFE3F3')
PALE_MOSS = ramp('#565A4B', '#767B66', '#999E86', '#BBC0A6', '#D8DBC5', '#EEF0E3')
RESIN = ramp('#4E1C06', '#7E300C', '#B04E13', '#DE741C', '#FFA63E', '#FFE29A')
BRACKET = ramp('#5B5446', '#80786A', '#A59D8B', '#C9C1AC', '#E4DECB', '#F6F2E6')
TURKEY = ramp('#3A2416', '#5E3A1F', '#8A5528', '#B87A34', '#D9A867', '#F0DDB6')
PETAL = ramp('#7E776A', '#A49D8E', '#C9C3B5', '#E6E1D6', '#F8F5EE')
BLOSSOM = ramp('#7A2E08', '#B4500E', '#E57216', '#FF9A32', '#FFC870', '#FFEDC0')
ROOT = ramp('#2E241B', '#433528', '#5A4A3A', '#73614F', '#8C7B68')
LEAFSTEM = ramp('#2A3B1C', '#3C5228', '#516C35', '#6A8845')
BONE = ramp('#544D40', '#7C7462', '#A59B84', '#C9BFA8', '#E2DBC6', '#F6F1E4')
SOOTBONE = ramp('#2A2620', '#4A443A', '#726A5A', '#9C927C', '#C2B8A0', '#E0D8C4')
SLIME_SHELL = ramp('#1F3314', '#2C481B', '#3E6125', '#547E30', '#6F9C3E', '#94BE5E')
ALGAE = ramp('#16220D', '#223413', '#30481B', '#405E24')
LILY = ramp('#163C10', '#225818', '#327522', '#46922E', '#62AE40')
SOUL = ramp('#0A2633', '#114458', '#1B7089', '#2CA2BC', '#78E2EE', '#E4FEFF')
BLACKSTONE = ramp('#120F15', '#1C1820', '#27222B', '#332D38', '#433C49', '#564E5D')
MOLD = ramp('#3C474E', '#56646C', '#76858C', '#98A7AC', '#BCC8CB')
BARNACLE = ramp('#34302A', '#56514A', '#837D70', '#AFA898', '#D6D0C0', '#F0ECE0')
CORAL = ramp('#6E5A56', '#957C77', '#BBA19A', '#D9C2BB', '#EEDDD7', '#FBF2EE')
KELP = ramp('#152A0E', '#213F16', '#30581F', '#427329', '#589035', '#73AD45')
CATARACT = ramp('#8E9A96', '#AEB9B5', '#CCD5D1', '#E6ECE9', '#F7FAF8')
COPPER = ramp('#4E2716', '#763C24', '#A25732', '#C97444', '#E59A66', '#F6C696')
VERDIGRIS = ramp('#163E33', '#245E4E', '#357F69', '#4C9F85', '#72C3A6', '#AAE6CF')
TUFF = ramp('#36362F', '#4A4A42', '#606057', '#76766C', '#8F8F84', '#A9A99E')
GHAST = ramp('#64615C', '#86837D', '#AAA7A1', '#CBC9C4', '#E5E4E0', '#F9F9F6')
SOOT = ramp('#0F0E0D', '#1B1917', '#282522', '#38342F', '#4A453F')
EMBER = ramp('#3E0F05', '#7A240D', '#B83E17', '#E86A22', '#FFA638', '#FFE58F')
ASH = ramp('#3E3A35', '#57524C', '#736D66', '#938D85', '#B6B0A7', '#D8D3CB')
CHARCOAL = ramp('#110E0D', '#1C1714', '#28201C', '#352B25', '#463930', '#594A3E')
MOSS = ramp('#1A2912', '#283D1C', '#385426', '#4B6C31', '#62873E', '#80A452')
LICHEN = ramp('#5E400F', '#8C6017', '#B78322', '#D9A63A', '#EFCB6E', '#FAE6A8')
FROST = ramp('#54779A', '#7EA2C3', '#A8C7E0', '#CDE3F2', '#EAF5FC', '#FFFFFF')
ICE = ramp('#355F8E', '#5384B6', '#7AABD8', '#A7CFEE', '#D6EDFA', '#FFFFFF')
SWAMP = ramp('#1C3412', '#2D5220', '#457332', '#619545', '#84B85C', '#AEDB82')
RED_CAP = ramp('#3C0808', '#661010', '#931A1A', '#BE2626', '#DF4A3C', '#F3846E')
BROWN_CAP = ramp('#33231A', '#4E3626', '#6C4E38', '#8D6A4C', '#AE8A66', '#CDAE88')
CRIMSON = ramp('#25050A', '#430B12', '#6B1219', '#941C23', '#BC2F33', '#DE5446')
WART = ramp('#2E0607', '#55100F', '#801A16', '#A92A1F', '#CF4A2E')
GOLD = ramp('#3A2F12', '#655320', '#93792E', '#C1A13E', '#E2C65E', '#F6E59A')
TARNISH = ramp('#22301E', '#33462B', '#4B6338')
CLOTH = ramp('#22201A', '#312E25', '#433F33', '#575242', '#6B6551', '#7F7961')
IRON = ramp('#2F2F33', '#4B4B51', '#6E6E76', '#9797A0', '#C2C2CA', '#ECECF2')
WOOD = ramp('#2E1E11', '#47301A', '#634526', '#7F5C33', '#9B7442')
FLETCH = ramp('#8E8E8E', '#B4B4B4', '#D8D8D8', '#F4F4F4')
EMERALD = ramp('#0B3319', '#135530', '#1E8546', '#33B864', '#7CEB9C', '#D2FFE0')
MIRROR = ramp('#3A4766', '#5C719E', '#8CA6D4', '#BFD3F0', '#E6F0FC', '#FFFFFF')
GLASS = ramp('#9DB4BF', '#C3D6DE', '#E6F1F5', '#FFFFFF')
POTION_PURPLE = ramp('#2E0B40', '#4E1668', '#782A96', '#A04EBE', '#C886E0')
POTION_GREEN = ramp('#163A12', '#25601E', '#3A8C2E', '#5CB646', '#94DC78')
CORK = ramp('#4A2F18', '#6A4626', '#8C6036', '#AC7C4A')
PURPUR = ramp('#2E1C34', '#47304F', '#62466C', '#7E5E89', '#9C7CA6', '#BFA4C6')
ENDSTONE = ramp('#7E7C52', '#A3A170', '#C6C491', '#E0DEB2', '#F2F0D2')


# ---------------- 바탕 텍스처 손질 ----------------

def tone(img, style):
    """원종 색을 몹에 맞게 살짝 바꾼다(얼룩은 찍지 않는다. 무늬는 디자인마다 따로)."""
    px = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            hh, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            if style == 'fungal':
                s *= 0.62
                hh = hh * 0.75 + 0.20 * 0.25
                v *= 0.86
            elif style == 'dark':
                s *= 0.8
                v *= 0.95
            elif style == 'warm':
                s *= 0.72
                v *= 0.78
            elif style == 'tint':
                s *= 0.72
                hh = hh * 0.86 + 0.26 * 0.14
                v *= 0.9
            elif style == 'murk':
                s *= 0.6
                hh = hh * 0.6 + 0.22 * 0.4
                v *= 0.78
            elif style == 'pale':
                s *= 0.5
                v *= 0.92
            elif style == 'sea':
                s *= 0.62
                hh = hh * 0.8 + 0.47 * 0.2
                v *= 0.82
            rr, gg, bb = colorsys.hsv_to_rgb(hh % 1.0, max(0, min(1, s)), max(0, min(1, v)))
            px[x, y] = (int(rr * 255), int(gg * 255), int(bb * 255), a)


def find(p, name):
    if p['name'] == name:
        return p
    for c in p['children']:
        r = find(c, name)
        if r:
            return r
    return None


SCALE = [1]  # 텍스처가 모델 UV 보다 몇 배인가(가스트 2). build.py 가 바탕을 칠하기 전에 정한다


def bfaces(data, bone, idx=0):
    """원종 큐브 하나의 면 사각형들(텍스처 픽셀, 배율 반영)."""
    c = find(data['root'], bone)['cubes'][idx]
    u, v = c['uv']
    w, h, d = (int(round(t)) for t in c['size'])
    k = SCALE[0]
    return {f: (r[0] * k, r[1] * k, r[2] * k, r[3] * k) for f, r in box_faces(int(u), int(v), w, h, d).items()}


def inset(rect, l=0, t=0, r=0, b=0):
    x, y, w, h = rect
    return (x + l, y + t, max(0, w - l - r), max(0, h - t - b))


def part(rect, fx0, fy0, fx1, fy1):
    """사각형의 비율 부분(0~1)."""
    x, y, w, h = rect
    return (x + int(w * fx0), y + int(h * fy0), max(1, int(w * (fx1 - fx0))), max(1, int(h * (fy1 - fy0))))


def is_dark(c, t=0.18):
    return lum(c) < t


# ---------------- 덧붙임 칠하기 ----------------

def mat(rp, var=0.15, grad=0.14, alpha=None, base=None, extra=None, cell=2.5, faces_only=None):
    def painter(img, faces, seed):
        for i, (face, rect) in enumerate(faces.items()):
            if rect[2] <= 0 or rect[3] <= 0:
                continue
            if faces_only and face not in faces_only:
                continue
            b = None if base is None else base.get(face) if isinstance(base, dict) else base
            shade_face(img, rect, rp, face, seed=seed * 31 + i, var=var, grad=grad, base=b, alpha=alpha, cell=cell)
        if extra:
            extra(img, faces, seed)
    return painter


def chain(*fns):
    def f(img, faces, seed):
        for k, fn in enumerate(fns):
            fn(img, faces, seed + k * 101)
    return f


def x_sac_veins(rp_vein, glow):
    """포자 자루·독샘: 가는 맥 + 가운데 빛나는 핵."""
    def f(img, faces, seed):
        for k, face in enumerate(('front', 'back', 'left', 'right', 'up')):
            r = faces.get(face)
            if not r or r[2] < 2 or r[3] < 2:
                continue
            crack(img, r, seed + k, max(2, (r[2] + r[3]) // 2), dark=[rp_vein[0], rp_vein[1]], wander=0.9, branch=0.3)
            cx, cy = r[0] + r[2] // 2, r[1] + r[3] // 2
            put(img, cx, cy, glow[-1])
            if r[2] > 2:
                put(img, cx - 1, cy, glow[-2])
            if r[3] > 2:
                put(img, cx, cy - 1, glow[-2])
    return f


def x_spots(rp, n=2, rmin=0.6, rmax=1.2, faces=('up', 'front', 'left', 'right', 'back')):
    def f(img, faces_, seed):
        rr = random.Random(seed)
        for face in faces:
            r = faces_.get(face)
            if not r or r[2] < 2 or r[3] < 2:
                continue
            for _ in range(n):
                disc(img, r[0] + rr.uniform(0.8, max(0.9, r[2] - 0.8)), r[1] + rr.uniform(0.8, max(0.9, r[3] - 0.8)),
                     rr.uniform(rmin, rmax), rp, spec=False)
    return f


def x_cap(rp_top, spots=None, gill_rp=None):
    """버섯갓: 윗면 가운데가 밝은 돔, 점, 아랫면 주름."""
    def f(img, faces, seed):
        r = faces.get('up')
        if r and r[2] >= 2:
            cx, cy = r[0] + r[2] / 2 - 0.3, r[1] + r[3] / 2 - 0.3
            disc(img, cx, cy, max(1.0, min(r[2], r[3]) / 2 - 0.2), rp_top, spec=True)
            if spots:
                rr = random.Random(seed)
                for _ in range(max(1, r[2] * r[3] // 5)):
                    put(img, r[0] + rr.randrange(r[2]), r[1] + rr.randrange(r[3]), spots)
        d = faces.get('down')
        if d and gill_rp:
            for y in range(d[3]):
                for x in range(d[2]):
                    put(img, d[0] + x, d[1] + y, gill_rp[1] if (x + y) % 2 else gill_rp[3])
            put(img, d[0] + d[2] // 2, d[1] + d[3] // 2, gill_rp[0])
        for side in ('front', 'back', 'left', 'right'):
            s = faces.get(side)
            if s and spots and s[2] > 1:
                put(img, s[0] + random.Random(seed + len(side)).randrange(s[2]), s[1], spots)
    return f


def x_rings(rp):
    """선반 버섯 윗면 나이테 + 아랫면 구멍."""
    def f(img, faces, seed):
        r = faces.get('up')
        if r:
            for y in range(r[3]):
                for x in range(r[2]):
                    dist = min(x, y, r[2] - 1 - x, r[3] - 1 - y)
                    put(img, r[0] + x, r[1] + y, rp[-1] if dist == 0 else (rp[3] if dist % 2 else rp[2]))
        d = faces.get('down')
        if d:
            for y in range(d[3]):
                for x in range(d[2]):
                    put(img, d[0] + x, d[1] + y, rp[0] if (x + 2 * y) % 3 == 0 else rp[1])
    return f


def x_teeth(dark, light):
    def f(img, faces, seed):
        r = faces.get('front')
        if r:
            for x in range(r[2]):
                put(img, r[0] + x, r[1], light if x % 2 == 0 else dark)
                if x % 2 == 0 and r[3] > 2:
                    put(img, r[0] + x, r[1] + 1, mix(light, dark, 0.5))
    return f


def x_void_mouth():
    def f(img, faces, seed):
        rr = random.Random(seed)
        r = faces.get('up')
        if r:
            for y in range(r[3]):
                for x in range(r[2]):
                    t = 0.15 + 0.5 * (y / max(1, r[3] - 1))
                    put(img, r[0] + x, r[1] + y, pick(VOID, t, r[0] + x, r[1] + y))
            for _ in range(3):
                put(img, r[0] + rr.randrange(r[2]), r[1] + rr.randrange(r[3]), VOID[-1])
    return f


def x_void_rim():
    def f(img, faces, seed):
        for face, r in faces.items():
            if r[2] <= 0 or r[3] <= 0:
                continue
            for x in range(r[2]):
                put(img, r[0] + x, r[1], VOID[3])
            for y in range(r[3]):
                put(img, r[0] + r[2] - 1, r[1] + y, VOID[2])
            put(img, r[0], r[1], VOID[4])
    return f


def x_embryo(dark):
    def f(img, faces, seed):
        for face in ('front', 'up', 'left'):
            r = faces.get(face)
            if r and r[2] >= 2 and r[3] >= 2:
                cx, cy = r[0] + r[2] // 2, r[1] + r[3] // 2
                put(img, cx, cy, dark)
                put(img, cx - 1, cy, mix(dark, EGG[2], 0.5))
                put(img, r[0], r[1], EGG[-1])
    return f


def x_tip(rp, rows=2, face_list=('front', 'back', 'left', 'right')):
    """끝(아래쪽) 몇 줄을 다른 색 띠로(송곳니 끝, 그을린 끝)."""
    def f(img, faces, seed):
        for face in face_list:
            r = faces.get(face)
            if not r:
                continue
            for y in range(max(0, r[3] - rows), r[3]):
                for x in range(r[2]):
                    put(img, r[0] + x, r[1] + y, pick(rp, 0.55 - 0.3 * (y - (r[3] - rows)) / max(1, rows), r[0] + x, r[1] + y))
        r = faces.get('down')
        if r:
            for y in range(r[3]):
                for x in range(r[2]):
                    put(img, r[0] + x, r[1] + y, rp[0])
    return f


def x_glow_crack(glow, n=1, faces_list=('up', 'front', 'back', 'left', 'right')):
    def f(img, faces, seed):
        for k, face in enumerate(faces_list):
            r = faces.get(face)
            if r and r[2] >= 2 and r[3] >= 2:
                for j in range(n):
                    crack(img, r, seed + 7 * k + j, max(2, r[2] + r[3]), dark=None, glow=glow, wander=0.7, branch=0.2)
    return f


def x_hole_top(hole, rim):
    def f(img, faces, seed):
        r = faces.get('up')
        if r and r[2] >= 1:
            for y in range(r[3]):
                for x in range(r[2]):
                    edge = x == 0 or y == 0 or x == r[2] - 1 or y == r[3] - 1
                    put(img, r[0] + x, r[1] + y, rim if edge and r[2] > 2 else hole)
    return f


def x_rivets(rp):
    def f(img, faces, seed):
        for face in ('front', 'back', 'left', 'right', 'up'):
            r = faces.get(face)
            if r and r[2] >= 3 and r[3] >= 3:
                rivets(img, [(r[0] + 1, r[1] + 1), (r[0] + r[2] - 2, r[1] + r[3] - 2)], rp)
    return f


def x_patina(rp, coverage=0.35):
    def f(img, faces, seed):
        for k, (face, r) in enumerate(faces.items()):
            if r[2] > 0 and r[3] > 0:
                stain(img, r, seed + k, rp, coverage=coverage, cell=1.6)
    return f


def x_web(img_alpha=180):
    """성긴 거미줄: 한쪽 모서리에서 방사선 몇 가닥 + 고리 한두 개, 나머지 투명."""
    def f(img, faces, seed):
        for k, (face, (x0, y0, w, h)) in enumerate(faces.items()):
            if w <= 0 or h <= 0:
                continue
            for y in range(h):
                for x in range(w):
                    put(img, x0 + x, y0 + y, (0, 0, 0, 0))
            col = (236, 236, 236, img_alpha)
            col2 = (196, 196, 196, img_alpha - 40)
            for a in (0.15, 0.55, 0.95, 1.35):
                for t in range(max(w, h) * 2):
                    x, y = int(t * math.cos(a) * 0.5), int(t * math.sin(a) * 0.5)
                    if 0 <= x < w and 0 <= y < h:
                        put(img, x0 + x, y0 + y, col)
            for rr in (2.5, 4.5):
                for t in range(16):
                    a = 1.4 * t / 15
                    x, y = int(rr * math.cos(a)), int(rr * math.sin(a))
                    if 0 <= x < w and 0 <= y < h and t % 3:
                        put(img, x0 + x, y0 + y, col2)
    return f


def x_strands(rp, density=0.6, alpha=255):
    def f(img, faces, seed):
        for k, (face, r) in enumerate(faces.items()):
            if r[2] > 0 and r[3] > 0:
                plank_strands(img, r, seed + k, rp, density=density, alpha=alpha)
    return f


def x_flame(rp):
    """불꽃 평면: 바닥은 거의 흰빛, 위로 갈수록 짙은 색의 혀 모양, 나머지 투명."""
    def f(img, faces, seed):
        rr = random.Random(seed)
        for face in ('front', 'back'):
            r = faces.get(face)
            if not r:
                continue
            for y in range(r[3]):
                for x in range(r[2]):
                    put(img, r[0] + x, r[1] + y, (0, 0, 0, 0))
            half = r[2] / 2
            for x in range(r[2]):
                dx = abs(x + 0.5 - half) / half
                L = max(1, int(round(r[3] * (0.45 + 0.55 * (1 - dx)) * rr.uniform(0.8, 1.0))))
                for i in range(L):
                    y = r[1] + r[3] - 1 - i
                    put(img, r[0] + x, y, pick(rp, 1.0 - 0.5 * i / max(1, L - 1), r[0] + x, y))
    return f


def x_lily(img, faces, seed):
    r = faces.get('front') or faces.get('up')
    for face in ('up', 'down', 'front', 'back'):
        r = faces.get(face)
        if not r:
            continue
        for y in range(r[3]):
            for x in range(r[2]):
                put(img, r[0] + x, r[1] + y, pick(LILY, 0.5 + 0.3 * ((x + y) % 3 == 0), r[0] + x, r[1] + y))
        put(img, r[0] + r[2] - 1, r[1], (0, 0, 0, 0))


def x_glass_vial(liquid):
    def f(img, faces, seed):
        for face, r in faces.items():
            if r[2] <= 0 or r[3] <= 0:
                continue
            for y in range(r[3]):
                for x in range(r[2]):
                    if face in ('up',):
                        c = CORK[2] if (x + y) % 2 else CORK[1]
                    elif face == 'down':
                        c = liquid[1]
                    elif y == 0:
                        c = CORK[2]
                    elif y >= r[3] // 2:
                        c = pick(liquid, 0.7 - 0.4 * (y - r[3] // 2) / max(1, r[3] // 2), r[0] + x, r[1] + y)
                    else:
                        g = GLASS[2] if x == 0 else GLASS[1]
                        c = (g[0], g[1], g[2], 120)
                    put(img, r[0] + x, r[1] + y, c)
            if r[2] > 1 and r[3] > 2 and face in ('front', 'left'):
                put(img, r[0], r[1] + 1, (255, 255, 255, 220))
    return f


def x_mirror(img, faces, seed):
    for face, r in faces.items():
        if r[2] <= 0 or r[3] <= 0:
            continue
        for y in range(r[3]):
            for x in range(r[2]):
                t = 0.35 + 0.5 * (((x - y) % 4) == 0) + 0.1 * (y == 0)
                c = pick(MIRROR, t, r[0] + x, r[1] + y)
                put(img, r[0] + x, r[1] + y, (c[0], c[1], c[2], 225))


def x_rune(img, faces, seed):
    rr = random.Random(seed)
    for face in ('front', 'back', 'left', 'right'):
        r = faces.get(face)
        if not r:
            continue
        for x in range(0, r[2], 2):
            if rr.random() < 0.7:
                put(img, r[0] + x, r[1] + rr.randrange(max(1, r[3])), EMERALD[-1] if rr.random() < 0.5 else EMERALD[-2])


# ---------------- 디자인 목록 ----------------

def A(bone, origin, size, paint, rot=None, tell=None, glow=False, name=None):
    return dict(bone=bone, origin=list(origin), size=list(size), paint=paint, rot=rot, tell=tell, glow=glow, name=name)


# ---- 크리퍼: 균사 크리퍼 ----

EMBRYO = ramp('#24180F', '#3E2C1C', '#5A4430')


def x_sphere(rp, alpha=None, spec=True):
    return lambda img, faces, seed: sphere_faces(img, faces, rp, alpha=alpha, spec=spec)


def x_paths(rp, paths, width=2, glow=False, faces_list=('front', 'back', 'left', 'right')):
    """정해 둔 경로(비율 좌표) 묶음을 면마다 긋는다."""
    def f(img, faces, seed):
        for face in faces_list:
            r = faces.get(face)
            if r and r[2] >= 2 and r[3] >= 2:
                for pts in paths:
                    stroke(img, r, pts, rp, width=width, glow=glow)
    return f


def x_core(glow):
    def f(img, faces, seed):
        for face in ('front', 'back', 'left', 'right', 'up'):
            r = faces.get(face)
            if r and r[2] >= 3 and r[3] >= 3:
                cx, cy = r[0] + r[2] // 2, r[1] + r[3] // 2
                put(img, cx, cy, glow[-1])
                put(img, cx - 1, cy, glow[-2])
                put(img, cx, cy - 1, glow[-2])
    return f


def x_club(rp):
    """동충하초 끝(자실체): 위가 밝은 세로 그러데이션 + 어두운 알갱이 + 왼쪽 반사광."""
    def f(img, faces, seed):
        rr = random.Random(seed)
        for face, (x0, y0, w, h) in faces.items():
            for y in range(h):
                for x in range(w):
                    t = FACE_BASE[face] + (0.18 * (1 - y / max(1, h - 1)) if face not in ('up', 'down') else 0.1)
                    if x == 0 and face not in ('up', 'down'):
                        t += 0.12
                    c = pick(rp, t, x0 + x, y0 + y)
                    if rr.random() < 0.18:
                        c = rp[1]
                    put(img, x0 + x, y0 + y, c)
    return f


def creeper_base(img, data):
    body, head, leg = bfaces(data, 'body'), bfaces(data, 'head'), bfaces(data, 'right_front_leg')
    # 포자 자루가 붙은 등 위쪽은 괴사(짙은 올리브갈색), 가장자리는 디더링으로 번짐
    stain(img, part(body['back'], 0.05, 0.0, 0.95, 0.75), 11, NECRO, coverage=0.62, cell=2.6, edge=NECRO[3])
    # 균사: 등 가운데 위에서 사방으로 굵은 줄기 → 가는 가지
    trunks = [[(0.5, 0.1), (0.25, 0.45), (0.1, 0.95)], [(0.5, 0.1), (0.8, 0.4), (0.95, 0.85)], [(0.5, 0.15), (0.55, 0.6), (0.45, 1.0)]]
    for pts in trunks:
        stroke(img, body['back'], pts, MYC, width=2, shade=NECRO[0])
    stroke(img, body['left'], [(1.0, 0.25), (0.5, 0.5), (0.1, 0.8)], MYC, width=1)
    stroke(img, body['right'], [(0.0, 0.25), (0.5, 0.55), (0.9, 0.85)], MYC, width=1)
    stroke(img, body['up'], [(0.5, 0.0), (0.4, 0.6), (0.2, 1.0)], MYC, width=1)
    stroke(img, head['back'], [(0.5, 1.0), (0.35, 0.55), (0.45, 0.0)], MYC, width=2, shade=NECRO[1])
    stroke(img, head['back'], [(0.4, 0.6), (0.85, 0.35)], MYC, width=1)
    stroke(img, head['up'], [(0.45, 0.0), (0.55, 0.45), (0.65, 0.6)], MYC, width=1)
    colony(img, head['up'][0] + 2.5, head['up'][1] + 5.0, 1.6, FUNGUS)
    colony(img, head['back'][0] + 6.0, head['back'][1] + 6.0, 1.4, FUNGUS)
    colony(img, body['left'][0] + 2.0, body['left'][1] + 8.0, 1.3, FUNGUS)
    # 얼굴: 눈·입은 더 깊게, 눈 아래쪽에 포자 빛 한 점
    f = head['front']
    recolor(img, f, lambda c, x, y: NECRO[0] if is_dark(c, 0.17) else None)
    k = SCALE[0]
    for (x, y) in [(2, 3), (5, 3)]:
        for dx in range(k):
            for dy in range(k):
                put(img, f[0] + x * k + dx, f[1] + y * k + dy, SPORE[-2])
    # 다리 아래: 흙
    for rect in (leg['front'], leg['back'], leg['left'], leg['right']):
        stain(img, part(rect, 0, 0.5, 1, 1.0), 40, DIRT, coverage=0.75, cell=1.5)


def creeper():
    veins = [[(0.15, 0.2), (0.5, 0.5), (0.8, 0.3)], [(0.5, 0.5), (0.4, 0.95)]]
    sac = chain(x_sphere(SPORE, alpha=232), x_paths([SPORE[0], SPORE[1], SPORE[1], SPORE[2]], veins, width=1), x_core(SPORE))
    stalk = mat(FUNGUS, var=0.06, grad=0.3)
    club = x_club(CORDY)
    shelf = mat(TURKEY, extra=x_rings(TURKEY))
    return dict(
        method='E', layer='minecraft:creeper#main', texture='creeper/creeper.png', tone='fungal', base=creeper_base,
        concept='균사 크리퍼: 균류가 등에서부터 몸을 먹어 들어간다. 머리에 동충하초, 옆구리에 선반 버섯',
        pose={'head': (4, 0, 7), 'right_hind_leg': (0, 0, -4)},
        adds=[
            A('body', [-3.8, 1.0, 1.4], [4, 4, 3], sac, tell='포자 자루: 점화 중 부풀며 빛난다', glow=True),
            A('body', [0.6, 3.8, 1.5], [3, 3, 2.4], sac, glow=True),
            A('body', [-1.8, 7.6, 1.5], [2.4, 2.4, 1.8], sac, glow=True),
            A('head', [0.8, -12.4, 0.2], [1, 4.4, 1], stalk, rot=('z', 22.5, [1.3, -8.0, 0.7])),
            A('head', [0.3, -15.4, -0.3], [2, 3, 2], club, rot=('z', 22.5, [1.3, -8.0, 0.7])),
            A('head', [-2.6, -11.0, -1.6], [1, 3, 1], stalk, rot=('z', -22.5, [-2.1, -8.0, -1.1])),
            A('head', [-3.1, -13.4, -2.1], [2, 2.4, 2], club, rot=('z', -22.5, [-2.1, -8.0, -1.1])),
            A('body', [-6.8, 4.2, -1.8], [3, 1, 3.6], shelf),
            A('body', [-6.2, 6.8, -1.4], [2.4, 1, 2.8], shelf),
            A('head', [4.0, -4.0, -2.0], [2, 0.8, 3], shelf),
        ])


# ---- 거미: 알집 거미 ----

def x_embryo2(img, faces, seed):
    for face in ('front', 'up', 'left', 'right'):
        r = faces.get(face)
        if r and r[2] >= 3 and r[3] >= 3:
            cx, cy = r[0] + r[2] // 2, r[1] + r[3] // 2
            put(img, cx, cy, (EMBRYO[0][0], EMBRYO[0][1], EMBRYO[0][2], 245))
            put(img, cx, cy - 1, (EMBRYO[1][0], EMBRYO[1][1], EMBRYO[1][2], 240))
            put(img, cx - 1, cy, (EMBRYO[2][0], EMBRYO[2][1], EMBRYO[2][2], 235))


def spider_base(img, data, venom=False):
    ab, hd, leg = bfaces(data, 'body1'), bfaces(data, 'head'), bfaces(data, 'right_front_leg')
    glow = VENOM if venom else ICHOR
    up = ab['up']
    stroke(img, up, [(0.2, 0.05), (0.45, 0.4), (0.38, 0.72), (0.6, 1.0)], glow, width=2, glow=True)
    stroke(img, up, [(0.45, 0.4), (0.85, 0.28)], glow, width=1, glow=True)
    stroke(img, up, [(0.38, 0.72), (0.1, 0.85)], glow, width=1, glow=True)
    for face, pts in (('left', [(0.1, 0.2), (0.5, 0.6), (0.9, 0.5)]), ('right', [(0.9, 0.3), (0.5, 0.55), (0.1, 0.8)]), ('back', [(0.5, 0.0), (0.4, 0.5), (0.6, 1.0)])):
        stroke(img, ab[face], pts, [CHITIN[0], CHITIN[0], CHITIN[1], CHITIN[5]], width=2, shade=CHITIN[4])
    if venom:
        for rect in (up, hd['up'], ab['back']):
            stain(img, rect, 70 + rect[0], TUFF, coverage=0.4, cell=1.4, edge=TUFF[1])
    else:
        stain(img, part(up, 0.15, 0.1, 0.85, 0.9), 75, with_alpha(ICHOR, 255), coverage=0.25, cell=1.3)


def spider_eyes(img, venom=False):
    def f(c, x, y):
        if c[3] and c[0] > c[1] + 40:  # 붉은 눈 → 뿌연 백내장(원래 색은 가운데에 흐리게)
            base = CATARACT[2] if not venom else VENOM[4]
            return mix(base, c, 0.25)
        return None
    recolor(img, (0, 0, img.size[0], img.size[1]), f)


def enderman_eyes(img):
    def f(c, x, y):
        if c[3] and lum(c) > 0.35:
            return mix(c, VOID[-1], 0.45)
        return None
    recolor(img, (0, 0, img.size[0], img.size[1]), f)


def spider():
    egg = chain(x_sphere(EGG, alpha=236), x_embryo2)
    fang = mat(FANG, grad=0.22, extra=x_tip([CHITIN[1], CHITIN[2], FANG[1]], rows=1))
    drop = mat(with_alpha(ICHOR, 230), var=0.04, grad=0.3)
    return dict(
        method='E', layer='minecraft:spider#main', texture='spider/spider.png', eyes='spider/spider_eyes.png', eyes_fn=spider_eyes,
        tone='dark', base=spider_base, view=(-35, 30),
        concept='알집 거미: 배 위에 쌓인 기생 알(속에 배아가 비침), 노란 체액이 스미는 갈라진 껍질, 거미줄 침',
        pose={'head': (6, 0, -4), 'right_middle_hind_leg': (0, 0, 10)},
        adds=[
            A('body1', [-3.6, -7.4, -0.6], [3.6, 3.6, 3.6], egg, tell='알집: 거미줄 침을 뱉기 전에 꿈틀댄다'),
            A('body1', [0.2, -7.0, 0.8], [3.4, 3.4, 3.4], egg),
            A('body1', [-1.4, -6.6, 3.2], [3, 3, 3], egg),
            A('body1', [1.8, -6.2, -2.8], [3, 3, 3], egg),
            A('body1', [-4.6, -6.0, -3.6], [2.6, 2.6, 2.6], egg),
            A('body1', [1.6, -5.8, 4.0], [2.4, 2.4, 2.4], egg),
            A('body1', [-1.2, -9.8, 0.6], [2.6, 2.6, 2.6], egg),
            A('head', [-2.8, 1.8, -8.9], [1.6, 2.2, 1.6], fang),
            A('head', [1.2, 1.8, -8.9], [1.6, 2.2, 1.6], fang),
            A('head', [-2.6, 3.8, -8.7], [1.2, 1.8, 1.2], mat(FANG, extra=x_tip([CHITIN[0], CHITIN[1], CHITIN[2]], rows=2))),
            A('head', [1.4, 3.8, -8.7], [1.2, 1.8, 1.2], mat(FANG, extra=x_tip([CHITIN[0], CHITIN[1], CHITIN[2]], rows=2))),
            A('head', [-2.3, 5.6, -8.6], [0.6, 0.8, 0.6], drop, glow=True),
        ])


def cave_spider():
    gland = chain(x_sphere(VENOM, alpha=228), x_paths([VENOM[0], VENOM[1], VENOM[1], VENOM[2]], [[(0.1, 0.3), (0.5, 0.5), (0.9, 0.2)], [(0.5, 0.5), (0.6, 1.0)]], width=1), x_core(VENOM))
    fang = mat(FANG, grad=0.2, extra=x_tip([VENOM[2], VENOM[3], VENOM[4]], rows=1))
    drip = mat(with_alpha(VENOM, 215), var=0.04, grad=0.35)
    splinter = mat(WOOD, var=0.1, extra=lambda img, faces, seed: [put(img, faces[f][0], faces[f][1] + faces[f][3] - 1, WOOD[0]) for f in ('front', 'back', 'left', 'right') if faces.get(f)])
    return dict(
        method='E', layer='minecraft:cave_spider#main', texture='spider/cave_spider.png', eyes='spider/spider_eyes.png',
        eyes_fn=lambda img: spider_eyes(img, venom=True), tone='dark', base=lambda img, data: spider_base(img, data, venom=True),
        scale=0.7, view=(-35, 30), concept='독샘 거미: 양옆에 부풀어 빛나는 청록 독샘, 독이 늘어지는 송곳니, 폐광 먼지·거미줄·박힌 갱목 조각',
        pose={'head': (6, 0, 0)},
        adds=[
            A('body1', [4.6, -3.0, -2.4], [3, 3.4, 4], gland, tell='독샘: 부풀어 빛난다', glow=True),
            A('body1', [-7.6, -2.8, -1.0], [3, 3.4, 4], gland, glow=True),
            A('body1', [-5.0, -4.7, -5.0], [10, 0, 9], x_web(165)),
            A('body1', [1.0, -9.0, 0.0], [1, 1, 6], splinter, rot=('x', 22.5, [1.5, -4.0, 3.0])),
            A('head', [-2.8, 1.8, -8.9], [1.6, 2.6, 1.6], fang),
            A('head', [1.2, 1.8, -8.9], [1.6, 2.6, 1.6], fang),
            A('head', [-2.3, 4.4, -8.6], [0.6, 2.4, 0.6], drip, glow=True),
            A('head', [1.7, 4.4, -8.6], [0.6, 1.8, 0.6], drip, glow=True),
        ])


# ---- 엔더맨: 공허 붕괴체 ----

def enderman_base(img, data):
    body, head, limb = bfaces(data, 'body'), bfaces(data, 'head'), bfaces(data, 'right_arm')
    kw = dict(width=1, glow=True, core=4, halo=2, halo_p=0.35)
    stroke(img, body['front'], [(0.25, 0.0), (0.38, 0.28), (0.28, 0.5), (0.5, 0.78), (0.45, 1.0)], VOID, seed=1, **kw)
    stroke(img, body['front'], [(0.38, 0.28), (0.78, 0.42)], VOID, seed=2, **kw)
    stroke(img, body['back'], [(0.8, 0.0), (0.62, 0.42), (0.7, 0.9)], VOID, seed=3, **kw)
    stroke(img, head['left'], [(0.0, 0.35), (0.55, 0.6), (1.0, 0.5)], VOID, seed=4, **kw)
    stroke(img, head['back'], [(0.35, 1.0), (0.5, 0.55), (0.42, 0.1)], VOID, seed=5, **kw)
    # 팔다리(네 개가 같은 텍스처 자리): 위쪽 한 곳만 갈라짐
    for face in ('front', 'left'):
        stroke(img, limb[face], [(0.0, 0.12), (1.0, 0.18), (0.0, 0.26)], VOID, seed=6, **kw)
    stroke(img, limb['back'], [(1.0, 0.55), (0.0, 0.62)], VOID, seed=7, **kw)
    # 떨어져 나간 자리: 몸통 가장자리 몇 칸이 공허색
    for face in ('front', 'back'):
        chip_edges(img, body[face], 95 + len(face), 3, VOID[1])


def enderman():
    jaw = mat(ENDER, extra=chain(x_void_mouth(), x_teeth(ENDER[0], ENDER[4])))
    frag = mat(ENDER, var=0.06, extra=x_void_rim())
    chorus = mat(CHORUS, var=0.14, extra=x_spots([CHORUS[1], CHORUS[2], CHORUS[2], CHORUS[3]], n=2, rmin=0.5, rmax=0.8))
    def petals(img, faces, seed):
        sphere_faces(img, faces, CHORUS, spec=True, strength=0.3)
        for face, r in faces.items():
            if r[2] >= 2 and r[3] >= 2:
                put(img, r[0] + r[2] // 2, r[1], CHORUS[-1])
                put(img, r[0], r[1] + r[3] // 2, CHORUS[-1])
    adds = [
        A('head', [-3.0, 0.0, -4.6], [6, 3.4, 3], jaw, tell='빠진 턱: 순간이동 0.5초 전에 크게 벌어진다'),
        A('body', [-4.2, -3.4, -0.8], [2, 3.4, 2], chorus),
        A('body', [-6.4, -4.4, -0.6], [2.4, 1.6, 1.6], chorus),
        A('body', [-7.4, -6.4, -1.2], [2.4, 2.4, 2.4], petals),
        A('body', [-2.0, -2.6, 0.4], [1.4, 2.6, 1.4], chorus),
        A('body', [-2.2, -4.4, 0.2], [1.8, 1.8, 1.8], petals),
    ]
    for (bone, o, sz) in [('right_arm', [-3.0, 3.6, -0.6], 1.6), ('right_arm', [-2.6, 11.8, 0.2], 1.2), ('right_arm', [-3.4, 18.0, -0.4], 1.0),
                          ('left_arm', [1.8, 8.6, 0.0], 1.2), ('body', [4.6, 2.6, -1.0], 1.6), ('body', [-6.4, 7.0, 0.2], 1.4),
                          ('head', [4.8, -6.4, -2.0], 1.6), ('head', [-6.0, -3.0, 1.0], 1.0), ('left_leg', [1.8, 14.0, -0.6], 1.2)]:
        adds.append(A(bone, o, [sz, sz, sz], frag, glow=True))
    return dict(
        method='E', layer='minecraft:enderman#main', texture='enderman/enderman.png', eyes='enderman/enderman_eyes.png',
        eyes_fn=enderman_eyes, tone='dark', base=enderman_base,
        concept='공허 붕괴체: 몸에 보라 균열이 번지고 조각이 떨어져 떠다닌다. 빠진 턱 속은 공허, 어깨에 후렴 열매',
        pose={'head': (9, 0, -6)}, adds=adds)


# ---- 크리킹: 창백한 썩음 ----

def creaking_base(img, data):
    head = bfaces(data, 'head', 0)
    top = bfaces(data, 'head', 1)
    body = bfaces(data, 'body', 0)
    body2 = bfaces(data, 'body', 1)
    for k, rect in enumerate([head['up'], top['up'], top['front'], body['up'], body2['up']]):
        stain(img, rect, 100 + k, PALE_MOSS, coverage=0.55, cell=1.6, edge=PALE_MOSS[1])
    kw = dict(width=1, glow=True, core=4, halo=2, halo_p=0.45)
    stroke(img, body['front'], [(0.3, 0.0), (0.45, 0.35), (0.35, 0.6), (0.55, 1.0)], RESIN, seed=11, **kw)
    stroke(img, body2['front'], [(0.6, 0.0), (0.4, 0.5), (0.5, 1.0)], RESIN, seed=12, **kw)
    stroke(img, body['back'], [(0.5, 0.0), (0.6, 0.5), (0.45, 1.0)], RESIN, seed=13, **kw)


def creaking():
    moss = mat(PALE_MOSS, extra=x_strands(PALE_MOSS, density=0.7))
    resin = mat(RESIN, var=0.08, grad=0.25, extra=lambda img, faces, seed: put(img, faces['front'][0], faces['front'][1], RESIN[-1]))
    shelf = mat(BRACKET, extra=x_rings(BRACKET))
    leafstem = mat(LEAFSTEM, var=0.08)
    bloom = mat(PETAL, extra=lambda img, faces, seed: (disc(img, faces['up'][0] + faces['up'][2] / 2, faces['up'][1] + faces['up'][3] / 2, 1.1, BLOSSOM, spec=True)))
    root = mat(ROOT, var=0.16, extra=x_spots([ROOT[0], ROOT[1], ROOT[2], ROOT[3]], n=1, rmin=0.4, rmax=0.6))
    return dict(
        method='E', layer='minecraft:creaking#main', texture='creaking/creaking.png', eyes='creaking/creaking_eyes.png',
        tone='tint', base=creaking_base,
        concept='창백한 썩음: 창백한 이끼가 늘어지고 갈라진 틈에서 송진이 흐른다. 머리에 눈꽃, 발에서 뿌리',
        pose={'head': (0, 0, 5)},
        adds=[
            A('body', [0.5, -3.2, -3.3], [5, 7, 0], moss),
            A('head', [-3.3, -12.4, -2.0], [0, 8, 4], moss),
            A('right_arm', [-2.3, -1.4, -1.8], [0, 9, 3.6], moss),
            A('body', [2.0, 1.0, -3.7], [1.6, 2.6, 0.8], resin, tell='송진: 오래 쳐다보면 심이 빛나며 뿌리를 내민다', glow=True),
            A('body', [2.4, 3.6, -3.5], [0.8, 2.2, 0.6], resin, glow=True),
            A('head', [1.5, -6.2, -3.6], [1.2, 1.6, 0.7], resin, glow=True),
            A('body', [6.0, 0.0, -2.0], [2.4, 0.8, 3.0], shelf),
            A('body', [6.0, 2.5, -1.5], [1.7, 0.7, 2.2], shelf),
            A('head', [-0.5, -15.0, -0.5], [1, 2, 1], leafstem),
            A('head', [-1.6, -16.2, -1.6], [3.2, 1.2, 3.2], bloom, glow=True),
            A('left_leg', [-2.6, 14.6, -2.4], [1.2, 1.4, 4.4], root, tell='발 뿌리'),
            A('right_leg', [-5.0, 16.0, -3.2], [4.4, 1.0, 1.2], root),
        ])


# ---- 슬라임: 늪 찌꺼기 ----

def slime_base(img, data):
    outer = bfaces(data, 'outer_cube') if find(data['root'], 'outer_cube') else None
    if outer:
        # 껍질은 반투명 그대로(알파 유지), 탁하게만. 바닥 쪽에 가라앉은 앙금, 군데군데 기포 반사
        for face, rect in outer.items():
            recolor(img, rect, lambda c, x, y: (int(c[0] * 0.8), int(c[1] * 0.82), int(c[2] * 0.7), min(c[3], 150)))
        stain(img, outer['down'], 131, with_alpha(ALGAE, 210), coverage=0.6, cell=1.6)
        for face in ('front', 'back', 'left', 'right'):
            r = outer[face]
            stain(img, part(r, 0, 0.7, 1, 1), 132 + len(face), with_alpha(ALGAE, 200), coverage=0.5, cell=1.4)
            rr = random.Random(140 + len(face))
            for _ in range(3):
                x, y = r[0] + rr.randrange(r[2]), r[1] + rr.randrange(max(1, r[3] - 2))
                put(img, x, y, (240, 255, 240, 200))
                put(img, x + 1, y + 1, (190, 225, 190, 150))


def slime_eyes(img, data):
    for bone in ('right_eye', 'left_eye', 'mouth'):
        f = bfaces(data, bone)
        for face, rect in f.items():
            recolor(img, rect, lambda c, x, y: mix(c, NECRO[0], 0.5))


def slime():
    def skull_face(img, faces, seed):
        f = faces['front']
        dark, mid = ramp('#15120D')[0], BONE[1]
        for (x, y) in ((1, 1), (3, 1)):
            put(img, f[0] + x, f[1] + y, dark)
            put(img, f[0] + x, f[1] + y + 1, mid)
        put(img, f[0] + 2, f[1] + 2, mid)
        stain(img, faces['up'], seed, ALGAE, coverage=0.35, cell=1.2)
    skull = mat(BONE, extra=skull_face)
    jaw = mat(BONE, extra=x_teeth(BONE[1], BONE[-1]))
    bubble = mat(ramp('#CDEFD3', '#E8FFF0', '#FFFFFF', a=150), var=0.05)
    return dict(
        method='E', layer='minecraft:slime#main', outer='minecraft:slime#outer', texture='slime/slime.png', tone='murk',
        base=slime_base, base2=slime_eyes,
        concept='늪 찌꺼기: 탁한 껍질 속에 해골·뼈·연잎·기포가 떠 있다',
        remove=[('cube', 0)],
        adds=[
            A('cube', [-2.2, 17.6, -2.2], [4.4, 4.0, 4.4], skull, tell='속에 비치는 해골: 작은 개체끼리 붙으면 다시 합쳐진다'),
            A('cube', [-1.8, 21.6, -2.0], [3.6, 1.2, 2.8], jaw),
            A('cube', [1.2, 19.2, 0.8], [1.6, 0.6, 0.6], mat(BONE), rot=('y', 22.5, [2.0, 19.5, 1.1])),
            A('cube', [-2.6, 17.4, 0.4], [2.6, 0, 2.6], x_lily),
            A('cube', [2.0, 17.5, -2.6], [0.8, 0.8, 0.8], bubble),
            A('cube', [-2.8, 20.4, 2.0], [0.6, 0.6, 0.6], bubble),
            A('cube', [0.4, 22.0, 2.4], [0.6, 0.6, 0.6], bubble),
        ])


# ---- 마그마 큐브: 혼불 큐브 ----

def magma_base(img, data):
    # 껍데기: 검붉은 바탕 → 흑암(보라빛 회색), 밝은 주황(눈·틈) → 영혼 불
    def f(c, x, y):
        r, g, b, a = c
        if r > 150 and g > 50:
            return pick(SOUL, min(1.0, 0.45 + lum(c) * 0.6), x, y)
        return pick(BLACKSTONE, min(0.95, 0.25 + lum(c) * 3.0), x, y)
    recolor(img, (0, 0, img.size[0], img.size[1]), f)
    # 식은 껍데기 사이로 영혼 불 띠: 2·5번째 조각 옆면이 군데군데 끊긴 채 빛난다
    for bone, seed in (('cube2', 1), ('cube5', 2), ('cube6', 3)):
        F = bfaces(data, bone)
        rr = random.Random(600 + seed)
        for face in ('front', 'back', 'left', 'right'):
            r = F[face]
            run = 0
            for x in range(r[2]):
                on = rr.random() < (0.72 if bone != 'cube6' else 0.3)
                if on:
                    put(img, r[0] + x, r[1], pick(SOUL, 0.55 + 0.4 * rr.random(), r[0] + x, r[1]))
    top = bfaces(data, 'cube0')['up']
    kw = dict(width=1, glow=True, core=4, halo=3, halo_p=0.4)
    stroke(img, top, [(0.1, 0.2), (0.45, 0.45), (0.4, 0.8), (0.75, 1.0)], SOUL, seed=5, **kw)
    stroke(img, top, [(0.45, 0.45), (0.9, 0.3)], SOUL, seed=6, **kw)
    for face, r in bfaces(data, 'inside_cube').items():
        recolor(img, r, lambda c, x, y: pick(SOUL, 0.75 + 0.2 * ((x + y) % 2), r[0] + x, r[1] + y))


def magma_cube():
    shard = mat(SOOTBONE, extra=chain(x_tip(SOOT, rows=1), lambda img, faces, seed: crack(img, faces['front'], seed, 3, dark=[SOOTBONE[0], SOOTBONE[1]])))
    flame = x_flame(with_alpha(SOUL, 235))
    return dict(
        method='E', layer='minecraft:magma_cube#main', texture='slime/magmacube.png', tone=None, base=magma_base, view=(-35, 28),
        concept='혼불 큐브: 용암이 영혼 불로 식어 흑암 껍데기가 되고, 네더 화석 뼈가 삐져나온다',
        adds=[
            A('cube3', [3.4, 18.6, -1.4], [2.2, 1.2, 1.2], shard, tell='삐져나온 화석 뼈: 갈라질 때 뼛조각 파편'),
            A('cube5', [-6.0, 20.6, 0.4], [2.2, 1, 1], shard),
            A('cube1', [-0.8, 16.2, -5.8], [1.4, 1.4, 2.2], shard),
            A('cube0', [1.0, 14.6, 1.0], [1, 1.8, 1], shard),
            A('cube0', [-2.5, 11.0, 0.0], [5, 5, 0], flame, glow=True),
            A('cube0', [0.0, 11.6, -2.2], [0, 4.4, 4.4], flame, glow=True),
        ])


# ---- 팬텀: 누더기 팬텀 ----

def phantom_base(img, data):
    body = bfaces(data, 'body')
    for bone in ('right_wing_tip', 'left_wing_tip', 'right_wing_base'):
        f = bfaces(data, bone)
        for k, face in enumerate(('up', 'down')):
            rect = f[face]
            rr = random.Random(150 + k + len(bone))
            for _ in range(max(2, rect[2] * rect[3] // 22)):
                cx, cy = rect[0] + rr.randrange(rect[2]), rect[1] + rr.randrange(rect[3])
                rad = rr.choice([0.8, 1.2, 1.6])
                for y in range(int(cy - 2), int(cy + 3)):
                    for x in range(int(cx - 2), int(cx + 3)):
                        if rect[0] <= x < rect[0] + rect[2] and rect[1] <= y < rect[1] + rect[3]:
                            dd = math.hypot(x - cx, y - cy)
                            if dd < rad:
                                put(img, x, y, (0, 0, 0, 0))
                            elif dd < rad + 1 and get(img, x, y)[3]:
                                put(img, x, y, mix(get(img, x, y), MOLD[0], 0.5))
            # 뒷가장자리 해짐
            for x in range(rect[2]):
                if rr.random() < 0.4:
                    put(img, rect[0] + x, rect[1] + rect[3] - 1, (0, 0, 0, 0))
    for k, rect in enumerate([body['up'], body['left'], body['right']]):
        stain(img, rect, 160 + k, MOLD, coverage=0.4, cell=1.6)


def phantom_eyes(img):
    recolor(img, (0, 0, img.size[0], img.size[1]), lambda c, x, y: mix(c, (200, 220, 205, 255), 0.5) if c[3] and lum(c) > 0.3 else None)


def phantom():
    spar = mat(BONE, var=0.08, grad=0.1)
    knob = mat(BONE, extra=lambda img, faces, seed: put(img, faces['up'][0], faces['up'][1], BONE[-1]))
    rib = mat(BONE, var=0.06)
    adds = [
        A('left_wing_tip', [0.0, -0.5, 1.0], [13, 0.6, 0.6], spar, tell='찢어진 날개: 화살에 맞으면 떨어져 5초 기어 다닌다'),
        A('left_wing_tip', [0.0, -0.5, 4.6], [11.5, 0.6, 0.6], spar),
        A('left_wing_tip', [0.0, -0.5, 7.6], [8.5, 0.6, 0.6], spar),
        A('right_wing_tip', [-13.0, -0.5, 1.0], [13, 0.6, 0.6], spar),
        A('right_wing_tip', [-11.5, -0.5, 4.6], [11.5, 0.6, 0.6], spar),
        A('right_wing_tip', [-8.5, -0.5, 7.6], [8.5, 0.6, 0.6], spar),
        A('left_wing_base', [5.4, -0.4, 0.4], [1.2, 1.2, 1.2], knob),
        A('right_wing_base', [-6.6, -0.4, 0.4], [1.2, 1.2, 1.2], knob),
    ]
    for k in range(4):
        adds.append(A('body', [-1.0, -2.7, -7.0 + 2.2 * k], [1.0, 0.8, 1.0], knob))
    for k in range(3):
        adds.append(A('body', [-3.3, 0.8, -6.6 + 2.1 * k], [5.6, 0.5, 0.7], rib))
    return dict(
        method='E', layer='minecraft:phantom#main', texture='phantom/phantom.png', eyes='phantom/phantom_eyes.png',
        eyes_fn=phantom_eyes, tone='pale', base=phantom_base, view=(-30, 50),
        concept='누더기 팬텀: 구멍 나고 해진 날개막 사이로 날개뼈, 등에 드러난 척추, 곰팡이',
        pose={'right_wing_tip': (0, 0, -8), 'left_wing_tip': (0, 0, 4)}, adds=adds)


# ---- 가디언: 따개비 가디언 ----

def guardian_base(img, data):
    head = bfaces(data, 'head', 0)
    side_r = bfaces(data, 'head', 1)
    side_l = bfaces(data, 'head', 2)
    top = bfaces(data, 'head', 3)
    eye = bfaces(data, 'eye')
    rr = random.Random(170)
    for rect in (head['left'], head['right'], side_r['right'], side_l['left'], top['up'], head['back']):
        stain(img, rect, rr.randrange(10000), KELP, coverage=0.3, cell=2.0)
        for _ in range(max(1, rect[2] * rect[3] // 30)):
            ring(img, rect[0] + 1 + rr.randrange(max(1, rect[2] - 2)), rect[1] + 1 + rr.randrange(max(1, rect[3] - 2)), BARNACLE, BARNACLE[0], size=3 if rr.random() < 0.5 else 2)
    PRIS = ramp('#14302C', '#1C3A36', '#24514B', '#3F7A70', '#7FB8A9')
    stroke(img, head['front'], [(0.1, 0.2), (0.35, 0.4), (0.3, 0.75)], PRIS, width=2, shade=PRIS[4])
    stroke(img, head['up'], [(0.6, 0.0), (0.5, 0.5), (0.8, 0.9)], PRIS, width=2, shade=PRIS[4])
    to_ramp(img, eye['front'], CATARACT)
    # 가시: 죽은 회갈색
    spike = bfaces(data, 'spike0')
    for rect in spike.values():
        to_ramp(img, rect, ramp('#3A322C', '#5A4E44', '#7C6E60', '#9E907E'))


def guardian():
    barn = mat(BARNACLE, extra=lambda img, faces, seed: ring(img, faces['up'][0] + faces['up'][2] // 2, faces['up'][1] + faces['up'][3] // 2, BARNACLE, BARNACLE[0], size=3 if faces['up'][2] >= 3 else 2))
    coral = mat(CORAL, var=0.18, extra=x_spots([CORAL[1], CORAL[2], CORAL[2], CORAL[3]], n=2, rmin=0.4, rmax=0.6))
    kelp = mat(KELP, extra=x_strands(KELP, density=0.75))
    adds = [
        A('head', [-4.2, 6.9, -4.0], [2, 1.2, 2], barn, tell='따개비·죽은 산호: 맞으면 탁한 피 구름'),
        A('head', [-1.6, 7.2, -5.2], [1.5, 0.8, 1.5], barn),
        A('head', [2.8, 6.9, 0.8], [2.4, 1.2, 2.4], barn),
        A('head', [-9.0, 13.0, -3.0], [1.1, 2, 2], barn),
        A('head', [-8.9, 17.0, 1.0], [0.9, 1.5, 1.5], barn),
        A('head', [8.0, 11.0, -2.0], [1.4, 3.4, 1.4], coral),
        A('head', [9.0, 9.4, -2.6], [1.0, 2.0, 1.0], coral, rot=('z', -22.5, [9.5, 11.0, -2.1])),
        A('head', [7.6, 9.8, -1.0], [1.0, 1.6, 1.0], coral, rot=('x', 22.5, [8.1, 11.0, -0.5])),
        A('head', [-0.7, 4.6, 7.0], [1.4, 3.6, 1.4], coral),
        A('head', [0.4, 3.2, 6.6], [1.0, 2.0, 1.0], coral, rot=('z', -22.5, [0.9, 4.6, 7.1])),
        A('head', [-2.0, 15.0, 8.0], [0, 4, 6], kelp),
        A('head', [2.6, 13.0, 8.0], [0, 3, 5], kelp),
    ]
    return dict(
        method='E', layer='minecraft:guardian#main', texture='guardian/guardian.png', tone='sea', base=guardian_base,
        concept='따개비 가디언: 따개비가 덮고 가시 몇 개는 하얗게 죽은 산호가 됐다. 다시마가 끌리고 눈은 백내장',
        remove=[('spike2', 0), ('spike8', 0), ('spike5', 0)], adds=adds)


# ---- 브리즈: 녹슨 바람 ----

def breeze_base(img, data):
    head = bfaces(data, 'head', 1)
    rod = bfaces(data, 'rod_1')
    for k, rect in enumerate([head['up'], head['left'], head['right'], head['back']]):
        stain(img, rect, 180 + k, VERDIGRIS, coverage=0.3, cell=1.6)
        speckle(img, rect, 185 + k, 0.06, [TUFF[3], TUFF[4]])
    rr = random.Random(190)
    for rect in (head['left'], head['right'], head['back']):
        for _ in range(2):
            ring(img, rect[0] + 1 + rr.randrange(max(1, rect[2] - 2)), rect[1] + 2 + rr.randrange(max(1, rect[3] - 4)), COPPER, ramp('#0E0C0B')[0], size=3)
    for face, rect in rod.items():
        to_ramp(img, rect, COPPER, lo=0.1, hi=0.9)
        if face in ('front', 'back', 'left', 'right'):
            for y in (1, 4, 6):
                if y < rect[3]:
                    for x in range(rect[2]):
                        put(img, rect[0] + x, rect[1] + y, VERDIGRIS[3] if x % 2 else VERDIGRIS[2])
            stain(img, rect, 195, VERDIGRIS, coverage=0.35, cell=1.4)


def breeze():
    plate = mat(COPPER, extra=chain(x_patina(VERDIGRIS, 0.4), x_rivets(COPPER)))
    pipe = mat(COPPER, var=0.1, extra=x_patina(VERDIGRIS, 0.5))
    bell = mat(COPPER, extra=chain(x_patina(VERDIGRIS, 0.45), x_hole_top(ramp('#0D0B0A')[0], COPPER[4])))
    return dict(
        method='E', layer='minecraft:breeze#main', texture='breeze/breeze.png', eyes='breeze/breeze_eyes.png', tone='tint',
        base=breeze_base, concept='녹슨 바람: 녹청 슨 구리판과 리벳, 바람이 새는 피리 구멍, 머리 뒤 바람 나팔',
        adds=[
            A('head', [-4.6, -7.6, -2.0], [0.8, 3.2, 4], plate, tell='바람 나팔·피리 구멍: 돌풍이 터진 자리로 좀비를 부른다'),
            A('head', [3.8, -6.2, -1.2], [0.8, 3, 3], plate),
            A('head', [-2.2, -8.8, -1.2], [4.4, 0.8, 3], plate),
            A('head', [1.2, -11.0, 1.6], [1.2, 3, 1.2], pipe),
            A('head', [0.6, -12.2, 1.0], [2.4, 1.2, 2.4], bell),
        ])


# ---- 가스트: 혼불 가스트 ----

def ghast_base(img, data):
    body = bfaces(data, 'body')
    k2 = SCALE[0]
    for k, face in enumerate(('up', 'left', 'right', 'back')):
        rect = body[face]
        peel(img, rect, 200 + k, 4, [GHAST[-1], GHAST[-2]], GHAST[1], size=(3 * k2, 5 * k2))
    f = body['front']
    # 얼굴: 어두운 눈·입 선 → 영혼 불빛(가운데 밝게), 눈 아래 그을린 눈물
    def soul(c, x, y):
        L = lum(c)
        if L < 0.6:
            return pick(SOUL, min(1.0, 0.55 + (0.6 - L) * 1.5), f[0] + x, f[1] + y)
        return None
    recolor(img, f, soul)
    rr = random.Random(210)
    k = SCALE[0]
    for ex in (0.3, 0.62):  # 두 눈 아래로 그을린 눈물 자국
        x = f[0] + int(f[2] * ex)
        y0 = f[1] + int(f[3] * 0.45)
        for i in range(rr.randint(4, 7) * k // 2 + 2):
            put(img, x, y0 + i, SOOT[2] if i % 3 else SOOT[3])
            if k > 1:
                put(img, x + 1, y0 + i, SOOT[3])


def ghast():
    flap = mat(GHAST, base={'up': 0.95, 'down': 0.25, 'front': 0.7, 'back': 0.7, 'left': 0.7, 'right': 0.7}, var=0.06)
    tip = mat(SOOT, extra=lambda img, faces, seed: speckle(img, faces['down'], seed, 0.4, [SOUL[3], SOUL[4]]))
    adds = [
        A('body', [-8.7, -5.0, -4.0], [0.7, 4, 5], flap, tell='영혼 불빛 눈·입: 울부짖으면 멀리 좀비가 모인다'),
        A('body', [-3.0, -8.7, 2.0], [5, 0.7, 4], flap),
        A('body', [3.0, -2.0, -8.7], [4, 3, 0.7], flap),
        A('body', [8.0, 1.0, 1.0], [0.7, 3, 4], flap),
    ]
    lens = {'tentacle0': 8, 'tentacle1': 6, 'tentacle2': 9, 'tentacle3': 11, 'tentacle4': 11, 'tentacle5': 10, 'tentacle6': 5, 'tentacle7': 9, 'tentacle8': 12}
    for t, L in lens.items():
        adds.append(A(t, [-1.2, L - 1.6, -1.2], [2.4, 1.6, 2.4], tip))
    return dict(
        method='E', layer='minecraft:ghast#main', texture='ghast/ghast.png', tone='pale', base=ghast_base,
        concept='혼불 가스트: 살갗이 벗겨지고 눈·입에서 영혼 불빛, 그을린 눈물 자국, 끝이 시든 촉수',
        resize=[('tentacle1', 0, 'y', 6.0, 'max'), ('tentacle6', 0, 'y', 5.0, 'max')], adds=adds)


# ---- 블레이즈: 잿불 블레이즈 ----

def blaze_base(img, data):
    head = bfaces(data, 'head')
    rod = bfaces(data, 'part0')
    for k, rect in enumerate([head['up'], part(head['front'], 0, 0, 1, 0.35), part(head['left'], 0, 0, 1, 0.4), part(head['right'], 0, 0, 1, 0.4), part(head['back'], 0, 0, 1, 0.4)]):
        stain(img, rect, 220 + k, ASH, coverage=0.6, cell=1.5, edge=ASH[1])
    kw = dict(width=1, glow=True, core=4, halo=2, halo_p=0.5)
    stroke(img, head['front'], [(0.0, 0.25), (0.35, 0.35), (0.5, 0.15), (1.0, 0.3)], EMBER, seed=21, **kw)
    stroke(img, head['left'], [(0.2, 0.0), (0.5, 0.4), (0.3, 0.7)], EMBER, seed=22, **kw)
    stroke(img, head['right'], [(0.8, 0.0), (0.5, 0.35), (0.7, 0.6)], EMBER, seed=23, **kw)
    for face, rect in rod.items():
        if face in ('front', 'back', 'left', 'right'):
            for y in range(rect[3]):
                for x in range(rect[2]):
                    if y >= rect[3] // 2:
                        put(img, rect[0] + x, rect[1] + y, pick(CHARCOAL, 0.6 - 0.4 * (y - rect[3] // 2) / max(1, rect[3] // 2), rect[0] + x, rect[1] + y))
            for x in range(rect[2]):
                put(img, rect[0] + x, rect[1] + rect[3] // 2, EMBER[4] if x % 2 else EMBER[3])
        elif face == 'down':
            for y in range(rect[3]):
                for x in range(rect[2]):
                    put(img, rect[0] + x, rect[1] + y, EMBER[2])


def blaze():
    crust = mat(CHARCOAL, extra=x_paths(EMBER, [[(0.0, 0.3), (0.4, 0.5), (0.6, 0.2), (1.0, 0.6)]], width=1, glow=True, faces_list=('up',)))
    vent = mat(CHARCOAL, extra=x_hole_top(SOOT[0], EMBER[3]))
    burnt = mat(CHARCOAL, extra=x_tip(ASH, rows=2))
    return dict(
        method='E', layer='minecraft:blaze#main', texture='blaze/blaze.png', tone='warm', base=blaze_base,
        concept='잿불 블레이즈: 머리를 덮은 숯 껍데기 틈으로 잿불, 굴뚝 같은 연기 구멍, 반쯤 타 버린 막대',
        remove=[('part2', 0), ('part9', 0), ('part11', 0)],
        retex=[('part4', 0, burnt), ('part10', 0, burnt)],
        adds=[
            A('head', [-4.3, -4.9, -4.3], [5, 1.2, 4.6], crust, tell='숯 껍데기: 죽을 때 재 구름'),
            A('head', [0.4, -4.6, -0.6], [3.9, 0.9, 4.9], crust),
            A('head', [-2.6, -6.4, 1.0], [1.6, 1.6, 1.6], vent),
            A('head', [1.4, -5.9, -2.6], [1.2, 1.2, 1.2], vent),
        ])


# ---- B: 사람형 장비 덧씌움 + 머리 아이템 ----

OV_HEAD = box_faces(0, 0, 8, 8, 8)
OV_BODY = box_faces(16, 16, 8, 12, 4)


def ov_skeleton(img):
    for k, rect in enumerate([OV_HEAD['up'], part(OV_BODY['up'], 0, 0, 1, 1), part(OV_BODY['front'], 0, 0, 1, 0.3), part(OV_BODY['back'], 0, 0, 1, 0.3)]):
        stain(img, rect, 300 + k, MOSS, coverage=0.5, cell=1.6, edge=MOSS[1], on_empty=True)
    rr = random.Random(310)
    for rect in (OV_HEAD['front'], OV_HEAD['left'], OV_HEAD['right'], OV_BODY['front'], OV_BODY['back']):
        for _ in range(2):
            disc(img, rect[0] + rr.uniform(1, rect[2] - 1), rect[1] + rr.uniform(1, rect[3] - 1), rr.choice([0.8, 1.1]), LICHEN, spec=False)
    for rect in (OV_BODY['front'], OV_BODY['back']):
        threads(img, rect, 320, 3, MYC, length=6)
        stain(img, part(rect, 0, 0.6, 1, 1), 330, DIRT, coverage=0.55, cell=1.5, on_empty=True)


def ov_stray(img):
    # 성에는 윗면 일부와 어깨에만, 고드름은 턱·갈비 아래에 몇 개
    stain(img, OV_HEAD['up'], 340, FROST, coverage=0.45, cell=1.4, on_empty=True)
    stain(img, OV_BODY['up'], 341, FROST, coverage=0.5, cell=1.2, on_empty=True)
    rr = random.Random(345)
    for rect in (OV_HEAD['front'], OV_HEAD['left'], OV_HEAD['right'], OV_BODY['front'], OV_BODY['back']):
        for x in range(rect[2]):
            if rr.random() < 0.35:
                put(img, rect[0] + x, rect[1], FROST[-1])
                if rr.random() < 0.5:
                    put(img, rect[0] + x, rect[1] + 1, FROST[3])
    for rect, xs in ((OV_HEAD['front'], (1, 3, 6)), (OV_BODY['front'], (2, 5))):
        for x in xs:
            L = rr.randint(2, 3)
            for i in range(L):
                put(img, rect[0] + x, rect[1] + rect[3] - L - 1 + i if rect is OV_HEAD['front'] else rect[1] + 5 + i, ICE[4 - min(3, i)])


def ov_bogged(img):
    for k, rect in enumerate([OV_HEAD['up'], OV_BODY['up']]):
        stain(img, rect, 370 + k, SWAMP, coverage=0.6, cell=1.5, edge=SWAMP[1], on_empty=True)
    for rect in (OV_HEAD['front'], OV_HEAD['back'], OV_BODY['front'], OV_BODY['back']):
        drips(img, part(rect, 0.0, 0.0, 1.0, 0.7), 380, 3, SWAMP)
    rr = random.Random(390)
    for rect in (OV_HEAD['left'], OV_HEAD['right'], OV_BODY['front']):
        disc(img, rect[0] + rr.uniform(1.5, rect[2] - 1.5), rect[1] + rr.uniform(1.5, rect[3] - 1.5), 1.2, BROWN_CAP, spec=True)


def ov_wither(img):
    for k, rect in enumerate([OV_HEAD['up'], OV_BODY['up'], part(OV_HEAD['back'], 0, 0, 1, 0.5), part(OV_BODY['front'], 0, 0, 0.5, 0.4)]):
        stain(img, rect, 400 + k, CRIMSON, coverage=0.55, cell=1.4, edge=CRIMSON[1], on_empty=True)
    speckle(img, OV_HEAD['up'], 410, 0.12, [ramp('#E8892E')[0]], on_empty=True)
    for rect in (OV_BODY['front'], OV_BODY['back']):
        stain(img, part(rect, 0, 0.5, 1, 1), 420, SOOT, coverage=0.4, cell=1.5, on_empty=True)


def ov_brute(img):
    for k, rect in enumerate([OV_HEAD['up'], OV_BODY['up'], part(OV_BODY['front'], 0.5, 0, 1, 0.4)]):
        stain(img, rect, 430 + k, CRIMSON, coverage=0.55, cell=1.4, edge=CRIMSON[1], on_empty=True)
    for rect in (OV_BODY['front'], OV_BODY['back']):
        for y in (4, 5):
            for x in range(rect[2]):
                put(img, rect[0] + x, rect[1] + y, pick(GOLD, 0.65 if y == 4 else 0.35, rect[0] + x, rect[1] + y))
        stain(img, (rect[0], rect[1] + 4, rect[2], 2), 440, TARNISH, coverage=0.4, cell=1.2)


def humanoid_overlay(fn, size=(64, 32)):
    from PIL import Image
    img = Image.new('RGBA', size, (0, 0, 0, 0))
    fn(img)
    return img


def skeleton():
    stem = mat(STEM, var=0.08)
    cap = mat(BROWN_CAP, extra=x_cap(BROWN_CAP, spots=None, gill_rp=BRACKET))
    moss = mat(MOSS, extra=x_spots([MOSS[2], MOSS[3], MOSS[4], MOSS[5]], n=2, rmin=0.4, rmax=0.7))
    root = mat(ROOT, var=0.14)
    return dict(
        method='B', layer='minecraft:skeleton#main', texture='skeleton/skeleton.png', overlay_fn=ov_skeleton,
        overlay=['minecraft:skeleton#helmet', 'minecraft:skeleton#chestplate'],
        concept='묘지 해골: 흙·이끼·노란 지의류, 뼈를 다시 잇는 흰 균사, 머리에 버섯과 뿌리',
        adds=[
            A('head', [1.6, -9.6, 0.2], [1, 1.6, 1], stem),
            A('head', [0.6, -10.8, -0.8], [3, 1.2, 3], cap, tell='균사로 이은 뼈: 쓰러져도 10초 뒤 다시 일어선다'),
            A('head', [-3.6, -8.8, -3.2], [3.2, 0.8, 3.2], moss),
            A('head', [-4.7, -7.0, -1.0], [0.7, 4.2, 0.7], root),
            A('head', [-4.7, -3.4, -1.8], [0.7, 0.7, 1.4], root),
        ])


def stray():
    crystal = mat(with_alpha(ICE, 215), var=0.08, grad=0.3, extra=lambda img, faces, seed: [put(img, faces[f][0], faces[f][1], ICE[-1]) for f in ('front', 'left') if faces.get(f) and faces[f][2]])
    icicle = mat(with_alpha(ICE, 220), var=0.05, grad=0.35)
    return dict(
        method='B', layer='minecraft:stray#main', texture='skeleton/stray.png', overlay_fn=ov_stray,
        overlay=['minecraft:stray#helmet', 'minecraft:stray#chestplate'],
        concept='동상 해골: 성에와 고드름, 머리에 솟은 얼음 결정',
        adds=[
            A('head', [-3.2, -11.4, -1.2], [1.4, 3.6, 1.4], crystal, rot=('z', 22.5, [-2.5, -8.0, -0.5]), tell='얼음 결정'),
            A('head', [-1.4, -10.6, 1.0], [1.0, 2.8, 1.0], crystal, rot=('x', -22.5, [-0.9, -8.0, 1.5])),
            A('head', [1.8, -11.0, -2.2], [1.4, 3.2, 1.4], crystal, rot=('z', -22.5, [2.5, -8.0, -1.5])),
            A('head', [0.2, -9.6, -3.6], [0.8, 1.8, 0.8], crystal),
            A('head', [-2.2, 0.0, -4.4], [0.8, 1.6, 0.8], icicle),
            A('head', [1.2, 0.0, -4.4], [0.6, 1.2, 0.6], icicle),
        ])


def bogged():
    stem = mat(STEM, var=0.08)
    red = mat(RED_CAP, extra=x_cap(RED_CAP, spots=ramp('#F2E8DC')[0], gill_rp=BRACKET))
    brown = mat(BROWN_CAP, extra=x_cap(BROWN_CAP, spots=None, gill_rp=BRACKET))
    slime_drip = mat(with_alpha(SWAMP, 225), var=0.05, grad=0.3)
    return dict(
        method='B', layer='minecraft:bogged#main', texture='skeleton/bogged.png', overlay_fn=ov_bogged,
        overlay=['minecraft:bogged#helmet', 'minecraft:bogged#chestplate'],
        concept='포자 해골: 늪 점액이 흐르고 빨강·갈색 버섯이 무더기로 핀다',
        adds=[
            A('head', [-2.4, -9.6, -1.4], [1, 1.6, 1], stem),
            A('head', [-3.6, -11.0, -2.6], [3.4, 1.4, 3.4], red, tell='버섯 무더기'),
            A('head', [1.6, -9.0, 1.2], [1, 1, 1], stem),
            A('head', [0.6, -10.0, 0.2], [2.8, 1, 2.8], brown),
            A('head', [2.4, -9.2, -3.4], [1.6, 0.8, 1.6], red),
            A('head', [-1.0, -8.4, -4.5], [1, 2.2, 0.6], slime_drip),
        ])


def wither_skeleton():
    stem = mat(CRIMSON, var=0.08, base={'up': 0.7, 'down': 0.3, 'front': 0.55, 'back': 0.5, 'left': 0.5, 'right': 0.52})
    cap = mat(CRIMSON, extra=x_cap(CRIMSON, spots=ramp('#E8892E')[0], gill_rp=WART))
    wart = mat(WART, extra=x_spots([WART[1], WART[2], WART[3], WART[4]], n=2, rmin=0.4, rmax=0.6))
    return dict(
        method='B', layer='minecraft:wither_skeleton#main', texture='skeleton/wither_skeleton.png', overlay_fn=ov_wither,
        overlay=['minecraft:wither_skeleton#helmet', 'minecraft:wither_skeleton#chestplate'],
        concept='진홍 균류 해골: 네더 요새의 진홍 균류와 네더 사마귀가 뼈를 뒤덮는다',
        adds=[
            A('head', [-2.4, -9.6, -0.4], [1, 1.6, 1], stem),
            A('head', [-3.8, -11.0, -1.8], [3.6, 1.4, 3.6], cap, tell='진홍 균류'),
            A('head', [1.4, -9.4, -2.4], [1.6, 1.4, 1.6], wart),
            A('head', [2.2, -9.0, 1.4], [1.3, 1.0, 1.3], wart),
        ])


def piglin_brute():
    stem = mat(CRIMSON, var=0.08)
    cap = mat(CRIMSON, extra=x_cap(CRIMSON, spots=ramp('#E8892E')[0], gill_rp=WART))
    wart = mat(WART, extra=x_spots([WART[1], WART[2], WART[3], WART[4]], n=2, rmin=0.4, rmax=0.6))
    return dict(
        method='B', layer='minecraft:piglin_brute#main', texture='piglin/piglin_brute.png', overlay_fn=ov_brute,
        overlay=['minecraft:piglin_brute#helmet', 'minecraft:piglin_brute#chestplate'],
        concept='진홍 브루트: 진홍 균류가 머리와 어깨에 번지고, 금 띠는 녹이 슬었다',
        adds=[
            A('head', [2.6, -9.6, 0.6], [1, 1.6, 1], stem),
            A('head', [1.4, -11.0, -0.6], [3.4, 1.4, 3.4], cap, tell='진홍 균류: 체력 절반 아래에서 광폭'),
            A('head', [-4.6, -9.2, -1.6], [1.6, 1.2, 1.6], wart),
            A('head', [-3.0, -8.8, 1.6], [1.2, 0.8, 1.2], wart),
        ])


# ---- C: 머리 아이템 ----

def pillager():
    band = mat(CLOTH, extra=lambda img, faces, seed: [stain(img, faces[f], seed, ramp('#1A1814', '#26231C', '#302C23'), coverage=0.35, cell=1.4) for f in faces if faces[f][2] and faces[f][3]])
    shaft = mat(WOOD, var=0.08)
    fletch = mat(FLETCH, extra=x_strands(FLETCH, density=0.8))
    tip = mat(IRON, var=0.06)
    return dict(
        method='C', layer='minecraft:pillager#main', texture='illager/pillager.png',
        concept='약탈자: 해진 두건 띠, 머리를 꿰뚫은 화살(표식 화살)',
        adds=[
            A('head', [-4.9, -8.6, -4.9], [9.8, 2.0, 9.8], band, tell='꿰뚫린 화살: 표식 화살을 쏜다'),
            A('head', [-3.0, -6.4, -6.6], [6.0, 1.2, 1.4], band),
            A('head', [4.0, -6.8, -0.6], [5.0, 1.2, 1.2], shaft),
            A('head', [7.0, -8.8, 0.0], [3.0, 5.0, 0], fletch),
            A('head', [-8.4, -6.8, -0.6], [4.4, 1.2, 1.2], shaft),
            A('head', [-10.2, -7.4, -1.2], [1.8, 2.4, 2.4], tip),
        ])


def vindicator():
    blade = mat(IRON, var=0.08, extra=lambda img, faces, seed: [put(img, faces['front'][0] + x, faces['front'][1], IRON[-1]) for x in range(faces['front'][2])])
    handle = mat(WOOD, var=0.08)
    moss = mat(MOSS, extra=x_spots([MOSS[2], MOSS[3], MOSS[4], MOSS[5]], n=2, rmin=0.4, rmax=0.7))
    return dict(
        method='C', layer='minecraft:vindicator#main', texture='illager/vindicator.png',
        concept='변명자: 머리에 박힌 도끼, 저택 숲의 이끼',
        adds=[
            A('head', [-5.4, -13.2, -3.4], [1.2, 5.0, 6.8], blade, rot=('z', 22.5, [-4.8, -10.4, 0.0]), tell='박힌 도끼: 문을 빨리 부순다'),
            A('head', [-5.6, -20.0, -0.7], [1.4, 9.0, 1.4], handle, rot=('z', 22.5, [-4.9, -10.4, 0.0])),
            A('head', [0.4, -10.9, -2.0], [4.2, 1.0, 4.2], moss),
        ])


def evoker():
    fang = mat(FANG, grad=0.2, extra=x_tip([FANG[2], FANG[3], FANG[4]], rows=1))
    band = mat(CLOTH, extra=x_rune)
    adds = [A('head', [-4.4, -8.6, -4.4], [8.8, 1.2, 8.8], band, tell='송곳니 왕관·룬 띠: 벡스 대신 아기 좀비를 부른다')]
    for (x, z, hgt) in [(-4.4, -4.4, 3.6), (2.6, -4.4, 3.6), (-4.4, 2.6, 3.0), (2.6, 2.6, 3.0), (-0.9, -4.6, 4.4), (-0.9, 2.8, 3.4)]:
        adds.append(A('head', [x, -10.0 - hgt, z], [1.8, hgt, 1.8], fang))
    return dict(method='C', layer='minecraft:evoker#main', texture='illager/evoker.png',
                concept='소환사: 송곳니로 된 왕관, 빛나는 에메랄드 룬 띠', adds=adds)


def illusioner():
    return dict(
        method='C', layer='minecraft:illusioner#main', texture='illager/illusioner.png',
        concept='환술사: 머리에 박힌 거울 조각(분신도 좀비 모습)',
        adds=[
            A('head', [-6.4, -10.4, -2.8], [1.6, 4.8, 3.6], x_mirror, rot=('y', 22.5, [-5.6, -8.0, -1.0]), tell='거울 조각: 분신도 좀비 모습'),
            A('head', [2.2, -14.8, -1.8], [3.2, 4.6, 1.4], x_mirror, rot=('z', -22.5, [3.8, -10.4, -1.1])),
            A('head', [-1.6, -14.0, 1.4], [2.6, 3.6, 1.4], x_mirror, rot=('x', 22.5, [-0.3, -10.4, 2.1])),
            A('head', [4.6, -6.4, 1.6], [1.6, 2.8, 1.6], x_mirror),
        ])


def witch():
    stem = mat(STEM, var=0.08)
    red = mat(RED_CAP, extra=x_cap(RED_CAP, spots=ramp('#F2E8DC')[0], gill_rp=BRACKET))
    brown = mat(BROWN_CAP, extra=x_cap(BROWN_CAP, spots=None, gill_rp=BRACKET))
    return dict(
        method='C', layer='minecraft:witch#main', texture='witch/witch.png',
        concept='역병 마녀: 모자 챙에 버섯이 피고, 띠에 꽂은 물약병(좀비를 치유하는 피해 물약)',
        adds=[
            A('head', [-5.4, -12.4, -4.4], [1.2, 2.2, 1.2], stem),
            A('head', [-7.2, -14.2, -6.2], [4.6, 1.8, 4.6], red, tell='버섯·물약병: 다친 좀비에게 물약을 던진다'),
            A('head', [3.2, -12.0, 3.0], [1.2, 1.8, 1.2], stem),
            A('head', [1.8, -13.6, 1.6], [4.0, 1.6, 4.0], brown),
            A('head', [-1.2, -11.4, 4.6], [1, 1, 1], stem),
            A('head', [-2.0, -12.2, 3.8], [2.6, 1, 2.6], red),
            A('head', [4.4, -14.6, -4.8], [2.0, 3.6, 2.0], x_glass_vial(POTION_PURPLE)),
            A('head', [-6.4, -14.2, 2.4], [1.8, 3.4, 1.8], x_glass_vial(POTION_GREEN)),
        ])


# ---- D: 셜커 ----

def shulker_base(img, data):
    lid, base, head = bfaces(data, 'lid'), bfaces(data, 'base'), bfaces(data, 'head')
    for rect in list(lid.values()) + list(base.values()):
        to_ramp(img, rect, PURPUR, lo=0.05, hi=0.75)
    for k, rect in enumerate([lid['up'], lid['front'], lid['left'], lid['right'], base['front'], base['back']]):
        stain(img, rect, 500 + k, ENDSTONE, coverage=0.3, cell=1.8, edge=ENDSTONE[0])
        rr = random.Random(510 + k)
        for _ in range(max(1, rect[2] * rect[3] // 40)):
            disc(img, rect[0] + rr.uniform(1, rect[2] - 1), rect[1] + rr.uniform(1, rect[3] - 1), rr.choice([0.8, 1.2]), CHORUS, spec=True)
    for rect in head.values():
        to_ramp(img, rect, ramp('#3F3A44', '#5E5866', '#827C8B', '#A8A2AF', '#CBC6D0'))


def shulker():
    return dict(method='D', layer='minecraft:shulker#main', texture='shulker/shulker_green.png', tone=None, base=shulker_base,
                concept='부패 셜커: 퍼퍼 껍데기가 썩어 엔드석 지의류와 후렴 혹이 덮는다(초록 셜커 텍스처 자리)', adds=[])


DESIGNS = {
    'creeper': creeper, 'spider': spider, 'cave_spider': cave_spider, 'enderman': enderman, 'creaking': creaking,
    'slime': slime, 'magma_cube': magma_cube, 'phantom': phantom, 'guardian': guardian, 'breeze': breeze,
    'ghast': ghast, 'blaze': blaze, 'skeleton': skeleton, 'stray': stray, 'bogged': bogged,
    'wither_skeleton': wither_skeleton, 'piglin_brute': piglin_brute, 'pillager': pillager, 'vindicator': vindicator,
    'evoker': evoker, 'illusioner': illusioner, 'witch': witch, 'shulker': shulker,
}
