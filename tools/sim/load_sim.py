#!/usr/bin/env python3
"""서버 부하 시뮬레이터: 인원 구성 → 시간대별 동시 접속 → 흩어진 정도 → 메모리·틱 시간.

zomapo_sim.py 의 접속 모델(유형별 접속 확률·세션 길이·시작 시각, 설 연휴)을 그대로 쓰고,
접속자가 몇 개의 '떨어진 무리'로 나뉘는지(로드되는 영역 수)를 더한다.
무리 하나의 비용(힙·MSPT)은 26.3 실측값(tools/perf/results.md)을 넣는다.

표준 라이브러리만 사용. python3 load_sim.py --runs 300
"""
import argparse
import math
import random
import statistics
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from zomapo_sim import ARCH, DAYS, SEOLLAL_DAYS, SEOLLAL_FACTOR, weekday  # noqa: E402

# 무리 모델: 접속한 국민은 확률 P_BASE 로 자국 기지(국가당 한 무리)에 있고,
# 아니면 바깥을 돌아다닌다. 돌아다니는 사람은 같은 국가 접속자와 P_PAIR 로 같이 다닌다.
P_BASE = 0.55
P_PAIR = 0.35


def composition(n: int, active: int) -> list[str]:
    rest = n - active
    regular = round(rest * 0.4)
    light = round(rest * 0.35)
    ghost = rest - regular - light
    return ["core"] * active + ["regular"] * regular + ["light"] * light + ["ghost"] * ghost


def sessions_for_day(rng, players, d):
    wd = weekday(d)
    weekend = wd >= 5
    hours = {h: [] for h in range(10, 27)}
    for i, (arch, student, nation) in enumerate(players):
        a = ARCH[arch]
        vac = student and not weekend
        prob = a["p_we"] if weekend else ((a["p_wd"] + a["p_we"]) / 2 if vac else a["p_wd"])
        prob *= 1.0 + 0.4 * math.exp(-d / 7.0)
        if a["fade"]:
            prob *= math.exp(-d / a["fade"]) + 0.03
        if d in SEOLLAL_DAYS:
            prob *= SEOLLAL_FACTOR
        if rng.random() >= min(0.98, prob):
            continue
        mu = a["hours"] * (1.2 if wd == 4 or weekend else 1.0)
        if weekend or vac:
            start = rng.gauss(17.0, 3.5)
        else:
            start = rng.gauss(21.0 if arch == "core" else 21.5, 1.8 if arch == "core" else 1.5)
        s = int(round(min(25.0, max(10.0, start))))
        length = max(1, int(round(rng.gauss(mu, a["sd"]))))
        for h in range(s, min(27, s + length)):
            hours[h].append(i)
    return hours


def clusters(rng, online, players):
    """접속자 목록 → 떨어진 무리들의 인원 리스트."""
    groups = []
    for n in range(3):
        mates = [i for i in online if players[i][2] == n]
        at_base = [i for i in mates if rng.random() < P_BASE]
        out = [i for i in mates if i not in at_base]
        if at_base:
            groups.append(len(at_base))
        while out:
            size = 1
            out.pop()
            while out and rng.random() < P_PAIR:
                out.pop()
                size += 1
            groups.append(size)
    return groups


def simulate(n, active, seed, student_frac=0.6):
    rng = random.Random(seed)
    arches = composition(n, active)
    order = list(range(n))
    rng.shuffle(order)
    players = [(arches[i], rng.random() < student_frac, order.index(i) % 3) for i in range(n)]
    samples = []  # (online, groups)
    for d in range(DAYS):
        for h, online in sessions_for_day(rng, players, d).items():
            if online:
                samples.append((len(online), clusters(rng, online, players)))
            else:
                samples.append((0, []))
    return samples


def pct(xs, q):
    xs = sorted(xs)
    if not xs:
        return 0
    k = (len(xs) - 1) * q
    f = math.floor(k)
    c = min(len(xs) - 1, f + 1)
    return xs[f] + (xs[c] - xs[f]) * (k - f)


