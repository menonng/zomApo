#!/usr/bin/env python3
"""서버 부하 실측: 26.x Fabric 전용 서버에 Carpet 봇을 단계적으로 띄워 MSPT·힙·할당률·GC 를 잰다.

자기 PC 에서 그대로 돌릴 수 있다(Windows·macOS·Linux, Python 3.10+, JDK 25). 결과 해설: results.md
    python bench.py setup                 # Fabric 서버 실행기와 측정용·최적화 모드 내려받기 (work/dl)
    python bench.py all                   # 월드 생성(처음 한 번, 약 15분) + 표준 4조합 측정(약 45분) + 요약
    python bench.py run NAME --mods opt --layout spread --mobs x2   # 조합 하나만
작업 파일은 tools/perf/work/ 에 생기고 저장소에는 올라가지 않는다(.gitignore).
우리 모드 jar 는 ../../mod/build/libs 와 ../../../simpleNationWar/mod/build/libs 에서 찾는다(먼저 ./gradlew buildAll).
"""
import argparse
import json
import math
import os
import queue
import re
import shutil
import subprocess
import threading
import time

import glob
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PERF = os.path.join(HERE, "work")
SEED = "20270116"
MC = "26.3"
LOADER = "0.20.1"
BASE_MODS = ["fabric-api", "fabric-language-kotlin", "carpet", "spark"]
OPT_MODS = ["lithium", "ferrite-core", "krypton", "c2me-fabric", "scalablelux", "vmp-fabric", "clumps"]
EXE = ".exe" if os.name == "nt" else ""


def java_bin():
    """JAVA_HOME → PATH 의 java → Gradle 이 받아 둔 JDK 25 순서로 찾는다."""
    if os.environ.get("JAVA_HOME"):
        return os.path.join(os.environ["JAVA_HOME"], "bin")
    w = shutil.which("java")
    if w:
        return os.path.dirname(os.path.realpath(w))
    found = sorted(glob.glob(os.path.expanduser("~/.gradle/jdks/jdk-25*")))
    if found:
        return os.path.join(found[-1], "bin")
    sys.exit("JDK 25 를 찾지 못했다. JAVA_HOME 을 지정한다.")


JAVA = java_bin()


def our_mods():
    pats = [os.path.join(HERE, "..", "..", "mod", "build", "libs", "zomapo-*.jar"),
            os.path.join(HERE, "..", "..", "..", "simpleNationWar", "mod", "build", "libs", "snw-*.jar")]
    return [f for p in pats for f in glob.glob(p) if not f.endswith("-sources.jar")]


def http_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": "zomapo-perf-bench"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def download(url, path):
    req = urllib.request.Request(url, headers={"User-Agent": "zomapo-perf-bench"})
    with urllib.request.urlopen(req, timeout=300) as r, open(path, "wb") as f:
        shutil.copyfileobj(r, f)


def setup():
    dl = os.path.join(PERF, "dl")
    inst = http_json("https://meta.fabricmc.net/v2/versions/installer")[0]["version"]
    os.makedirs(dl, exist_ok=True)
    download(f"https://meta.fabricmc.net/v2/versions/loader/{MC}/{LOADER}/{inst}/server/jar", os.path.join(dl, "fabric-server.jar"))
    for group, names in (("base", BASE_MODS), ("opt", OPT_MODS), ("bluemap", ["bluemap"])):
        gdir = os.path.join(dl, group)
        shutil.rmtree(gdir, ignore_errors=True)
        os.makedirs(gdir)
        for name in names:
            vs = http_json(f'https://api.modrinth.com/v2/project/{name}/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22{MC}%22%5D')
            if not vs:
                print(f"  {name}: {MC} 빌드 없음, 건너뜀")
                continue
            f = ([x for x in vs[0]["files"] if x.get("primary")] or vs[0]["files"])[0]
            download(f["url"], os.path.join(gdir, f["filename"]))
            print(f"  {group}/{f['filename']}")
    print("우리 모드:", our_mods() or "없음(./gradlew buildAll 먼저)")

BASES = [(800, 0), (-400, 693), (-400, -693)]
# 방랑자: 기지·서로에게서 300블록 이상, 반경 2,000 안
WANDERERS = [(1400, 700), (-1300, 300), (200, 1500), (300, -1400), (-900, 1500), (1500, -600),
             (-1600, -800), (900, 1100), (-200, -300)]
