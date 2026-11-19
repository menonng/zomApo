#!/usr/bin/env python3
"""좀아포 국가전쟁 서버 플레이 양상 시뮬레이터.

에이전트 기반 몬테카를로 시뮬레이션. 표준 라이브러리만 사용한다.

    python3 zomapo_sim.py                 # 전체 시나리오, 결과를 표준 출력으로
    python3 zomapo_sim.py --runs 300 --out results.md

모델의 가정과 수치의 의미는 docs/server/simulation.md 에 정리돼 있다.
수치는 '예측'이 아니라 설계 선택지끼리 비교하기 위한 상대 지표다.
"""
from __future__ import annotations

import argparse
import itertools
import math
import random
import statistics
from dataclasses import dataclass, field

DAYS = 70                # 10주 시즌: 2027-01-04(월) ~ 2027-03-14(일)
MARCH_DAY = 56           # 2027-03-01(월), 개학·개강
CALIB_DAY = 41           # 6주차 마지막 날 평균 영토 레벨이 3.0 이 되도록 보정
CALIB_TARGET = 3.0
Z_MAX = 5.0
TERRITORY_SHARE = 0.6    # 접속 시간 중 자국 영토·접경에 머무는 비율
FUN_NEUTRAL = 4.6        # 이 값보다 재미있으면 다음 접속 의욕이 오르고, 낮으면 내려간다

ARCH = {
    # p_wd/p_we: 평일/주말 접속 확률, hours: 평균 세션 길이(시간)
    "core":    dict(label="핵심",   p_wd=0.90, p_we=0.93, hours=3.0, sd=1.0, fade=None, sens=0.015),
    "regular": dict(label="일반",   p_wd=0.45, p_we=0.65, hours=1.7, sd=0.6, fade=None, sens=0.03),
    "light":   dict(label="라이트", p_wd=0.12, p_we=0.45, hours=1.5, sd=0.6, fade=None, sens=0.04),
    "ghost":   dict(label="유령",   p_wd=0.35, p_we=0.45, hours=1.2, sd=0.5, fade=6.0,  sens=0.05),
}
ARCH_ORDER = ["core", "regular", "light", "ghost"]

METHODS = {
    "A": "누적 합계",
    "B": "누적 평균(전체 국민)",
    "C": "누적 중앙값(전체 국민)",
    "D": "(평균+중앙값)/2",
    "E": "편차 보정 평균(평균+1σ 상한)",
    "F": "추천 하이브리드(지역 레벨만)",
    "G": "추천 하이브리드 + 개인 숙련도 보정",
}


def composition(n: int, active: int) -> list[str]:
    rest = n - active
    regular = round(rest * 0.4)
    light = round(rest * 0.35)
    ghost = rest - regular - light
    return ["core"] * active + ["regular"] * regular + ["light"] * light + ["ghost"] * ghost


@dataclass
class Player:
    arch: str
    student: bool
    nation: int = 0
    e: float = 1.0
    quit: bool = False
    hours: float = 0.0
    score: float = 0.0
    kills: int = 0
    deaths: int = 0
    pk: int = 0
    pa: int = 0
    pd: int = 0
    fun_sum: float = 0.0
    fun_hours: int = 0
    day_score: list = field(default_factory=lambda: [0.0] * DAYS)
    day_kills: list = field(default_factory=lambda: [0] * DAYS)
    day_hours: list = field(default_factory=lambda: [0] * DAYS)
    day_group: list = field(default_factory=lambda: [0] * DAYS)

    @property
    def power(self) -> float:
        return 5.0 * (1.0 - math.exp(-self.hours / 50.0))

    def recent(self, arr, d: int, window: int = 7) -> float:
        return sum(arr[max(0, d - window):d])


def global_ramp(d: int) -> float:
    return 2.0 * (1.0 - math.exp(-d / 30.0))


def poisson(rng: random.Random, lam: float) -> int:
    if lam <= 0:
        return 0
    if lam > 30:
        return max(0, int(round(rng.gauss(lam, math.sqrt(lam)))))
    l, k, p = math.exp(-lam), 0, 1.0
    while True:
        p *= rng.random()
        if p <= l:
            return k
        k += 1


