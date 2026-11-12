#!/bin/zsh
# 공증된 배포용 dmg 를 만든다. (사용: ./release.sh) → dist/OhMyMusic-<버전>.dmg
set -euo pipefail
cd "$(dirname "$0")"

IDENTITY="Developer ID Application: Jahyeon Ko (RP5GZ99V95)"
APP="build/Oh My Music.app"
DIST=dist
mkdir -p "$DIST"

./build.sh
codesign --verify --strict --deep "$APP"
VERSION=$(/usr/libexec/PlistBuddy -c "Print CFBundleShortVersionString" "$APP/Contents/Info.plist")

# 1) 앱 공증 + 스테이플
ditto -c -k --keepParent "$APP" "$DIST/OhMyMusic.zip"
asc notarization submit --file "$DIST/OhMyMusic.zip" --wait --poll-interval 20s --timeout 30m
xcrun stapler staple "$APP"
rm "$DIST/OhMyMusic.zip"

# 2) 드래그 설치용 dmg (앱 + Applications 바로가기)
STAGE=$(mktemp -d)
ditto "$APP" "$STAGE/Oh My Music.app"
ln -s /Applications "$STAGE/Applications"
DMG="$DIST/OhMyMusic-$VERSION.dmg"
rm -f "$DMG"
hdiutil create -volname "Oh My Music" -srcfolder "$STAGE" -ov -format UDZO "$DMG" >/dev/null
codesign --force --timestamp --sign "$IDENTITY" "$DMG"

# 3) dmg 공증 + 스테이플
asc notarization submit --file "$DMG" --wait --poll-interval 20s --timeout 30m
xcrun stapler staple "$DMG"
spctl -a -t open --context context:primary-signature -vv "$DMG"
echo "완료: $DMG"
