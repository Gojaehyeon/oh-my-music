"""고전적(프로필 상관) 키 추정의 변형들을 데이터셋 전체로 비교한다.

사용: python3 classic.py [--seconds 30]
"""
import argparse
import numpy as np
from common import (load, report, semitones, chroma, suppress_harmonics, profile_scores, argkey, MIDI_LO)

ap = argparse.ArgumentParser()
ap.add_argument("--seconds", type=float, default=0, help="앞부분 N초만 사용(0=전체)")
args = ap.parse_args()

items = load(("gs", "gtzan"))
FPS = 10


def variant(spec, *, tune=True, sup=False, hi_midi=84, lo_midi=31, comp="lin", prof="Temperley", frame_norm=True):
    if args.seconds:
        spec = spec[: int(args.seconds * FPS)]
    sem = semitones(spec, tune)
    if comp == "log":
        sem = np.log1p(100 * sem)
    elif comp == "sqrt":
        sem = np.sqrt(sem)
    if sup:
        sem = suppress_harmonics(sem)
    c = chroma(sem, lo=lo_midi - MIDI_LO, hi=hi_midi - MIDI_LO)
    if frame_norm:
        c = c / np.maximum(c.sum(1, keepdims=True), 1e-9)
    return argkey(profile_scores(c.sum(0), prof))


configs = {
    "temperley lin": dict(),
    "temperley lin notune": dict(tune=False),
    "temperley log": dict(comp="log"),
    "temperley sqrt": dict(comp="sqrt"),
    "temperley lin sup": dict(sup=True),
    "temperley log sup": dict(comp="log", sup=True),
    "temperley log hi96": dict(comp="log", hi_midi=96),
    "KK log": dict(comp="log", prof="KK"),
    "AlbSha log": dict(comp="log", prof="AlbSha"),
    "EDMA log": dict(comp="log", prof="EDMA"),
    "AlbSha lin sup": dict(sup=True, prof="AlbSha"),
}
for label, kw in configs.items():
    report([(n, variant(s, **kw), k) for n, s, k, _ in items], label)
