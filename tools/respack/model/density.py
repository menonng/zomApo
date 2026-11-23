"""픽셀 밀도 절충안(2026-11-23 운영자 결정).

- 텍스처 크기·UV 는 2배 그대로 두고, 그림을 바닐라 밀도(모델 1칸 = 1픽셀)로 보이게 2×2 씩 한 색으로 묶는다.
- 예외로 2배를 남기는 곳: 겹 구조의 안쪽 큐브(구멍 속 두개골·장기), 구멍 둘레 안쪽 벽, 껍질 구멍 테두리 2픽셀.
  움푹 들어간 그늘 속이라 픽셀이 더 잘아도 '속 디테일'로 읽힌다. 바깥 피부와 덧붙임은 바닐라와 결이 같아진다.
- 묶는 규칙: 투명 3칸 이상이면 투명(구멍이 커지지 않게), 아니면 최빈색(같으면 가장 어두운 색). 바탕과 크게 다른 색(핏줄·균열·이빨)이
  한 칸이라도 있으면 그 색을 남기고(선이 끊기지 않게), 그 결과 생기는 계단(ㄱ자 모서리)은 한 번 깎아 대각선 한 줄로 만든다.
- 안쪽 큐브는 구멍 뒤가 비치지 않게 투명 픽셀을 모두 어두운 속살 색으로 채운다(데이터를 아끼려 비워 두지 않는다).
"""
import numpy as np
from PIL import Image

from paint import box_faces

CAVITY = (22, 7, 6, 255)
FEATURE_DIST = 90.0


def _lum(c):
    return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]


def _dist(a, b):
    return float(np.linalg.norm(np.array(a[:3], float) - np.array(b[:3], float)))


def _pick(px):
    """2×2 → (색, 바탕색, 선인가)."""
    op = [tuple(int(v) for v in p) for p in px if p[3] >= 128]
    if sum(1 for p in px if p[3] < 128) >= 3 or not op:
        return (0, 0, 0, 0), (0, 0, 0, 0), False
    cnt = {}
    for p in op:
        cnt[p] = cnt.get(p, 0) + 1
    best = max(cnt.values())
    maj = min([c for c, n in cnt.items() if n == best], key=_lum)
    feats = [c for c in cnt if _dist(c, maj) > FEATURE_DIST]
    if feats:
        return max(feats, key=lambda c: _dist(c, maj)), maj, True
    return maj, maj, False


def _dilate(m, it):
    m = m.copy()
    for _ in range(it):
        n = m.copy()
        n[1:, :] |= m[:-1, :]; n[:-1, :] |= m[1:, :]; n[:, 1:] |= m[:, :-1]; n[:, :-1] |= m[:, 1:]
        m = n
    return m


def convert(img, keep=None):
    """2배 텍스처를 1배로 보이게(같은 크기). keep(픽셀 불리언) 이 닿는 2×2 는 그대로 둔다."""
    a = np.array(img.convert('RGBA'))
    h, w = a.shape[:2]
    H, W = h // 2, w // 2
    col = np.zeros((H, W, 4), np.uint8)
    base = np.zeros((H, W, 4), np.uint8)
    feat = np.zeros((H, W), bool)
    hold = np.zeros((H, W), bool)
    for y in range(H):
        for x in range(W):
            if keep is not None and keep[2 * y:2 * y + 2, 2 * x:2 * x + 2].any():
                hold[y, x] = True
                continue
            c, b, f = _pick(a[2 * y:2 * y + 2, 2 * x:2 * x + 2].reshape(-1, 4))
            col[y, x], base[y, x], feat[y, x] = c, b, f
    # 계단 깎기: 선 픽셀이 가로 이웃 하나·세로 이웃 하나와만 이어진 ㄱ자 모서리면 바탕색으로(대각선으로 이어진다)
    for y in range(1, H - 1):
        for x in range(1, W - 1):
            if not feat[y, x] or hold[y, x]:
                continue
            same = lambda yy, xx: feat[yy, xx] and _dist(col[yy, xx], col[y, x]) < 40
            hz = [same(y, x - 1), same(y, x + 1)]
            vt = [same(y - 1, x), same(y + 1, x)]
            if sum(hz) == 1 and sum(vt) == 1:
                dy = -1 if vt[0] else 1
                dx = -1 if hz[0] else 1
                if not same(y + dy, x + dx):           # 대각선 자리가 비어 있으면 모서리만 깎는다
                    n8 = sum(same(y + j, x + i) for j in (-1, 0, 1) for i in (-1, 0, 1) if (i or j))
                    if n8 == 2:
                        col[y, x] = base[y, x]
                        feat[y, x] = False
    out = a.copy()
    for y in range(H):
        for x in range(W):
            if not hold[y, x]:
                out[2 * y:2 * y + 2, 2 * x:2 * x + 2] = col[y, x]
    return Image.fromarray(out)


def _face_rects(c, scale):
    w, h, dd = c['uv_dims']
    u, v = c['uv']
    for f, (x, y, fw, fh) in box_faces(u, v, w, h, dd).items():
        if fw > 0 and fh > 0:
            yield f, (int(x * scale), int(y * scale), int((x + fw) * scale), int((y + fh) * scale))


