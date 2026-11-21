"""좀비화 몹 모델 도구의 바탕: 바닐라 모델 형상 읽기, 다각형 만들기, 미리보기 렌더.

형상은 ModelDump.java 가 실행하는 사람의 마인크래프트 jar 에서 뽑은 models.json 을 쓴다(저장소에 넣지 않음).
좌표는 모델 픽셀 단위. 바닐라 모델 공간(y 아래)에서 다각형을 만들고, 세계(블록벤치) 공간 (-x, 24-y, z) 로 옮긴다.
"""
import io
import json
import math
import zipfile

import numpy as np
from PIL import Image, ImageDraw


def rot_zyx(xr, yr, zr):
    cx, sx = math.cos(xr), math.sin(xr)
    cy, sy = math.cos(yr), math.sin(yr)
    cz, sz = math.cos(zr), math.sin(zr)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return rz @ ry @ rx


class Part:
    def __init__(self, d, parent=None):
        self.name = d['name']
        p = d['pose']
        self.offset = np.array(p[0:3], dtype=float)
        self.rot = np.array(p[3:6], dtype=float)
        self.scale = np.array(p[6:9], dtype=float)
        self.cubes = [dict(c) for c in d['cubes']]
        self.parent = parent
        self.children = [Part(c, self) for c in d['children']]

    def walk(self):
        yield self
        for c in self.children:
            yield from c.walk()

    def find(self, name):
        for p in self.walk():
            if p.name == name:
                return p
        raise KeyError(name)

    def matrix(self):
        """모델 공간(픽셀)에서 이 뼈대 좌표를 부모 기준으로 옮기는 4×4."""
        m = np.eye(4)
        m[:3, :3] = rot_zyx(*self.rot) @ np.diag(self.scale)
        m[:3, 3] = self.offset
        return m

    def world(self):
        m = self.matrix()
        p = self.parent
        while p is not None:
            m = p.matrix() @ m
            p = p.parent
        return m


class Model:
    def __init__(self, key, data, texture, extra_textures=None):
        self.key = key
        self.tex_size = data['tex']
        self.root = Part(data['root'])
        self.texture = texture.convert('RGBA')
        self.textures = {'base': self.texture}
        if extra_textures:
            self.textures.update(extra_textures)


def load_models(path):
    return json.load(open(path))


def jar_texture(jar, rel):
    with zipfile.ZipFile(jar) as z:
        return Image.open(io.BytesIO(z.read('assets/minecraft/textures/entity/' + rel))).convert('RGBA')


def cube_polys(c, tw, th):
    """ModelPart.Cube 와 같은 방식. 반환: [(면 이름, [(x,y,z,u,v)*4])], u·v 는 0~1."""
    x, y, z = c['origin']
    w, h, d = c['size']
    uw, uh, ud = c.get('uv_dims', c['size'])
    gx, gy, gz = c.get('grow', [0, 0, 0])
    u, v = c['uv']
    ts = c.get('texScale', [1, 1])
    TW, TH = tw * ts[0], th * ts[1]
    f, f1, f2, f3, f4, f5 = x - gx, y - gy, z - gz, x + w + gx, y + h + gy, z + d + gz
    mirror = c.get('mirror', False)
    if mirror:
        f, f3 = f3, f
    V = [(f, f1, f2), (f3, f1, f2), (f3, f4, f2), (f, f4, f2), (f, f1, f5), (f3, f1, f5), (f3, f4, f5), (f, f4, f5)]
    f7, f8, f9, f10, f11, f12 = u, u + ud, u + ud + uw, u + ud + uw + uw, u + ud + uw + ud, u + ud + uw + ud + uw
    f13, f14, f15 = v, v + ud, v + ud + uh
    spec = [
        ('DOWN', [5, 4, 0, 1], (f8, f13, f9, f14)),
        ('UP', [2, 3, 7, 6], (f9, f14, f10, f13)),
        ('WEST', [0, 4, 7, 3], (f7, f14, f8, f15)),
        ('NORTH', [1, 0, 3, 2], (f8, f14, f9, f15)),
        ('EAST', [5, 1, 2, 6], (f9, f14, f11, f15)),
        ('SOUTH', [4, 5, 6, 7], (f11, f14, f12, f15)),
    ]
    faces = set(f.upper() for f in (c.get('faces') or ['DOWN', 'UP', 'WEST', 'NORTH', 'EAST', 'SOUTH']))
    out = []
    for name, idx, (u1, v1, u2, v2) in spec:
        if name not in faces:
            continue
        uvs = [(u2, v1), (u1, v1), (u1, v2), (u2, v2)]
        verts = [V[i] + (uvs[k][0] / TW, uvs[k][1] / TH) for k, i in enumerate(idx)]
        if mirror:
            verts = verts[::-1]
        out.append((name, verts))
    return out


