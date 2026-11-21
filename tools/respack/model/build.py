#!/usr/bin/env python3
"""좀비화 몹 모델 만들기: 미리보기 그림과 블록벤치 .bbmodel.

    java ModelDump (models.json 만들기, README 참고)
    python3 build.py --models models.json --jar <마인크래프트 클라이언트 jar> --out ../out/models

결과(저장소 제외): <out>/preview-*.png, <out>/<몹>.bbmodel, <out>/parts/<몹>.png
"""
import argparse
import base64
import copy
import io
import json
import math
import os
import sys
import uuid

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
from zmodel import Model, Part, jar_texture, load_models, render  # noqa: E402
from specs import SPECS  # noqa: E402
from parts import build_atlas, humanoid_overlay  # noqa: E402
import zombify_textures as zt  # noqa: E402


def spec_of(name):
    s = dict(SPECS[name])
    if 'base' in s:
        b = dict(SPECS[s['base']])
        b.update({k: v for k, v in s.items() if k != 'base'})
        s = b
    return s


def merged_data(models, s, zombified=True):
    d = copy.deepcopy(models[s['layer']])
    if zombified and s.get('overlay'):
        bones = {}

        def index(p):
            bones[p['name']] = p
            for c in p['children']:
                index(c)
        index(d['root'])
        for key in s['overlay']:
            o = models[key]
            tw, th = o['tex']

            def merge(p):
                if p['name'] in bones and p['name'] != 'root':
                    for cube in p['cubes']:
                        cube = dict(cube)
                        cube['tex'] = 'overlay'
                        cube['tex_size'] = (tw, th)
                        bones[p['name']]['cubes'].append(cube)
                for c in p['children']:
                    merge(c)
            merge(o['root'])
    if 'outer' in s:
        o = copy.deepcopy(models[s['outer']])
        for c in o['root']['children']:
            c['name'] = 'outer_' + c['name']
            for cube in c['cubes']:
                cube['outer'] = True
            d['root']['children'].append(c)
    return d


def base_texture(jar, s, zombified):
    img = jar_texture(jar, s['texture'])
    if zombified:
        img = zt.zombify(img, s.get('style', 'flesh'), seed=hash(s['texture']) & 0xffff)
    if s.get('eyes'):
        eyes = jar_texture(jar, s['eyes'])
        if zombified:
            eyes = zt.recolor_eyes(eyes)
        img = img.copy()
        img.alpha_composite(eyes.resize(img.size, Image.NEAREST))
    return img


def make(models, jar, name, zombified):
    s = spec_of(name)
    data = merged_data(models, s, zombified)
    tex = base_texture(jar, s, zombified)
    m = Model(name, data, tex)
    if s.get('scale'):
        m.root.scale = m.root.scale * s['scale']
    if not zombified:
        return m, s
    adds = []
    for bone, (dx, dy, dz) in s.get('pose', {}).items():
        p = m.root.find(bone)
        p.rot = p.rot + [math.radians(dx), math.radians(dy), math.radians(dz)]
    for bone, idx in s.get('remove', []):
        m.root.find(bone).cubes[idx]['hidden'] = True
    for bone, idx, axis, length, keep in s.get('resize', []):
        c = m.root.find(bone).cubes[idx]
        ai = 'xyz'.index(axis)
        old = c['size'][ai]
        c['size'] = list(c['size'])
        c['origin'] = list(c['origin'])
        if keep == 'min':  # 원점 쪽(최솟값)을 잘라 낸다 = 끝이 짧아짐
            c['origin'][ai] += old - length
        c['size'][ai] = length
    for bone, idx, mat in s.get('retex', []):
        c = m.root.find(bone).cubes[idx]
        c.update(tex='parts', mat=mat)
        c['uv_dims'] = [max(1, math.ceil(v)) for v in c['size']]
        c['mirror'] = False
        adds.append(c)
    for a in s.get('add', []) + s.get('add_tip', []):
        c = dict(origin=list(a['origin']), size=list(a['size']), grow=[0, 0, 0], mirror=False, uv=[0, 0],
                 texScale=[1, 1], faces=[], tex='parts', mat=a['mat'], added=True, tell=a.get('tell'))
        c['uv_dims'] = [max(1, math.ceil(v)) for v in a['size']]
        m.root.find(a['bone']).cubes.append(c)
        adds.append(c)
    if adds:
        m.textures['parts'] = build_atlas(adds)
    if s.get('overlay'):
        m.textures['overlay'] = humanoid_overlay()
    return m, s


