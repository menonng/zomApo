#!/usr/bin/env python3
"""리그(E 방식) 내보내기: 좀비화 모델을 뼈대마다 아이템 모델로 나눠 리소스팩 파일로 쓴다.

    python3 rig_export.py --models models.json --jar <클라이언트 jar> --out <폴더> enderman
    python3 rig_export.py --models models.json --jar <클라이언트 jar> --out <폴더> --vanilla zombie husk drowned

결과(저장소 제외: 텍스처가 모장 텍스처에서 나온다): <out>/assets/zomapo/{items,models}/rig/<몹>/<뼈대>.json,
<out>/assets/zomapo/textures/item/rig/<몹>_*.png. 서버(gimmick/rig)가 뼈대마다 아이템 표시 엔티티를 띄워
바닐라 모델과 같은 식으로 움직인다(docs/tech/architecture.md '좀비화 외형' E).

좌표: 뼈대 기준(피벗 = 원점) 바닐라 모델 공간 v(픽셀, y 아래) → 아이템 모델 e = 8 + 0.5 * (x, -y, -z).
 - (-x, -y, z) 는 엔티티 렌더러의 scale(-1, -1, 1), 그 위에 아이템 표시 렌더러가 Y 축 180° 를 더 돌리므로 미리 되돌린다.
 - 0.5 배: 아이템 모델 좌표 범위(-16~32)에 엔더맨 팔(30픽셀)이 들어가도록. 서버가 표시 크기 2 를 곱한다.
"""
import argparse
import json
import math
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build  # noqa: E402
from zmodel import Model, cube_polys, jar_texture, load_models, axis_rot  # noqa: E402

# 리그(E) 몹: 뼈대는 모델에서 그대로(큐브 없는 뼈대도 계층용으로). 서버 정의는 data/zomapo/rig/<몹>.json
RIG_MOBS = ['enderman', 'creeper', 'spider', 'cave_spider', 'slime', 'magma_cube', 'phantom', 'guardian', 'elder_guardian',
            'ghast', 'blaze', 'breeze', 'creaking', 'ravager', 'shulker', 'shulker_core']
