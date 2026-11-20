#!/usr/bin/env python3
"""스타포스 0 → 10성 비용·운 분포. 현재안과 '순한 맛 보강안' 비교.

보강안: 실패할 때마다 그 단계 성공 확률 +5%p(성공하면 초기화), 실패 시 결정 절반 환급,
5~9성 경험치 비용 5 → 3 레벨. 표준 라이브러리만 사용.
"""
import random
import statistics


def odds(star):
    return 0.95 - 0.05 * star if star < 5 else 0.60 - 0.05 * (star - 5)


def cost(star, plan):
    if star < 5:
        return 1, 2
    return (3, 5) if plan == "현재안" else (3, 3)


def run(plan, rng, target=10):
    star, tries, crystals, levels, worst_streak, streak, bonus = 0, 0, 0.0, 0, 0, 0, 0.0
    while star < target:
        c, lv = cost(star, plan)
        tries += 1
        levels += lv
        p = odds(star) + (bonus if plan != "현재안" else 0.0)
        if rng.random() < min(1.0, p):
            crystals += c
            star += 1
            streak = 0
            bonus = 0.0
        else:
            crystals += c if plan == "현재안" else c / 2
            streak += 1
            worst_streak = max(worst_streak, streak)
            bonus += 0.05
    return tries, crystals, levels, worst_streak


def pct(xs, q):
    xs = sorted(xs)
    return xs[int(q * (len(xs) - 1))]


def main():
    print("| 안 | 시도 평균 | 상위 10% (운 나쁨) | 상위 1% | 결정 평균 | 결정 상위 10% | 경험치 레벨 평균 | 한 번에 연속 실패 최대(상위 10%) |")
    print("|---|---|---|---|---|---|---|---|")
    for plan in ["현재안", "보강안"]:
        rng = random.Random(20270116)
        rs = [run(plan, rng) for _ in range(50000)]
        t = [r[0] for r in rs]; c = [r[1] for r in rs]; l = [r[2] for r in rs]; w = [r[3] for r in rs]
        print(f"| {plan} | {statistics.fmean(t):.1f} | {pct(t, .9)} | {pct(t, .99)} | {statistics.fmean(c):.1f} | {pct(c, .9):.0f} | {statistics.fmean(l):.0f} | {pct(w, .9)} |")
    print("\n(장비 한 개 기준. 무기 1 + 방어구 4 = 다섯 배)")


if __name__ == "__main__":
    main()