# 국가 배치 23명: 기지 14(5/5/4) + 방랑 9. 앞에서부터 잘라도 6:4 가 유지되게 섞는다.
NATIONS_ORDER = [("B", 0), ("B", 1), ("B", 2), ("W", 0), ("B", 0), ("B", 1), ("W", 1), ("B", 2),
                 ("B", 0), ("W", 2), ("B", 1), ("W", 3), ("B", 2), ("B", 0), ("W", 4), ("B", 1),
                 ("W", 5), ("B", 2), ("B", 0), ("W", 6), ("B", 1), ("W", 7), ("W", 8)]
# 전원 흩어짐: 서로 500블록 이상
SPREAD = [(x, z) for x in (-1500, -750, 0, 750, 1500) for z in (-1500, -750, 0, 750, 1500)]
SPREAD = [p for p in SPREAD if p != (0, 0)][:23]

STEPS = [0, 4, 8, 12, 16, 20, 23]


def positions(layout):
    if layout == "spread":
        return [("S%d" % i, p, 0) for i, p in enumerate(SPREAD)]
    out = []
    for kind, idx in NATIONS_ORDER:
        if kind == "B":
            out.append(("B%d" % idx, BASES[idx], 20))
        else:
            out.append(("W%d" % idx, WANDERERS[idx], 0))
    return out


class Server:
    def __init__(self, d, xmx):
        self.d = d
        self.lines = queue.Queue()
        self.log = open(os.path.join(d, "console.log"), "a", encoding="utf-8")
        # Aikar 계열 G1 설정(서버 문서 modlist 7-1 과 같음). 측정을 위해 System.gc() 는 막지 않는다.
        jvm = [os.path.join(JAVA, "java" + EXE), f"-Xms{xmx}", f"-Xmx{xmx}", "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled",
               "-XX:MaxGCPauseMillis=200", "-XX:+UnlockExperimentalVMOptions", "-XX:+AlwaysPreTouch",
               "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20",
               "-XX:InitiatingHeapOccupancyPercent=15", f"-Xlog:gc:file={os.path.join(d, 'gc.log')}:uptime",
               "-jar", "fabric-server.jar", "nogui"]
        env = dict(os.environ)
        env.pop("_JAVA_OPTIONS", None)
        self.p = subprocess.Popen(jvm, cwd=d, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, encoding="utf-8", errors="replace", bufsize=1, env=env)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.p.stdout:
            self.log.write(line)
            self.log.flush()
            self.lines.put(line.rstrip("\n"))

    def cmd(self, c):
        self.p.stdin.write(c + "\n")
        self.p.stdin.flush()

    def drain(self):
        out = []
        while True:
            try:
                out.append(self.lines.get_nowait())
            except queue.Empty:
                return out

    def wait_for(self, pattern, timeout):
        rx = re.compile(pattern)
        end = time.time() + timeout
        seen = []
        while time.time() < end:
            try:
                line = self.lines.get(timeout=1)
            except queue.Empty:
                if self.p.poll() is not None:
                    raise RuntimeError("server exited")
                continue
            seen.append(line)
            m = rx.search(line)
            if m:
                return m, seen
        raise TimeoutError(pattern)

    def ask(self, c, pattern, timeout=30):
        self.drain()
        self.cmd(c)
        return self.wait_for(pattern, timeout)

    def jcmd(self, *args):
        env = dict(os.environ)
        env.pop("JAVA_TOOL_OPTIONS", None)
        env.pop("_JAVA_OPTIONS", None)
        r = subprocess.run([os.path.join(JAVA, "jcmd" + EXE), str(self.p.pid), *args], capture_output=True, text=True, env=env)
        return r.stdout

    def rss_mb(self):
        try:
            with open(f"/proc/{self.p.pid}/status") as f:
                for line in f:
                    if line.startswith("VmRSS"):
                        return int(line.split()[1]) / 1024
        except OSError:
            pass
        return 0

    def stop(self):
        try:
            self.cmd("stop")
            self.p.wait(timeout=180)
        except Exception:
            self.p.kill()


