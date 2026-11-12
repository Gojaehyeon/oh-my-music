"""여러 체크포인트의 확률 평균(앙상블)을 평가한다.

사용: python3 ensemble.py runs/a.pt:8 runs/b.pt:16 ...   (경로:채널폭)
"""
import json, os, sys
import numpy as np
import torch
from common import CACHE, MIDI_LO, BPS, mirex
from keynet import KeyNet, LO, NB, FPS

models = []
for arg in sys.argv[1:]:
    path, width = arg.rsplit(":", 1)
    m = KeyNet(int(width))
    m.load_state_dict(torch.load(path, map_location="cpu"))
    models.append(m.eval())

lab = json.load(open(os.path.join(CACHE, "labels.json")))
a = (LO - MIDI_LO) * BPS


rows = {s: [] for s in (0, 20)}
with torch.no_grad():
    for name, meta in sorted(lab.items()):
        if meta["split"] != "test":
            continue
        spec = np.load(os.path.join(CACHE, name + ".npy")).astype(np.float32)
        n = len(spec) // 2 * 2
        spec = np.log1p((spec[:n:2] + spec[1:n:2]) / 2)[:, a:a + NB]
        for sec in rows:
            s = spec if not sec else spec[max(0, len(spec) // 2 - sec * FPS // 2):][: sec * FPS]
            x = torch.tensor(s)[None]
            p = sum(torch.softmax(m(x), 1) for m in models)
            i = int(p.argmax())
            rows[sec].append((name, (i % 12, i // 12), tuple(meta["key"])))

for sec, r in rows.items():
    out = []
    for s in ("gs", "gtzan", "fmak"):
        rr = [(p, t) for n, p, t in r if n.startswith(s + "/")]
        if rr:
            out.append(f"{s} acc {np.mean([p == t for p, t in rr])*100:.1f} mirex {np.mean([mirex(p, t) for p, t in rr])*100:.1f}")
    print(f"[{'전체' if not sec else f'{sec}초'}] " + " | ".join(out))
