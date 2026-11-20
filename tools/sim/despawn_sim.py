#!/usr/bin/env python3
"""좀비 디스폰 시뮬레이션. 바닐라(26.3) 규칙 vs zomApo '기억' 규칙.

python3 despawn_sim.py > despawn-results.md

바닐라 Mob.checkDespawn(몬스터): 엔티티 틱 청크 안에서만 검사한다.
  - 가장 가까운 플레이어가 128블록 밖 → 즉시 사라짐
  - 32블록 밖 + 무행동 시간 600 초과 → 매 틱 1/800 (무행동 시간은 32블록 안이면 0, 밝으면 틱당 3씩)
  - 엔티티 틱 청크 = 플레이어 청크에서 체비셰프 거리 ≤ 시뮬레이션 거리 (정사각형)
  - 시야 밖으로 나가 청크가 언로드되면 무행동 시간은 저장되지 않는다(다시 불려 오면 0)
"""
import math
import random

TICK_SPEED = 5.6 / 20      # 달리기 5.6m/s
VIEW = 10                  # view-distance
ZOMBIE_MS_9600X = 0.0105 / 1.8  # 좀비 1마리 틱 비용(실측 좀비 ×2 차이 0.0105ms, 9600X 환산 1.8배)


def chunk(v):
    return math.floor(v / 16)


def ticking(px, pz, zx, zz, sim):
    return max(abs(chunk(px) - chunk(zx)), abs(chunk(pz) - chunk(zz))) <= sim


def loaded(px, pz, zx, zz):
    return max(abs(chunk(px) - chunk(zx)), abs(chunk(pz) - chunk(zz))) <= VIEW + 1


def bunker(minutes, bright, rng, n=12, trials=2000):
    """플레이어가 32블록 밖(벙커·기둥 위)에서 기다린다. 남은 비율."""
    ticks = int(minutes * 1200)
    start = 201 if bright else 601
    rolls = max(0, ticks - start + 1)
    p = (1 - 1 / 800) ** rolls
    return p


def trip(sim, rng, trials=3000, n=12, away=600, wait_ticks=12000, memory=False):
    """기지(원점) 주변 반경 20 안에 좀비 n마리. 플레이어가 무작위 방향으로 away 블록 나갔다가 다른 무작위 방향에서 돌아온다.
    돌아와 기지 한가운데 섰을 때 남은 좀비 비율, 그리고 '전멸' 확률."""
    left_total = 0
    wiped = 0
    for _ in range(trials):
        zs = []
        for _ in range(n):
            r = 20 * math.sqrt(rng.random())
            a = rng.random() * 2 * math.pi
            zs.append([r * math.cos(a), r * math.sin(a), 0, True])  # x, z, noAction, alive
        out = rng.random() * 2 * math.pi
        back = rng.random() * 2 * math.pi
        path = []
        steps = int(away / TICK_SPEED)
        for i in range(steps + 1):
            d = i * TICK_SPEED
            path.append((d * math.cos(out), d * math.sin(out)))
        path += [path[-1]] * 1  # 기다림은 아래에서 처리(그동안 기지 청크는 얼어 있음)
        bx, bz = away * math.cos(back), away * math.sin(back)
        for i in range(steps, -1, -1):
            d = i * TICK_SPEED
            path.append((d * math.cos(back), d * math.sin(back)))
        unloaded = False
        for (px, pz) in path:
            for z in zs:
                if not z[3]:
                    continue
                if not loaded(px, pz, z[0], z[1]):
                    unloaded = True
                    z[2] = 0
                    continue
                if not ticking(px, pz, z[0], z[1], sim):
                    continue
                if memory:
                    continue
                d2 = (px - z[0]) ** 2 + (pz - z[1]) ** 2
                if d2 > 128 * 128:
                    z[3] = False
                    continue
                if d2 < 32 * 32:
                    z[2] = 0
                    continue
                z[2] += 1
                if z[2] > 600 and rng.random() < 1 / 800:
                    z[3] = False
        left = sum(1 for z in zs if z[3])
        left_total += left
        if left == 0:
            wiped += 1
    return left_total / (trials * n), wiped / trials


