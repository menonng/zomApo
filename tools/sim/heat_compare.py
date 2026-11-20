#!/usr/bin/env python3
"""국가 영토 레벨의 '열기' 기준 비교: 처치 수(F) vs 좀비 스코어(S: 레벨 배율 포함, T: 기본 점수).

각 방식의 k 를 20명/활성 10명에서 2주차 마지막 날 평균 3.0 으로 맞춘 뒤, 같은 k 로 17/8·23/12 도 돌린다.
python3 heat_compare.py --runs 200
"""
import argparse
import statistics
import zomapo_sim as Z


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=200)
    ap.add_argument("--seed", type=int, default=20270116)
    a = ap.parse_args()
    print("# 국가 영토 레벨: 처치 수 기준 vs 좀비 스코어 기준\n")
    print(f"runs={a.runs}, seed={a.seed}. k 는 20/10 에서 1/29(13일째) 평균 3.0 으로 보정. 레벨 = 전역 램프 + k·열기 − 정화.\n")
    print("| 방식 | k | 인원 | 1/22 | 1/29 | 2/5 | 2/7 | 2/7 최고 국가 | 2/7 국가 격차 | 너무 어려움 | 너무 쉬움 |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    labels = {"F": "처치 수", "S": "스코어(레벨 배율 포함)", "T": "기본 스코어(레벨 배율 제외)"}
    for method in ["F", "S", "T"]:
        k = Z.calibrate(20, 10, method, a.runs // 2, a.seed)
        for n, act in [(17, 8), (20, 10), (23, 12)]:
            rs = Z.run_many(Z.Config(n, act, method, k=k), a.runs, a.seed)
            za = lambda d: statistics.fmean(r["z_at"][d] for r in rs)
            zm = statistics.fmean(r["z_max"][22] for r in rs)
            sp = statistics.fmean(r["z_spread"][22] for r in rs)
            hrs = sum(r["player_hours"] for r in rs)
            hard = sum(r["too_hard"] for r in rs) / hrs
            easy = sum(r["too_easy"] for r in rs) / hrs
            print(f"| {labels[method]} | {k:.2f} | {n}/{act} | {za(6):.2f} | {za(13):.2f} | {za(20):.2f} | {za(22):.2f} | {zm:.2f} | {sp:.2f} | {hard:.1%} | {easy:.1%} |")


if __name__ == "__main__":
    main()
