"""키 분류 CNN 학습 (Korzeniowski & Widmer 2017/2018 방식을 직접 재구현, 가중치는 자체 학습).

학습: MTG(신뢰도 2) + GTZAN 앞절반(장르별 00~49)
평가: GiantSteps Key 604 + GTZAN 뒷절반(50~99)
사용: python3 train.py [--epochs 60] [--out model.pt]
"""
import argparse, json, os, random, time
import numpy as np
import torch, torch.nn as nn, torch.nn.functional as F
from common import CACHE, MIDI_LO, BPS, mirex
from keynet import FPS, LO, HI, NB, KeyNet

ap = argparse.ArgumentParser()
ap.add_argument("--epochs", type=int, default=60)
ap.add_argument("--seg", type=float, default=20, help="학습 구간(초)")
ap.add_argument("--width", type=int, default=8)
ap.add_argument("--seed", type=int, default=0)
ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "model.pt"))
args = ap.parse_args()
random.seed(args.seed); np.random.seed(args.seed); torch.manual_seed(args.seed)

SHIFT_MIN, SHIFT_MAX = -6, 5  # 증강: 반음 이동 범위(캐시 28~107 안에서 잘라낸다)
dev = "mps" if torch.backends.mps.is_available() else "cpu"


def to5fps(s):
    n = len(s) // 2 * 2
    return (s[:n:2] + s[1:n:2]) / 2


lab = json.load(open(os.path.join(CACHE, "labels.json")))
data = {"train": [], "val": [], "test": []}
for name, meta in sorted(lab.items()):
    spec = to5fps(np.load(os.path.join(CACHE, name + ".npy")).astype(np.float32))
    data[meta["split"]].append((name, spec, meta["key"]))
print({k: len(v) for k, v in data.items()}, "device", dev)


def crop_bins(spec, k):
    """k 반음 위에서 잘라내면 내용은 k 반음 낮아진 것으로 보인다 → 라벨 tonic - k."""
    a = (LO + k - MIDI_LO) * BPS
    return spec[:, a:a + NB]


def batch(items, seg):
    xs, ys = [], []
    for _, spec, (tonic, minor) in items:
        k = random.randint(SHIFT_MIN, SHIFT_MAX)
        t = len(spec)
        a = random.randint(0, max(0, t - seg))
        x = crop_bins(spec[a:a + seg], k)
        if len(x) < seg:
            x = np.pad(x, ((0, seg - len(x)), (0, 0)))
        xs.append(np.log1p(x * 10 ** random.uniform(-1, 0.5)))  # 음량 -20dB~+10dB
        ys.append((tonic - k) % 12 + 12 * minor)
    return torch.tensor(np.stack(xs), dtype=torch.float32), torch.tensor(ys)


@torch.no_grad()
def evaluate(model, seconds=0, part="test"):
    model.eval()
    rows = []
    for name, spec, (tonic, minor) in data[part]:
        s = spec if not seconds else spec[max(0, len(spec) // 2 - int(seconds * FPS) // 2):][: int(seconds * FPS)]
        x = torch.tensor(np.log1p(crop_bins(s, 0)))[None].to(dev)
        i = int(model(x).argmax())
        rows.append((name, (i % 12, i // 12), (tonic, minor)))
    model.train()
    out = {}
    for s in ("gs", "gtzan", "mtg", "fmak"):
        r = [(p, t) for n, p, t in rows if n.startswith(s + "/")]
        if not r:
            continue
        out[s] = (np.mean([p == t for p, t in r]) * 100, np.mean([mirex(p, t) for p, t in r]) * 100)
    return out


model = KeyNet(args.width).to(dev)
print("params", sum(p.numel() for p in model.parameters()))
opt = torch.optim.AdamW(model.parameters(), lr=2e-3, weight_decay=1e-3)
steps_per_epoch = len(data["train"]) // 32
sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=3e-3, total_steps=args.epochs * steps_per_epoch)
seg = int(args.seg * FPS)
best = -1
for ep in range(args.epochs):
    random.shuffle(data["train"])
    t0, losses = time.time(), []
    for b in range(steps_per_epoch):
        x, y = batch(data["train"][b * 32:(b + 1) * 32], seg)
        loss = F.cross_entropy(model(x.to(dev)), y.to(dev), label_smoothing=0.05)
        opt.zero_grad(); loss.backward(); opt.step(); sched.step()
        losses.append(loss.item())
    if ep % 5 == 4 or ep == args.epochs - 1:
        r = evaluate(model, part="val")
        score = sum(v[1] for v in r.values())
        if score > best:
            best = score
            torch.save(model.state_dict(), args.out)
        print(f"ep {ep+1:3d} loss {np.mean(losses):.3f} | val " +
              " ".join(f"{k} {v[0]:.1f}/{v[1]:.1f}" for k, v in r.items()) + f" | {time.time()-t0:.0f}s", flush=True)

model.load_state_dict(torch.load(args.out))
for sec in (0, 30, 20, 10):
    r = evaluate(model, sec)
    print(f"[{'전체' if not sec else f'{sec}초'}] gs acc {r['gs'][0]:.1f} mirex {r['gs'][1]:.1f} | "
          f"gtzan acc {r['gtzan'][0]:.1f} mirex {r['gtzan'][1]:.1f}")
