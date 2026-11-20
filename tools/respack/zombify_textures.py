#!/usr/bin/env python3
"""바닐라 몹 텍스처에 '좀비화' 느낌을 입힌 시안 텍스처를 만든다(빌드 때 실행).

저장소에는 Mojang 텍스처를 넣지 않는다. 이 스크립트가 실행하는 사람의 마인크래프트 jar 에서
원본을 읽어 변형하고, 결과는 tools/respack/out/ (저장소 제외)에 쓴다.
여기서 나온 텍스처는 출발점이다. Blockbench 에서 손으로 다듬어 서버 리소스팩에 넣는다(docs/tech/architecture.md '좀비화 외형').

    python zombify_textures.py --jar <minecraft-jar> [--preview preview.png]

Pillow 필요(pip install pillow). 결과는 시드 고정이라 몇 번을 돌려도 같다.
"""
import argparse
import colorsys
import glob
import io
import os
import random
import zipfile

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))

# 원본 텍스처 → 처리 방식
TARGETS = {
    "skeleton/skeleton.png": "bone",
    "skeleton/stray.png": "bone",
    "skeleton/bogged.png": "bone",
    "skeleton/parched.png": "bone",
    "skeleton/wither_skeleton.png": "bone",
    "spider/spider.png": "chitin",
    "spider/cave_spider.png": "chitin",
    "creeper/creeper.png": "flesh",
    "enderman/enderman.png": "flesh",
    "slime/slime.png": "slime",
    "creaking/creaking.png": "bark",
    "phantom/phantom.png": "flesh",
    "silverfish/silverfish.png": "chitin",
    "endermite/endermite.png": "chitin",
    "illager/pillager.png": "flesh",
    "illager/vindicator.png": "flesh",
    "illager/evoker.png": "flesh",
    "illager/illusioner.png": "flesh",
    "witch/witch.png": "flesh",
    "guardian/guardian.png": "flesh",
    "breeze/breeze.png": "bark",
    "shulker/shulker.png": "chitin",
    "blaze/blaze.png": "flesh",
    "ghast/ghast.png": "bone",
    "slime/magma_cube.png": "slime",
}
EYES = ["enderman/enderman_eyes.png", "phantom/phantom_eyes.png", "creaking/creaking_eyes.png", "breeze/breeze_eyes.png"]

ROT = (74, 90, 46)        # 썩은 살(올리브)
WOUND = (107, 42, 38)     # 벌어진 상처(검붉은)
FLESH = (122, 74, 58)     # 뼈에 붙은 살점
SPORE = (127, 163, 90)    # 포자(연두)
GLOW = (200, 255, 90)     # 감염된 눈


def find_jar():
    pats = [os.path.expanduser("~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/*/minecraft-merged-deobf-*.jar"),
            os.path.expanduser("~/.minecraft/versions/*/*.jar"),
            os.path.join(os.environ.get("APPDATA", ""), ".minecraft", "versions", "*", "*.jar")]
    for p in pats:
        found = sorted(glob.glob(p))
        if found:
            return found[-1]
    return None