def assign_nations(rng: random.Random, players: list[Player], mode: str) -> None:
    if mode == "random":
        idx = list(range(len(players)))
        rng.shuffle(idx)
        for slot, i in enumerate(idx):
            players[i].nation = slot % 3
        return
    # balanced: 상호평가(활동량 추정, 오차 ±30%)를 기준으로 스네이크 드래프트
    def expected(p: Player) -> float:
        a = ARCH[p.arch]
        base = (5 * a["p_wd"] + 2 * a["p_we"]) / 7 * a["hours"]
        if a["fade"]:
            base *= 0.4
        return base * max(0.2, rng.gauss(1.0, 0.3))
    order = sorted(players, key=expected, reverse=True)
    snake = [0, 1, 2, 2, 1, 0]
    for i, p in enumerate(order):
        p.nation = snake[i % 6]


@dataclass
class Config:
    n: int
    active: int
    method: str = "G"
    assign: str = "balanced"
    student_frac: float = 0.5
    k: float = 1.0


def nation_levels(cfg: Config, players: list[Player], d: int) -> tuple[list[float], float, list[tuple[float, float]]]:
    zt = []
    parts = []
    for n in range(3):
        mem = [p for p in players if p.nation == n]
        cum = [p.score for p in mem]
        if cfg.method == "A":
            raw = sum(cum)
        elif cfg.method == "B":
            raw = statistics.fmean(cum)
        elif cfg.method == "C":
            raw = statistics.median(cum)
        elif cfg.method == "D":
            raw = (statistics.fmean(cum) + statistics.median(cum)) / 2
        elif cfg.method == "E":
            mu = statistics.fmean(cum)
            sd = statistics.pstdev(cum)
            raw = statistics.fmean(min(c, mu + sd) for c in cum)
        else:
            recent = sum(p.recent(p.day_kills, d) for p in mem)
            actives = sum(1 for p in mem if p.recent(p.day_hours, d) >= 1)
            heat = math.log1p(recent / max(1, actives) / 100.0)
            group_h = sum(p.recent(p.day_group, d) for p in mem)
            purify = min(1.0, 0.12 * math.sqrt(group_h))
            parts.append((heat, purify))
            zt.append(min(Z_MAX, max(0.0, global_ramp(d) + cfg.k * heat - purify)))
            continue
        parts.append((raw, 0.0))
        zt.append(min(Z_MAX, max(0.0, cfg.k * raw)))
    zwild = 0.0 if cfg.method in "ABCDE" else min(Z_MAX, global_ramp(d) + 0.3)
    return zt, zwild, parts


