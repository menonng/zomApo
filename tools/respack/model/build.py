#!/usr/bin/env python3
"""좀비화 몹 모델 만들기(디자인 4차): 미리보기 그림과 블록벤치 .bbmodel.

    java ModelDump (models.json 만들기, docs/tech/architecture.md '좀비화 외형' 3-1)
    python3 build.py --models models.json --jar <마인크래프트 클라이언트 jar> --out ../out/models [--only creeper,spider]

결과(저장소 제외): <out>/preview-*.png, <out>/<몹>.bbmodel, <out>/tex/<몹>_*.png
디자인은 designs4.py(3차 designs3.py 위에 겹 구조·2배 텍스처·1차 복원), 칠하기 도구는 paint.py.
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
from zmodel import Model, cube_polys, jar_texture, load_models, render  # noqa: E402
import designs as designs_mod  # noqa: E402
from designs import humanoid_overlay, tone  # noqa: E402
import designs4  # noqa: E402
from designs4 import DESIGNS  # noqa: E402
from paint import box_faces  # noqa: E402

HIRES = 2   # 4차: 모든 몹 텍스처 2배(모델 UV 1칸 = 텍스처 2픽셀). 디자인이 'hires': 1 이면 1배


def merged_data(models, d, zombified):
    data = copy.deepcopy(models[d['layer']])
    if d.get('outer'):
        o = copy.deepcopy(models[d['outer']])

        def mark(p):
            p['name'] = 'outer_' + p['name']
            if d.get('outer_tex'):
                for cube in p['cubes']:
                    cube['tex'] = 'outer'
                    cube['tex_size'] = tuple(o['tex'])
            for ch in p['children']:
                mark(ch)
        for c in o['root']['children']:
            mark(c)
            data['root']['children'].append(c)
    for eb in (d.get('extra_bones', []) if zombified else []):
        # 디자인이 더하는 뼈대(엔더맨 공허 날개 등): 바닐라 모델에 없는 움직이는 부위
        def find(p):
            if p['name'] == eb['parent']:
                return p
            for c in p['children']:
                r = find(c)
                if r:
                    return r
            return None
        parent = find(data['root'])
        parent['children'].append({'name': eb['name'], 'pose': list(eb['pivot']) + list(eb.get('rot', [0.0, 0.0, 0.0])) + [1.0, 1.0, 1.0], 'cubes': [], 'children': []})
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
                if p['name'] in d.get('overlay_bones', ('head', 'body')) and p['name'] in bones:
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
    k = max(1, d.get('hires', HIRES) // max(1, img.width // data['tex'][0]))
    if redraw and k > 1:
        # 바탕을 2배로 키워 그 위에 칠한다(모델 UV 는 그대로, 텍스처만 고해상도)
        img = img.resize((img.width * k, img.height * k), Image.NEAREST)
        if eyes is not None:
            eyes = eyes.resize((eyes.width * k, eyes.height * k), Image.NEAREST)
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
        # 겹 구조: 껍질(살)은 해부도를 그리기 전 모습을 옮겨 쓰고, 안쪽 큐브에는 뼈·장기를 그린다
        d['_pre'] = img.copy()
        if d.get('anatomy'):
            d['anatomy'](img, data)
    if eyes is not None:
        img = img.copy()
        img.alpha_composite(eyes.resize(img.size, Image.NEAREST))
        if d.get('_pre') is not None:
            d['_pre'].alpha_composite(eyes.resize(img.size, Image.NEAREST))
    return img


def face_crop(m, cube, face, src_img=None):
    """원종 큐브 한 면의 텍스처 조각(그 큐브가 쓰는 텍스처에서, 배율 반영)."""
    img = src_img if src_img is not None else m.textures[cube.get('tex', 'base')]
    tw = (cube['tex_size'] if 'tex_size' in cube else m.tex_size)[0]
    kk = max(1, img.width // int(tw))
    su, sv = cube['uv']
    sw, sh, sd = (int(round(t)) for t in cube.get('uv_dims', cube['size']))
    r = box_faces(int(su), int(sv), sw, sh, sd)[face]
    return img.crop((r[0] * kk, r[1] * kk, (r[0] + r[2]) * kk, (r[1] + r[3]) * kk))


def face_frame(c, face):
    """큐브 c 의 한 면(텍스처 이름 'front' 등): 정규 좌표 (u, v) → 모델 좌표 함수와 바깥 법선."""
    import numpy as np
    dims = [int(round(v)) for v in c.get('uv_dims', c['size'])]
    tmp = dict(origin=list(c['origin']), size=list(c['size']), uv=[0, 0], grow=[0, 0, 0], mirror=False, faces=[], uv_dims=dims)
    r = box_faces(0, 0, *dims)[face]
    for _name, verts, nrm in cube_polys(tmp, 1, 1):
        us = [v[3] for v in verts]
        vs = [v[4] for v in verts]
        if abs(min(us) - r[0]) < 1e-6 and abs(min(vs) - r[1]) < 1e-6 and abs(max(us) - min(us) - r[2]) < 1e-6 and abs(max(vs) - min(vs) - r[3]) < 1e-6:
            def corner(uu, vv):
                for v in verts:
                    if abs(v[3] - uu) < 1e-6 and abs(v[4] - vv) < 1e-6:
                        return np.array(v[:3], dtype=float)
            a, b, cc = corner(min(us), min(vs)), corner(max(us), min(vs)), corner(min(us), max(vs))
            return (lambda un, vn: a + un * (b - a) + vn * (cc - a)), np.array(nrm, dtype=float)
    raise KeyError(face)


def pocket_walls(c, face, box, depth):
    """껍질 면의 구멍(정규 좌표 상자 u0,v0,u1,v1)에서 안쪽으로 depth 만큼 들어가는 벽 4장(두께 0 큐브: origin, size)."""
    import numpy as np
    P, n = face_frame(c, face)
    u0, v0, u1, v1 = box
    A, B, C, D = P(u0, v0), P(u1, v0), P(u1, v1), P(u0, v1)
    out = []
    for e1, e2 in ((A, B), (B, C), (C, D), (D, A)):
        pts = [e1, e2, e1 - n * depth, e2 - n * depth]
        lo = np.min(pts, axis=0)
        hi = np.max(pts, axis=0)
        out.append(([float(t) for t in lo], [float(t) for t in hi - lo]))
    return out


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


def make(models, jar, name, zombified, preview_pose=True):
    """preview_pose=False: 미리보기용 자세(고개 기울임 등)를 빼고 바닐라 기본 자세로(리그 내보내기)."""
    designs4.JAR[0] = jar
    d = DESIGNS[name]()
    data = merged_data(models, d, zombified)
    tex = base_texture(jar, d, data, zombified)
    m = Model(name, data, tex)
    if d.get('outer_tex'):
        m.textures['outer'] = jar_texture(jar, d['outer_tex'])
        if zombified and d.get('outer_fn'):
            d['outer_fn'](m.textures['outer'])
    if d.get('scale'):
        m.root.scale = m.root.scale * d['scale']
    if not zombified:
        return m, d
    for bone, (dx, dy, dz) in (d.get('pose', {}) if preview_pose else {}).items():
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
    jobs = []  # (큐브, 칠하기, 복사할 원종 큐브 목록)
    for bone, idx, painter in d.get('retex', []):
        c = m.root.find(bone).cubes[idx]
        c.update(tex='parts', mirror=False)
        c['uv_dims'] = uv_dims(c['size'])
        jobs.append((c, painter, None))
    for L in d.get('layered', []):
        # 겹 구조: 원종 큐브를 depth 만큼 안으로 줄여 '속'(뼈·장기)으로 쓰고, 원래 자리에 살 껍질을 씌운다.
        # 겉모양(실루엣)은 바닐라 그대로. 껍질 구멍으로 한 겹 안쪽의 뼈가 보인다.
        part_ = m.root.find(L['bone'])
        c = part_.cubes[L.get('idx', 0)]
        dep = L['depth']
        shell = dict(origin=list(c['origin']), size=list(c['size']), grow=[0, 0, 0], mirror=False, uv=[0, 0], texScale=[1, 1],
                     faces=[], tex='parts', added=True, tell=L.get('tell'), glow=False, uv_dims=uv_dims(c['size']), shell=True)
        c['uv_dims'] = [int(round(v)) for v in c['size']]
        c['origin'] = [o + dep for o in c['origin']]
        c['size'] = [max(0.01, v - 2 * dep) for v in c['size']]
        part_.cubes.append(shell)
        jobs.append((shell, L['paint'], [('pre', L['bone'], L.get('idx', 0))]))
        for face, box in L.get('walls', []):
            # 구멍 둘레 벽: 비스듬히 봐도 껍질과 속 사이 틈으로 뒤가 비치지 않게, 찢긴 살의 두께가 보이게
            for o, sz in pocket_walls(shell, face, box, dep):
                w = dict(origin=o, size=sz, grow=[0, 0, 0], mirror=False, uv=[0, 0], texScale=[1, 1], faces=[], tex='parts',
                         added=True, tell=None, glow=False, uv_dims=uv_dims(sz))
                part_.cubes.append(w)
                jobs.append((w, L.get('wall_paint') or L['paint'], None))
    for a in d.get('adds', []):
        c = dict(origin=list(a['origin']), size=list(a['size']), grow=list(a.get('grow') or [0, 0, 0]), mirror=False, uv=[0, 0],
                 texScale=[1, 1], faces=[], tex='parts', added=True, tell=a.get('tell'), glow=a.get('glow', False), name=a.get('name'))
        if a.get('rot'):
            c['rot'] = a['rot']
        c['uv_dims'] = uv_dims(a['size'])
        m.root.find(a['bone']).cubes.append(c)
        cp = a.get('copy')
        if cp and not isinstance(cp, list):
            cp = [cp]
        jobs.append((c, a['paint'], cp))
    if jobs:
        ps = int(d.get('parts_scale', HIRES))  # 덧붙임 텍스처 배율
        sizes = []
        for c, _, _ in jobs:
            w, h, dd = c['uv_dims']
            sizes.append((max(1, 2 * (w + dd)), max(1, dd + h)))
        pos, S = pack(sizes)
        atlas = Image.new('RGBA', (S * ps, S * ps), (0, 0, 0, 0))
        for k, ((c, painter, copy_src), (x, y)) in enumerate(zip(jobs, pos)):
            c['uv'] = [x, y]
            c['tex_size'] = (S, S)
            w, h, dd = c['uv_dims']
            faces = {f: (r[0] * ps, r[1] * ps, r[2] * ps, r[3] * ps) for f, r in box_faces(x, y, w, h, dd).items() if r[2] > 0 and r[3] > 0}
            for src in (copy_src or []):
                # 원종 큐브의 면을 옮겨 온 뒤 다시 칠한다(머리 덮개, 살 껍질). 여러 개면 차례로 겹친다(덧층 합치기)
                if src[0] == 'pre':
                    sc, simg = m.root.find(src[1]).cubes[src[2]], d['_pre']
                else:
                    sc, simg = m.root.find(src[0]).cubes[src[1]], None
                for f, r in faces.items():
                    crop = face_crop(m, sc, f, simg).resize((r[2], r[3]), Image.NEAREST)
                    atlas.alpha_composite(crop, (r[0], r[1]))
            painter(atlas, faces, 1000 + 97 * k)
        m.textures['parts'] = atlas
        m.parts_uv = S
    if d.get('tethers'):
        apply_tethers(m, d['tethers'])
    if preview_pose:
        # 미리보기에서는 평소 숨어 있는 뼈대(집중 때만 펼치는 날개 등)를 감춘다
        for b in d.get('hidden_bones', []):
            for c in m.root.find(b).cubes:
                c['hidden'] = True
    if d.get('overlay_fn'):
        m.textures['overlay'] = humanoid_overlay(d['overlay_fn'], d.get('overlay_size', (64, 32)))
    if d.get('overlay_fn2'):
        # 장비 레이어 텍스처를 바닐라 몸 텍스처(와 덧층)에서 옮겨 그린다(1차 해골, 브루트)
        m.textures['overlay'] = d['overlay_fn2'](m.textures['base'], m.textures.get('outer'))
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
    for key in ('parts', 'overlay', 'outer'):
        if key in m.textures:
            a = m.textures[key]
            tindex[key] = len(textures)
            uvw, uvh = (64, 32) if key in ('overlay', 'outer') else (getattr(m, 'parts_uv', a.width),) * 2
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
    'creaking': '창백한 썩음(크리킹)', 'slime': '늪 찌꺼기(슬라임)', 'magma_cube': '부패 마그마 큐브',
    'phantom': '시체 운반자(팬텀)', 'guardian': '익사체 지휘관(가디언)', 'elder_guardian': '썩은 장로(엘더 가디언)',
    'breeze': '녹슨 바람(브리즈)', 'ghast': '혼불 가스트', 'blaze': '잿불 블레이즈', 'skeleton': '묘지 해골',
    'stray': '동상 해골(스트레이)', 'bogged': '포자 해골(보그드)', 'wither_skeleton': '진홍 해골(위더 스켈레톤)',
    'piglin_brute': '진홍 브루트', 'pillager': '소음 약탈자', 'vindicator': '도끼잡이(변명자)', 'evoker': '강령 소환사',
    'illusioner': '거울 환술사', 'witch': '역병 마녀', 'shulker': '기생 셜커', 'shulker_core': '기생체 핵(숙주 갈아타기)', 'ravager': '썩은 파괴수',
}
METHOD = {'E': '리그', 'B': '장비 덧씌움 + 머리 아이템 (몸 텍스처는 바닐라)', 'C': '머리 아이템 (몸 텍스처는 바닐라)', 'D': '색 변형 텍스처'}


def apply_tethers(m, tethers):
    """줄 뼈대(셜커 기생체 줄): 피벗에서 '줄 끝'(다른 뼈대 기준 점)을 향해 길이 len 막대(-y 방향)를 돌리고 늘린다.
    서버 리그(RigInstance 의 tethers)와 같은 식. 회전은 ZYX 오일러(x = asin(-u.z), z = atan2(u.x, -u.y)).
    줄은 피벗에서 r0 만큼 나간 곳(핵 겉면)에서 시작한다. 원래 피벗은 _pivot0 에 남긴다(리그 정의에는 그 값을 쓴다)."""
    import numpy as np
    for name, t in tethers.items():
        p = m.root.find(name)
        if not hasattr(p, '_pivot0'):
            p._pivot0 = p.offset.copy()
        tgt = m.root.find(t['to']).world() @ np.array(list(t['at']) + [1.0])
        org = p.parent.world() @ np.array(list(p._pivot0) + [1.0])
        dv = (tgt - org)[:3]
        L = float(np.linalg.norm(dv)) or 0.01
        u = dv / L
        r0 = min(t.get('r0', 0.0), L * 0.5)
        p.offset = p._pivot0 + u * r0
        p.rot = np.array([math.asin(max(-1.0, min(1.0, -u[2]))), 0.0, math.atan2(u[0], -u[1])])
        p.scale = np.array([1.0, (L - r0) / t['len'], 1.0])


def inner_posed(m, pose, tethers=None):
    """속을 보여 주는 자세(셜커 뚜껑을 들어 올림, 마그마 큐브 층이 벌어짐): {뼈대: (dx, dy, dz, rx, ry, rz 도)}."""
    import copy as _c
    mm = _c.copy(m)
    mm.root = _c.deepcopy(m.root)
    for bone, (dx, dy, dz, rx, ry, rz) in pose.items():
        p = mm.root.find(bone)
        p.offset = p.offset + [dx, dy, dz]
        p.rot = p.rot + [math.radians(rx), math.radians(ry), math.radians(rz)]
    if tethers:
        apply_tethers(mm, tethers)
    return mm


def preview_sheet(models, jar, names, out_path, cell=300):
    font = ImageFont.truetype(FONT, 22, index=1)
    small = ImageFont.truetype(FONT, 15, index=1)
    W, H = cell * 4, (cell + 58) * len(names)
    sheet = Image.new('RGBA', (W, H), (34, 34, 40, 255))
    dr = ImageDraw.Draw(sheet)
    for r, name in enumerate(names):
        y = r * (cell + 58)
        o, _ = make(models, jar, name, False)
        z, d = make(models, jar, name, True)
        yaw, pitch = d.get('view', (-35, 22))
        tiles = [render(o, size=cell, yaw=yaw, pitch=pitch), render(z, size=cell, yaw=yaw, pitch=pitch), render(z, size=cell, yaw=yaw + 180, pitch=pitch)]
        if d.get('inner_pose'):
            tiles.append(render(inner_posed(z, d['inner_pose'], d.get('tethers')), size=cell, yaw=yaw, pitch=d.get('inner_view_pitch', pitch)))
        for i, t in enumerate(tiles):
            sheet.alpha_composite(t, (i * cell, y + 34))
        hires = '  ·  텍스처 2배' if d.get('hires', 1) > 1 else ''
        inner = ' / 속(열린 모습)' if d.get('inner_pose') else ''
        dr.text((10, y + 4), f'{TITLE.get(name, name)}  ·  {METHOD[d["method"]]}{hires}  —  원종 / 좀비화 앞 / 뒤{inner}', font=font, fill=(235, 235, 235))
        dr.text((10, y + cell + 12), d.get('concept', ''), font=small, fill=(205, 205, 205))
        tell = next((a.get('tell') for a in d.get('adds', []) if a.get('tell')), d.get('tell'))
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