def world_faces(model):
    """모든 면을 세계 좌표로. [(점 4개(3D), uv 4개, 텍스처 이름, 중심, 법선)]"""
    tw, th = model.tex_size
    out = []
    for part in model.root.walk():
        m = part.world()
        for c in part.cubes:
            if c.get('hidden'):
                continue
            ox, oy, oz = c['origin']
            w, h, d = c['size']
            cc = m @ np.array([ox + w / 2, oy + h / 2, oz + d / 2, 1.0])
            ctr_cube = np.array([-cc[0], 24 - cc[1], cc[2]])
            ctw, cth = (c['tex_size'] if 'tex_size' in c else (tw, th))
            for name, verts in cube_polys(c, ctw, cth):
                pts = []
                uvs = []
                for (x, y, z, u, v) in verts:
                    p = m @ np.array([x, y, z, 1.0])
                    pts.append(np.array([-p[0], 24 - p[1], p[2]]))
                    uvs.append((u, v))
                ctr = sum(pts) / 4
                n = ctr - ctr_cube
                ln = np.linalg.norm(n)
                n = n / ln if ln > 1e-9 else np.array([0, 1.0, 0])
                out.append((pts, uvs, c.get('tex', 'base'), ctr, n))
    return out


def render(model, size=480, yaw=-35.0, pitch=22.0, scale=None, bg=(0, 0, 0, 0), ss=2):
    """정사영 미리보기. 모델 앞은 북쪽(-z). yaw 음수면 모델의 오른쪽 앞에서 본다."""
    faces = world_faces(model)
    ry, rp = math.radians(yaw), math.radians(pitch)
    R = np.array([[1, 0, 0], [0, math.cos(rp), -math.sin(rp)], [0, math.sin(rp), math.cos(rp)]]) @ \
        np.array([[math.cos(ry), 0, math.sin(ry)], [0, 1, 0], [-math.sin(ry), 0, math.cos(ry)]])
    proj = []
    allpts = []
    for pts, uvs, tex, ctr, n in faces:
        q = [R @ p for p in pts]
        nn = R @ n
        if nn[2] >= -1e-6:  # 카메라는 +z 를 본다. 법선이 카메라 쪽(-z)이어야 보인다
            continue
        allpts += q
        proj.append((q, uvs, tex, (R @ ctr)[2], n))
    if not allpts:
        return Image.new('RGBA', (size, size), bg)
    xs = [-p[0] for p in allpts]
    ys = [-p[1] for p in allpts]
    W = size * ss
    span = max(max(xs) - min(xs), max(ys) - min(ys))
    k = (scale or (W * 0.82 / span))
    cx, cy = (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2
    canvas = Image.new('RGBA', (W, W), bg)

    def to_screen(p):
        return ((-p[0] - cx) * k + W / 2, (-p[1] - cy) * k + W / 2)

    proj.sort(key=lambda t: -t[3])
    for q, uvs, tex, depth, n in proj:
        img = model.textures[tex]
        iw, ih = img.size
        s = [to_screen(p) for p in q]
        t = [(u * iw, v * ih) for (u, v) in uvs]
        # 화면 → 텍스처 아핀(점 0,1,3)
        A = np.array([[s[0][0], s[0][1], 1], [s[1][0], s[1][1], 1], [s[3][0], s[3][1], 1]])
        if abs(np.linalg.det(A)) < 1e-6:
            continue
        bx = np.linalg.solve(A, np.array([t[0][0], t[1][0], t[3][0]]))
        by = np.linalg.solve(A, np.array([t[0][1], t[1][1], t[3][1]]))
        x0, y0 = int(min(p[0] for p in s)) - 1, int(min(p[1] for p in s)) - 1
        x1, y1 = int(max(p[0] for p in s)) + 2, int(max(p[1] for p in s)) + 2
        x0, y0 = max(0, x0), max(0, y0)
        x1, y1 = min(W, x1), min(W, y1)
        if x1 <= x0 or y1 <= y0:
            continue
        bw, bh = x1 - x0, y1 - y0
        coeff = (bx[0], bx[1], bx[0] * x0 + bx[1] * y0 + bx[2], by[0], by[1], by[0] * x0 + by[1] * y0 + by[2])
        # 텍스처 경계 밖을 투명으로: 면이 쓰는 uv 사각형만 남긴다
        umin, umax = min(a for a, b in t), max(a for a, b in t)
        vmin, vmax = min(b for a, b in t), max(b for a, b in t)
        src = Image.new('RGBA', img.size, (0, 0, 0, 0))
        box = (int(round(umin)), int(round(vmin)), int(round(umax)), int(round(vmax)))
        if box[2] > box[0] and box[3] > box[1]:
            src.paste(img.crop(box), box[:2])
        tile = src.transform((bw, bh), Image.AFFINE, coeff, resample=Image.NEAREST)
        # 그림자: 면 방향에 따라 밝기
        shade = 0.55 + 0.45 * max(0.0, n[1]) + 0.25 * abs(n[2]) + 0.12 * abs(n[0])
        shade = min(1.0, shade if n[1] >= 0 else 0.5)
        arr = np.array(tile, dtype=np.float32)
        arr[..., :3] *= shade
        tile = Image.fromarray(arr.clip(0, 255).astype(np.uint8), 'RGBA')
        mask = Image.new('L', (bw, bh), 0)
        ImageDraw.Draw(mask).polygon([(p[0] - x0, p[1] - y0) for p in s], fill=255, outline=255)
        a = np.minimum(np.array(mask), np.array(tile)[..., 3])
        tile.putalpha(Image.fromarray(a.astype(np.uint8)))
        canvas.alpha_composite(tile, (x0, y0))
    return canvas.resize((size, size), Image.LANCZOS) if ss > 1 else canvas