def simulate(cfg: Config, seed: int) -> dict:
    rng = random.Random(seed)
    archs = composition(cfg.n, cfg.active)
    players = [Player(a, rng.random() < cfg.student_frac) for a in archs]
    assign_nations(rng, players, cfg.assign)

    m = {
        "player_hours": 0, "alone_server": 0, "no_mate": 0, "too_hard": 0, "too_easy": 0,
        "pvp_fights": 0, "pve_deaths": 0, "conc_wd": [0.0] * 26, "conc_we": [0.0] * 26,
        "wd_days": 0, "we_days": 0, "z_week": {w: [] for w in (2, 4, 6, 8, 10)},
        "spread6": 0.0, "bloodmoon_group": 0, "bloodmoon_total": 0,
    }
    nation_hours = [0.0, 0.0, 0.0]
    nation_z42 = [0.0, 0.0, 0.0]

    for d in range(DAYS):
        wd = d % 7
        weekend = wd >= 5
        zt, zwild, parts = nation_levels(cfg, players, d)
        if d == CALIB_DAY:
            nation_z42 = list(zt)
            m["spread6"] = max(zt) - min(zt)
            m["calib_raw"] = statistics.fmean(x for x, _ in parts)
            m["calib_purify"] = statistics.fmean(y for _, y in parts)
        if d % 7 == 6 and (d // 7 + 1) in m["z_week"]:
            m["z_week"][d // 7 + 1].append(statistics.fmean(zt))

        sessions: dict[int, list[Player]] = {h: [] for h in range(10, 26)}
        for p in players:
            a = ARCH[p.arch]
            prob = a["p_we"] if weekend else a["p_wd"]
            novelty = 1.0 + 0.4 * math.exp(-d / 7.0)
            prob *= novelty * p.e
            hours_mu = a["hours"] * (1.2 if wd == 4 or weekend else 1.0) * math.sqrt(p.e)
            if a["fade"]:
                prob *= math.exp(-d / a["fade"]) + 0.03
            if p.student and d >= MARCH_DAY:
                prob *= 0.65
                hours_mu *= 0.8
            if p.quit:
                prob *= 0.08
            if rng.random() >= min(0.98, prob):
                if p.arch in ("light", "ghost"):
                    p.e = max(0.2, p.e - 0.004)
                continue
            if weekend:
                start = rng.gauss(17.0, 3.5)
            else:
                start = rng.gauss(21.0 if p.arch == "core" else 21.5, 1.8 if p.arch == "core" else 1.5)
            s = int(round(min(25.0, max(10.0, start))))
            length = max(1, int(round(rng.gauss(hours_mu, a["sd"]))))
            for h in range(s, min(26, s + length)):
                sessions[h].append(p)

        day_fun: dict[int, list[float]] = {}
        if weekend:
            m["we_days"] += 1
        else:
            m["wd_days"] += 1
        for h in range(10, 26):
            online = sessions[h]
            conc = m["conc_we"] if weekend else m["conc_wd"]
            conc[h] += len(online)
            if not online:
                continue
            by_nation = [[p for p in online if p.nation == n] for n in range(3)]
            bloodmoon = wd == 5 and h == 21
            airdrop = wd in (2, 4) and h == 22
            grouped = {}
            for p in online:
                mates = len(by_nation[p.nation]) - 1
                grouped[id(p)] = mates > 0 and rng.random() < 0.65
            fun_hour = {id(p): 1.5 for p in online}

            for p in online:
                pid = id(p)
                mates = len(by_nation[p.nation]) - 1
                g = grouped[pid]
                m["player_hours"] += 1
                if len(online) == 1:
                    m["alone_server"] += 1
                if mates == 0:
                    m["no_mate"] += 1
                if g:
                    fun_hour[pid] += 2.0
                elif mates > 0:
                    fun_hour[pid] += 1.0
                elif len(online) > 1:
                    fun_hour[pid] += 0.4

                z = zt[p.nation] if rng.random() < TERRITORY_SHARE else zwild
                pw = p.power
                if cfg.method == "G":
                    z = max(z, min(Z_MAX, pw - 1.0))
                    if not g:
                        z = min(z, pw + 1.5)
                peff = pw + (0.7 if g else 0.0)

                lam = (5.0 + 4.0 * z) * (0.6 + 0.4 * min(peff, 5.0) / 5.0)
                death_p = 0.035 * math.exp(0.8 * (z - peff)) * (0.8 if g else 1.25)
                if bloodmoon:
                    lam *= 2.5
                    death_p *= 1.8
                    m["bloodmoon_total"] += 1
                    if g:
                        m["bloodmoon_group"] += 1
                        fun_hour[pid] += 1.5
                    else:
                        fun_hour[pid] += 0.3
                kills = poisson(rng, lam)
                gained = kills * (1.0 + 0.5 * z)
                p.kills += kills
                p.score += gained
                p.day_score[d] += gained
                p.day_kills[d] += kills
                p.day_hours[d] += 1
                if g:
                    p.day_group[d] += 1
                p.hours += 1
                nation_hours[p.nation] += 1

                if z - peff > 1.5:
                    m["too_hard"] += 1
                elif peff - z > 1.5:
                    m["too_easy"] += 1
                fun_hour[pid] += 3.0 * math.exp(-(((z - peff - 0.3) / 1.4) ** 2))
                fun_hour[pid] += 1.2 * (1.0 - math.exp(-gained / 15.0))
                if rng.random() < min(0.5, death_p):
                    p.deaths += 1
                    m["pve_deaths"] += 1
                    fun_hour[pid] -= 1.2

            for a_n, b_n in ((0, 1), (0, 2), (1, 2)):
                side_a, side_b = by_nation[a_n], by_nation[b_n]
                if not side_a or not side_b:
                    continue
                lam = 0.07 * math.sqrt(len(side_a) * len(side_b)) + (0.45 if airdrop else 0.0)
                for _ in range(min(3, poisson(rng, lam))):
                    if rng.random() >= 0.6:
                        continue
                    pa_ = [p for p in side_a if rng.random() < 0.55] or [rng.choice(side_a)]
                    pb_ = [p for p in side_b if rng.random() < 0.55] or [rng.choice(side_b)]
                    sa = sum(1 + p.power for p in pa_)
                    sb = sum(1 + p.power for p in pb_)
                    a_wins = rng.random() < sa * sa / (sa * sa + sb * sb)
                    win, lose = (pa_, pb_) if a_wins else (pb_, pa_)
                    m["pvp_fights"] += 1
                    for p in pa_ + pb_:
                        fun_hour[id(p)] += 1.0
                    for p in win:
                        fun_hour[id(p)] += 0.8
                    victims = [rng.choice(lose)]
                    if len(lose) >= 2 and rng.random() < 0.3:
                        victims.append(rng.choice([p for p in lose if p is not victims[0]]))
                    for v in victims:
                        killer = rng.choice(win)
                        killer.pk += 1
                        for w in win:
                            if w is not killer and rng.random() < 0.75:
                                w.pa += 1
                        v.pd += 1
                        v.deaths += 1
                        fun_hour[id(v)] -= 1.5

            for p in online:
                f = fun_hour[id(p)]
                p.fun_sum += f
                p.fun_hours += 1
                day_fun.setdefault(id(p), []).append(f)

        for p in players:
            fs = day_fun.get(id(p))
            if fs:
                p.e = min(1.3, max(0.2, p.e + ARCH[p.arch]["sens"] * (statistics.fmean(fs) - FUN_NEUTRAL)))
            if not p.quit:
                p.e += 0.02 * (1.0 - p.e)
            if p.e < 0.35:
                p.quit = True

    week_hours = lambda p, w: sum(p.day_hours[w * 7:(w + 1) * 7])
    m["weekly_active"] = {w: sum(1 for p in players if week_hours(p, w - 1) >= 1) for w in (1, 4, 8, 10)}
    m["weekly_2h"] = {w: sum(1 for p in players if week_hours(p, w - 1) >= 14) for w in (1, 4, 8, 10)}
    m["retained"] = sum(1 for p in players if week_hours(p, 9) >= 1) / len(players)
    m["fun_by_arch"] = {
        a: statistics.fmean([p.fun_sum / p.fun_hours for p in players if p.arch == a and p.fun_hours] or [0.0])
        for a in ARCH_ORDER
    }
    m["fun"] = sum(p.fun_sum for p in players) / max(1, sum(p.fun_hours for p in players))
    m["hours_by_arch"] = {a: statistics.fmean([p.hours for p in players if p.arch == a] or [0.0]) for a in ARCH_ORDER}
    m["kda_core"] = statistics.fmean(
        [(p.pk + p.pa) / max(1, p.deaths) for p in players if p.arch == "core"] or [0.0])
    m["kda_core_pvp"] = statistics.fmean(
        [(p.pk + p.pa) / max(1, p.pd) for p in players if p.arch == "core"] or [0.0])
    m["deaths_core"] = statistics.fmean([p.deaths for p in players if p.arch == "core"] or [0.0])
    m["pk_core"] = statistics.fmean([p.pk for p in players if p.arch == "core"] or [0.0])
    m["nation_imbalance"] = max(nation_hours) / max(1.0, min(nation_hours))
    m["zero_core_nation"] = any(
        not any(p.arch == "core" and p.nation == n for p in players) for n in range(3))
    m["corr"] = corr(nation_hours, nation_z42)
    m["z42"] = statistics.fmean(nation_z42)
    return m


def corr(xs, ys) -> float:
    if statistics.pstdev(xs) == 0 or statistics.pstdev(ys) == 0:
        return 0.0
    return statistics.correlation(xs, ys)


def run_many(cfg: Config, runs: int, seed: int) -> list[dict]:
    return [simulate(cfg, seed * 100003 + i) for i in range(runs)]


def calibrate(n: int, active: int, method: str, runs: int, seed: int) -> float:
    """고정점 반복: 보정일의 원지표 평균으로 계수를 다시 풀고, 바뀐 계수로 다시 돌린다."""
    k = {"A": 0.001, "B": 0.005, "C": 0.006, "D": 0.005, "E": 0.005}.get(method, 0.6)
    for _ in range(6):
        rs = run_many(Config(n, active, method, k=k), runs, seed + 7)
        raw = statistics.fmean(r["calib_raw"] for r in rs)
        purify = statistics.fmean(r["calib_purify"] for r in rs)
        if raw <= 1e-9:
            k *= 3.0
            continue
        base = global_ramp(CALIB_DAY) - purify if method in "FG" else 0.0
        k_new = (CALIB_TARGET - base) / raw
        k = 0.5 * k + 0.5 * k_new
    return k


def mean(rs, key):
    return statistics.fmean(r[key] for r in rs)


def prob_zero_core(n: int, active: int) -> float:
    sizes = [n // 3 + (1 if i < n % 3 else 0) for i in range(3)]
    team_of = [t for t, s in enumerate(sizes) for _ in range(s)]
    total = bad = 0
    for combo in itertools.combinations(range(n), active):
        total += 1
        teams = {team_of[i] for i in combo}
        if len(teams) < 3:
            bad += 1
    return bad / total


def fmt(x, nd=2):
    return f"{x:.{nd}f}"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=200)
    ap.add_argument("--seed", type=int, default=20270104)
    ap.add_argument("--out", type=str, default="")
    args = ap.parse_args()
    out: list[str] = []
    w = out.append

    w("# 시뮬레이션 결과 (자동 생성)\n")
    w(f"`python3 tools/sim/zomapo_sim.py --runs {args.runs} --seed {args.seed}` 로 생성. "
      "시나리오마다 몬테카를로 반복 횟수만큼 돌린 평균이다.\n")

    ks = {}
    for method in METHODS:
        ks[method] = calibrate(15, 4, method, max(30, args.runs // 4), args.seed)

    pops = [(10, 3), (12, 3), (12, 4), (15, 3), (15, 4)]
    w("## 1. 인원 시나리오별 플레이 양상 (추천 방식 G, 균형 편성)\n")
    w("| 전체/활성 | 황금시간 평균 동접 | 서버에 혼자인 시간 | 자국민 없이 노는 시간 | 주간 PvP 교전(서버 전체) | 핵심 1인 시즌 PvP 킬 | 핵심 1인 시즌 사망 | 핵심 KDA(전체 사망) | 핵심 KDA(PvP 사망만) | 블러드문 단체참여율 | 10주차 잔존율 | 평균 재미 |")
    w("|---|---|---|---|---|---|---|---|---|---|---|---|")
    pop_results = {}
    for n, a in pops:
        rs = run_many(Config(n, a, "G", k=ks["G"]), args.runs, args.seed)
        pop_results[(n, a)] = rs
        prime = statistics.fmean(
            (r["conc_wd"][h] / max(1, r["wd_days"]) * 5 + r["conc_we"][h] / max(1, r["we_days"]) * 2) / 7
            for r in rs for h in (21, 22, 23))
        bm = statistics.fmean(r["bloodmoon_group"] / max(1, r["bloodmoon_total"]) for r in rs)
        w(f"| {n}/{a} | {fmt(prime)}명 | {fmt(100 * mean(rs, 'alone_server') / mean(rs, 'player_hours'), 0)}% "
          f"| {fmt(100 * mean(rs, 'no_mate') / mean(rs, 'player_hours'), 0)}% | {fmt(mean(rs, 'pvp_fights') / 10, 1)} "
          f"| {fmt(mean(rs, 'pk_core'), 1)} | {fmt(mean(rs, 'deaths_core'), 1)} | {fmt(mean(rs, 'kda_core'))} "
          f"| {fmt(mean(rs, 'kda_core_pvp'))} | {fmt(100 * bm, 0)}% "
          f"| {fmt(100 * mean(rs, 'retained'), 0)}% | {fmt(mean(rs, 'fun'))} |")
    w("")

    w("### 1-1. 주차별 활동 인원 (15/4, 10/3)\n")
    w("| 시나리오 | 지표 | 1주차 | 4주차 | 8주차 | 10주차 |")
    w("|---|---|---|---|---|---|")
    for key in ((15, 4), (10, 3)):
        rs = pop_results[key]
        for label, field_ in (("주 1시간 이상 접속", "weekly_active"), ("일 2시간 이상(주 14시간)", "weekly_2h")):
            vals = " | ".join(fmt(statistics.fmean(r[field_][wk] for r in rs), 1) for wk in (1, 4, 8, 10))
            w(f"| {key[0]}/{key[1]} | {label} | {vals} |")
    w("")

    w("### 1-2. 시간대별 평균 동시 접속자 (15/4)\n")
    rs = pop_results[(15, 4)]
    hours = list(range(14, 26))
    w("| 구분 | " + " | ".join(f"{h % 24}시" for h in hours) + " |")
    w("|---|" + "---|" * len(hours))
    for label, key, days in (("평일", "conc_wd", "wd_days"), ("주말", "conc_we", "we_days")):
        vals = " | ".join(fmt(statistics.fmean(r[key][h] / max(1, r[days]) for r in rs), 1) for h in hours)
        w(f"| {label} | {vals} |")
    w("")

    w("### 1-3. 아키타입별 시즌 누적 플레이 시간과 재미 (15/4)\n")
    w("| 아키타입 | 시즌 누적 시간 | 시간당 재미 |")
    w("|---|---|---|")
    for a in ARCH_ORDER:
        w(f"| {ARCH[a]['label']} | {fmt(statistics.fmean(r['hours_by_arch'][a] for r in rs), 0)}h "
          f"| {fmt(statistics.fmean(r['fun_by_arch'][a] for r in rs))} |")
    w("")

    w("## 2. 좀비화 진척도 산정 방식 비교\n")
    w("모든 방식은 15명/활성 4명 기준으로 6주차 마지막 날 평균 영토 레벨이 3.0 이 되도록 계수를 보정했고, "
      "10명/활성 3명에도 같은 계수를 그대로 썼다(서버 설정은 같고 인원만 다른 상황). 레벨 칸은 각 주 마지막 날 세 국가 영토 레벨의 평균이다. "
      "A~E 는 영토(+접경) 밖을 레벨 0 으로 둔 원안, F·G 는 전역 시간 램프가 황야에도 적용된다.\n")
    for n, a in ((15, 4), (10, 3)):
        w(f"### {n}명 / 활성 {a}명\n")
        w("| 방식 | 2주 | 4주 | 6주 | 8주 | 10주 | 6주차 국가 간 격차 | 국가 활동량↔레벨 상관 | 너무 어려움 | 너무 쉬움 | 평균 재미 | 10주차 잔존율 |")
        w("|---|---|---|---|---|---|---|---|---|---|---|---|")
        for method, label in METHODS.items():
            rs = run_many(Config(n, a, method, k=ks[method]), args.runs, args.seed + 1)
            zw = " | ".join(fmt(statistics.fmean(r["z_week"][wk][0] for r in rs if r["z_week"][wk]), 1)
                            for wk in (2, 4, 6, 8, 10))
            ph = mean(rs, "player_hours")
            w(f"| {method}. {label} | {zw} | {fmt(mean(rs, 'spread6'))} | {fmt(mean(rs, 'corr'))} "
              f"| {fmt(100 * mean(rs, 'too_hard') / ph, 0)}% | {fmt(100 * mean(rs, 'too_easy') / ph, 0)}% "
              f"| {fmt(mean(rs, 'fun'))} | {fmt(100 * mean(rs, 'retained'), 0)}% |")
        w("")

    w("## 3. 국가 편성 방식 비교 (방식 G)\n")
    w("| 전체/활성 | 편성 | 핵심 유저 0명인 국가가 생길 확률(시뮬) | 국가 간 플레이시간 비(최대/최소) | 자국민 없이 노는 시간 | 10주차 잔존율 |")
    w("|---|---|---|---|---|---|")
    for n, a in ((15, 4), (10, 3)):
        for mode, label in (("random", "완전 랜덤"), ("balanced", "상호평가 기반 균형")):
            rs = run_many(Config(n, a, "G", assign=mode, k=ks["G"]), args.runs, args.seed + 2)
            w(f"| {n}/{a} | {label} | {fmt(100 * mean(rs, 'zero_core_nation'), 0)}% "
              f"| {fmt(mean(rs, 'nation_imbalance'))} | {fmt(100 * mean(rs, 'no_mate') / mean(rs, 'player_hours'), 0)}% "
              f"| {fmt(100 * mean(rs, 'retained'), 0)}% |")
    w("")
    w("### 3-1. 완전 랜덤 편성 시 핵심 유저가 한 명도 없는 국가가 생길 정확한 확률\n")
    w("| 전체 인원 | 활성 3명 | 활성 4명 |")
    w("|---|---|---|")
    for n in (10, 12, 15):
        w(f"| {n} | {fmt(100 * prob_zero_core(n, 3), 1)}% | {fmt(100 * prob_zero_core(n, 4), 1)}% |")
    w("")
    w("보정 계수: " + ", ".join(f"{m}={ks[m]:.5f}" for m in METHODS) + "\n")

    text = "\n".join(out)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
