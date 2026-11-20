#!/usr/bin/env python3
"""국가(팀) 편성 방식 비교 시뮬레이터.

비교하는 방식
  self      자유 선택: 친구 무리끼리 국가를 고른다(정원 = ceil(N/3)+1). 강한 쪽으로 쏠리는 경향 포함
  random    완전 랜덤
  draft     팀장 드래프트: 팀장 3명이 스네이크 순서로 직접 뽑는다(친구 우선 경향 포함)
  user      제안안: 팀장 먼저, 이후 팀 평균 별점을 전체 평균 쪽으로 당기는 사람일수록 뽑힐 확률이 높은
            가중 랜덤을 팀마다 돌아가며 한 번에 뽑는다(가중치 ∝ 점수 격차)
  user_a    제안안 + 참여도: 점수 = 별점과 예상 활동 시간을 반반 섞은 '전력 점수'
  user_ap   제안안 + 참여도 + 단짝: 서로 지목한 단짝은 한 묶음으로 뽑는다
  user_apg  위 + 재추첨 가드: 핵심 유저 수 격차가 2 이상이거나 전력 격차가 크면 자동으로 다시 뽑는다
  optimal   최적화(현재 문서안): 목적함수 최소 상위 3안 중 하나

지표: 별점 평균 격차, 활동량 격차, 핵심 유저 격차, 단짝 유지율, 시즌 중 '같은 국가 사람과 함께 접속한 시간' 비율.
표준 라이브러리만 사용. python3 team_sim.py --runs 2000
"""
import argparse
import math
import random
import statistics
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from zomapo_sim import ARCH, DAYS, SEOLLAL_DAYS, SEOLLAL_FACTOR, composition, weekday  # noqa: E402

EPS = 0.08            # 가중치 바닥: 격차를 줄이지 않는 사람도 뽑힐 수 있게
GUARD_CORE = 2        # 핵심 유저 수 격차가 이 이상이면 재추첨
GUARD_POWER = 0.18    # 국가 활동량 합 격차(최대-최소)/평균 이 이 이상이면 재추첨
FRIEND_BOOST = 1.3    # 단짝이 오늘 접속하면 나도 접속할 확률 배율
FRIEND_SYNC = 0.6     # 단짝이 접속해 있으면 그 시각에 맞춰 들어올 확률
GUARD_STARS = 0.35    # (별점 가드 변형) 국가 평균 별점 격차 상한
SELF_BANDWAGON = 1.5  # 자유 선택에서 핵심 유저가 많은 국가로 쏠리는 정도


class P:
    __slots__ = ("i", "arch", "core", "hours", "est", "stars", "power", "pair", "clique", "student")


def weekly_hours(arch):
    a = ARCH[arch]
    h = (5 * a["p_wd"] + 2 * a["p_we"]) * a["hours"]
    if a["fade"]:
        h *= 0.45
    return h


def make_players(rng, n, active):
    arches = composition(n, active)
    rng.shuffle(arches)
    ps = []
    for i, ar in enumerate(arches):
        p = P()
        p.i, p.arch, p.core = i, ar, ar == "core"
        p.hours = weekly_hours(ar) * max(0.3, rng.gauss(1.0, 0.15))
        p.est = p.hours * max(0.3, rng.gauss(1.0, 0.3))          # 가입 때 적은 예상 시간(오차 ±30%)
        p.student = rng.random() < 0.6
        p.pair, p.clique = None, None
        ps.append(p)
    hz = statistics.pstdev([p.hours for p in ps]) or 1.0
    hm = statistics.fmean([p.hours for p in ps])
    for p in ps:
        act = (p.hours - hm) / hz
        skill = 0.45 * act + math.sqrt(1 - 0.45 ** 2) * rng.gauss(0, 1)   # 별점 평균은 활동량과 0.45 상관
        true = 3.0 + 0.7 * skill
        p.stars = min(5.0, max(1.0, true + rng.gauss(0, 0.2)))         # 상호평가 평균(측정 오차 0.2)
    # 전력 점수 = 별점과 예상 시간(가입 응답)을 반반, 각각 표준화
    sm, ss = statistics.fmean(p.stars for p in ps), statistics.pstdev([p.stars for p in ps]) or 1
    em, es = statistics.fmean(p.est for p in ps), statistics.pstdev([p.est for p in ps]) or 1
    for p in ps:
        p.power = 0.5 * (p.stars - sm) / ss + 0.5 * (p.est - em) / es
    # 단짝: 약 70% 가 서로 지목한 쌍. 활동량이 비슷할수록 짝이 되기 쉽다(동질성)
    pool = ps[:]
    rng.shuffle(pool)
    want = int(n * 0.7) // 2
    made = 0
    while made < want and len(pool) >= 2:
        a = pool.pop()
        ws = [math.exp(-abs(a.hours - b.hours) / (0.8 * hz)) for b in pool]
        b = rng.choices(pool, weights=ws)[0]
        pool.remove(b)
        a.pair, b.pair = b.i, a.i
        made += 1
    # 친구 무리(자유 선택용): 단짝 쌍 일부를 합쳐 3~4명 무리로
    cid = 0
    units = []
    seen = set()
    for p in ps:
        if p.i in seen:
            continue
        u = [p.i] + ([p.pair] if p.pair is not None else [])
        seen.update(u)
        units.append(u)
    rng.shuffle(units)
    cliques = []
    while units:
        u = units.pop()
        if len(u) == 2 and units and rng.random() < 0.35:
            # 활동량 비슷한 다른 쌍과 합침
            pairs = [v for v in units if len(v) == 2]
            if pairs:
                v = min(pairs, key=lambda v: abs(ps[v[0]].hours - ps[u[0]].hours) + rng.random() * hz)
                units.remove(v)
                u = u + v
        cliques.append(u)
    for c in cliques:
        for i in c:
            ps[i].clique = cid
        cid += 1
    return ps, cliques


