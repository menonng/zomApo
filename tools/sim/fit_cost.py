#!/usr/bin/env python3
"""tools/perf 실측 결과(result-*.json)를 load_sim.py 의 비용 모델로 적합한다.

모델: y = base + area × (떨어진 무리 수) + player × (같은 무리에 더해진 인원)
최소제곱(정규방정식, 3×3)으로 구한다. 표준 라이브러리만 사용.
python3 fit_cost.py <result-*.json ...> > cost.json
"""
import json
import sys

BASES = 3
# bench.py 의 NATIONS_ORDER 와 같은 순서: B=기지, W=방랑
NATIONS_ORDER = ["B0", "B1", "B2", "W0", "B0", "B1", "W1", "B2", "B0", "W2", "B1", "W3", "B2", "B0", "W4", "B1",
                 "W5", "B2", "B0", "W6", "B1", "W7", "W8"]


def shape(layout, n):
    if layout == "spread":
        return n, 0
    anchors = set(NATIONS_ORDER[:n])
    return len(anchors), n - len(anchors)


def solve3(a, b):
    # 가우스 소거
    m = [row[:] + [b[i]] for i, row in enumerate(a)]
    for c in range(3):
        p = max(range(c, 3), key=lambda r: abs(m[r][c]))
        m[c], m[p] = m[p], m[c]
        for r in range(3):
            if r != c and m[c][c] != 0:
                f = m[r][c] / m[c][c]
                for k in range(c, 4):
                    m[r][k] -= f * m[c][k]
    return [m[i][3] / m[i][i] for i in range(3)]


def fit(points):
    ata = [[0.0] * 3 for _ in range(3)]
    atb = [0.0] * 3
    for (x1, x2), y in points:
        v = (1.0, x1, x2)
        for i in range(3):
            atb[i] += v[i] * y
            for j in range(3):
                ata[i][j] += v[i] * v[j]
    return solve3(ata, atb)


def fit_nonneg(points):
    """계수가 음수로 나오면(봇은 움직이지 않아 같은 무리 인원의 비용이 0 에 가깝다) 0 으로 두고 다시 적합한다.
    배치가 한 가지뿐이라 무리 수와 인원을 가를 수 없으면 인원 항을 0 으로 두고 2변수로 맞춘다."""
    try:
        b = fit(points)
    except ZeroDivisionError:
        b = [0.0, 0.0, -1.0]
    if b[2] < 0:
        # player 항을 0 으로 고정: y = base + area·x1 (2변수 최소제곱)
        n = len(points)
        sx = sum(x[0] for x, _ in points)
        sy = sum(y for _, y in points)
        sxx = sum(x[0] * x[0] for x, _ in points)
        sxy = sum(x[0] * y for x, y in points)
        den = n * sxx - sx * sx
        if den == 0:
            raise ValueError("측정점이 모자라다(봇 수 단계를 2개 이상, 배치를 2가지 이상)")
        area = (n * sxy - sx * sy) / den
        b = [(sy - area * sx) / n, area, 0.0]
    if b[0] < 0:
        b[0] = 0.0
    return b


def main():
    groups = {}
    for path in sys.argv[1:]:
        with open(path) as f:
            r = json.load(f)
        if r["mobs"] != "x2" or r["view"] != 10 or r.get("bluemap"):
            continue
        for row in r["rows"]:
            if row.get("live_heap_mb") is None or "mspt_med" not in row:
                continue
            groups.setdefault(r["mods"], []).append((shape(r["layout"], row["players"]), row))
    out = {}
    for mods, pts in groups.items():
        if len(pts) < 3:
            print(f"# {mods}: 측정점 {len(pts)}개라 건너뜀", file=sys.stderr)
            continue
        h = fit_nonneg([(x, row["live_heap_mb"]) for x, row in pts])
        med = fit_nonneg([(x, row["mspt_med"]) for x, row in pts])
        p95 = fit_nonneg([(x, row["mspt_p95"]) for x, row in pts])
        out[mods] = dict(heap_base=round(h[0]), heap_area=round(h[1], 1), heap_player=round(h[2], 1),
                         mspt_base=round(med[0], 2), mspt_area=round(med[1], 2), mspt_player=round(med[2], 2),
                         p95_base=round(p95[0], 2), p95_area=round(p95[1], 2), p95_player=round(p95[2], 2))
    json.dump(out, sys.stdout, indent=1)
    print()


if __name__ == "__main__":
    main()
