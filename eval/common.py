"""평가 공통: 캐시 로드, 채점(MIREX 가중 점수), 고전적 키 추정 블록."""
import json, os
import numpy as np

ROOT = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(ROOT, "cache")
BPS = 3
MIDI_LO = 28
NAMES = ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"]

PROFILES = {
    "KK": ([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88],
           [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17]),
    "Temperley": ([5, 2, 3.5, 2, 4.5, 4, 2, 4.5, 2, 3.5, 1.5, 4],
                  [5, 2, 3.5, 4.5, 2, 4, 2, 4.5, 3.5, 2, 1.5, 4]),
    "AlbSha": ([0.238, 0.006, 0.111, 0.006, 0.137, 0.094, 0.016, 0.214, 0.009, 0.080, 0.008, 0.081],
               [0.220, 0.006, 0.104, 0.123, 0.019, 0.103, 0.012, 0.214, 0.062, 0.022, 0.061, 0.052]),
    "EDMA": ([0.16519551, 0.04749026, 0.08293076, 0.06687112, 0.09994645, 0.09274123, 0.05294487, 0.13159476,
              0.05218986, 0.07443653, 0.06940723, 0.06424152],
             [0.17235348, 0.04, 0.0761009, 0.12001464, 0.05221771, 0.07803302, 0.0451661, 0.12280546,
              0.08130844, 0.04919016, 0.07188638, 0.04911236]),
}


def load(sets=("gs", "mtg", "gtzan")):
    lab = json.load(open(os.path.join(CACHE, "labels.json")))
    items = []
    for name, meta in sorted(lab.items()):
        if name.split("/")[0] in sets:
            spec = np.load(os.path.join(CACHE, name + ".npy")).astype(np.float32)
            items.append((name, spec, tuple(meta["key"]), meta.get("genre")))
    return items


def key_index(tonic, minor):
    return tonic + 12 * minor


def mirex(pred, true):
    """MIREX 가중 점수: 정답 1, 5도 0.5, 관계조 0.3, 동주조 0.2."""
    (pt, pm), (tt, tm) = pred, true
    if pt == tt and pm == tm:
        return 1.0
    if pm == tm and (pt - tt) % 12 in (5, 7):
        return 0.5
    if pm != tm:
        if tm == 0 and pm == 1 and (pt - tt) % 12 == 9:
            return 0.3
        if tm == 1 and pm == 0 and (pt - tt) % 12 == 3:
            return 0.3
        if pt == tt:
            return 0.2
    return 0.0


def report(results, label=""):
    """results: [(name, pred, true)] -> 세트별 정확도/MIREX 출력."""
    rows = []
    for s in ("gs", "mtg", "gtzan"):
        r = [(p, t) for n, p, t in results if n.startswith(s + "/")]
        if not r:
            continue
        acc = np.mean([p == t for p, t in r])
        mx = np.mean([mirex(p, t) for p, t in r])
        rows.append(f"{s}: acc {acc*100:5.1f} mirex {mx*100:5.1f} (n={len(r)})")
    print(f"{label:28s} " + " | ".join(rows))


# ---- 신호 처리 블록 ----

def semitones(spec, tune=True):
    """(T, 3*S) -> (T, S). 튜닝 보정 후 각 반음의 중앙칸 주변을 합친다."""
    T, B = spec.shape
    S = B // BPS
    if tune:
        e = spec.mean(0)
        best = max(range(BPS), key=lambda j: e[j::BPS].sum())
        # best 칸이 중심(1)이 되도록 정수 칸 이동
        shift = best - 1
        if shift:
            spec = np.roll(spec, -shift, axis=1)
    return spec.reshape(T, S, BPS).sum(2)


def chroma(sem, lo=0, hi=None):
    S = sem.shape[1]
    hi = S if hi is None else hi
    c = np.zeros((sem.shape[0], 12), np.float32)
    for s in range(lo, hi):
        c[:, (MIDI_LO + s) % 12] += sem[:, s]
    return c


def suppress_harmonics(sem, a3=0.5, a5=0.3, a6=0.25):
    sem = sem.copy()
    S = sem.shape[1]
    for s in range(S):
        for h, a in ((19, a3), (28, a5), (31, a6)):
            if a and s + h < S:
                sem[:, s + h] = np.maximum(0, sem[:, s + h] - a * sem[:, s])
    return sem


def profile_scores(c, prof):
    ma, mi = (np.array(p, float) for p in PROFILES[prof])
    out = np.zeros(24)
    for m, p in ((0, ma), (1, mi)):
        for t in range(12):
            out[t + 12 * m] = np.corrcoef(c, np.roll(p, t))[0, 1]
    return out


def argkey(scores):
    i = int(np.argmax(scores))
    return (i % 12, i // 12)