def sizes(n):
    base = n // 3
    return [base + (1 if k < n % 3 else 0) for k in range(3)]


# ---------------- 편성 방식 ----------------

def m_random(rng, ps, cliques):
    idx = list(range(len(ps)))
    rng.shuffle(idx)
    return {i: k % 3 for k, i in enumerate(idx)}


def m_self(rng, ps, cliques):
    n = len(ps)
    cap = math.ceil(n / 3) + 1
    nat = {}
    count = [0, 0, 0]
    core = [0, 0, 0]
    order = cliques[:]
    rng.shuffle(order)
    # 핵심 유저가 많은 무리가 먼저 움직이는 경향(먼저 모이자고 말을 꺼낸다)
    order.sort(key=lambda c: -sum(ps[i].core for i in c) - rng.random())
    for c in order:
        best, bs = None, -1e9
        for k in range(3):
            if count[k] + len(c) > cap:
                continue
            s = SELF_BANDWAGON * core[k] + rng.gauss(0, 1.0) - 0.3 * count[k]   # 강한 쪽(핵심 많은 곳)으로 쏠림 + 우연
            if s > bs:
                best, bs = k, s
        if best is None:  # 정원 초과: 무리를 쪼갠다
            for i in c:
                k = min(range(3), key=lambda k: count[k])
                nat[i] = k
                count[k] += 1
                core[k] += ps[i].core
            continue
        for i in c:
            nat[i] = best
            count[best] += 1
            core[best] += ps[i].core
    # 운영자가 인원 차를 2 이하로만 맞춘다(혼자 온 사람부터 옮김)
    while max(count) - min(count) > 2:
        big, small = count.index(max(count)), count.index(min(count))
        movable = [i for i, k in nat.items() if k == big and ps[i].pair is None] or [i for i, k in nat.items() if k == big]
        i = rng.choice(movable)
        nat[i] = small
        count[big] -= 1
        count[small] += 1
    return nat


def leaders_of(rng, ps):
    """팀장: 핵심 유저 중 자원자 3명(자원자가 모자라면 활동량 순)."""
    cores = [p.i for p in ps if p.core]
    rng.shuffle(cores)
    if len(cores) >= 3:
        return cores[:3]
    rest = sorted((p for p in ps if not p.core), key=lambda p: -p.hours)
    return cores + [p.i for p in rest[:3 - len(cores)]]


def m_draft(rng, ps, cliques):
    """팀장 스네이크 드래프트. 팀장은 전력을 잘못 볼 수 있고(오차), 단짝이 남아 있으면 먼저 데려온다."""
    lead = leaders_of(rng, ps)
    nat = {l: k for k, l in enumerate(lead)}
    target = sizes(len(ps))
    left = [p.i for p in ps if p.i not in nat]
    perceived = {p.i: p.power + rng.gauss(0, 0.5) for p in ps}
    snake = [0, 1, 2, 2, 1, 0]
    t = 0
    while left:
        k = snake[t % 6]
        t += 1
        if sum(1 for v in nat.values() if v == k) >= target[k]:
            continue
        mates = [i for i in left if ps[i].pair is not None and nat.get(ps[i].pair) == k]
        if mates and rng.random() < 0.7:
            pick = mates[0]
        else:
            pick = max(left, key=lambda i: perceived[i])
        nat[pick] = k
        left.remove(pick)
    return nat


