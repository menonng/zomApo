"""zomApo 리소스팩의 손그림 자산(HUD 아이콘 글꼴, 해독제·바이러스 결정 아이템, 투명 보스바)을 만든다.

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
    """둥근 플라스크: 코르크, 유리 목, 초록 약물, 그 안에 뜬 결정 조각, 거품."""
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    px = im.load()
    glass_dark = hexc('#3A4A52')
    glass = hexc('#9FB6C0', 230)
    glass_hi = hexc('#E8F6FA')
    cork = hexc('#8A5A32')
    cork_d = hexc('#5C3A1E')
    cork_h = hexc('#B07A48')
    liquid = [hexc('#1F5E1A'), hexc('#2E8A26'), hexc('#55BB44'), hexc('#7FD36E')]
    cx, cy, r = 7.5, 10.0, 5.2
    # 몸통(원) 외곽선과 내부
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - cx, y - cy)
            if d <= r - 0.9:
                # 약물: 위쪽 0.25 는 빈 유리, 아래로 갈수록 어둡게
                level = cy - r * 0.45
                if y >= level:
                    t = (y - level) / (cy + r - level)
                    shade = 3 if t < 0.15 else 2 if t < 0.55 else 1 if t < 0.85 else 0
                    if x < cx - 2.5 and shade < 3:
                        shade += 1
                    px[x, y] = liquid[shade]
                else:
                    px[x, y] = hexc('#C9DDE3', 110)
            elif d <= r + 0.2:
                px[x, y] = glass_dark
    # 목
    for y in range(3, 6):
        px[6, y] = glass_dark
        px[9, y] = glass_dark
        px[7, y] = glass
        px[8, y] = hexc('#C9DDE3', 140)
    # 코르크
    for x in range(6, 10):
        px[x, 1] = cork_h if x < 8 else cork
        px[x, 2] = cork if x < 9 else cork_d
    px[6, 0] = (0, 0, 0, 0)
    for x in (6, 9):
        px[x, 1] = cork_d
    # 결정 조각(하늘색 마름모)
    shard = {(8, 9): '#E6FFFC', (8, 10): '#39C5BB', (9, 10): '#1F8C84', (7, 11): '#39C5BB', (8, 11): '#24A79E', (8, 12): '#1F8C84'}
    for (x, y), c in shard.items():
        px[x, y] = hexc(c)
    # 거품
    px[5, 10] = hexc('#B6EE9A')
    px[10, 12] = hexc('#B6EE9A')
    # 유리 반사
    px[4, 8] = glass_hi
    px[4, 9] = glass_hi
    px[5, 7] = glass_hi
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
    print('written to', os.path.abspath(ROOT))


if __name__ == '__main__':
    main()