def value_noise(w, h, rng, cell):
    gw, gh = w // cell + 2, h // cell + 2
    grid = [[rng.random() for _ in range(gw)] for _ in range(gh)]
    out = [[0.0] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            gx, gy = x / cell, y / cell
            x0, y0 = int(gx), int(gy)
            tx, ty = gx - x0, gy - y0
            tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
            a = grid[y0][x0] * (1 - tx) + grid[y0][x0 + 1] * tx
            b = grid[y0 + 1][x0] * (1 - tx) + grid[y0 + 1][x0 + 1] * tx
            out[y][x] = a * (1 - ty) + b * ty
    return out


def mix(c, d, t):
    return tuple(int(round(c[i] * (1 - t) + d[i] * t)) for i in range(3))


def zombify(img, style, seed):
    img = img.convert("RGBA")
    w, h = img.size
    rng = random.Random(seed)
    unit = max(1, w // 64)                     # 64 폭 기준 픽셀 크기
    n1 = value_noise(w, h, rng, 4 * unit)
    n2 = value_noise(w, h, rng, 2 * unit)
    px = img.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            hh, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            n = 0.65 * n1[y][x] + 0.35 * n2[y][x]
            if style == "slime":
                s *= 0.6
                hh = hh * 0.6 + 0.24 * 0.4      # 탁한 황록
                v *= 0.85
            elif style == "bone":
                s *= 0.7
                v *= 0.9
            else:
                s *= 0.55
                hh = hh * 0.65 + 0.26 * 0.35    # 녹회색 쪽으로
                v *= 0.82
            rr, gg, bb = colorsys.hsv_to_rgb(hh % 1.0, max(0, min(1, s)), max(0, min(1, v)))
            c = (int(rr * 255), int(gg * 255), int(bb * 255))
            if style == "bone":
                if n > 0.60:
                    c = mix(c, FLESH, 0.65)     # 뼈에 붙은 살점
                if n > 0.76:
                    c = mix(c, WOUND, 0.7)
            else:
                if n > 0.58:
                    c = mix(c, ROT, 0.55)
                if n > 0.74:
                    c = mix(c, WOUND, 0.7)
                if 0.72 < n <= 0.74:
                    c = mix(c, (30, 22, 18), 0.6)  # 상처 가장자리
            if rng.random() < 0.025:
                c = mix(c, SPORE, 0.6)
            px[x, y] = (*c, a)
    return img


def recolor_eyes(img):
    img = img.convert("RGBA")
    px = img.load()
    for y in range(img.size[1]):
        for x in range(img.size[0]):
            r, g, b, a = px[x, y]
            if a:
                v = max(r, g, b) / 255
                px[x, y] = (*mix((40, 60, 20), GLOW, v), a)
    return img


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", default=None)
    ap.add_argument("--out", default=os.path.join(HERE, "out"))
    ap.add_argument("--preview", default=None)
    ap.add_argument("--preview-only", default="", help="미리보기에 넣을 텍스처(쉼표 구분)")
    a = ap.parse_args()
    jar = a.jar or find_jar()
    if not jar:
        raise SystemExit("마인크래프트 jar 를 찾지 못했다. --jar 로 지정한다.")
    z = zipfile.ZipFile(jar)
    base = "assets/minecraft/textures/entity/"
    done = []
    for rel, style in TARGETS.items():
        try:
            src = Image.open(io.BytesIO(z.read(base + rel)))
        except KeyError:
            print("없음:", rel)
            continue
        out = zombify(src, style, seed=sum(map(ord, rel)))
        dst = os.path.join(a.out, "assets", "zomapo", "textures", "entity", "zombified", rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        out.save(dst)
        done.append((rel, src.convert("RGBA"), out))
    for rel in EYES:
        try:
            src = Image.open(io.BytesIO(z.read(base + rel)))
        except KeyError:
            continue
        dst = os.path.join(a.out, "assets", "zomapo", "textures", "entity", "zombified", rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        recolor_eyes(src).save(dst)
    print(f"{len(done)}개 → {a.out}")
    if a.preview:
        scale = 5
        pick = a.preview_only.split(",") if a.preview_only else None
        rows = [d for d in done if d[1].size[0] <= 64 and (pick is None or d[0] in pick)]
        cw = max(d[1].size[0] for d in rows) * scale
        chs = [d[1].size[1] * scale for d in rows]
        W = cw * 2 + 60
        H = sum(chs) + 28 * len(rows) + 10
        sheet = Image.new("RGBA", (W, H), (32, 32, 36, 255))
        dr = ImageDraw.Draw(sheet)
        y = 10
        for (rel, src, out), ch in zip(rows, chs):
            dr.text((10, y), rel + "   (left: vanilla / right: zombified draft)", fill=(230, 230, 230, 255))
            y += 16
            for i, im in enumerate((src, out)):
                big = im.resize((im.size[0] * scale, im.size[1] * scale), Image.NEAREST)
                bg = Image.new("RGBA", big.size, (70, 70, 76, 255))
                bg.alpha_composite(big)
                sheet.alpha_composite(bg, (20 + i * (cw + 20), y))
            y += ch + 12
        sheet.save(a.preview)
        print("미리보기:", a.preview)


if __name__ == "__main__":
    main()
