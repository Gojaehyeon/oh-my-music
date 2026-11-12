#!/bin/zsh
# Oh My Music.app 을 빌드해 ~/Applications 에 설치한다. (사용: ./build.sh [--open])
set -euo pipefail
cd "$(dirname "$0")"

swift build -c release
# 키 모델: CLI(--analyze) 용으로 바이너리 옆에, 앱 번들에는 Resources 에 컴파일해 둔다
xcrun coremlcompiler compile Models/KeyNet.mlpackage .build/release/ >/dev/null
APP="build/Oh My Music.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/OhMyMusic "$APP/Contents/MacOS/OhMyMusic"
rm -rf "$APP/Contents/Resources/KeyNet.mlmodelc"
cp -R .build/release/KeyNet.mlmodelc "$APP/Contents/Resources/"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleIdentifier</key><string>kr.tntlabs.ohmymusic</string>
    <key>CFBundleName</key><string>Oh My Music</string>
    <key>CFBundleDisplayName</key><string>Oh My Music</string>
    <key>CFBundleExecutable</key><string>OhMyMusic</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSMinimumSystemVersion</key><string>14.4</string>
    <key>LSUIElement</key><true/>
    <key>NSAudioCaptureUsageDescription</key><string>재생 중인 음악의 키와 BPM 을 분석하기 위해 시스템 오디오를 듣습니다. 녹음하거나 저장하지 않습니다.</string>
</dict>
</plist>
PLIST
# 같은 인증서로 서명해야 재빌드 후에도 오디오 녹음 권한이 유지된다
codesign --force --timestamp --sign "Developer ID Application: Jahyeon Ko (RP5GZ99V95)" --options runtime "$APP"

mkdir -p ~/Applications
pkill -x OhMyMusic || true
rm -rf ~/Applications/"Oh My Music.app"
ditto "$APP" ~/Applications/"Oh My Music.app"
echo "설치 완료: ~/Applications/Oh My Music.app"
[[ "${1:-}" == "--open" ]] && open ~/Applications/"Oh My Music.app"
exit 0