def weighted_draw(rng, ps, key, use_pairs):
    """제안안: 팀장 → 팀마다 돌아가며 가중 랜덤. 가중치 = EPS + 팀 평균을 전체 평균 쪽으로 당기는 정도."""
    lead = leaders_of(rng, ps)
    nat = {l: k for k, l in enumerate(lead)}
    target = sizes(len(ps))
    mu = statistics.fmean(key(p) for p in ps)
    spread = statistics.pstdev([key(p) for p in ps]) or 1.0
    # 뽑는 단위: 단짝(둘 다 아직 안 뽑힘)이면 묶음, 아니면 한 명. 팀장의 단짝은 팀장 팀으로 바로 간다
    if use_pairs:
        for l in lead:
            q = ps[l].pair
            if q is not None and q not in nat and sum(1 for v in nat.values() if v == nat[l]) < target[nat[l]]:
                nat[q] = nat[l]
    units, seen = [], set(nat)
    for p in ps:
        if p.i in seen:
            continue
        if use_pairs and p.pair is not None and p.pair not in seen:
            units.append([p.i, p.pair])
            seen.update([p.i, p.pair])
        else:
            units.append([p.i])
            seen.add(p.i)
    members = {k: [i for i, v in nat.items() if v == k] for k in range(3)}
    order = [0, 1, 2]
    rng.shuffle(order)
    t = 0
    stall = 0
    while units:
        k = order[t % 3]
        t += 1
        room = target[k] - len(members[k])
        if room <= 0:
            stall += 1
            if stall > 3:
                # 모든 팀이 꽉 찼는데 묶음이 남음(정원 홀수 문제): 묶음을 풀어 한 명씩
                units = [[i] for u in units for i in u]
                stall = 0
            continue
        stall = 0
        cand = [u for u in units if len(u) <= room]
        if not cand:
            # 묶음만 남았고 자리가 하나: 묶음을 푼다
            u = units.pop(units.index(max(units, key=len)))
            units += [[i] for i in u]
            continue
        cur = [key(ps[i]) for i in members[k]]
        m = statistics.fmean(cur) if cur else mu
        ws = []
        for u in cand:
            newm = (sum(cur) + sum(key(ps[i]) for i in u)) / (len(cur) + len(u))
            gain = (abs(m - mu) - abs(newm - mu)) / spread     # 전체 평균 쪽으로 당기는 만큼(격차에 비례)
            ws.append(EPS + max(0.0, gain) * len(cur))
        u = rng.choices(cand, weights=ws)[0]
        units.remove(u)
        for i in u:
            nat[i] = k
            members[k].append(i)
    return nat


def m_user(rng, ps, cliques):
    return weighted_draw(rng, ps, lambda p: p.stars, False)


def m_user_a(rng, ps, cliques):
    return weighted_draw(rng, ps, lambda p: p.power, False)


def m_user_ap(rng, ps, cliques):
    return weighted_draw(rng, ps, lambda p: p.power, True)


def guard_ok(ps, nat, stars=False):
    """재추첨 가드. 운영자가 보는 값(핵심 여부는 가입 응답의 예상 시간 상위, 별점은 상호평가)만 쓴다."""
    cut = sorted((p.est for p in ps), reverse=True)[sum(p.core for p in ps) - 1]
    cores = [sum(1 for i, k in nat.items() if k == n and ps[i].est >= cut) for n in range(3)]
    est = [sum(ps[i].est for i, k in nat.items() if k == n) for n in range(3)]
    ok = max(cores) - min(cores) < GUARD_CORE and (max(est) - min(est)) / statistics.fmean(est) < GUARD_POWER
    if stars and ok:
        means = [statistics.fmean([ps[i].stars for i, k in nat.items() if k == n]) for n in range(3)]
        ok = max(means) - min(means) <= GUARD_STARS
    return ok


def m_user_apg(rng, ps, cliques):
    for _ in range(500):
        nat = m_user_ap(rng, ps, cliques)
        if guard_ok(ps, nat):
            return nat
    return nat


def m_user_apgs(rng, ps, cliques):
    best = None
    for _ in range(500):
        nat = m_user_ap(rng, ps, cliques)
        if guard_ok(ps, nat, stars=True):
            return nat
        if best is None and guard_ok(ps, nat):
            best = nat
    return best or nat


