#!/usr/bin/env python3
"""머리 칸 아이템 모델 내보내기(C 방식): 좀비화 우민의 머리 덮개·덧붙임을 머리에 씌우는 아이템 모델로 쓴다.

    python3 head_export.py --models models.json --jar <클라이언트 jar> --out <폴더>

결과(저장소 제외: 텍스처가 모장 텍스처에서 나온다): <out>/assets/zomapo/{items,models}/head/<이름>.json,
<out>/assets/zomapo/textures/item/head/<몹>_parts.png. 서버가 좀비화 우민의 머리 칸에 item_model 을 단 종이를 넣는다
(바닐라 CustomHeadLayer 가 그린다, 떨어뜨리지 않음). 변명자는 '도끼 박힘'(vindicator_axe)과 '상흔'(vindicator_scar) 두 가지.

좌표: CustomHeadLayer 는 머리 뼈대에서 (0, -0.25, 0) 이동 → Y 180° → (0.625, -0.625, -0.625) 크기 → 아이템 중심(-0.5)으로 그린다.
그래서 머리 기준 모델 좌표 v(픽셀, y 아래) → 아이템 e = (8 - 1.6x, 8 - 1.6(y + 4), 8 + 1.6z). 요소 회전 부호는 x, y 가 뒤집힌다.
"""
import argparse
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build  # noqa: E402
from rig_export import corners, face_name, write_json, _mk  # noqa: E402
from zmodel import cube_polys, load_models  # noqa: E402

K = 1.6
# (몹, 변종 이름, 뺄 큐브 이름)
HEADS = [
    ('pillager', 'pillager', set()),
    ('evoker', 'evoker', set()),
    ('illusioner', 'illusioner', set()),
    ('vindicator', 'vindicator_axe', set()),
    ('vindicator', 'vindicator_scar', {'axe_blade', 'axe_handle'}),
]


def to_item(p):
    return np.array([8 - K * p[0], 8 - K * (p[1] + 4), 8 + K * p[2]])


def element(c, offset, tw, th):
    """큐브 하나 → 블록 모델 요소. offset: 머리 기준으로 옮기는 자식 뼈대 피벗(코)."""
    ctw, cth = c['tex_size'] if 'tex_size' in c else (tw, th)
    cc = dict(c)
    cc['origin'] = [c['origin'][i] + offset[i] for i in range(3)]
    polys = cube_polys(cc, ctw, cth)
    faces, allp = {}, []
    for _n, verts, nrm in polys:
        pts = np.array([to_item(v[:3]) for v in verts])
        uvs = [(v[3] * 16, v[4] * 16) for v in verts]
        if ((pts.max(axis=0) - pts.min(axis=0)) > 1e-6).sum() < 2:
            continue
        f = face_name(np.array(nrm, dtype=float) * np.array([-1, -1, 1]))
        i1, i2 = corners(f, pts)
        (u1, v1), (u2, v2) = uvs[i1], uvs[i2]
        if abs(u1 - u2) < 1e-6 or abs(v1 - v2) < 1e-6:
            continue
        faces[f] = {'uv': [round(u1, 4), round(v1, 4), round(u2, 4), round(v2, 4)], 'texture': '#0'}
        allp.append(pts)
    if not faces:
        return None
    P = np.vstack(allp)
    lo, hi = P.min(axis=0), P.max(axis=0)
    for v in (lo, hi):
        if v.min() < -16 or v.max() > 32:
            raise SystemExit(f'머리 아이템 모델 범위 밖: {lo} {hi}')
    e = {'from': [round(float(t), 4) for t in lo], 'to': [round(float(t), 4) for t in hi], 'faces': faces}
    if c.get('rot'):
        axis, deg, pv = c['rot']
        sign = {'x': -1, 'y': -1, 'z': 1}[axis]
        e['rotation'] = {'origin': [round(float(t), 4) for t in to_item([pv[i] + offset[i] for i in range(3)])], 'axis': axis, 'angle': sign * deg}
    return e


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--models', required=True)
    ap.add_argument('--jar', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    models = load_models(a.models)
    root = os.path.join(a.out, 'assets', 'zomapo')
    made = {}
    for mob, name, skip in HEADS:
        if mob not in made:
            made[mob] = build.make(models, a.jar, mob, True, preview_pose=False)
        m, d = made[mob]
        tex = m.textures['parts']
        tex.save(os.path.join(_mk(root, 'textures', 'item', 'head'), f'{mob}_parts.png'))
        S = getattr(m, 'parts_uv', tex.width)
        head = m.root.find('head')
        els = []
        for part in head.walk():
            off = [0.0, 0.0, 0.0] if part is head else list(part.offset)
            for c in part.cubes:
                if not c.get('added') or c.get('hidden') or c.get('name') in skip:
                    continue
                el = element(c, off, S, S)
                if el:
                    els.append(el)
        texid = f'zomapo:item/head/{mob}_parts'
        write_json(os.path.join(root, 'models', 'head', name + '.json'), {'textures': {'0': texid, 'particle': texid}, 'elements': els})
        write_json(os.path.join(root, 'items', 'head', name + '.json'), {'model': {'type': 'minecraft:model', 'model': f'zomapo:head/{name}'}})
        print(name, len(els))


if __name__ == '__main__':
    main()