# ---------- 블록벤치 .bbmodel ----------

def _png64(img):
    b = io.BytesIO()
    img.save(b, 'PNG')
    return 'data:image/png;base64,' + base64.b64encode(b.getvalue()).decode()


def to_bbmodel(m, name):
    """바닐라 모델 공간 → 블록벤치 공간: x → -x, y → 24 - y (z축 180° 회전), 회전은 x·y 부호 반전."""
    tw, th = m.tex_size
    textures = [{'name': name + '.png', 'id': '0', 'width': m.texture.width, 'height': m.texture.height,
                 'uv_width': tw, 'uv_height': th, 'source': _png64(m.texture), 'uuid': str(uuid.uuid4())}]
    tindex = {'base': 0}
    for key in ('parts', 'overlay'):
        if key in m.textures:
            a = m.textures[key]
            tindex[key] = len(textures)
            uvw, uvh = (64, 32) if key == 'overlay' else (a.width, a.height)
            textures.append({'name': f'{name}_{key}.png', 'id': str(len(textures)), 'width': a.width, 'height': a.height,
                             'uv_width': uvw, 'uv_height': uvh, 'source': _png64(a), 'uuid': str(uuid.uuid4())})
    elements = []

    def abs_pivot(p):
        o = p.offset.copy()
        q = p.parent
        while q is not None:
            o = o + q.offset
            q = q.parent
        return o

    def group(p):
        piv = abs_pivot(p)
        g = {'name': p.name, 'origin': [-piv[0], 24 - piv[1], piv[2]],
             'rotation': [-math.degrees(p.rot[0]), -math.degrees(p.rot[1]), math.degrees(p.rot[2])],
             'uuid': str(uuid.uuid4()), 'export': True, 'isOpen': False, 'children': []}
        for i, c in enumerate(p.cubes):
            if c.get('hidden'):
                continue
            x0 = piv + c['origin']
            x1 = x0 + c['size']
            gx, gy, gz = c.get('grow', [0, 0, 0])
            frm = [-x1[0], 24 - x1[1], x0[2]]
            to = [-x0[0], 24 - x0[1], x1[2]]
            ti = tindex.get(c.get('tex', 'base'), 0)
            uw, uh, ud = c.get('uv_dims', c['size'])
            u, v = c['uv']
            faces = {
                'north': [u + ud, v + ud, u + ud + uw, v + ud + uh],
                'east': [u, v + ud, u + ud, v + ud + uh],
                'south': [u + 2 * ud + uw, v + ud, u + 2 * ud + 2 * uw, v + ud + uh],
                'west': [u + ud + uw, v + ud, u + 2 * ud + uw, v + ud + uh],
                'up': [u + ud + uw, v + ud, u + ud, v],
                'down': [u + ud + 2 * uw, v, u + ud + uw, v + ud],
            }
            e = {'name': (c.get('mat') or p.name) + ('' if not c.get('added') else '_z'), 'box_uv': True,
                 'rescale': False, 'locked': False, 'from': frm, 'to': to, 'autouv': 0, 'color': 0,
                 'inflate': gx, 'origin': [-piv[0], 24 - piv[1], piv[2]], 'uv_offset': [u, v],
                 'mirror_uv': bool(c.get('mirror')),
                 'faces': {k: {'uv': val, 'texture': ti} for k, val in faces.items()}, 'type': 'cube', 'uuid': str(uuid.uuid4())}
            elements.append(e)
            g['children'].append(e['uuid'])
        for ch in p.children:
            g['children'].append(group(ch))
        return g

    outliner = [group(c) for c in m.root.children]
    return {'meta': {'format_version': '4.10', 'model_format': 'free', 'box_uv': True}, 'name': name,
            'model_identifier': name, 'visible_box': [1, 1, 0], 'resolution': {'width': tw, 'height': th},
            'elements': elements, 'outliner': outliner, 'textures': textures, 'animations': []}


