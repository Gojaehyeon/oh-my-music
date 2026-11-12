"""빌드된 OhMyMusic 바이너리(--analyze)로 데이터셋을 채점한다. 앱 실제 동작의 점수.

사용: python3 swift_eval.py [gs|mtg|gtzan ...]
"""
import json, os, re, subprocess, sys
from concurrent.futures import ThreadPoolExecutor
from common import CACHE, ROOT, report
from prep import labels

BIN = os.path.join(ROOT, "..", ".build", "release", "OhMyMusic")
NAME = {"C": 0, "C♯": 1, "D": 2, "E♭": 3, "E": 4, "F": 5, "F♯": 6, "G": 7, "A♭": 8, "A": 9, "B♭": 10, "B": 11}
sets = sys.argv[1:] or ["gs"]
lab = {k: v for k, v in labels().items()
       if k.split("/")[0] in sets and v["split"] == "test" and os.path.exists(v["audio"])}


def run(item):
    name, meta = item
    out = subprocess.run([BIN, "--analyze", meta["audio"]], capture_output=True, text=True).stdout
    res = {}
    for tag in ("누적", "20초"):
        m = re.search(rf"키\({tag}\): (\S+) (major|minor)", out)
        res[tag] = (NAME[m.group(1)], int(m.group(2) == "minor")) if m else (-1, 0)
    return name, res, tuple(meta["key"])


with ThreadPoolExecutor(14) as ex:
    rows = list(ex.map(run, lab.items()))
for tag in ("누적", "20초"):
    report([(n, r[tag], k) for n, r, k in rows], f"swift app ({tag})")
