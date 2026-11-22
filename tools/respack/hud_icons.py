"""zomApo 리소스팩의 손그림 자산(HUD 아이콘 글꼴, 체력 하트, 감염 화면 가장자리, 해독제·바이러스 결정 아이템, 투명 보스바)을 만든다.

바닐라 텍스처를 복사하지 않고 픽셀을 직접 찍는다. 결과는 저장소의 respack/ 아래에 쓴다.
    python3 tools/respack/hud_icons.py
"""
import json
import math
import os
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), '..', '..', 'respack')


def hexc(s, a=255):
    s = s.lstrip('#')
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


def grid_image(rows, palette):
    h = len(rows)
    w = len(rows[0])
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        assert len(row) == w, (y, row)
        for x, ch in enumerate(row):
            if ch != '.':
                im.putpixel((x, y), palette[ch])
    return im


def save(im, rel):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)
    return path


def write_json(obj, rel):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write('\n')


# ---------------------------------------------------------------- HUD 아이콘 (9×9)

BLEEDING = [
    "....o....",
    "...oro...",
    "...orro..",
    "..orrrro.",
    ".orhrrrro",
    ".orhrrrso",
    ".orrrrsso",
    "..orsss o".replace(' ', 'o'),
    "...oooo..",
]
BLEED_PAL = {
    'o': hexc('#4A0010'), 'r': hexc('#D0153A'), 'h': hexc('#FF7A8C'), 's': hexc('#8E0A24'),
}

INFECTION = [
    "....t....",
    ".t.ooo.t.",
    "..oggdo..",
    ".oghggdo.",
    "togggdgot",
    ".ogdgggo.",
    "..odggo..",
    ".t.ooo.t.",
    "....t....",
]
INF_PAL = {
    't': hexc('#2E7A26'), 'o': hexc('#163A12'), 'g': hexc('#55BB44'), 'h': hexc('#B6EE9A'), 'd': hexc('#2F8A27'),
}


# ---------------------------------------------------------------- 아이템 (16×16)

def antidote():
    """해독제(황금 사과로 만든 약): 바닐라 병과 같은 하늘빛 유리 4단계 + 황금 사과 색 띠의 약물.
    바닐라 물약과 한눈에 구별되게 목이 긴 약병, 금실 감은 코르크, (꼬리표는 뺐다)."""
    rows = [
        "......kkkC......",
        "......CCCc......",
        ".....gyGGGg.....",
        "......LH.D......",
        "......LH.D......",
        ".....L....D.....",
        "....L......D....",
        "...L........D...",
        "...L........D...",
        "...L........D...",
        "...L........D...",
        "...L........D...",
        "...L........D...",
        "....D......D....",
        ".....DDDDDD.....",
        "................",
    ]
    pal = {
        'L': hexc('#B3CFEC'), 'H': hexc('#D4E5F7'), 'M': hexc('#8BADD0'), 'D': hexc('#5D8FC2'),
        'k': hexc('#D46D49'), 'C': hexc('#A94725'), 'c': hexc('#973716'),
        'g': hexc('#B26411'), 'G': hexc('#DBA213'), 'y': hexc('#ECCB45'),
    }
    im = grid_image(rows, pal)
    px = im.load()
    gold = [hexc('#752802'), hexc('#B26411'), hexc('#DBA213'), hexc('#ECCB45'), hexc('#EAEE57'), hexc('#F6F892')]
    # 약물: 7~13행 안쪽. 왼쪽 위가 밝고 오른쪽 아래가 어둡다(바닐라 물약과 같은 빛 방향)
    inner = {7: (4, 11), 8: (4, 11), 9: (4, 11), 10: (4, 11), 11: (4, 11), 12: (4, 11), 13: (5, 10)}
    for y, (x0, x1) in inner.items():
        for x in range(x0, x1 + 1):
            u = (x - x0) / max(1, x1 - x0)
            v = (y - 7) / 6
            t = 0.92 - 0.5 * u - 0.34 * v
            if y == 7:
                t += 0.18           # 맨 위 액면은 밝게
            if x == x1 or y == 13:
                t -= 0.12           # 오른쪽·바닥 가장자리 그늘
            i = max(1, min(5, int(round(t * 5))))
            px[x, y] = gold[i]
    # 빛나는 알갱이(황금 사과의 반짝임)와 유리 반사
    for (x, y, c) in ((6, 9, '#FEFFE6'), (9, 11, '#F6F892'), (7, 12, '#ECCB45'), (5, 8, '#D4E5F7'), (4, 9, '#D4E5F7'), (4, 10, '#B3CFEC')):
        px[x, y] = hexc(c)
    # 액면 위 빈 유리 안쪽에 아주 옅은 반사 한 줄
    px[5, 6] = hexc('#D4E5F7', 150)
    return im


