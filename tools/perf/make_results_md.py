#!/usr/bin/env python3
"""results/result-*.json → results.md 의 원자료 표."""
import glob
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
COLS = [("players", "봇"), ("entities", "엔티티"), ("undead", "언데드"), ("live_heap_mb", "실사용 힙 MB"),
        ("mspt_med", "MSPT 중앙"), ("mspt_p95", "MSPT 95%"), ("mspt_max", "MSPT 최대"), ("tps_1m", "TPS"),
        ("alloc_mb_s", "할당 MB/s"), ("pause_avg_ms", "GC 정지 평균 ms"), ("rss_mb", "RSS MB")]
LABEL = {"base": "기본 모드", "opt": "최적화 모드", "nations": "국가 배치(기지 3 + 방랑)", "spread": "전원 흩어짐",
         "x2": "좀비 ×2", "vanilla": "바닐라 몹"}

out = []
for path in sorted(glob.glob(os.path.join(HERE, "results", "result-*.json"))):
    r = json.load(open(path))
    title = f"{r['run']}: {LABEL[r['mods']]}, {LABEL[r['layout']]}, {LABEL[r['mobs']]}, 시야 {r['view']}/시뮬 {r['sim']}"
    if r.get("bluemap"):
        title += ", BlueMap 전체 렌더 중"
    out.append(f"### {title}\n\nCPU: {r.get('cpu', '')}\n")
    out.append("| " + " | ".join(c for _, c in COLS) + " |")
    out.append("|" + "---|" * len(COLS))
    for row in r["rows"]:
        out.append("| " + " | ".join("" if row.get(k) is None else str(row.get(k)) for k, _ in COLS) + " |")
    out.append("")
print("\n".join(out))
