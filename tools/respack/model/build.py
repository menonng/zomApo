#!/usr/bin/env python3
"""좀비화 몹 모델 만들기(디자인 2차): 미리보기 그림과 블록벤치 .bbmodel.

    java ModelDump (models.json 만들기, docs/tech/architecture.md '좀비화 외형' 3-1)
    python3 build.py --models models.json --jar <마인크래프트 클라이언트 jar> --out ../out/models [--only creeper,spider]

결과(저장소 제외): <out>/preview-*.png, <out>/<몹>.bbmodel, <out>/tex/<몹>_*.png
디자인은 designs.py, 칠하기 도구는 paint.py.
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
from zmodel import Model, jar_texture, load_models, render  # noqa: E402
import designs as designs_mod  # noqa: E402
from designs import DESIGNS, humanoid_overlay, tone  # noqa: E402
from paint import box_faces  # noqa: E402


def merged_data(models, d, zombified):
    data = copy.deepcopy(models[d['layer']])
    if d.get('outer'):
        o = copy.deepcopy(models[d['outer']])
        for c in o['root']['children']:
            c['name'] = 'outer_' + c['name']
            data['root']['children'].append(c)
    if zombified and d.get('overlay'):
        bones = {}

        def index(p):
            bones[p['name']] = p
            for c in p['children']:
                index(c)
        index(data['root'])
        for key in d['overlay']:
            o = models[key]
            tw, th = o['tex']

            def merge(p):
                # 머리·몸통만(팔·다리 장비 모델은 해골 팔다리보다 굵어 떠 보인다 → 덧씌움 텍스처도 그 자리는 비움)
                if p['name'] in ('head', 'body') and p['name'] in bones:
                    for cube in p['cubes']:
                        cube = dict(cube)
                        cube['tex'] = 'overlay'
                        cube['tex_size'] = (tw, th)
                        bones[p['name']]['cubes'].append(cube)
                for c in p['children']:
                    merge(c)
            merge(o['root'])
    return data


def base_texture(jar, d, data, zombified):
    img = jar_texture(jar, d['texture'])
    eyes = jar_texture(jar, d['eyes']) if d.get('eyes') else None
    redraw = zombified and d['method'] in ('E', 'D')
    designs_mod.SCALE[0] = max(1, img.width // data['tex'][0])
    if redraw:
        if d.get('tone'):
            tone(img, d['tone'])
        if eyes is not None and d.get('eyes_fn'):
            d['eyes_fn'](eyes)
        if d.get('base'):
            d['base'](img, data)
        if d.get('base2'):
            d['base2'](img, data)
    if eyes is not None:
        img = img.copy()
        img.alpha_composite(eyes.resize(img.size, Image.NEAREST))
    return img


def uv_dims(size):
    return [math.ceil(v - 1e-6) if v > 1e-6 else 0 for v in size]


def pack(items, size=64):
    """박스 UV 자리 잡기(선반 방식). items: (w, h) 목록 → 좌표 목록, 아틀라스 크기."""
    while True:
        x = y = row = 0
        out = []
        ok = True
        for (W, H) in items:
            if x + W > size:
                x, y, row = 0, y + row, 0
            if y + H > size or W > size:
                ok = False
                break
            out.append((x, y))
            x += W
            row = max(row, H)
        if ok:
            return out, size
        size *= 2


def make(models, jar, name, zombified):
    d = DESIGNS[name]()
    data = merged_data(models, d, zombified)
    tex = base_texture(jar, d, data, zombified)
    m = Model(name, data, tex)
    if d.get('scale'):
        m.root.scale = m.root.scale * d['scale']
    if not zombified:
        return m, d
    for bone, (dx, dy, dz) in d.get('pose', {}).items():
        p = m.root.find(bone)
        p.rot = p.rot + [math.radians(dx), math.radians(dy), math.radians(dz)]
    for bone, idx in d.get('remove', []):
        m.root.find(bone).cubes[idx]['hidden'] = True
    for bone, idx, axis, length, keep in d.get('resize', []):
        c = m.root.find(bone).cubes[idx]
        ai = 'xyz'.index(axis)
        old = c['size'][ai]
        c['size'] = list(c['size'])
        c['origin'] = list(c['origin'])
        if keep == 'min':
            c['origin'][ai] += old - length
        c['size'][ai] = length
    jobs = []  # (큐브, 칠하기)
    for bone, idx, painter in d.get('retex', []):
        c = m.root.find(bone).cubes[idx]
        c.update(tex='parts', mirror=False)
        c['uv_dims'] = uv_dims(c['size'])
        jobs.append((c, painter))
    for a in d.get('adds', []):
        c = dict(origin=list(a['origin']), size=list(a['size']), grow=[0, 0, 0], mirror=False, uv=[0, 0], texScale=[1, 1],
                 faces=[], tex='parts', added=True, tell=a.get('tell'), glow=a.get('glow', False))
        if a.get('rot'):
            c['rot'] = a['rot']
        c['uv_dims'] = uv_dims(a['size'])
        m.root.find(a['bone']).cubes.append(c)
        jobs.append((c, a['paint']))
    if jobs:
        sizes = []
        for c, _ in jobs:
            w, h, dd = c['uv_dims']
            sizes.append((max(1, 2 * (w + dd)), max(1, dd + h)))
        pos, S = pack(sizes)
        atlas = Image.new('RGBA', (S, S), (0, 0, 0, 0))
        for k, ((c, painter), (x, y)) in enumerate(zip(jobs, pos)):
            c['uv'] = [x, y]
            c['tex_size'] = (S, S)
            w, h, dd = c['uv_dims']
            faces = {f: r for f, r in box_faces(x, y, w, h, dd).items() if r[2] > 0 and r[3] > 0}
            painter(atlas, faces, 1000 + 97 * k)
        m.textures['parts'] = atlas
    if d.get('overlay_fn'):
        m.textures['overlay'] = humanoid_overlay(d['overlay_fn'])
    return m, d


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

    def bb(v):
        return [-v[0], 24 - v[1], v[2]]

    def group(p):
        piv = abs_pivot(p)
        g = {'name': p.name, 'origin': bb(piv),
             'rotation': [-math.degrees(p.rot[0]), -math.degrees(p.rot[1]), math.degrees(p.rot[2])],
             'uuid': str(uuid.uuid4()), 'export': True, 'isOpen': False, 'children': []}
        for c in p.cubes:
            if c.get('hidden'):
                continue
            x0 = piv + c['origin']
            x1 = x0 + c['size']
            gx = c.get('grow', [0, 0, 0])[0]
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
            rot = [0, 0, 0]
            origin = bb(piv)
            if c.get('rot'):
                axis, deg, pv = c['rot']
                rot = {'x': [-deg, 0, 0], 'y': [0, -deg, 0], 'z': [0, 0, deg]}[axis]
                origin = bb(piv + pv)
            e = {'name': 'zombie_part' if c.get('added') else p.name, 'box_uv': True, 'rescale': False, 'locked': False,
                 'from': [-x1[0], 24 - x1[1], x0[2]], 'to': [-x0[0], 24 - x0[1], x1[2]], 'autouv': 0, 'color': 0,
                 'inflate': gx, 'rotation': rot, 'origin': origin, 'uv_offset': [u, v], 'mirror_uv': bool(c.get('mirror')),
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
TITLE = {
    'creeper': '균사 크리퍼', 'spider': '알집 거미', 'cave_spider': '독샘 거미', 'enderman': '공허 붕괴체',
    'creaking': '창백한 썩음(크리킹)', 'slime': '늪 찌꺼기(슬라임)', 'magma_cube': '혼불 큐브(마그마 큐브)',
    'phantom': '누더기 팬텀', 'guardian': '따개비 가디언', 'breeze': '녹슨 바람(브리즈)', 'ghast': '혼불 가스트',
    'blaze': '잿불 블레이즈', 'skeleton': '묘지 해골', 'stray': '동상 해골(스트레이)', 'bogged': '포자 해골(보그드)',
    'wither_skeleton': '진홍 균류 해골(위더 스켈레톤)', 'piglin_brute': '진홍 브루트', 'pillager': '부패 약탈자',
    'vindicator': '부패 변명자', 'evoker': '부패 소환사', 'illusioner': '부패 환술사', 'witch': '역병 마녀', 'shulker': '부패 셜커',
}
METHOD = {'E': '리그', 'B': '장비 덧씌움 + 머리 아이템 (몸 텍스처는 바닐라)', 'C': '머리 아이템 (몸 텍스처는 바닐라)', 'D': '색 변형 텍스처'}


def preview_sheet(models, jar, names, out_path, cell=300):
    font = ImageFont.truetype(FONT, 22, index=1)
    small = ImageFont.truetype(FONT, 15, index=1)
    W, H = cell * 3, (cell + 58) * len(names)
    sheet = Image.new('RGBA', (W, H), (34, 34, 40, 255))
    dr = ImageDraw.Draw(sheet)
    for r, name in enumerate(names):
        y = r * (cell + 58)
        o, _ = make(models, jar, name, False)
        z, d = make(models, jar, name, True)
        yaw, pitch = d.get('view', (-35, 22))
        tiles = [render(o, size=cell, yaw=yaw, pitch=pitch), render(z, size=cell, yaw=yaw, pitch=pitch), render(z, size=cell, yaw=yaw + 180, pitch=pitch)]
        for i, t in enumerate(tiles):
            sheet.alpha_composite(t, (i * cell, y + 34))
        dr.text((10, y + 4), f'{TITLE.get(name, name)}  ·  {METHOD[d["method"]]}  —  원종 / 좀비화 앞 / 뒤', font=font, fill=(235, 235, 235))
        dr.text((10, y + cell + 12), d.get('concept', ''), font=small, fill=(205, 205, 205))
        tell = next((a.get('tell') for a in d.get('adds', []) if a.get('tell')), None)
        if tell:
            dr.text((10, y + cell + 32), '신호: ' + tell, font=small, fill=(160, 200, 160))
    sheet.convert('RGB').save(out_path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--models', required=True)
    ap.add_argument('--jar', required=True)
    ap.add_argument('--out', default=os.path.join(HERE, '..', 'out', 'models'))
    ap.add_argument('--only', default='')
    ap.add_argument('--per', type=int, default=6)
    args = ap.parse_args()
    os.makedirs(os.path.join(args.out, 'tex'), exist_ok=True)
    models = load_models(args.models)
    names = [n for n in DESIGNS if not args.only or n in args.only.split(',')]
    for n in names:
        z, d = make(models, args.jar, n, True)
        json.dump(to_bbmodel(z, 'zomapo_' + n), open(os.path.join(args.out, n + '.bbmodel'), 'w'))
        for key, img in z.textures.items():
            img.save(os.path.join(args.out, 'tex', f'{n}_{key}.png'))
    for i in range(0, len(names), args.per):
        preview_sheet(models, args.jar, names[i:i + args.per], os.path.join(args.out, f'preview-{i // args.per + 1}.png'))
    print('ok', len(names))


if __name__ == '__main__':
    main()