def virus_crystal():
    """결정 무리: 하늘색 기둥 셋, 밑동을 감싼 초록 균사, 빛나는 속."""
    rows = [
        "................",
        "........l.......",
        ".......lc.......",
        ".......lcd......",
        "...l...lcd......",
        "..lc...lcd..l...",
        "..lcd..lccd.lc..",
        "..lcd..lhcd.lcd.",
        "..lccd.lhcd.lcd.",
        "...lcd.lccd.lcd.",
        "...lcdllccdlcd..",
        "....gcdlhcdgd...",
        "...gvgggcdggvg..",
        "..gvvggvggvggvg.",
        "...gg.gg..gg.g..",
        "................",
    ]
    pal = {
        'l': hexc('#E6FFFC'), 'c': hexc('#39C5BB'), 'd': hexc('#1F7E77'), 'h': hexc('#BFF7F2'),
        'g': hexc('#2E7A26'), 'v': hexc('#55BB44'),
    }
    return grid_image(rows, pal)


# ---------------------------------------------------------------- 체력 하트(9×9): 출혈·감염
# 바닐라 클라이언트는 독·위더·얼어붙음일 때만 하트 그림을 바꾼다. 그 세 자리를 zomApo 상처가 빌린다:
#   위더 자리 = 출혈(금 간 하트), 얼어붙음 자리 = 감염(썩어 가는 하트), 독 자리 = 둘 다(금 가고 썩은 하트).
# 플레이어가 받는 진짜 독·위더는 zomApo 가 대신 처리해 상태 줄에 글자로 보인다(하트는 상처 전용).

HEART_CRACKED = [
    ".........",
    "..HR.RR..",
    ".HRRKRRr.",
    ".RRKRRRr.",
    ".RRKKRRr.",
    "..RRKRr..",
    "...RKr...",
    "....r....",
    ".........",
]
HEART_CRACKED_PAL = {'R': hexc('#C8102E'), 'r': hexc('#860A1E'), 'H': hexc('#FF6A7E'), 'K': hexc('#2A0508')}

HEART_INFECTED = [
    ".........",
    "..LG.GG..",
    ".LGVGGNg.",
    ".GGGVGGg.",
    ".GNGVVGg.",
    "..GGGVg..",
    "...GVg...",
    "....g....",
    ".........",
]
HEART_INFECTED_PAL = {'G': hexc('#55604A'), 'g': hexc('#363D2F'), 'L': hexc('#8A967A'), 'V': hexc('#55BB44'), 'N': hexc('#16190F')}

HEART_BOTH = [
    ".........",
    "..LG.GG..",
    ".LGVKGNg.",
    ".GGKVGGg.",
    ".GNKKVGg.",
    "..GGKVg..",
    "...GKg...",
    "....g....",
    ".........",
]
HEART_BOTH_PAL = dict(HEART_INFECTED_PAL, K=hexc('#C8102E'))