def prepare(run, mods, warmup, view, sim, bluemap=False):
    d = os.path.join(PERF, "runs", run)
    if os.path.exists(d):
        shutil.rmtree(d)
    os.makedirs(os.path.join(d, "mods"))
    shutil.copy(os.path.join(PERF, "dl", "fabric-server.jar"), d)
    if not warmup:
        shutil.copytree(os.path.join(PERF, "world-template"), os.path.join(d, "world"))
    # 서버 jar·라이브러리 캐시 재사용
    for cache in ("libraries", "versions", ".fabric"):
        src = os.path.join(PERF, "cache", cache)
        if os.path.exists(src):
            try:
                os.symlink(src, os.path.join(d, cache), target_is_directory=True)
            except OSError:  # Windows 에서 심볼릭 링크 권한이 없으면 복사
                shutil.copytree(src, os.path.join(d, cache))
    sets = ["base"] + (["opt"] if mods == "opt" else []) + (["bluemap"] if bluemap else [])
    for s in sets:
        for f in os.listdir(os.path.join(PERF, "dl", s)):
            shutil.copy(os.path.join(PERF, "dl", s, f), os.path.join(d, "mods"))
    for f in our_mods():
        shutil.copy(f, os.path.join(d, "mods"))
    with open(os.path.join(d, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    with open(os.path.join(d, "server.properties"), "w") as f:
        f.write(f"online-mode=false\ndifficulty=hard\nlevel-seed={SEED}\nview-distance={view}\nsimulation-distance={sim}\n"
                "spawn-protection=0\nmax-players=40\npause-when-empty-seconds=-1\nenable-rcon=false\n")
    return d


def parse_gc(path, t0, t1):
    """t0~t1(서버 가동 초) 사이 young GC 로 할당률과 정지 시간을 구한다."""
    rx = re.compile(r"\[(\d+\.\d+)s\].*Pause Young.* (\d+)M->(\d+)M\((\d+)M\) (\d+\.\d+)ms")
    evs = []
    with open(path) as f:
        for line in f:
            m = rx.search(line)
            if m:
                evs.append((float(m.group(1)), int(m.group(2)), int(m.group(3)), float(m.group(5))))
    win = [e for e in evs if t0 <= e[0] <= t1]
    if len(win) < 2:
        return None
    alloc = sum(win[i][1] - win[i - 1][2] for i in range(1, len(win)))
    dt = win[-1][0] - win[0][0]
    pauses = [e[3] for e in win]
    return {"alloc_mb_s": round(alloc / dt, 1) if dt > 0 else None, "young_gcs": len(win),
            "pause_avg_ms": round(sum(pauses) / len(pauses), 1), "pause_max_ms": round(max(pauses), 1),
            "after_young_mb": win[-1][2]}


def uptime(srv):
    out = srv.jcmd("VM.uptime")
    m = re.search(r"(\d+\.\d+) s", out)
    return float(m.group(1)) if m else 0.0


def spark_tps(srv):
    srv.drain()
    srv.cmd("spark tps")
    time.sleep(4)
    lines = srv.drain()
    text = "\n".join(lines)
    res = {}
    # 'Tick durations (min/med/95%ile/max ms) from last 10s, 1m:' 다음 줄: a/b/c/d; e/f/g/h
    m = re.search(r"Tick durations.*?\n.*?([\d.]+)/([\d.]+)/([\d.]+)/([\d.]+);\s*([\d.]+)/([\d.]+)/([\d.]+)/([\d.]+)", text)
    if m:
        v = [float(x) for x in m.groups()]
        res = {"mspt_min": v[4], "mspt_med": v[5], "mspt_p95": v[6], "mspt_max": v[7]}
    m = re.search(r"TPS from last.*?\n.*?([\d.*]+), ([\d.*]+), ([\d.*]+)", text)
    if m:
        res["tps_1m"] = float(m.group(3).replace("*", ""))
    m = re.search(r"CPU usage.*?\n.*?\n.*?([\d.]+)%, ([\d.]+)%, ([\d.]+)%\s+\(process\)", text)
    if m:
        res["cpu_proc_10s"] = float(m.group(1))
    if not res:
        res["raw"] = text[-800:]
    return res


def count(srv, selector):
    try:
        m, _ = srv.ask(f"execute if entity {selector}", r"Test (passed|failed)(?:[.,] [Cc]ount: (\d+))?", 20)
        return int(m.group(2) or 0)
    except TimeoutError:
        return -1


def run(args):
    d = prepare(args.run, args.mods, args.warmup, args.view, args.sim, args.bluemap)
    srv = Server(d, args.xmx)
    srv.wait_for(r"Done \(", 900)
    # 캐시 보관(첫 실행)
    for cache in ("libraries", "versions", ".fabric"):
        src = os.path.join(d, cache)
        dst = os.path.join(PERF, "cache", cache)
        if os.path.isdir(src) and not os.path.islink(src) and not os.path.exists(dst) and not args.bluemap:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copytree(src, dst, symlinks=True)
    for c in ["gamerule advance_time false", "gamerule advance_weather false", "time set midnight", "weather clear",
              "difficulty hard", "carpet setDefault commandPlayer true"]:
        srv.cmd(c)
    time.sleep(3)

    if args.warmup:
        srv.cmd("carpet setDefault commandPlayer true")
        # 두 배치의 모든 지점을 한 번씩 생성한다.
        pts = sorted({p for _, p, _ in positions("nations")} | {p for _, p, _ in positions("spread")})
        for i in range(0, len(pts), 6):
            batch = pts[i:i + 6]
            for j, (x, z) in enumerate(batch):
                n = f"w{i + j:02d}"
                srv.cmd(f"player {n} spawn at {x} 200 {z}")
                srv.cmd(f"effect give {n} minecraft:resistance infinite 255 true")
            time.sleep(150)
            for j in range(len(batch)):
                srv.cmd(f"player w{i + j:02d} kill")
            print(f"warmup batch {i // 6 + 1}/{math.ceil(len(pts) / 6)}", flush=True)
        srv.cmd("save-all flush")
        time.sleep(20)
        srv.stop()
        shutil.rmtree(os.path.join(PERF, "world-template"), ignore_errors=True)
        shutil.copytree(os.path.join(d, "world"), os.path.join(PERF, "world-template"))
        return

    steps = [int(x) for x in args.steps.split(",")] if getattr(args, "steps", "") else STEPS
    if args.bluemap:
        # 기본 설정이 만들어진 뒤 다운로드 동의로 바꾸고 다시 읽혀 전체 렌더를 시작시킨다.
        conf = os.path.join(d, "config", "bluemap", "core.conf")
        for _ in range(30):
            if os.path.exists(conf):
                break
            time.sleep(2)
        txt = open(conf).read().replace("accept-download: false", "accept-download: true")
        txt = re.sub(r"^#?[ \t]*render-thread-count:.*$", "render-thread-count: 2", txt, flags=re.M)
        open(conf, "w").write(txt)
        srv.cmd("bluemap reload")
        time.sleep(30)
        steps = [0, 8, 12]
    pos = positions(args.layout)
    anchors_done = set()
    results = []
    spawned = 0
    for n in steps:
        while spawned < n:
            anchor, (x, z), spread = pos[spawned]
            name = f"b{spawned:02d}"
            srv.cmd(f"player {name} spawn at {x} 200 {z}")
            time.sleep(0.5)
            srv.cmd(f"effect give {name} minecraft:resistance infinite 255 true")
            srv.cmd(f"effect give {name} minecraft:saturation infinite 255 true")
            srv.cmd(f"spreadplayers {x} {z} 1 {max(2, spread)} false {name}")
            if args.mobs == "x2" and anchor not in anchors_done:
                anchors_done.add(anchor)
                tag = "x" + anchor
                for _ in range(70):
                    srv.cmd(f"summon minecraft:zombie {x} 200 {z} {{PersistenceRequired:1b,Tags:[\"{tag}\"]}}")
                srv.cmd(f"spreadplayers {x} {z} 1 96 false @e[tag={tag}]")
            spawned += 1
        time.sleep(args.settle)
        t1 = uptime(srv)
        tps = spark_tps(srv)
        ents = count(srv, "@e[type=!minecraft:player]")
        monsters = count(srv, "@e[type=minecraft:zombie]")
        hostile = count(srv, "@e[type=#minecraft:undead]")
        items = count(srv, "@e[type=minecraft:item]")
        gc = parse_gc(os.path.join(d, "gc.log"), t1 - args.settle + 10, t1)
        srv.jcmd("GC.run")
        time.sleep(3)
        heap = srv.jcmd("GC.heap_info")
        m = re.search(r"used (\d+)K", heap)
        live = int(m.group(1)) / 1024 if m else None
        row = {"players": n, "entities": ents, "zombies": monsters, "undead": hostile, "items": items, "live_heap_mb": round(live) if live else None,
               "rss_mb": round(srv.rss_mb()), **tps, **(gc or {})}
        results.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)
    srv.stop()
    os.makedirs(os.path.join(PERF, "results"), exist_ok=True)
    with open(os.path.join(PERF, "results", f"result-{args.run}.json"), "w") as f:
        json.dump({"run": args.run, "bluemap": args.bluemap, "cpu": cpu_name(), "mods": args.mods, "layout": args.layout, "mobs": args.mobs, "view": args.view,
                   "sim": args.sim, "rows": results}, f, ensure_ascii=False, indent=1)


def cpu_name():
    try:
        import platform
        if os.name == "nt":
            return platform.processor()
        with open("/proc/cpuinfo") as f:
            for line in f:
                if line.startswith("model name"):
                    return line.split(":", 1)[1].strip()
        return platform.processor()
    except Exception:
        return ""


STANDARD = [("base-nations-x2", "base", "nations"), ("opt-nations-x2", "opt", "nations"),
            ("opt-spread-x2", "opt", "spread"), ("base-spread-x2", "base", "spread")]


def summary():
    import importlib.util
    spec = importlib.util.spec_from_file_location("fit_cost", os.path.join(HERE, "..", "sim", "fit_cost.py"))
    fc = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fc)
    files = sorted(glob.glob(os.path.join(PERF, "results", "result-*.json")))
    for path in files:
        r = json.load(open(path))
        print(f"\n{r['run']} ({r.get('cpu', '')})")
        print("  인원  엔티티  힙MB  MSPT중앙  MSPT95%  TPS")
        for x in r["rows"]:
            print(f"  {x['players']:>4}  {x.get('entities', 0):>6}  {x.get('live_heap_mb') or 0:>4}  {x.get('mspt_med', 0):>8}  {x.get('mspt_p95', 0):>7}  {x.get('tps_1m', 0)}")
    sys.argv = ["fit_cost"] + files
    print("\n비용 모델(load_sim.py --cost 에 넣는다):")
    fc.main()


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("setup")
    sub.add_parser("summary")
    for name in ("run", "all", "warmup"):
        p = sub.add_parser(name)
        if name == "run":
            p.add_argument("run")
        p.add_argument("--mods", default="base")
        p.add_argument("--layout", default="nations")
        p.add_argument("--mobs", default="x2")
        p.add_argument("--view", type=int, default=10)
        p.add_argument("--sim", type=int, default=8)
        p.add_argument("--xmx", default="6G")
        p.add_argument("--settle", type=int, default=75)
        p.add_argument("--bluemap", action="store_true")
        p.add_argument("--steps", default="", help="봇 수 단계, 예: 0,4,8 (기본 0,4,8,12,16,20,23)")
    a = ap.parse_args()
    if a.cmd == "setup":
        return setup()
    if a.cmd == "summary":
        return summary()
    if not os.path.exists(os.path.join(PERF, "dl", "fabric-server.jar")):
        setup()
    if a.cmd == "warmup" or (a.cmd == "all" and not os.path.exists(os.path.join(PERF, "world-template"))):
        w = argparse.Namespace(**vars(a))
        w.run, w.mods, w.warmup, w.bluemap = "warmup", "opt", True, False
        run(w)
    if a.cmd == "run":
        a.warmup = False
        run(a)
    if a.cmd == "all":
        for name, mods, layout in STANDARD:
            r = argparse.Namespace(**vars(a))
            r.run, r.mods, r.layout, r.warmup, r.bluemap = name, mods, layout, False, False
            print(f"=== {name}", flush=True)
            run(r)
        summary()


if __name__ == "__main__":
    main()
