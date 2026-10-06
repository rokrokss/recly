# Recly 설치 안내

폰과 워치 앱은 스토어에 있습니다.

- **Android 폰과 Galaxy Watch**: [Google Play](https://play.google.com/store/apps/details?id=app.recly).
  Android 14 / Wear OS 5 이상.
- **iPhone과 Apple Watch**: [App Store](https://apps.apple.com/app/recly-record-for-your-ai/id6809930443).
  iOS 17 / watchOS 10 이상.

워치는 혼자 업로드하지 못하고 녹음을 폰 앱에 넘깁니다. 그래서 **둘 다 설치해야 합니다**.

Mac과 Windows 앱, 그리고 직접 설치용 Android APK는 [GitHub Releases](https://github.com/rokrokss/recly/releases)에
`v<버전>-build.<번호>` 태그로 있습니다. 가장 새 앱 릴리스에는 Latest 표시가 붙어 있고,
[이 링크](https://github.com/rokrokss/recly/releases/latest)로 바로 열립니다. 옆의 `events-v…` 릴리스는
앱이 아니라 recly-events입니다. 릴리스마다 `SHA256SUMS`가 있어서, 받은 파일을 한 폴더에 두고
`shasum -a 256 -c SHA256SUMS`로 확인할 수 있습니다.

설치한 다음에는 [설정 안내](setup.ko.md)를 따라 저장소를 연결하고 전사 방식을 고르세요.

## Android 폰

[Google Play](https://play.google.com/store/apps/details?id=app.recly)에서 Recly를 설치합니다.

**직접 설치하려면**: 폰 브라우저로 릴리스를 열어 `Recly-Android-<버전>-<빌드>.apk`를 받고, 연 다음 그
출처에서 설치를 허용합니다. Play 프로텍트가 Play 밖의 앱이라고 경고해도 설치할 수 있습니다. APK는
Google Play가 서명하는 키가 아니라 프로젝트의 업로드 키로 서명되어 있어서, Play로 설치한 앱과 APK로
설치한 앱은 서로 업데이트할 수 없습니다. 바꾸려면 먼저 삭제해야 하는데, 삭제하면 폰에만 있는 녹음도
지워지므로 모든 녹음이 업로드됨으로 보인 다음에 삭제하세요.

## Galaxy Watch

폰 앱을 설치한 다음, 워치의 Play 스토어를 열어 Recly를 설치하거나 폰의 Google Play에서 워치에 설치합니다.

*홈 키 두 번 누르기 → 녹음*: 워치에서 설정 → 고급 기능 → 키 사용자화 → 홈 키 두 번 누르기 → 앱 열기로
가서 **"Recly 녹음"**을 고릅니다. 런처의 두 번째 항목이고, "Recly"를 고르면 앱만 열립니다. 폰의 백그라운드
배터리 제한에서 Galaxy Wearable을 빼 두세요. 그러지 않으면 블루투스 연결이 끊깁니다.

**직접 설치하려면**: 폰도 APK로 설치해야 합니다. 워치 앱과 폰 앱은 같은 곳에서 받아야 합니다. Wear OS에는
브라우저도 APK 설치 앱도 없어서 `Recly-WearOS-<버전>-<빌드>.apk`는 ADB로만 넣을 수 있습니다. 워치 설정의
워치 정보에서 소프트웨어 버전을 일곱 번 눌러 개발자 옵션을 켜고, 개발자 옵션에서 **ADB 디버깅**과
**무선 디버깅**을 켠 뒤, 같은 Wi-Fi의 컴퓨터에서 페어링합니다.

```bash
adb pair <페어링 IP:포트> <코드>         # 무선 디버깅 → "새 기기 페어링"
adb connect <IP:포트>                   # 무선 디버깅 화면에 나온 주소
adb -s <IP:포트> install -r Recly-WearOS-*.apk
```

컴퓨터가 없으면 폰에서 워치 APK를 받고, Play에 있는 ADB 기반 설치 앱(예: "Wear Installer 2")으로 넣을 수
있습니다. 워치 설정은 위와 같습니다.

## iPhone · Apple Watch

[App Store](https://apps.apple.com/app/recly-record-for-your-ai/id6809930443)에서 Recly를 설치합니다.
Apple Watch 앱은 함께 설치됩니다. 워치에 보이지 않으면 iPhone의 Watch 앱을 열어 Recly를 설치하세요.

GitHub 릴리스의 `.ipa`는 App Store 업로드용 패키지라서 기기에 바로 설치할 수 없습니다. 소스에서 빌드하는
방법은 [development.md](development.md)(영어)에 있습니다.

## macOS

가장 새 [릴리스](https://github.com/rokrokss/recly/releases/latest)에서 `Recly-macOS-<버전>-<빌드>.dmg`를
받아 열고, Recly를 응용 프로그램 폴더로 끌어다 놓습니다. Developer ID 인증서로 서명되고 Apple 공증을
받았으므로 따로 우회하지 않아도 열립니다. macOS 14.4 이상, Apple Silicon.

## Windows

MSI는 [GitHub 릴리스](https://github.com/rokrokss/recly/releases/latest)에 있습니다. 아직 코드 서명이 없어서
SmartScreen이 "Windows의 PC 보호" 창을 띄웁니다. **추가 정보** → **실행**을 고르세요.