def main():
    rng = random.Random(20270116)
    print("# 좀비 디스폰 시뮬레이션 (바닐라 vs 기억)\n")
    print("`tools/sim/despawn_sim.py`. 바닐라 규칙은 26.3 `Mob.checkDespawn` 바이트코드에서 확인했다. 시뮬레이션 거리 6 은 이 서버 설정, 4 는 ServerCore 가 부하 때 줄이는 값, 10 은 비교용.\n")

    print("## 1. 벙커·기둥 트릭: 좀비 떼에서 32블록 넘게 떨어져 기다리기\n")
    print("좀비 떼 12마리. 플레이어가 33블록 이상 떨어진 곳(높이 포함: 기둥 33칸 위도 같다)에서 기다릴 때 남는 비율.\n")
    print("| 기다린 시간 | 바닐라 (밤) | 바닐라 (밝은 곳) | 기억 규칙 |")
    print("|---|---|---|---|")
    for m in (0.5, 1, 2, 3, 5):
        a = bunker(m, False, rng)
        b = bunker(m, True, rng)
        print(f"| {m:g}분 | {a*100:.0f}% ({a*12:.1f}마리) | {b*100:.0f}% ({b*12:.1f}마리) | 100% (2시간) |")
    print("\n→ 바닐라에서는 **멀리 갈 필요도 없다.** 33블록만 떨어져 2~3분 버티면 좀비 떼가 거의 사라진다.\n")

    print("## 2. 멀리 다녀오기: 600블록 나갔다가 다른 방향에서 돌아오기\n")
    print("기지 반경 20 안에 좀비 12마리(플레이어를 놓친 뒤 서 있음). 나가는 방향·돌아오는 방향은 무작위, 달리기 속도. 3,000번.\n")
    print("| 시뮬레이션 거리 | 바닐라: 남은 좀비 | 바닐라: 전멸 확률 | 기억 규칙: 남은 좀비 |")
    print("|---|---|---|---|")
    for sim in (4, 6, 10):
        left, wiped = trip(sim, rng)
        lm, _ = trip(sim, rng, trials=300, memory=True)
        print(f"| {sim} | {left*100:.0f}% | {wiped*100:.0f}% | {lm*100:.0f}% |")
    print("\n→ 시뮬레이션 거리 6 에서는 좀비가 얼어 있다가 돌아올 때 대부분 남는다. 다만 **대각선 쪽으로 오가면 128블록 넘는 순간 엔티티 틱 범위 안이라 한꺼번에 사라진다.** 시뮬레이션 거리가 클수록 더 잘 사라진다.\n")

    print("## 3. 부하: 기억 좀비가 늘리는 틱 비용 (9600X)\n")
    print(f"좀비 1마리 = {ZOMBIE_MS_9600X:.4f}ms (실측 '좀비 ×2' 의 엔티티 수 차이로 구함). 기억 좀비는 몬스터 상한에 잡히므로 **밤에 플레이어 주변 수는 바닐라와 같다.** 늘어나는 것은 아래 두 가지뿐이다.\n")
    print("| 경우 | 무리(떨어진 플레이어 묶음)당 추가 좀비 | 무리당 추가 틱 | 무리 7곳(국가 3 + 혼자 4) |")
    print("|---|---|---|---|")
    rows = [
        ("밤: 좀비 떼가 상한 위로 쌓임 (구역 상한 40, 1~2구역)", 12, 40),
        ("낮 (Lv2 이상): 밤에 쫓던 좀비가 남음. 쫓은 비율 30~60%", int(126 * 0.3), int(126 * 0.6)),
    ]
    for name, lo, hi in rows:
        print(f"| {name} | {lo}~{hi}마리 | {lo*ZOMBIE_MS_9600X:.2f}~{hi*ZOMBIE_MS_9600X:.2f}ms | {7*lo*ZOMBIE_MS_9600X:.1f}~{7*hi*ZOMBIE_MS_9600X:.1f}ms |")
    print("\n- 최악(낮, 7곳 모두 Lv2 이상, 많이 쫓김)이 3.1ms, 틱 예산 50ms 의 약 6%. 틱 중앙값이 35ms 를 넘으면 기억 시간이 절반(1시간), 45ms 면 1/4(30분)로 줄어(부하 연동) 쫓지 않는 기억 좀비부터 일반 좀비로 돌아간다.")
    print("- 얼어 있는(시뮬레이션 거리 밖) 기억 좀비는 틱 비용이 0 이다. 청크와 함께 저장되는 몫은 한 마리 1KB 안팎.")


if __name__ == "__main__":
    main()
