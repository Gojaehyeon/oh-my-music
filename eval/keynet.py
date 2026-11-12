"""키 분류 CNN 정의와 입력 규격(학습·변환·앱이 공유)."""
import torch.nn as nn
from common import BPS

FPS = 5                      # 캐시는 10fps → 2프레임 평균
LO, HI = 36, 96              # 입력 범위(반음): C2(65Hz) ~ C7(2093Hz) 미만
NB = (HI - LO) * BPS         # 180칸


class KeyNet(nn.Module):
    def __init__(self, w=8):
        super().__init__()
        layers, c = [], 1
        for _ in range(5):
            layers += [nn.Conv2d(c, w, 5, padding=2), nn.BatchNorm2d(w), nn.ELU()]
            c = w
        self.conv = nn.Sequential(*layers)
        self.dense = nn.Sequential(nn.Conv2d(w, 48, (1, NB)), nn.BatchNorm2d(48), nn.ELU(), nn.Dropout(0.3),
                                   nn.Conv2d(48, 24, 1))

    def forward(self, x):            # x: (B, T, NB)
        x = self.conv(x.unsqueeze(1))
        x = self.dense(x)            # (B, 24, T, 1)
        return x.mean(dim=(2, 3))    # 시간 평균 → 로짓