def m_self_fair(rng, ps, cliques):
    global SELF_BANDWAGON
    old = SELF_BANDWAGON
    SELF_BANDWAGON = 0.0
    try:
        return m_self(rng, ps, cliques)
    finally:
        SELF_BANDWAGON = old


def objective(ps, nat):
    est = [0.0] * 3
    st = [[] for _ in range(3)]
    cores = [0] * 3
    cnt = [0] * 3
    for i, k in nat.items():
        est[k] += ps[i].est
        st[k].append(ps[i].stars)
        cores[k] += ps[i].core
        cnt[k] += 1
    mean_est = statistics.fmean(est)
    j = statistics.pvariance([e / mean_est for e in est]) * 10
    j += statistics.pvariance([statistics.fmean(s) for s in st]) * 4
    j += 10 * sum(1 for c in cores if c == 0) + 2 * (max(cores) - min(cores) >= 2)
    j += 100 * sum(1 for p in ps if p.pair is not None and nat[p.i] != nat[p.pair]) / 2
    j += 100 * (max(cnt) - min(cnt) > 1)
    return j


def m_optimal(rng, ps, cliques):
    """담금질로 목적함수를 줄이고, 서로 다른 상위 3안 중 하나(투표 결과를 무작위로 가정)."""
    best = []
    for restart in range(4):
        nat = m_random(rng, ps, cliques)
        # 단짝을 먼저 같은 국가로
        for p in ps:
            if p.pair is not None and nat[p.i] != nat[p.pair]:
                # 같은 국가의 다른 사람과 자리 바꿈
                k = nat[p.i]
                others = [i for i, v in nat.items() if v == k and i != p.i and ps[i].pair is None]
                if others:
                    o = rng.choice(others)
                    nat[o], nat[p.pair] = nat[p.pair], k
        cur = objective(ps, nat)
        temp = 1.0
        for step in range(1500):
            a, b = rng.sample(range(len(ps)), 2)
            if nat[a] == nat[b]:
                continue
            nat[a], nat[b] = nat[b], nat[a]
            new = objective(ps, nat)
            if new <= cur or rng.random() < math.exp((cur - new) / temp):
                cur = new
            else:
                nat[a], nat[b] = nat[b], nat[a]
            temp *= 0.996
        best.append((cur, dict(nat)))
    best.sort(key=lambda x: x[0])
    return rng.choice(best[:3])[1]


METHODS = {
    "self": ("자유 선택 (강한 쪽 쏠림 있음)", m_self),
    "self_fair": ("자유 선택 (쏠림 없음, 친구끼리만)", m_self_fair),
    "random": ("완전 랜덤", m_random),
    "draft": ("팀장 드래프트(직접 뽑기)", m_draft),
    "user": ("제안안: 별점 가중 추첨", m_user),
    "user_a": ("제안안 + 참여도", m_user_a),
    "user_ap": ("제안안 + 참여도 + 단짝", m_user_ap),
    "user_apg": ("제안안 + 참여도 + 단짝 + 재추첨 가드", m_user_apg),
    "user_apgs": ("위 + 별점 격차 가드(≤0.35)", m_user_apgs),
    "optimal": ("최적화 상위 3안 투표(현재 문서안)", m_optimal),
}


# ---------------- 평가 ----------------

def assign_metrics(ps, nat):
    st = [[ps[i].stars for i, k in nat.items() if k == n] for n in range(3)]
    hrs = [sum(ps[i].hours for i, k in nat.items() if k == n) for n in range(3)]
    cores = [sum(ps[i].core for i, k in nat.items() if k == n) for n in range(3)]
    cnt = [len(s) for s in st]
    pairs = [p for p in ps if p.pair is not None and p.i < p.pair]
    kept = sum(1 for p in pairs if nat[p.i] == nat[p.pair]) / max(1, len(pairs))
    means = [statistics.fmean(s) for s in st]
    return {
        "star_gap": max(means) - min(means),
        "hours_gap": (max(hrs) - min(hrs)) / statistics.fmean(hrs),
        "weak_share": min(hrs) / sum(hrs),
        "core_gap2": 1.0 if max(cores) - min(cores) >= 2 else 0.0,
        "core_le1": 1.0 if min(cores) <= 1 else 0.0,
        "size_gap": max(cnt) - min(cnt),
        "pair_kept": kept,
    }


