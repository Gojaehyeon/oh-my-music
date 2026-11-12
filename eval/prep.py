"""데이터셋 정답을 통합하고 곡별 로그 주파수 스펙트로그램을 캐시한다.

사용: python3 prep.py  (eval/data 아래 오디오가 받아져 있어야 한다)
출력: eval/cache/<set>/<id>.npy (T x BINS float16), eval/cache/labels.json
"""
import csv, glob, hashlib, json, os, re, subprocess, sys
from multiprocessing import Pool
import numpy as np

ROOT = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(ROOT, "data")
CACHE = os.path.join(ROOT, "cache")

SR = 22050
N_FFT = 8192
HOP = 2205            # 10 fps
MIDI_LO, MIDI_HI = 28, 107   # E1 ~ B7
BPS = 3               # 반음당 칸 수
BINS = (MIDI_HI - MIDI_LO + 1) * BPS

NAMES = {"C": 0, "C#": 1, "DB": 1, "D": 2, "D#": 3, "EB": 3, "E": 4, "FB": 4, "E#": 5, "F": 5, "F#": 6, "GB": 6,
         "G": 7, "G#": 8, "AB": 8, "A": 9, "A#": 10, "BB": 10, "B": 11, "CB": 11, "B#": 0}


def parse_key(s):
    m = re.match(r"\s*([A-G][#b♯♭]?)\s*(major|minor|maj|min)?", s.strip()[:1].upper() + s.strip()[1:], re.I)
    if not m:
        return None
    tonic = NAMES.get(m.group(1).upper().replace("♯", "#").replace("♭", "B"))
    if tonic is None:
        return None
    return [tonic, 1 if (m.group(2) or "").lower().startswith("min") else 0]


def bucket(group, n=10):
    return int(hashlib.md5(group.encode()).hexdigest(), 16) % n


def split_of(name, group=None):
    """학습/검증/평가 분할. 같은 곡(중복 등록)은 group 으로 묶어 한쪽에만 들어가게 한다."""
    s, tid = name.split("/", 1)
    if s == "gs":
        return "test"
    if s == "gtzan":
        n = int(tid.split(".")[-1])
        return "test" if n >= 50 else ("val" if n >= 45 else "train")
    if s == "mtg":
        return "val" if int(tid) % 10 == 0 else "train"
    b = bucket(group or tid)
    return "test" if b < 2 else ("val" if b == 2 else "train")


def labels():
    out = {}
    d = os.path.join(DATA, "gs-key/annotations/key")
    for f in sorted(os.listdir(d)):
        k = parse_key(open(os.path.join(d, f)).read())
        tid = f.replace(".key", "")
        if k:
            out[f"gs/{tid}"] = {"key": k, "audio": os.path.join(DATA, "gs-audio", tid + ".mp3")}
    for line in open(os.path.join(DATA, "mtg-key/annotations/annotations.txt")).read().splitlines()[1:]:
        tid, key, conf = line.split("\t")[:3]
        k = parse_key(key)
        if k and conf.strip() == "2":
            out[f"mtg/{tid}"] = {"key": k, "audio": os.path.join(DATA, "mtg-audio", tid + ".LOFI.mp3")}
    gk = os.path.join(DATA, "gtzan-key/gtzan_key/genres")
    for g in sorted(os.listdir(gk)):
        for f in sorted(x for x in os.listdir(os.path.join(gk, g)) if x.endswith(".lerch.txt")):
            v = int(open(os.path.join(gk, g, f)).read().split()[0])
            if v < 0:
                continue
            # 0=A 장조 … 11=G# 장조, 12~23 = 단조
            tonic = (v % 12 + 9) % 12
            tid = f.replace(".lerch.txt", "")
            cand = [os.path.join(DATA, "gtzan-audio/genres", g, tid + ext) for ext in (".au", ".wav")]
            audio = next((c for c in cand if os.path.exists(c)), cand[0])
            out[f"gtzan/{tid}"] = {"key": [tonic, 1 if v >= 12 else 0], "audio": audio, "genre": g}
    fdir = os.path.join(DATA, "fmak")
    if os.path.exists(os.path.join(fdir, "fmakv2.csv")):
        paths = {os.path.basename(p)[:-4]: p for p in glob.glob(os.path.join(fdir, "audio", "**", "*.mp3"), recursive=True)}
        for row in list(csv.reader(open(os.path.join(fdir, "fmakv2.csv"))))[1:]:
            _, key, tid, uri = row
            k = parse_key(key)
            tid = f"{int(tid):06d}"
            if k and tid in paths:
                out[f"fmak/{tid}"] = {"key": k, "audio": paths[tid], "group": uri if uri.startswith("spotify:") else tid}
    for name, meta in out.items():
        meta["split"] = split_of(name, meta.get("group"))
    return out


def filterbank():
    freqs = np.arange(N_FFT // 2 + 1) * SR / N_FFT
    # 각 로그 칸 중심 주파수 기준 삼각 필터(이웃 칸까지), 저역에서 FFT 해상도보다 좁으면 최근접 칸
    centers = 440 * 2 ** ((MIDI_LO - 69 + (np.arange(BINS) - (BPS // 2)) / BPS) / 12)
    fb = np.zeros((BINS, len(freqs)), np.float32)
    for i, c in enumerate(centers):
        lo, hi = c * 2 ** (-1 / (12 * BPS)), c * 2 ** (1 / (12 * BPS))
        w = np.maximum(0, 1 - np.abs(freqs - c) / np.where(freqs < c, c - lo, hi - c))
        if w.sum() == 0:
            w[np.argmin(np.abs(freqs - c))] = 1
        fb[i] = w / w.sum()
    return fb


FB = filterbank()
WIN = np.hanning(N_FFT).astype(np.float32)


def decode(path):
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", path, "-ac", "1", "-ar", str(SR), "-f", "f32le", "-"],
                         capture_output=True).stdout
    return np.frombuffer(raw, np.float32)


def logspec(x):
    if len(x) < N_FFT:
        return np.zeros((0, BINS), np.float16)
    frames = np.lib.stride_tricks.sliding_window_view(x, N_FFT)[::HOP]
    mag = np.abs(np.fft.rfft(frames * WIN, axis=1)).astype(np.float32)
    return (mag @ FB.T).astype(np.float16)


def work(item):
    name, meta = item
    dst = os.path.join(CACHE, name + ".npy")
    if os.path.exists(dst):
        return name, True
    if not os.path.exists(meta["audio"]) or os.path.getsize(meta["audio"]) < 1000:
        return name, False
    x = decode(meta["audio"])
    if len(x) < SR * 5:
        return name, False
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    np.save(dst, logspec(x))
    return name, True


if __name__ == "__main__":
    lab = labels()
    with Pool(14) as p:
        ok = dict(p.imap_unordered(work, lab.items(), chunksize=4))
    lab = {k: v for k, v in lab.items() if ok.get(k)}
    json.dump(lab, open(os.path.join(CACHE, "labels.json"), "w"))
    for s in ("gs", "mtg", "gtzan"):
        print(s, sum(1 for k in lab if k.startswith(s + "/")))