def heart_set(rows, pal, prefix):
    """full/half 와 깜빡임(맞았을 때 밝게)·하드코어 변형까지 같은 그림으로."""
    full = grid_image(rows, pal)
    half = full.copy()
    for y in range(9):
        for x in range(5, 9):
            half.putpixel((x, y), (0, 0, 0, 0))
    def blink(im):
        b = im.copy()
        for y in range(9):
            for x in range(9):
                r, g, bb, a = b.getpixel((x, y))
                if a:
                    b.putpixel((x, y), (min(255, r + (255 - r) // 2), min(255, g + (255 - g) // 2), min(255, bb + (255 - bb) // 2), a))
        return b
    for hc in ('', 'hardcore_'):
        save(full, f'assets/minecraft/textures/gui/sprites/hud/heart/{prefix}_{hc}full.png')
        save(half, f'assets/minecraft/textures/gui/sprites/hud/heart/{prefix}_{hc}half.png')
        save(blink(full), f'assets/minecraft/textures/gui/sprites/hud/heart/{prefix}_{hc}full_blinking.png')
        save(blink(half), f'assets/minecraft/textures/gui/sprites/hud/heart/{prefix}_{hc}half_blinking.png')


def infection_vignette(size=256):
    """감염 중 화면 가장자리(바닐라 가루눈 서리 자리): 가장자리에서 안쪽으로 뻗는 가는 녹색 핏줄. 가운데는 비어 있다."""
    import random
    im = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    rr = random.Random(7)
    c = size / 2
    for k in range(46):
        ang = rr.uniform(0, 2 * math.pi)
        x, y = c + math.cos(ang) * c * 1.02, c + math.sin(ang) * c * 1.02
        heading = math.atan2(c - y, c - x) + rr.uniform(-0.5, 0.5)
        length = rr.uniform(size * 0.12, size * 0.26)
        steps = int(length)
        for i in range(steps):
            t = i / max(1, steps)
            heading += rr.uniform(-0.25, 0.25)
            x += math.cos(heading); y += math.sin(heading)
            if not (0 <= x < size and 0 <= y < size):
                continue
            a = int(150 * (1 - t))
            col = (0x1E, 0x3A, 0x16, a) if i % 7 else (0x55, 0xBB, 0x44, a)
            im.putpixel((int(x), int(y)), col)
            if t < 0.4 and int(x) + 1 < size:
                im.putpixel((int(x) + 1, int(y)), (0x1E, 0x3A, 0x16, a // 2))
            if rr.random() < 0.04:
                heading += rr.choice((-1, 1)) * 0.9
    # 가장자리 아주 옅은 어둠
    for y in range(size):
        for x in range(size):
            d = max(abs(x - c), abs(y - c)) / c
            if d > 0.86:
                r, g, b, a = im.getpixel((x, y))
                im.putpixel((x, y), (r or 0x10, g or 0x18, b or 0x0C, max(a, int(70 * (d - 0.86) / 0.14))))
    return im


def main():
    write_json({"pack": {"description": "zomApo 서버 리소스팩", "min_format": 97, "max_format": 97}}, 'pack.mcmeta')

    save(grid_image(BLEEDING, BLEED_PAL), 'assets/zomapo/textures/font/hud/bleeding.png')
    save(grid_image(INFECTION, INF_PAL), 'assets/zomapo/textures/font/hud/infection.png')
    write_json({"providers": [
        {"type": "bitmap", "file": "zomapo:font/hud/bleeding.png", "ascent": 8, "height": 9, "chars": [""]},
        {"type": "bitmap", "file": "zomapo:font/hud/infection.png", "ascent": 8, "height": 9, "chars": [""]},
    ]}, 'assets/zomapo/font/hud.json')

    # 흰색 보스바는 바닐라가 쓰지 않는다 → 막대를 투명하게 해 상태 효과 줄(글자 + 아이콘)로 쓴다
    clear = Image.new('RGBA', (182, 5), (0, 0, 0, 0))
    save(clear, 'assets/minecraft/textures/gui/sprites/boss_bar/white_background.png')
    save(clear, 'assets/minecraft/textures/gui/sprites/boss_bar/white_progress.png')

    heart_set(HEART_CRACKED, HEART_CRACKED_PAL, 'withered')
    heart_set(HEART_INFECTED, HEART_INFECTED_PAL, 'frozen')
    heart_set(HEART_BOTH, HEART_BOTH_PAL, 'poisoned')
    save(infection_vignette(), 'assets/minecraft/textures/misc/powder_snow_outline.png')

    save(antidote(), 'assets/zomapo/textures/item/antidote.png')
    save(virus_crystal(), 'assets/zomapo/textures/item/virus_crystal.png')
    write_json({"parent": "minecraft:item/generated", "textures": {"layer0": "zomapo:item/antidote"}}, 'assets/zomapo/models/item/antidote.json')
    write_json({"parent": "minecraft:item/generated", "textures": {"layer0": "zomapo:item/virus_crystal"}}, 'assets/zomapo/models/item/virus_crystal.json')

    # 바닐라 아이템 정의 덮어쓰기: custom_model_data 문자열로 고르고, 아니면 바닐라 그대로
    write_json({"model": {
        "type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
        "cases": [{"when": "zomapo:antidote", "model": {"type": "minecraft:model", "model": "zomapo:item/antidote"}}],
        "fallback": {"type": "minecraft:model", "model": "minecraft:item/potion",
                     "tints": [{"type": "minecraft:potion", "default": -13083194}]},
    }}, 'assets/minecraft/items/potion.json')
    write_json({"model": {
        "type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
        "cases": [{"when": "zomapo:virus_crystal", "model": {"type": "minecraft:model", "model": "zomapo:item/virus_crystal"}}],
        "fallback": {"type": "minecraft:model", "model": "minecraft:item/amethyst_shard"},
    }}, 'assets/minecraft/items/amethyst_shard.json')
    # 이름은 아이템 설명 대신 번역 키로(리소스팩 언어 파일은 바닐라 파일과 키 단위로 합쳐진다)
    write_json({"item.minecraft.potion.effect.zomapo_antidote": "해독제", "item.zomapo.virus_crystal": "바이러스 결정", "effect.zomapo.cure_infection": "감염 해제"}, 'assets/minecraft/lang/ko_kr.json')
    write_json({"item.minecraft.potion.effect.zomapo_antidote": "Antidote", "item.zomapo.virus_crystal": "Virus Crystal", "effect.zomapo.cure_infection": "Cures Infection"}, 'assets/minecraft/lang/en_us.json')
    print('written to', os.path.abspath(ROOT))


if __name__ == '__main__':
    main()
