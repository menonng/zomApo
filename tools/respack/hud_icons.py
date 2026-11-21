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

ANTIDOTE_INNER = {7: (4, 11), 8: (4, 11), 9: (4, 11), 10: (4, 11), 11: (4, 11), 12: (4, 11), 13: (5, 10)}


def antidote():
    """해독제 병(바닐라 물약과 같은 두 겹 구조의 위층): 하늘빛 유리 4단계, 금실 감은 코르크, 목이 긴 약병.
    약물은 아래층(antidote_overlay)이고, 바닐라 물약처럼 물약 색 속성(potion_contents.custom_color)으로 물든다."""
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
    # 물들지 않는 반짝임과 유리 반사(위층이라 약물 색 위에 그대로 보인다)
    for (x, y, c) in ((6, 9, '#FEFFE6'), (9, 11, '#FFFBD0'), (5, 8, '#D4E5F7'), (4, 9, '#D4E5F7'), (4, 10, '#B3CFEC')):
        px[x, y] = hexc(c)
    px[5, 6] = hexc('#D4E5F7', 150)
    return im


def antidote_overlay():
    """약물 아래층: 회색 단계만 칠한다. 게임이 물약 색(황금)을 곱해 물들인다(바닐라 potion_overlay 와 같은 방식)."""
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    px = im.load()
    grays = [0x9C, 0xB6, 0xCE, 0xE3, 0xF3, 0xFF]
    for y, (x0, x1) in ANTIDOTE_INNER.items():
        for x in range(x0, x1 + 1):
            u = (x - x0) / max(1, x1 - x0)
            v = (y - 7) / 6
            t = 0.92 - 0.5 * u - 0.34 * v + (0.18 if y == 7 else 0) - (0.12 if (x == x1 or y == 13) else 0)
            g = grays[max(1, min(5, int(round(t * 5))))]
            px[x, y] = (g, g, g, 255)
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
    save(antidote_overlay(), 'assets/zomapo/textures/item/antidote_overlay.png')
    save(virus_crystal(), 'assets/zomapo/textures/item/virus_crystal.png')
    write_json({"parent": "minecraft:item/generated", "textures": {"layer0": "zomapo:item/antidote_overlay", "layer1": "zomapo:item/antidote"}}, 'assets/zomapo/models/item/antidote.json')
    write_json({"parent": "minecraft:item/generated", "textures": {"layer0": "zomapo:item/virus_crystal"}}, 'assets/zomapo/models/item/virus_crystal.json')

    # 바닐라 아이템 정의 덮어쓰기: custom_model_data 문자열로 고르고, 아니면 바닐라 그대로
    write_json({"model": {
        "type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
        "cases": [{"when": "zomapo:antidote", "model": {"type": "minecraft:model", "model": "zomapo:item/antidote",
                                                         "tints": [{"type": "minecraft:potion", "default": -13083194}]}}],
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
