# Oh My Music

지금 재생 중인 음악의 **키와 BPM**을 맥 메뉴바에 바로 보여 주는 앱입니다.

Spotify, Apple Music, YouTube 등 어떤 앱에서 나오는 소리든 시스템 오디오를 직접 듣고 분석합니다. 음악이 없을 때는 메뉴바에서 사라집니다.

```
A♭m · 128
```

메뉴를 열면 조성(장조/단조), 카멜롯 코드(DJ 믹싱용, 예: 1A), 소수점 BPM, 확신도를 볼 수 있습니다.

## 설치

1. [Releases](https://github.com/Gojaehyeon/oh-my-music/releases/latest)에서 `OhMyMusic-1.0.dmg` 를 받습니다. (Apple 공증 완료)
2. dmg 를 열고 **Oh My Music** 을 Applications 로 끌어 놓습니다.
3. 처음 실행하면 **시스템 오디오 녹음** 권한을 묻습니다. 허용해 주십시오.
   - 나중에 바꾸려면: 시스템 설정 > 개인정보 보호 및 보안 > 화면 및 시스템 오디오 녹음

요구 사항: macOS 14.4 이상, Apple Silicon

소리는 기기 안에서만 분석하며, 녹음하거나 저장하거나 전송하지 않습니다.

## 사용법

- 음악을 재생하면 약 5초 뒤 BPM, 약 10~20초 뒤 키가 표시됩니다. 오래 들을수록 키가 안정됩니다.
- 곡이 바뀌었는데 이전 키가 남아 있으면 메뉴의 **다시 분석(⌘R)** 을 누릅니다. 4초 이상 소리가 없으면 자동으로 초기화됩니다.
- 음악이 없어서 아이콘이 숨겨진 상태에서 메뉴가 필요하면, 앱을 한 번 더 열면 아이콘이 나타납니다.

## 정확도

학습에 쓰지 않은 곡으로 측정한 키 정답률입니다.

| 데이터셋 | 곡 전체 | 20초만 듣고 |
|---|---|---|
| GiantSteps Key (EDM 604곡) | 66.9% | 62.4% |
| GTZAN Key (10개 장르, 418곡) | 73.2% | 69.4% |

틀리는 경우는 대부분 관계조(예: B 장조 ↔ A♭ 단조)나 5도 관계 조와의 혼동입니다. 관계조는 카멜롯 번호가 같습니다(1A/1B).

## 동작 방식

- **오디오 캡처:** Core Audio 프로세스 탭(macOS 14.2+)으로 시스템 출력음 전체를 받습니다. 화면 녹화 권한은 필요 없습니다.
- **키:** 22.05kHz로 리샘플한 뒤 반음당 3칸 로그 주파수 스펙트로그램(65Hz~2.1kHz, 초당 5프레임)을 만들고, 합성곱 신경망 5개의 앙상블(Core ML, 1.5MB)로 24개 조성을 분류합니다. 구조는 Korzeniowski & Widmer(2017, 2018)의 키 분류 CNN을 따랐고, 가중치는 직접 학습했습니다.
- **BPM:** 스펙트럴 플럭스 온셋 엔벨로프의 자기상관으로 박자 간격을 찾고, 최근 추정값의 중앙값을 씁니다.

## 빌드

```sh
./build.sh --open     # 빌드 후 ~/Applications 에 설치하고 실행
./release.sh          # 공증된 dmg 생성 (Developer ID 인증서와 asc 인증 필요)
.build/release/OhMyMusic --analyze 곡.mp3   # 파일로 분석 결과 확인
```

## 모델 재학습 (`eval/`)

```sh
cd eval
python3 prep.py                      # 데이터셋 정답 통합 + 스펙트로그램 캐시 (data/ 에 오디오 필요)
python3 train.py --epochs 120 --width 16 --out runs/m.pt
python3 ensemble.py runs/m.pt:16     # 평가
.venv/bin/python export.py ../Models/KeyNet.mlpackage runs/m.pt:16   # Core ML 변환
python3 swift_eval.py gs gtzan       # 빌드된 앱 바이너리로 채점
```

데이터셋: [GiantSteps Key](https://github.com/GiantSteps/giantsteps-key-dataset), [GiantSteps-MTG Key](https://github.com/GiantSteps/giantsteps-mtg-key-dataset), [GTZAN Key](https://github.com/alexanderlerch/gtzan_key) (각 데이터셋의 다운로드 절차를 따르십시오).

---

© 2026 TNTLABS Co., Ltd. All rights reserved.