# ---------- 미리보기 ----------

FONT = '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'

NAMES = {
    'creeper': '부패 크리퍼', 'spider': '감염 거미', 'cave_spider': '감염 동굴 거미', 'enderman': '공허 부패자',
    'creaking': '부패 크리킹', 'slime': '부패 슬라임', 'magma_cube': '부패 마그마 큐브', 'phantom': '부패 팬텀',
    'guardian': '부패 가디언', 'breeze': '부패 브리즈', 'ghast': '부패 가스트', 'blaze': '부패 블레이즈',
    'skeleton': '부패 궁수(스켈레톤)', 'stray': '부패 궁수(스트레이)', 'bogged': '부패 궁수(보그드)',
    'wither_skeleton': '부패 위더 해골', 'piglin_brute': '부패 브루트', 'pillager': '부패 약탈자',
    'vindicator': '부패 변명자', 'evoker': '부패 소환사', 'illusioner': '부패 환술사', 'witch': '역병 마녀', 'shulker': '부패 셜커',
}


def preview_sheet(models, jar, names, out_path, cell=300):
    font = ImageFont.truetype(FONT, 22, index=1) if os.path.exists(FONT) else None
    small = ImageFont.truetype(FONT, 16, index=1) if os.path.exists(FONT) else None
    rows = len(names)
    W, H = cell * 3, (cell + 40) * rows
    sheet = Image.new('RGBA', (W, H), (34, 34, 40, 255))
    d = ImageDraw.Draw(sheet)
    for r, name in enumerate(names):
        y = r * (cell + 40)
        o, _ = make(models, jar, name, False)
        z, s = make(models, jar, name, True)
        tiles = [render(o, size=cell, yaw=-35), render(z, size=cell, yaw=-35), render(z, size=cell, yaw=145)]
        for i, t in enumerate(tiles):
            sheet.alpha_composite(t, (i * cell, y + 36))
        d.text((10, y + 6), f'{NAMES.get(name, name)}  —  원종 / 좀비화(앞) / 좀비화(뒤)', font=font, fill=(235, 235, 235))
        tell = next((a.get('tell') for a in s.get('add', []) if a.get('tell')), s.get('tell_text'))
        if tell:
            d.text((10, y + cell + 12), '신호: ' + tell, font=small, fill=(200, 200, 200))
    sheet.convert('RGB').save(out_path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--models', required=True)
    ap.add_argument('--jar', required=True)
    ap.add_argument('--out', default=os.path.join(HERE, '..', 'out', 'models'))
    ap.add_argument('--only', default='')
    args = ap.parse_args()
    os.makedirs(os.path.join(args.out, 'parts'), exist_ok=True)
    models = load_models(args.models)
    names = [n for n in SPECS if not args.only or n in args.only.split(',')]
    for n in names:
        z, s = make(models, args.jar, n, True)
        json.dump(to_bbmodel(z, 'zomapo_' + n), open(os.path.join(args.out, n + '.bbmodel'), 'w'))
        if 'parts' in z.textures:
            z.textures['parts'].save(os.path.join(args.out, 'parts', n + '.png'))
        z.texture.save(os.path.join(args.out, 'parts', n + '_base.png'))
    per = 6
    for i in range(0, len(names), per):
        preview_sheet(models, args.jar, names[i:i + per], os.path.join(args.out, f'preview-{i // per + 1}.png'))
    print('ok', len(names))


if __name__ == '__main__':
    main()