# ---- 비용 모델 (26.3 실측 적합값으로 덮어쓴다: fit_cost.py → --cost) ----
COST = {
    # heap_*: 살아 있는 힙(MB) = base + area × 무리 수 + player × (무리에 더해진 인원)
    # mspt_*: 틱 시간 중앙값(ms, 측정 컨테이너 CPU), p95_*: 95백분위
    "base": dict(heap_base=900, heap_area=180, heap_player=25, mspt_base=1.5, mspt_area=4.0, mspt_player=0.6,
                 p95_base=3, p95_area=6, p95_player=1),
    "opt": dict(heap_base=800, heap_area=120, heap_player=20, mspt_base=1.2, mspt_area=2.6, mspt_player=0.4,
                p95_base=2.5, p95_area=4, p95_player=0.7),
}

# 봇(가만히 서서 좀비에게 맞기만 함) → 실제 플레이어(이동·청크 로드·전투·건축) 보정. 가정값.
REAL_FACTOR = 1.5
# CPU 단일 코어 성능, 측정 컨테이너(클라우드 Xeon 2.1GHz vCPU) = 1.0 기준의 대략적 배율. 가정값.
CPU_TIERS = [
    ("구형 노트북·저전력 (예: 8세대 i5 U)", 0.9),
    ("측정 컨테이너 (클라우드 Xeon 2.1GHz)", 1.0),
    ("보급형 데스크톱 (예: Ryzen 5 3600, i5-10400)", 1.4),
    ("최근 데스크톱 (예: Ryzen 5 5600·7600, i5-12400~13400)", 1.8),
    ("고성능 데스크톱 (예: Ryzen 7 7800X3D, i7-13700 이상)", 2.3),
]
FOLIA_OVERHEAD = 1.15   # 지역 간 동기화·스케줄링 비용 가정
FOLIA_GLOBAL_MS = 0.5   # 전역 틱(시간·날씨·게임규칙)


def load(groups, c, key="mspt"):
    areas = len(groups)
    extra = sum(g - 1 for g in groups)
    heap = c["heap_base"] + c["heap_area"] * areas + c["heap_player"] * extra
    mspt = c[key + "_base"] + c[key + "_area"] * areas + c[key + "_player"] * extra
    return heap, mspt


