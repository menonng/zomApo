#!/usr/bin/env python3
"""좀아포 국가전쟁 서버 플레이 양상 시뮬레이터.

에이전트 기반 몬테카를로 시뮬레이션. 표준 라이브러리만 사용한다.

    python3 zomapo_sim.py                 # 전체 시나리오, 결과를 표준 출력으로
    python3 zomapo_sim.py --runs 300 --out results.md

모델의 가정과 수치의 의미는 docs/server/simulation.md 에 정리돼 있다.
수치는 '예측'이 아니라 설계 선택지끼리 비교하기 위한 상대 지표다.
시간은 모두 현실 시간이다. 게임 시간은 블러드문 '보름달' 방식에서만 쓴다(게임 하루 = 현실 20분).
"""
from __future__ import annotations

import argparse
import datetime
import itertools
import math
import random
import statistics
from dataclasses import dataclass, field

SEASON_START = datetime.date(2027, 1, 16)   # 토요일 개장
DAYS = 23                                    # ~ 2027-02-07(일)
SEOLLAL_DAYS = {20, 21, 22}                  # 2/5~2/7 설 연휴(시즌 안에 걸친 부분)
SEOLLAL_FACTOR = 0.8                         # 연휴 접속 확률 배율(가족 일정)
CALIB_DAY = 13                               # 2주차 마지막 날 평균 영토 레벨이 3.0 이 되도록 보정
CALIB_TARGET = 3.0
Z_MAX = 5.0
TERRITORY_SHARE = 0.6                        # 접속 시간 중 자국 영토·접경에 머무는 비율
FUN_NEUTRAL = 4.6                            # 이보다 재미있으면 다음 접속 의욕이 오르고, 낮으면 내려간다
WINDOW = 3                                   # 하이브리드 방식의 국가 열기·정화 창(현실 일)
GAME_DAYS_PER_HOUR = 3.0                     # 게임 하루 = 현실 20분
MOON_CYCLE = 8                               # 보름달 주기(게임 일)
CURE_STAGES = 3

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
    "F": "하이브리드(지역 레벨만)",
    "G": "하이브리드 + 개인 숙련도 보정",
}

BM_MODES = {
    "weekly": "현실 시간 고정(토 21시)",
    "fullmoon": "게임 시간 보름달(8 게임일마다)",
    "trigger": "동시 접속 트리거(3명+·2개국+, 24시간 간격)",
    "trigger48": "동시 접속 트리거(3명+·2개국+, 48시간 간격)",
    "hybrid": "보름달(소) + 토 21시(대)",
}


def weekday(d: int) -> int:
    return (SEASON_START.weekday() + d) % 7


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
    bm_big: int = 0
    day_score: list = field(default_factory=lambda: [0.0] * DAYS)
    day_kills: list = field(default_factory=lambda: [0] * DAYS)
    day_hours: list = field(default_factory=lambda: [0] * DAYS)
    day_group: list = field(default_factory=lambda: [0] * DAYS)

    @property
    def power(self) -> float:
        return 5.0 * (1.0 - math.exp(-self.hours / 50.0))

    @staticmethod
    def recent(arr, d: int, window: int) -> float:
        return sum(arr[max(0, d - window):d])


def global_ramp(d: int) -> float:
    return 2.0 * (1.0 - math.exp(-d / 10.0))


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
    bm: str = "hybrid"
    student_frac: float = 0.5
    k: float = 1.0


def nation_levels(cfg: Config, players: list[Player], d: int):
    zt, parts = [], []
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
            recent = sum(p.recent(p.day_kills, d, WINDOW) for p in mem)
            actives = sum(1 for p in mem if p.recent(p.day_hours, d, WINDOW) >= 1)
            heat = math.log1p(recent / max(1, actives) / 40.0)
            group_h = sum(p.recent(p.day_group, d, WINDOW) for p in mem)
            purify = min(1.0, 0.18 * math.sqrt(group_h))
            parts.append((heat, purify))
            zt.append(min(Z_MAX, max(0.0, global_ramp(d) + cfg.k * heat - purify)))
            continue
        parts.append((raw, 0.0))
        zt.append(min(Z_MAX, max(0.0, cfg.k * raw)))
    zwild = 0.0 if cfg.method in "ABCDE" else min(Z_MAX, global_ramp(d) + 0.3)
    return zt, zwild, parts


