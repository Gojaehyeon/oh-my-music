"""Android 분석 코드(:core, PC 에서 실행)로 평가셋을 채점한다. Mac 앱의 swift_eval.py 와 같은 표.

사용: (android 에서 ./gradlew :eval:installDist 후) python3 android_eval.py [gs|gtzan ...]
"""
import os, subprocess, sys
from common import ROOT, report
from prep import labels

BIN = os.path.join(ROOT, "..", "android", "eval", "build", "install", "eval", "bin", "eval")
MODEL = os.path.join(ROOT, "..", "android", "app", "src", "main", "assets", "keynet.onnx")
sets = sys.argv[1:] or ["gs"]
lab = {k: v for k, v in labels().items()
       if k.split("/")[0] in sets and v["split"] == "test" and os.path.exists(v["audio"])}
by_path = {v["audio"]: (k, tuple(v["key"])) for k, v in lab.items()}
env = dict(os.environ, JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home")
out = subprocess.run([BIN, MODEL], input="\n".join(by_path), capture_output=True, text=True, env=env).stdout
rows = {"누적": [], "20초": []}
for line in out.splitlines():
    path, k20, kfin, _ = line.split("\t")
    name, true = by_path[path]
    for tag, k in (("누적", int(kfin)), ("20초", int(k20))):
        rows[tag].append((name, (k % 12, k // 12) if k >= 0 else (-1, 0), true))
for tag, r in rows.items():
    report(r, f"android core ({tag})")