def season_coplay(rng, ps, nat):
    """23일 매시: 접속한 사람 중 같은 국가 사람이 함께 접속해 있던 비율. 단짝은 접속을 맞추는 경향."""
    together = 0
    total = 0
    for d in range(DAYS):
        wd = weekday(d)
        weekend = wd >= 5
        start = {}
        order = list(range(len(ps)))
        rng.shuffle(order)
        for i in order:
            p = ps[i]
            a = ARCH[p.arch]
            vac = p.student and not weekend
            prob = a["p_we"] if weekend else ((a["p_wd"] + a["p_we"]) / 2 if vac else a["p_wd"])
            prob *= 1.0 + 0.4 * math.exp(-d / 7.0)
            if a["fade"]:
                prob *= math.exp(-d / a["fade"]) + 0.03
            if d in SEOLLAL_DAYS:
                prob *= SEOLLAL_FACTOR
            friend = p.pair if p.pair is not None and p.pair in start else None
            if friend is not None:
                prob *= FRIEND_BOOST
            if rng.random() >= min(0.98, prob):
                continue
            if friend is not None and rng.random() < FRIEND_SYNC:
                s0 = start[friend][0] + rng.gauss(0, 0.6)
            elif weekend or vac:
                s0 = rng.gauss(17.0, 3.5)
            else:
                s0 = rng.gauss(21.0 if p.arch == "core" else 21.5, 1.8 if p.arch == "core" else 1.5)
            s = int(round(min(25.0, max(10.0, s0))))
            mu = a["hours"] * (1.2 if wd == 4 or weekend else 1.0)
            length = max(1, int(round(rng.gauss(mu, a["sd"]))))
            start[i] = (s, min(27, s + length))
        for h in range(10, 27):
            online = [i for i, (s, e) in start.items() if s <= h < e]
            byn = [0, 0, 0]
            for i in online:
                byn[nat[i]] += 1
            for i in online:
                total += 1
                if byn[nat[i]] >= 2:
                    together += 1
    return together / max(1, total)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=2000)
    ap.add_argument("--season-runs", type=int, default=150)
    ap.add_argument("--seed", type=int, default=20270116)
    args = ap.parse_args()
    print("# 국가 편성 방식 비교\n")
    print(f"runs={args.runs} (편성 지표), 시즌 접속 {args.season_runs}회, seed={args.seed}. 별점 = 상호평가 5항목 평균(활동량과 상관 0.45, 측정 오차 0.2).")
    print("단짝 = 서로 지목한 쌍(인원의 약 70%가 짝을 가짐), 단짝은 접속을 맞추는 경향(같이 접속할 확률 ×1.3, 60% 는 시각도 맞춤).\n")
    print("지표: 별점 격차 = 국가 평균 별점 최대-최소, 활동량 격차 = 국가 총 플레이 시간 (최대-최소)/평균, 약소국 몫 = 가장 적은 국가의 플레이 시간 비중(33%가 완벽),")
    print("핵심 격차≥2 = 하루 2시간+ 유저 수가 2명 이상 차이 날 확률, 함께 접속 = 접속 시간 중 같은 국가 사람이 함께 있던 비율.\n")
    for n, a in [(17, 8), (20, 10), (23, 12)]:
        print(f"## 전체 {n}명 / 활성 {a}명\n")
        print("| 방식 | 별점 격차 | 활동량 격차 | 약소국 몫 | 핵심 격차≥2 | 핵심 ≤1 국가 | 인원 차 | 단짝 유지 | 함께 접속 |")
        print("|---|---|---|---|---|---|---|---|---|")
        for key, (label, fn) in METHODS.items():
            agg = {}
            co = []
            runs = args.runs if key != "optimal" else max(200, args.runs // 5)
            for r in range(runs):
                rng = random.Random(args.seed + r * 104729 + n)
                ps, cl = make_players(rng, n, a)
                nat = fn(random.Random(rng.random()), ps, cl)
                for k2, v in assign_metrics(ps, nat).items():
                    agg.setdefault(k2, []).append(v)
                if r < args.season_runs:
                    co.append(season_coplay(random.Random(rng.random()), ps, nat))
            mm = {k2: statistics.fmean(v) for k2, v in agg.items()}
            print(f"| {label} | {mm['star_gap']:.2f} | {mm['hours_gap']:.0%} | {mm['weak_share']:.1%} | {mm['core_gap2']:.0%} | "
                  f"{mm['core_le1']:.0%} | {mm['size_gap']:.1f} | {mm['pair_kept']:.0%} | {statistics.fmean(co):.1%} |")
        print()


if __name__ == "__main__":
    main()