def folia_mspt(groups, c, key="mspt"):
    """무리마다 독립 지역이라고 보고, 가장 무거운 지역의 틱 시간."""
    if not groups:
        return 0.0
    worst = max(c[key + "_area"] + c[key + "_player"] * (g - 1) for g in groups)
    return FOLIA_GLOBAL_MS + (c[key + "_base"] * 0.3 + worst) * FOLIA_OVERHEAD


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=300)
    ap.add_argument("--seed", type=int, default=20270116)
    ap.add_argument("--cost", type=str, default="", help="비용 모델 JSON 파일(실측 적합 결과)")
    ap.add_argument("--p-base", type=float, default=P_BASE, help="접속 중 자국 기지에 있을 확률")
    ap.add_argument("--p-pair", type=float, default=P_PAIR, help="밖에 나간 사람이 같은 국가 접속자와 같이 다닐 확률")
    args = ap.parse_args()
    globals()["P_BASE"], globals()["P_PAIR"] = args.p_base, args.p_pair
    if args.cost:
        import json
        with open(args.cost) as f:
            COST.update(json.load(f))

    print("# 서버 부하 시뮬레이션\n")
    print(f"runs={args.runs}, seed={args.seed}, 시즌 {DAYS}일, 10~26시(새벽 2시) 매시 표본. 무리 모델 P_BASE={P_BASE}, P_PAIR={P_PAIR}\n")
    print("## 1. 동시 접속과 떨어진 무리 수\n")
    print("| 전체 | 활성 | 동시접속 평균(19~24시) | p95 | p99 | 최대 | 무리 수 p95 | 무리 수 최대 | 혼자 접속 시간 비율 |")
    print("|---|---|---|---|---|---|---|---|---|")
    scen = [(17, 8), (20, 8), (20, 10), (23, 10), (23, 12), (20, 13), (15, 4)]
    store = {}
    for n, a in scen:
        allS = []
        for r in range(args.runs):
            allS += simulate(n, a, args.seed + r * 7919)
        store[(n, a)] = allS
        prime = [o for i, (o, g) in enumerate(allS) if 19 <= (10 + i % 17) <= 24]
        onl = [o for o, g in allS if o > 0]
        areas = [len(g) for o, g in allS]
        alone = sum(1 for o, g in allS if o == 1) / max(1, len(onl))
        print(f"| {n} | {a} | {statistics.fmean(prime):.1f} | {pct([o for o, _ in allS], 0.95):.0f} | {pct([o for o, _ in allS], 0.99):.0f} | "
              f"{max(o for o, _ in allS)} | {pct(areas, 0.95):.0f} | {max(areas)} | {alone:.0%} |")

    print("\n## 2. 힙과 틱 시간 (봇 실측 기준, 측정 컨테이너 CPU)\n")
    print("| 전체/활성 | 구성 | 살아 있는 힙 p99 (MB) | 최대 | MSPT 중앙값 p50 | p99 | 최대 | MSPT 95백분위 p99 |")
    print("|---|---|---|---|---|---|---|---|")
    for (n, a), allS in store.items():
        for k, c in COST.items():
            hs, ms, ps = [], [], []
            for o, g in allS:
                if o == 0:
                    continue
                h, m = load(g, c)
                hs.append(h)
                ms.append(m)
                ps.append(load(g, c, "p95")[1])
            print(f"| {n}/{a} | {k} | {pct(hs, 0.99):.0f} | {max(hs):.0f} | {pct(ms, 0.5):.1f} | {pct(ms, 0.99):.1f} | {max(ms):.1f} | {pct(ps, 0.99):.1f} |")

    print(f"\n## 3. CPU 등급별 예상 (실제 플레이 보정 ×{REAL_FACTOR}, 20/10 기준)\n")
    print("MSPT 50ms 를 넘으면 TPS 가 20 아래로 떨어진다. '느려지는 시간'은 접속 중인 시간 가운데 95백분위 틱이 50ms 를 넘는 비율.\n")
    print("| CPU | 구성 | MSPT 중앙값 p99 | 95백분위 p99 | 최대(동시 최대 순간) | 느려지는 시간 |")
    print("|---|---|---|---|---|---|")
    allS = store[(20, 10)]
    for label, f in CPU_TIERS:
        for k, c in COST.items():
            ms, ps = [], []
            for o, g in allS:
                if o == 0:
                    continue
                ms.append(load(g, c)[1] * REAL_FACTOR / f)
                ps.append(load(g, c, "p95")[1] * REAL_FACTOR / f)
            slow = sum(1 for p in ps if p > 50) / len(ps)
            print(f"| {label} | {k} | {pct(ms, 0.99):.1f} | {pct(ps, 0.99):.1f} | {max(ps):.1f} | {slow:.2%} |")

    print(f"\n## 4. Folia 가정 비교 (20/10, 측정 컨테이너 CPU, 실제 플레이 보정 ×{REAL_FACTOR})\n")
    print(f"무리마다 독립 지역(스레드)이 된다고 가장 유리하게 가정. 지역 간 비용 ×{FOLIA_OVERHEAD}, 전역 틱 {FOLIA_GLOBAL_MS}ms.\n")
    print("| 구성 | Fabric 95백분위 p99 | Folia 최악 지역 95백분위 p99 | Fabric 이 50ms 넘는 시간 | Folia 가 줄여 주는 시간 |")
    print("|---|---|---|---|---|")
    for k, c in COST.items():
        fa, fo = [], []
        for o, g in allS:
            if o == 0:
                continue
            fa.append(load(g, c, "p95")[1] * REAL_FACTOR)
            fo.append(folia_mspt(g, c, "p95") * REAL_FACTOR)
        over_fa = sum(1 for x in fa if x > 50) / len(fa)
        over_fo = sum(1 for x in fo if x > 50) / len(fo)
        print(f"| {k} | {pct(fa, 0.99):.1f} | {pct(fo, 0.99):.1f} | {over_fa:.2%} | {over_fa - over_fo:.2%}p |")

if __name__ == "__main__":
    main()
