"""학습한 KeyNet 앙상블을 ONNX 로 변환한다(Android 용, ONNX Runtime).

사용: .venv/bin/python export_onnx.py ../android/app/src/main/assets/keynet.onnx runs/a.pt:8 runs/b.pt:16 ...
입력 spec: (1, T, 180) float32 = log1p(로그주파수 스펙트럼), T 는 가변(5fps)
출력 probs: (1, 24) — 0~11 장조(C..B), 12~23 단조. Core ML 판(export.py)과 같은 규격.
"""
import glob, sys
import numpy as np
import torch
import onnxruntime as ort

from common import MIDI_LO, BPS
from keynet import KeyNet, NB, LO

dst = sys.argv[1]
members = []
for arg in sys.argv[2:]:
    path, width = arg.rsplit(":", 1)
    m = KeyNet(int(width))
    m.load_state_dict(torch.load(path, map_location="cpu"))
    members.append(m.eval())


class Ensemble(torch.nn.Module):
    def __init__(self, ms):
        super().__init__()
        self.ms = torch.nn.ModuleList(ms)

    def forward(self, x):
        return sum(torch.softmax(m(x), dim=1) for m in self.ms) / len(self.ms)


model = Ensemble(members).eval()
torch.onnx.export(model, torch.zeros(1, 100, NB), dst, input_names=["spec"], output_names=["probs"],
                  dynamic_axes={"spec": {1: "frames"}}, opset_version=17, dynamo=False)

# 변환 검증: 실제 곡 스펙트로그램으로 torch 와 ONNX Runtime 출력 비교
sess = ort.InferenceSession(dst, providers=["CPUExecutionProvider"])
a = (LO - MIDI_LO) * BPS
worst, agree, files = 0.0, 0, sorted(glob.glob("cache/gs/*.npy"))[:50]
for f in files:
    spec = np.load(f).astype(np.float32)
    n = len(spec) // 2 * 2
    x = np.log1p((spec[:n:2] + spec[1:n:2]) / 2)[:, a:a + NB][None].astype(np.float32)
    ref = model(torch.tensor(x)).detach().numpy()
    got = sess.run(["probs"], {"spec": x})[0]
    worst = max(worst, float(np.abs(ref - got).max()))
    agree += int(ref.argmax() == got.argmax())
print(f"실제 곡 {len(files)}개: 최대 확률 차 {worst:.2e}, argmax 일치 {agree}/{len(files)}")