EYES = {'enderman': ['head'], 'spider': ['head'], 'cave_spider': ['head'], 'phantom': ['head'], 'creaking': ['head'], 'breeze': ['head']}
RIGS = {'enderman': ['head', 'hat', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg']}
VANILLA = {   # 행글라이더 탑승자(바닐라 몹 그대로, 매달린 자세는 서버가 뼈대를 돌린다)
    'zombie': ('minecraft:zombie#main', 'zombie/zombie.png'),
    'husk': ('minecraft:husk#main', 'zombie/husk.png'),
    'drowned': ('minecraft:drowned#main', 'zombie/drowned.png'),
}
HUMANOID = ['head', 'hat', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg']
K = 0.5


def to_item(p):
    return np.array([8 + K * p[0], 8 - K * p[1], 8 - K * p[2]])


def face_name(n):
    a = int(np.argmax(np.abs(n)))
    return [('west', 'east'), ('down', 'up'), ('north', 'south')][a][1 if n[a] > 0 else 0]


def corners(face, pts):
    """블록 모델 면 규칙: (u1, v1) 이 놓이는 꼭짓점과 (u2, v2) 꼭짓점."""
    x, y, z = pts[:, 0], pts[:, 1], pts[:, 2]
    def pick(fx, fy):
        return int(np.argmin([fx(p) + fy(p) for p in pts]))
    big = 1e3
    if face == 'north':
        return pick(lambda p: -p[0], lambda p: -p[1] * big), pick(lambda p: p[0], lambda p: p[1] * big)
    if face == 'south':
        return pick(lambda p: p[0], lambda p: -p[1] * big), pick(lambda p: -p[0], lambda p: p[1] * big)
    if face == 'east':
        return pick(lambda p: -p[2], lambda p: -p[1] * big), pick(lambda p: p[2], lambda p: p[1] * big)
    if face == 'west':
        return pick(lambda p: p[2], lambda p: -p[1] * big), pick(lambda p: -p[2], lambda p: p[1] * big)
    if face == 'up':
        return pick(lambda p: p[0], lambda p: p[2] * big), pick(lambda p: -p[0], lambda p: -p[2] * big)
    return pick(lambda p: p[0], lambda p: -p[2] * big), pick(lambda p: -p[0], lambda p: p[2] * big)   # down


def cube_element(c, tw, th, tex_index, emission=0):
    ctw, cth = c['tex_size'] if 'tex_size' in c else (tw, th)
    polys = cube_polys(c, ctw, cth)
    rot = c.get('rot')
    allp = []
    faces = {}
    for _name, verts, nrm in polys:
        pts = np.array([to_item(v[:3]) for v in verts])
        uvs = [(v[3] * 16, v[4] * 16) for v in verts]
        span = pts.max(axis=0) - pts.min(axis=0)
        if (span > 1e-6).sum() < 2:
            continue                       # 두께 0 큐브의 옆면(넓이 0)
        n = np.array(nrm, dtype=float) * np.array([1, -1, -1])
        f = face_name(n)
        i1, i2 = corners(f, pts)
        (u1, v1), (u2, v2) = uvs[i1], uvs[i2]
        if abs(u1 - u2) < 1e-6 or abs(v1 - v2) < 1e-6:
            continue
        faces[f] = {'uv': [round(u1, 4), round(v1, 4), round(u2, 4), round(v2, 4)], 'texture': f'#{tex_index}', 'tintindex': 0}
        allp.append(pts)
    if not faces:
        return None
    P = np.vstack(allp)
    lo, hi = P.min(axis=0), P.max(axis=0)
    e = {'from': [round(float(t), 4) for t in lo], 'to': [round(float(t), 4) for t in hi], 'faces': faces}
    if rot:
        axis, deg, pv = rot
        sign = {'x': 1, 'y': -1, 'z': -1}[axis]
        e['rotation'] = {'origin': [round(float(t), 4) for t in to_item(pv)], 'axis': axis, 'angle': sign * deg}
    if emission:
        e['light_emission'] = emission
    return e


def export_bone(m, part, tex_ids, emission_for):
    tw, th = m.tex_size
    els = []
    for i, c in enumerate(part.cubes):
        if c.get('hidden'):
            continue
        tex = c.get('tex', 'base')
        if tex not in tex_ids:
            continue
        em = 15 if c.get('glow') else emission_for(part.name, i, c)
        el = cube_element(c, tw, th, tex_ids[tex], em)
        if el:
            for k in ('from', 'to'):
                if min(el[k]) < -16 or max(el[k]) > 32:
                    raise SystemExit(f'{part.name}: 아이템 모델 범위 밖 {el[k]}')
            els.append(el)
    return els


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(obj, f, ensure_ascii=False, separators=(',', ':'))


def export(m, mob, bones, out, emission_for=lambda b, i, c: 0):
    root = os.path.join(out, 'assets', 'zomapo')
    tex_ids, textures = {}, {}
    for key, img in m.textures.items():
        if key == 'overlay':
            continue
        name = f'{mob}_{key}'
        img.save(os.path.join(_mk(root, 'textures', 'item', 'rig'), name + '.png'))
        tex_ids[key] = str(len(textures))
        textures[str(len(textures))] = f'zomapo:item/rig/{name}'
    textures['particle'] = textures['0']
    meta = {}
    for b in bones:
        part = m.root.find(b)
        els = export_bone(m, part, tex_ids, emission_for)
        write_json(os.path.join(root, 'models', 'rig', mob, b + '.json'), {'textures': textures, 'elements': els})
        if els:
            # 염색 틴트: 서버가 맞은 순간 dyed_color 를 붉게 바꿔 바닐라 피격 붉은 빛을 흉내 낸다
            write_json(os.path.join(root, 'items', 'rig', mob, b + '.json'),
                       {'model': {'type': 'minecraft:model', 'model': f'zomapo:rig/{mob}/{b}', 'tints': [{'type': 'minecraft:dye', 'default': -1}]}})
        parent = part.parent.name if part.parent is not None and part.parent.name != 'root' else None
        meta[b] = {'pivot': [float(t) for t in getattr(part, '_pivot0', part.offset)], 'rest': [round(float(t), 5) for t in part.rot], 'parent': parent, 'elements': len(els)}
    write_json(os.path.join(out, 'rig-' + mob + '.json'), meta)
    return meta


def bones_of(m):
    """부모가 먼저 오는 순서의 모든 뼈대 이름(root 제외)."""
    return [p.name for p in m.root.walk() if p.name != 'root']


def server_def(mob, m, meta, data_dir, tethers=None):
    """서버 리그 정의(뼈대 이름·부모·피벗·기본 회전·모델 유무·눈 레이어 덮기 배율). 바닐라 모델 배치 숫자만 담는다.
    tethers: 줄 뼈대(셜커 기생체 줄) — 서버가 매 틱 '줄 끝'(다른 뼈대 기준 점)을 향해 돌리고 늘린다."""
    bones = []
    for b in bones_of(m):
        v = meta[b]
        bones.append({'name': b, 'parent': v['parent'], 'pivot': v['pivot'], 'rest': v['rest'], 'model': v['elements'] > 0,
                      'scale': 1.06 if b in EYES.get(mob, []) else 1.0})
    out = {'id': mob, 'bones': bones}
    if tethers:
        out['tethers'] = tethers
    write_json(os.path.join(data_dir, mob + '.json'), out)


def _mk(*p):
    d = os.path.join(*p)
    os.makedirs(d, exist_ok=True)
    return d


def core_model(out):
    """엔더맨 집중 때 가슴에서 커지는 공허 핵(스스로 빛남)."""
    root = os.path.join(out, 'assets', 'zomapo')
    img = Image.new('RGBA', (16, 16))
    cols = [(0xFA, 0xEB, 0xFF), (0xD6, 0x9A, 0xFF), (0xA0, 0x4E, 0xE0), (0x6A, 0x26, 0xA0), (0x3A, 0x12, 0x60)]
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5) / 8
            img.putpixel((x, y), (*cols[min(4, int(d * 5))], 255))
    img.save(os.path.join(_mk(root, 'textures', 'item', 'rig'), 'void_core.png'))
    face = {'uv': [0, 0, 16, 16], 'texture': '#0'}
    el = {'from': [6, 6, 6], 'to': [10, 10, 10], 'faces': {f: dict(face) for f in ('north', 'south', 'east', 'west', 'up', 'down')}, 'light_emission': 15}
    write_json(os.path.join(root, 'models', 'rig', 'void_core.json'), {'textures': {'0': 'zomapo:item/rig/void_core', 'particle': 'zomapo:item/rig/void_core'}, 'elements': [el]})
    write_json(os.path.join(root, 'items', 'rig', 'void_core.json'), {'model': {'type': 'minecraft:model', 'model': 'zomapo:rig/void_core'}})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--models', required=True)
    ap.add_argument('--jar', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--vanilla', action='store_true')
    ap.add_argument('--data', default=None, help='서버 리그 정의를 쓸 폴더(mod/src/main/resources/data/zomapo/rig)')
    ap.add_argument('mobs', nargs='*')
    a = ap.parse_args()
    models = load_models(a.models)
    for mob in (a.mobs or (list(VANILLA) if a.vanilla else RIG_MOBS)):
        if a.vanilla:
            key, tex = VANILLA[mob]
            m = Model(mob, models[key], jar_texture(a.jar, tex))
            meta = export(m, mob, HUMANOID, a.out)
            if a.data:
                server_def(mob, m, meta, a.data)
        else:
            m, d = build.make(models, a.jar, mob, True, preview_pose=False)
            inner = {L['bone']: L.get('idx', 0) for L in d.get('layered', [])}
            glow_inner = d.get('emissive_inner', {})

            def em(bone, i, c, inner=inner, glow_inner=glow_inner):
                return glow_inner.get(bone, 0) if inner.get(bone) == i else 0
            meta = export(m, mob, bones_of(m), a.out, em)
            if a.data:
                server_def(mob, m, meta, a.data, d.get('tethers'))
        print(mob, {b: v['elements'] for b, v in meta.items()})
    core_model(a.out)


if __name__ == '__main__':
    main()