def simulate(cfg: Config, seed: int) -> dict:
    rng = random.Random(seed)
    players = [Player(a, rng.random() < cfg.student_frac) for a in composition(cfg.n, cfg.active)]
    assign_nations(rng, players, cfg.assign)

    m = {
        "player_hours": 0, "alone_server": 0, "no_mate": 0, "too_hard": 0, "too_easy": 0,
        "pvp_fights": 0, "conc_wd": [0.0] * 26, "conc_we": [0.0] * 26, "wd_days": 0, "we_days": 0,
        "z_at": {}, "spread_calib": 0.0, "events": [],
    }
    nation_hours = [0.0, 0.0, 0.0]
    nation_z_calib = [0.0, 0.0, 0.0]
    nation_work = [[0.0] * DAYS for _ in range(3)]
    game_days = 0.0
    last_trigger = -10_000

    for d in range(DAYS):
        wd = weekday(d)
        weekend = wd >= 5
        zt, zwild, parts = nation_levels(cfg, players, d)
        if d == CALIB_DAY:
            nation_z_calib = list(zt)
            m["spread_calib"] = max(zt) - min(zt)
            m["calib_raw"] = statistics.fmean(x for x, _ in parts)
            m["calib_purify"] = statistics.fmean(y for _, y in parts)
        if d in (6, 13, 20, 22):
            m["z_at"][d] = statistics.fmean(zt)

        sessions: dict[int, list[Player]] = {h: [] for h in range(10, 26)}
        for p in players:
            a = ARCH[p.arch]
            vacation_weekday = p.student and not weekend
            if weekend:
                prob = a["p_we"]
            elif vacation_weekday:
                prob = (a["p_wd"] + a["p_we"]) / 2
            else:
                prob = a["p_wd"]
            prob *= (1.0 + 0.4 * math.exp(-d / 7.0)) * p.e
            hours_mu = a["hours"] * (1.2 if wd == 4 or weekend else 1.0) * math.sqrt(p.e)
            if a["fade"]:
                prob *= math.exp(-d / a["fade"]) + 0.03
            if d in SEOLLAL_DAYS:
                prob *= SEOLLAL_FACTOR
            if p.quit:
                prob *= 0.08
            if rng.random() >= min(0.98, prob):
                if p.arch in ("light", "ghost"):
                    p.e = max(0.2, p.e - 0.004)
                continue
            if weekend or vacation_weekday:
                start = rng.gauss(17.0, 3.5)
            else:
                start = rng.gauss(21.0 if p.arch == "core" else 21.5, 1.8 if p.arch == "core" else 1.5)
            s = int(round(min(25.0, max(10.0, start))))
            length = max(1, int(round(rng.gauss(hours_mu, a["sd"]))))
            for h in range(s, min(26, s + length)):
                sessions[h].append(p)

        day_fun: dict[int, list[float]] = {}
        m["we_days" if weekend else "wd_days"] += 1
        for h in range(10, 26):
            online = sessions[h]
            (m["conc_we"] if weekend else m["conc_wd"])[h] += len(online)
            abs_h = d * 24 + h
            if not online:
                continue
            by_nation = [[p for p in online if p.nation == n] for n in range(3)]
            nations_present = sum(1 for g in by_nation if g)

            kind = None
            if cfg.bm in ("weekly", "hybrid") and wd == 5 and h == 21 and d > 0:
                kind = "big"
            if cfg.bm in ("fullmoon", "hybrid"):
                before = game_days
                game_days += GAME_DAYS_PER_HOUR      # 아무도 없으면 서버가 멈추므로 접속자가 있는 시간에만 흐른다
                if kind is None and int(before // MOON_CYCLE) != int(game_days // MOON_CYCLE):
                    kind = "small"
            cooldown = 48 if cfg.bm == "trigger48" else 24
            if (cfg.bm.startswith("trigger") and kind is None and 19 <= h <= 24 and d >= 1 and len(online) >= 3
                    and nations_present >= 2 and abs_h - last_trigger >= cooldown):
                kind = "big"
                last_trigger = abs_h
            if kind:
                m["events"].append((kind, len(online), nations_present))

            airdrop = wd in (2, 4) and h == 22
            grouped = {}
            for p in online:
                if kind == "big" and len(online) >= 2:
                    grouped[id(p)] = rng.random() < 0.8          # 예고된 이벤트: 국가 무관 집결
                else:
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
                work = (1.5 if g else 1.0) * (0.8 + 0.4 * min(pw, 5.0) / 5.0)
                if kind == "big":
                    lam *= 2.5
                    death_p *= 1.8
                    fun_hour[pid] += 1.5 if g else 0.3
                    work += 1.0
                    p.bm_big += 1
                elif kind == "small":
                    lam *= 1.6
                    death_p *= 1.3
                    fun_hour[pid] += 0.8 if g else 0.4
                    work += 0.5
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
                nation_work[p.nation][d] += work

                if z - peff > 1.5:
                    m["too_hard"] += 1
                elif peff - z > 1.5:
                    m["too_easy"] += 1
                fun_hour[pid] += 3.0 * math.exp(-(((z - peff - 0.3) / 1.4) ** 2))
                fun_hour[pid] += 1.2 * (1.0 - math.exp(-gained / 15.0))
                if rng.random() < min(0.5, death_p):
                    p.deaths += 1
                    fun_hour[pid] -= 1.2

            for a_n, b_n in ((0, 1), (0, 2), (1, 2)):
                side_a, side_b = by_nation[a_n], by_nation[b_n]
                if not side_a or not side_b or kind == "big":
                    continue
                lam = 0.07 * math.sqrt(len(side_a) * len(side_b)) + (0.45 if airdrop else 0.0)
                for _ in range(min(3, poisson(rng, lam))):
                    if rng.random() >= 0.6:
                        continue
                    pa_ = [p for p in side_a if rng.random() < 0.55] or [rng.choice(side_a)]
                    pb_ = [p for p in side_b if rng.random() < 0.55] or [rng.choice(side_b)]
                    sa = sum(1 + p.power for p in pa_)
                    sb = sum(1 + p.power for p in pb_)
                    win, lose = (pa_, pb_) if rng.random() < sa * sa / (sa * sa + sb * sb) else (pb_, pa_)
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
    played = [p for p in players if p.hours >= 1]
    m["weekly_active"] = {w: sum(1 for p in players if week_hours(p, w - 1) >= 1) for w in (1, 2, 3)}
    m["weekly_2h"] = {w: sum(1 for p in players if week_hours(p, w - 1) >= 14) for w in (1, 2, 3)}
    m["retained"] = sum(1 for p in players if sum(p.day_hours[DAYS - 7:]) >= 1) / len(players)
    m["fun_by_arch"] = {
        a: statistics.fmean([p.fun_sum / p.fun_hours for p in players if p.arch == a and p.fun_hours] or [0.0])
        for a in ARCH_ORDER}
    m["fun"] = sum(p.fun_sum for p in players) / max(1, sum(p.fun_hours for p in players))
    m["hours_by_arch"] = {a: statistics.fmean([p.hours for p in players if p.arch == a] or [0.0]) for a in ARCH_ORDER}
    cores = [p for p in players if p.arch == "core"]
    m["kda_core"] = statistics.fmean([(p.pk + p.pa) / max(1, p.deaths) for p in cores] or [0.0])
    m["kda_core_pvp"] = statistics.fmean([(p.pk + p.pa) / max(1, p.pd) for p in cores] or [0.0])
    m["deaths_core"] = statistics.fmean([p.deaths for p in cores] or [0.0])
    m["pk_core"] = statistics.fmean([p.pk for p in cores] or [0.0])
    m["bm_reach"] = sum(1 for p in played if p.bm_big > 0) / max(1, len(played))
    m["nation_imbalance"] = max(nation_hours) / max(1.0, min(nation_hours))
    m["zero_core_nation"] = any(not any(p.arch == "core" and p.nation == n for p in players) for n in range(3))
    m["corr"] = corr(nation_hours, nation_z_calib)
    m["nation_hours"] = nation_hours
    m["nation_work"] = nation_work
    return m


def corr(xs, ys) -> float:
    if statistics.pstdev(xs) == 0 or statistics.pstdev(ys) == 0:
        return 0.0
    return statistics.correlation(xs, ys)


def run_many(cfg: Config, runs: int, seed: int) -> list[dict]:
    return [simulate(cfg, seed * 100003 + i) for i in range(runs)]


def calibrate(n: int, active: int, method: str, runs: int, seed: int) -> float:
    """고정점 반복: 보정일의 원지표 평균으로 계수를 다시 풀고, 바뀐 계수로 다시 돌린다."""
    k = {"A": 0.004, "B": 0.02, "C": 0.03, "D": 0.02, "E": 0.02}.get(method, 1.5)
    for _ in range(6):
        rs = run_many(Config(n, active, method, k=k), runs, seed + 7)
        raw = statistics.fmean(r["calib_raw"] for r in rs)
        purify = statistics.fmean(r["calib_purify"] for r in rs)
        if raw <= 1e-9:
            k *= 3.0
            continue
        base = global_ramp(CALIB_DAY) - purify if method in "FG" else 0.0
        k = 0.5 * k + 0.5 * (CALIB_TARGET - base) / raw
    return k


# ---------------------------------------------------------------- 치료제 연구 (사후 계산)

def cumsum(xs):
    out, s = [], 0.0
    for x in xs:
        s += x
        out.append(s)
    return out


def race(work, stage_cost, catchup: bool):
    """국가별 레이스. 반환: (국가별 완료일 또는 None, 국가별 시즌 말 진척률)."""
    progress = [0.0, 0.0, 0.0]
    finish = [None, None, None]
    leaked = set()
    total = stage_cost * CURE_STAGES
    for d in range(DAYS):
        leader_stage = max(int(p // stage_cost) for p in progress)
        for n in range(3):
            gain = work[n][d]
            if catchup:
                behind = max(0, leader_stage - int(progress[n] // stage_cost))
                gain *= 1.0 + 0.3 * behind
            progress[n] = min(total, progress[n] + gain)
        new_leader_stage = max(int(p // stage_cost) for p in progress)
        if catchup:
            for s in range(1, min(CURE_STAGES, new_leader_stage) + 1):
                if s not in leaked:
                    leaked.add(s)
                    for n in range(3):
                        if int(progress[n] // stage_cost) < s:
                            progress[n] = min(total, progress[n] + 0.25 * stage_cost)
        for n in range(3):
            if finish[n] is None and progress[n] >= total - 1e-9:
                finish[n] = d
    return finish, [p / total for p in progress]


def cure_stats_race(rs, stage_cost, catchup):
    any_finish, winner_days, winner_is_top, last_prog, two_finish = 0, [], 0, [], 0
    for r in rs:
        finish, prog = race(r["nation_work"], stage_cost, catchup)
        done = [d for d in finish if d is not None]
        last_prog.append(min(prog))
        if done:
            any_finish += 1
            first = min(done)
            winner_days.append(first)
            winners = [n for n in range(3) if finish[n] == first]
            top = max(range(3), key=lambda n: r["nation_hours"][n])
            if top in winners:
                winner_is_top += 1
            if len(done) >= 2:
                two_finish += 1
    n = len(rs)
    return dict(p_finish=any_finish / n,
                winner_day=statistics.median(winner_days) if winner_days else None,
                top_wins=winner_is_top / max(1, any_finish),
                last_prog=statistics.fmean(last_prog),
                two_finish=two_finish / n)


def cure_stats_shared(rs, total_cost):
    finishes, top_share, min_share = [], [], []
    for r in rs:
        totals = [sum(w) for w in r["nation_work"]]
        cs = cumsum([sum(r["nation_work"][n][d] for n in range(3)) for d in range(DAYS)])
        f = next((d for d, v in enumerate(cs) if v >= total_cost), None)
        finishes.append(f)
        s = sum(totals) or 1.0
        top_share.append(max(totals) / s)
        min_share.append(min(totals) / s)
    done = [f for f in finishes if f is not None]
    return dict(p_finish=len(done) / len(rs), finish_day=statistics.median(done) if done else None,
                top_share=statistics.fmean(top_share), min_share=statistics.fmean(min_share))


def day_label(d) -> str:
    if d is None:
        return "—"
    date = SEASON_START + datetime.timedelta(days=round(d))
    return f"{date.month}/{date.day}"


def prob_zero_core(n: int, active: int) -> float:
    sizes = [n // 3 + (1 if i < n % 3 else 0) for i in range(3)]
    team_of = [t for t, s in enumerate(sizes) for _ in range(s)]
    total = bad = 0
    for combo in itertools.combinations(range(n), active):
        total += 1
        if len({team_of[i] for i in combo}) < 3:
            bad += 1
    return bad / total


def mean(rs, key):
    return statistics.fmean(r[key] for r in rs)


def fmt(x, nd=2):
    return "—" if x is None else f"{x:.{nd}f}"


def pct(x):
    return f"{100 * x:.0f}%"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=200)
    ap.add_argument("--seed", type=int, default=20270116)
    ap.add_argument("--out", type=str, default="")
    args = ap.parse_args()
    out: list[str] = []
    w = out.append
    end = SEASON_START + datetime.timedelta(days=DAYS - 1)

    w("# 시뮬레이션 결과 (자동 생성)\n")
    w(f"`python3 tools/sim/zomapo_sim.py --runs {args.runs} --seed {args.seed}` 로 생성. "
      f"시즌 {SEASON_START.isoformat()} ~ {end.isoformat()} ({DAYS}일). 시나리오마다 반복 횟수만큼 돌린 평균이다. "
      "기본 설정은 좀비화 방식 G, 균형 편성, 블러드문 hybrid.\n")

    ks = {m_: calibrate(15, 4, m_, max(30, args.runs // 4), args.seed) for m_ in METHODS}

    def base(n, a, **kw):
        return Config(n, a, k=ks[kw.get("method", "G")], **kw)

    pops = [(10, 3), (12, 3), (12, 4), (15, 3), (15, 4)]
    w("## 1. 인원 시나리오별 플레이 양상\n")
    w("| 전체/활성 | 황금시간 평균 동접 | 서버에 혼자인 시간 | 자국민 없이 노는 시간 | 주간 PvP 교전(서버 전체) | 핵심 1인 시즌 PvP 킬 | 핵심 1인 시즌 사망 | 핵심 KDA(전체 사망) | 핵심 KDA(PvP 사망만) | 마지막 7일 잔존율 | 평균 재미 |")
    w("|---|---|---|---|---|---|---|---|---|---|---|")
    pop_results = {}
    for n, a in pops:
        rs = run_many(base(n, a), args.runs, args.seed)
        pop_results[(n, a)] = rs
        prime = statistics.fmean(
            (r["conc_wd"][h] / max(1, r["wd_days"]) * 5 + r["conc_we"][h] / max(1, r["we_days"]) * 2) / 7
            for r in rs for h in (21, 22, 23))
        ph = mean(rs, "player_hours")
        w(f"| {n}/{a} | {fmt(prime)}명 | {pct(mean(rs, 'alone_server') / ph)} | {pct(mean(rs, 'no_mate') / ph)} "
          f"| {fmt(mean(rs, 'pvp_fights') / (DAYS / 7), 1)} | {fmt(mean(rs, 'pk_core'), 1)} | {fmt(mean(rs, 'deaths_core'), 1)} "
          f"| {fmt(mean(rs, 'kda_core'))} | {fmt(mean(rs, 'kda_core_pvp'))} | {pct(mean(rs, 'retained'))} | {fmt(mean(rs, 'fun'))} |")
    w("")

    w("### 1-1. 주차별 활동 인원\n")
    w("| 시나리오 | 지표 | 1주차 (1/16~22) | 2주차 (1/23~29) | 3주차 (1/30~2/5) |")
    w("|---|---|---|---|---|")
    for key in ((15, 4), (10, 3)):
        rs = pop_results[key]
        for label, f_ in (("주 1시간 이상 접속", "weekly_active"), ("하루 2시간 이상(주 14시간)", "weekly_2h")):
            vals = " | ".join(fmt(statistics.fmean(r[f_][wk] for r in rs), 1) for wk in (1, 2, 3))
            w(f"| {key[0]}/{key[1]} | {label} | {vals} |")
    w("")

    w("### 1-2. 시간대별 평균 동시 접속자 (15/4)\n")
    rs = pop_results[(15, 4)]
    hours = list(range(14, 26))
    w("| 구분 | " + " | ".join(f"{h % 24}시" for h in hours) + " |")
    w("|---|" + "---|" * len(hours))
    for label, key, days in (("평일", "conc_wd", "wd_days"), ("주말", "conc_we", "we_days")):
        w(f"| {label} | " + " | ".join(fmt(statistics.fmean(r[key][h] / max(1, r[days]) for r in rs), 1) for h in hours) + " |")
    w("")

    w("### 1-3. 아키타입별 시즌 누적 플레이 시간과 재미 (15/4)\n")
    w("| 아키타입 | 시즌 누적 시간 | 시간당 재미 |")
    w("|---|---|---|")
    for a in ARCH_ORDER:
        w(f"| {ARCH[a]['label']} | {fmt(statistics.fmean(r['hours_by_arch'][a] for r in rs), 0)}h "
          f"| {fmt(statistics.fmean(r['fun_by_arch'][a] for r in rs))} |")
    w("")

    w("## 2. 좀비화 진척도 산정 방식 비교\n")
    w(f"모든 방식은 15명/활성 4명 기준으로 2주차 마지막 날({day_label(CALIB_DAY)}) 평균 영토 레벨이 3.0 이 되도록 계수를 보정했고, "
      "10명/활성 3명에도 같은 계수를 썼다. 레벨 칸은 그날 세 국가 영토 레벨의 평균. "
      "A~E 는 영토(+접경) 밖을 레벨 0 으로 둔 원안, F·G 는 전역 시간 램프가 황야에도 적용된다.\n")
    for n, a in ((15, 4), (10, 3)):
        w(f"### {n}명 / 활성 {a}명\n")
        w(f"| 방식 | {day_label(6)} | {day_label(13)} | {day_label(20)} | {day_label(22)} | 보정일 국가 간 격차 | 국가 활동량↔레벨 상관 | 너무 어려움 | 너무 쉬움 | 평균 재미 | 마지막 7일 잔존율 |")
        w("|---|---|---|---|---|---|---|---|---|---|---|")
        for method, label in METHODS.items():
            rs = run_many(base(n, a, method=method), args.runs, args.seed + 1)
            zs = " | ".join(fmt(statistics.fmean(r["z_at"][d] for r in rs), 1) for d in (6, 13, 20, 22))
            ph = mean(rs, "player_hours")
            w(f"| {method}. {label} | {zs} | {fmt(mean(rs, 'spread_calib'))} | {fmt(mean(rs, 'corr'))} "
              f"| {pct(mean(rs, 'too_hard') / ph)} | {pct(mean(rs, 'too_easy') / ph)} | {fmt(mean(rs, 'fun'))} | {pct(mean(rs, 'retained'))} |")
        w("")

    w("## 3. 국가 편성 방식 비교\n")
    w("| 전체/활성 | 편성 | 핵심 유저 0명인 국가가 생길 확률(시뮬) | 국가 간 플레이시간 비(최대/최소) | 자국민 없이 노는 시간 | 마지막 7일 잔존율 |")
    w("|---|---|---|---|---|---|")
    for n, a in ((15, 4), (10, 3)):
        for mode, label in (("random", "완전 랜덤"), ("balanced", "상호평가 기반 균형")):
            rs = run_many(base(n, a, assign=mode), args.runs, args.seed + 2)
            w(f"| {n}/{a} | {label} | {pct(mean(rs, 'zero_core_nation'))} | {fmt(mean(rs, 'nation_imbalance'))} "
              f"| {pct(mean(rs, 'no_mate') / mean(rs, 'player_hours'))} | {pct(mean(rs, 'retained'))} |")
    w("")
    w("### 3-1. 완전 랜덤 편성 시 핵심 유저가 한 명도 없는 국가가 생길 정확한 확률\n")
    w("| 전체 인원 | 활성 3명 | 활성 4명 |")
    w("|---|---|---|")
    for n in (10, 12, 15):
        w(f"| {n} | {fmt(100 * prob_zero_core(n, 3), 1)}% | {fmt(100 * prob_zero_core(n, 4), 1)}% |")
    w("")

    w("## 4. 블러드문(호드 밤) 방식 비교\n")
    w("'대'는 예고된 큰 이벤트(국가 무관 집결 가정), '소'는 예고 없는 보름달 밤. 보름달은 게임 시간 기준이고, "
      "서버가 비면 멈추는 설정(pause-when-empty)이라 접속자가 있는 동안에만 게임 시간이 흐른다고 가정했다.\n")
    bm_results = {}
    for n, a in ((15, 4), (10, 3)):
        w(f"### {n}명 / 활성 {a}명\n")
        w("| 방식 | 종류 | 시즌 중 횟수 | 회당 평균 참가자 | 2명 이상 참가한 비율 | 2개국 이상 참가한 비율 | 대 이벤트를 한 번 이상 겪은 플레이어 | 평균 재미 | 마지막 7일 잔존율 |")
        w("|---|---|---|---|---|---|---|---|---|")
        for mode, label in BM_MODES.items():
            rs = run_many(base(n, a, bm=mode), args.runs, args.seed + 3)
            bm_results[(n, a, mode)] = rs
            for kind, kl in (("big", "대"), ("small", "소")):
                evs = [e for r in rs for e in r["events"] if e[0] == kind]
                if not evs:
                    continue
                w(f"| {label} | {kl} | {fmt(len(evs) / len(rs), 1)} | {fmt(statistics.fmean(e[1] for e in evs), 1)} "
                  f"| {pct(sum(1 for e in evs if e[1] >= 2) / len(evs))} | {pct(sum(1 for e in evs if e[2] >= 2) / len(evs))} "
                  f"| {pct(mean(rs, 'bm_reach')) if kind == 'big' else '—'} | {fmt(mean(rs, 'fun'))} | {pct(mean(rs, 'retained'))} |")
        w("")

    w("## 5. 치료제 연구 방식 비교\n")
    ref = bm_results[(15, 4, "hybrid")]
    stage_cost = statistics.median(max(cumsum(r["nation_work"][n])[17] for n in range(3)) for r in ref) / CURE_STAGES
    total_shared = statistics.median(sum(cumsum(r["nation_work"][n])[18] for n in range(3)) for r in ref)
    w(f"연구 진척 = 접속 1시간당 (그룹 1.5 / 솔로 1.0) × 장비 보정 + 블러드문 참가 보너스. "
      f"단계 비용은 15명/활성 4명에서 '선두 국가'가 {day_label(17)} 에 {CURE_STAGES}단계를 끝내도록, "
      f"공동 연구 총량은 서버 전체가 {day_label(18)} 에 끝내도록 맞췄고, 10명/활성 3명에도 같은 비용을 썼다.\n")
    ref_total = statistics.median(sum(sum(w_) for w_ in r["nation_work"]) for r in ref)
    w("'비용 조정'은 그 서버의 총 연구량(중앙값)에 비례해 비용을 줄인 경우다. 실제로는 첫 3~4일의 활동량을 보고 운영자가 비용을 확정하는 방식에 해당한다.\n")
    w("| 전체/활성 | 방식 | 비용 | 시즌 안 완성 확률 | 완성일(중앙값) | 우승국 = 플레이시간 1위 국가 | 꼴찌 국가 최종 진척 | 2개국 이상 완성 |")
    w("|---|---|---|---|---|---|---|---|")
    shared_rows = []
    for n, a in ((15, 4), (10, 3)):
        rs = bm_results[(n, a, "hybrid")]
        scale = statistics.median(sum(sum(w_) for w_ in r["nation_work"]) for r in rs) / ref_total
        costs = [("고정", 1.0)] + ([("조정", scale)] if (n, a) != (15, 4) else [])
        for cost_label, sc in costs:
            for catchup, label in ((False, "R1. 국가 레이스"), (True, "R2. 국가 레이스 + 따라잡기")):
                s = cure_stats_race(rs, stage_cost * sc, catchup)
                w(f"| {n}/{a} | {label} | {cost_label} | {pct(s['p_finish'])} | {day_label(s['winner_day'])} | {pct(s['top_wins'])} "
                  f"| {pct(s['last_prog'])} | {pct(s['two_finish'])} |")
            shared_rows.append((n, a, cost_label, cure_stats_shared(rs, total_shared * sc)))
    w("")
    w("| 전체/활성 | 방식 | 비용 | 시즌 안 완성 확률 | 완성일(중앙값) | 기여 1위 국가 몫 | 기여 꼴찌 국가 몫 |")
    w("|---|---|---|---|---|---|---|")
    for n, a, cost_label, s in shared_rows:
        w(f"| {n}/{a} | R3. 서버 공동 연구 | {cost_label} | {pct(s['p_finish'])} | {day_label(s['finish_day'])} | {pct(s['top_share'])} | {pct(s['min_share'])} |")
    w("")
    w("보정 계수: " + ", ".join(f"{m_}={ks[m_]:.5f}" for m_ in METHODS) + f", 단계 비용={stage_cost:.1f}, 공동 총량={total_shared:.1f}\n")

    text = "\n".join(out)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
