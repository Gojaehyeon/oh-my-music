"""학습한 KeyNet 가중치를 Core ML(.mlpackage)로 변환한다.

사용: .venv/bin/python export.py ../Models/KeyNet.mlpackage runs/a.pt:8 runs/b.pt:16 ...  (경로:채널폭, 여러 개면 확률 평균)
입력 spec: (1, T, 180) = log1p(로그주파수 스펙트럼), T 는 25~1500 프레임(5fps)
출력 probs: (1, 24) — 0~11 장조(C..B), 12~23 단조
"""
import sys
import numpy as np
import torch, coremltools as ct

from keynet import KeyNet, NB

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
traced = torch.jit.trace(model, torch.zeros(1, 100, NB))
mlmodel = ct.convert(
    traced,
    inputs=[ct.TensorType(name="spec", shape=(1, ct.RangeDim(25, 1500, default=100), NB), dtype=np.float32)],
    outputs=[ct.TensorType(name="probs", dtype=np.float32)],
    minimum_deployment_target=ct.target.macOS14,
    convert_to="mlprogram",
    compute_precision=ct.precision.FLOAT32,  # FP16 은 앙상블에서 예측이 뒤바뀌었다
)
mlmodel.short_description = "Oh My Music key classifier (24 keys)"
mlmodel.save(dst)

# 변환 검증: 같은 입력에 torch 와 Core ML 출력 비교
import glob
from common import MIDI_LO, BPS
from keynet import LO
a = (LO - MIDI_LO) * BPS
cpu = ct.models.MLModel(dst, compute_units=ct.ComputeUnit.CPU_ONLY)  # 앱과 같은 실행 장치
worst, agree, files = 0.0, 0, sorted(glob.glob("cache/gs/*.npy"))[:50]
for f in files:
    spec = np.load(f).astype(np.float32)
    n = len(spec) // 2 * 2
    x = np.log1p((spec[:n:2] + spec[1:n:2]) / 2)[:, a:a + NB][None].astype(np.float32)
    ref = model(torch.tensor(x)).detach().numpy()
    got = cpu.predict({"spec": x})["probs"]
    worst = max(worst, float(np.abs(ref - got).max()))
    agree += int(ref.argmax() == got.argmax())
print(f"실제 곡 {len(files)}개: 최대 확률 차 {worst:.2e}, argmax 일치 {agree}/{len(files)}")