def apply(m, d, pocket_walls):
    """build.make 끝에서 부른다(좀비화 모델만). 겹 구조 안쪽을 불투명으로 채우고 절충안 변환을 한다."""
    base = m.textures['base']
    kb = base.width // m.tex_size[0]
    parts = m.textures.get('parts')
    ps = (parts.width // m.parts_uv) if parts is not None else 1
    keep_b = np.zeros((base.height, base.width), bool)
    keep_p = np.zeros((parts.height, parts.width), bool) if parts is not None else None
    ba = np.array(base.convert('RGBA'))
    pa = np.array(parts) if parts is not None else None
    for L in d.get('layered', []):
        part = m.root.find(L['bone'])
        inner = part.cubes[L.get('idx', 0)]
        for _, (x0, y0, x1, y1) in _face_rects(inner, kb):
            region = ba[y0:y1, x0:x1]
            region[region[:, :, 3] < 255] = CAVITY            # 안쪽 큐브는 비워 두지 않는다
            keep_b[y0:y1, x0:x1] = True
        shells = [i for i, c in enumerate(part.cubes) if c.get('shell')]
        if not shells or pa is None:
            continue
        si = shells[0]
        shell = part.cubes[si]
        nwalls = sum(len(pocket_walls(shell, face, box, L['depth'])) for face, box in L.get('walls', []))
        for c in part.cubes[si + 1:si + 1 + nwalls]:
            for _, (x0, y0, x1, y1) in _face_rects(c, ps):
                keep_p[y0:y1, x0:x1] = True
        holes = L.get('holes', {})
        for face, (x0, y0, x1, y1) in _face_rects(shell, ps):
            fw, fh = x1 - x0, y1 - y0
            sub = pa[y0:y1, x0:x1]
            # 구멍 상자 밖의 투명 픽셀(원종 텍스처에서 옮겨 온 것)은 가장 가까운 살색으로 메운다: 껍질 뒤 빈틈으로 비치지 않게
            boxes = [hh.box() for hh in holes.get(face, [])]
            ys, xs = np.where(sub[:, :, 3] < 128)
            for x, y in zip(xs, ys):
                u, v = (x + 0.5) / fw, (y + 0.5) / fh
                if any(b[0] <= u <= b[2] and b[1] <= v <= b[3] for b in boxes):
                    continue
                best = None
                for rad in range(1, max(fw, fh)):
                    for dy in range(-rad, rad + 1):
                        for dx in range(-rad, rad + 1):
                            qx, qy = x + dx, y + dy
                            if 0 <= qx < fw and 0 <= qy < fh and sub[qy, qx, 3] == 255:
                                best = sub[qy, qx].copy(); break
                        if best is not None: break
                    if best is not None: break
                sub[y, x] = best if best is not None else CAVITY
            hole = sub[:, :, 3] < 128
            if hole.any():
                keep_p[y0:y1, x0:x1] |= _dilate(hole, 2) & ~hole
    base = Image.fromarray(ba)
    if kb >= 2:
        base = convert(base, keep_b)
    m.textures['base'] = base
    m.texture = base
    if parts is not None:
        parts = Image.fromarray(pa)
        m.textures['parts'] = convert(parts, keep_p) if ps >= 2 else parts
    ov = m.textures.get('overlay')
    if ov is not None and ov.width >= 128:
        m.textures['overlay'] = convert(ov)
    return m


def check_layered(m, d, pocket_walls, holes_of):
    """비침 검사(빌드를 멈춘다): 안쪽 큐브·구멍 둘레 벽은 전부 불투명, 껍질의 투명 픽셀은 벽 상자 안에만.
    holes_of(L) → {면: [Hole]}."""
    base = np.array(m.textures['base'].convert('RGBA'))
    kb = m.textures['base'].width // m.tex_size[0]
    parts = m.textures.get('parts')
    pa = np.array(parts) if parts is not None else None
    ps = (parts.width // m.parts_uv) if parts is not None else 1
    errs = []
    for L in d.get('layered', []):
        part = m.root.find(L['bone'])
        inner = part.cubes[L.get('idx', 0)]
        for f, (x0, y0, x1, y1) in _face_rects(inner, kb):
            if (base[y0:y1, x0:x1, 3] < 255).any():
                errs.append(f"{L['bone']} 안쪽 큐브 {f} 면에 투명 픽셀")
        shells = [i for i, c in enumerate(part.cubes) if c.get('shell')]
        if not shells or pa is None:
            continue
        si = shells[0]
        shell = part.cubes[si]
        nwalls = sum(len(pocket_walls(shell, face, box, L['depth'])) for face, box in L.get('walls', []))
        for c in part.cubes[si + 1:si + 1 + nwalls]:
            for f, (x0, y0, x1, y1) in _face_rects(c, ps):
                if (pa[y0:y1, x0:x1, 3] < 255).any():
                    errs.append(f"{L['bone']} 구멍 벽 {f} 면에 투명 픽셀")
        holes = holes_of(L)
        rects = dict(_face_rects(shell, ps))
        for face, hs in holes.items():
            r = rects.get(face)
            if not r:
                continue
            x0, y0, x1, y1 = r
            w, h = x1 - x0, y1 - y0
            ys, xs = np.where(pa[y0:y1, x0:x1, 3] < 128)
            for x, y in zip(xs, ys):
                u, v = (x + 0.5) / w, (y + 0.5) / h
                if not any(bx0 - 0.5 / w <= u <= bx1 + 0.5 / w and by0 - 0.5 / h <= v <= by1 + 0.5 / h for bx0, by0, bx1, by1 in (hh.box() for hh in hs)):
                    errs.append(f"{L['bone']} 껍질 {face} 면 구멍이 벽 상자 밖({x},{y})")
                    break
    if errs:
        raise RuntimeError('비침 검사 실패: ' + '; '.join(errs[:6]))
