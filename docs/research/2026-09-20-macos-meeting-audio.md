# macOS 회의 오디오 캡처 비교 조사

조사일: 2026-09-20. 범위: AirPods 마이크를 유지하면서 회의 상대방 소리도 녹음하고, 통화에 추가 장애를 만들지 않는 설계. 제품 공식 문서와 공개 소스를 확인했다. 아래 개선안은 제안이며 `docs/recly.md`의 규칙을 변경하지 않는다. 외부 프로젝트를 실행하거나 실제 통화를 녹음하지 않았다.

## 판단 기준

- 회의 앱의 마이크 선택, macOS 기본 마이크, Recly의 마이크 선택은 서로 다를 수 있다.
- Bluetooth 통화 모드 자체의 품질과 Recly에서 발생한 음높이 변화·주기적 무음은 별개의 문제다.
- API 이름이나 프로젝트 인지도만으로 AirPods 안정성을 보장할 수 없다. 장치 변경, 포맷 변경, 시간 정보와 데이터 부족 처리까지 비교한다.
- 비공개 앱은 공개된 동작만 확인했다. 내부 캡처 API나 샘플레이트 보정 알고리즘을 추정해 사실처럼 쓰지 않는다.

## 회의 앱과 상용 미팅 노트

| 제품 | 확인한 동작 | Recly에 주는 의미 |
| --- | --- | --- |
| Teams | 앱 안에서 스피커와 마이크를 각각 선택한다. Computer audio 기본 동작은 컴퓨터의 기본 장치를 사용한다. | 기본 장치 모드와 특정 장치 선택을 구분해야 한다. |
| Google Meet | 입장 전후 오디오 설정에서 마이크와 스피커를 선택한다. | 브라우저가 선택한 입력이 macOS 기본 입력과 같다고 가정할 수 없다. |
| Zoom | AirPods를 마이크와 스피커로 직접 선택하는 방법을 공식 지원한다. Bluetooth 사용 시 다른 참가자가 듣는 마이크 음질이 낮아질 수 있다고도 안내한다. | AirPods 마이크 지원은 정상 요구사항이다. Bluetooth 품질 제약을 Recly의 추가 왜곡과 구분해야 한다. |
| Granola | 기기의 마이크와 시스템 오디오를 캡처한다. 공식 문제 해결 문서는 OS 기본 마이크와 회의 앱 마이크를 맞추라고 안내한다. | Recly와 가까운 캡처 모델이며, 회의 앱의 입력을 모든 경우 자동으로 따라간다고 보장하지 않는다. |

근거: [Teams 오디오 설정](https://support.microsoft.com/en-us/teams/meetings/manage-audio-settings-in-microsoft-teams-meetings), [Meet 오디오 연결](https://support.google.com/meet/answer/10409699?hl=en-NZ), [Zoom Bluetooth 안내](https://support.zoom.com/hc/en/article?id=zm_kb&sysparm_article=KB0058146), [Granola 오디오 문제 해결](https://www.granola.ai/blog/granola-integration-troubleshooting-common-issues-solutions), [Granola의 캡처 설명](https://www.granola.ai/security).

Meet 내장 녹화는 회의 내용이 주최자의 Drive에 저장되며, Zoom 로컬 녹화는 참가자별 오디오 파일도 지원한다. 이들은 회의 서비스의 내장 기능이다. 독립 앱인 Recly가 OS의 마이크와 출력을 캡처하는 구현의 직접적인 선례로 취급하면 안 된다. [Meet 녹화](https://support.google.com/meet/answer/9308681?hl=en-GB), [Zoom 로컬 녹화](https://support.zoom.com/hc/en/article?id=zm_kb&sysparm_article=KB0076922).

## Apple API에서 확인한 제약

### 마이크 선택과 시스템 오디오

Core Audio process tap은 프로세스의 **출력 오디오**를 캡처하는 API다. 회의 앱이 사용하는 마이크의 원본 입력을 자동으로 복제해 주는 API는 아니다. Apple의 공식 예제도 tap을 aggregate device의 입력으로 사용하는 방식이다. [Apple Core Audio tap 예제](https://developer.apple.com/documentation/coreaudio/capturing-system-audio-with-core-audio-taps).

`kAudioProcessPropertyDevices`는 프로세스가 사용하는 장치 목록을 제공하며, input/output scope로 구분된다. 설치된 SDK `AudioHardware.h:1958–1961`에서 확인했다. 따라서 활성 회의 프로세스의 입력 장치를 조사하는 구현은 검토할 수 있다. 다만 여러 브라우저 프로세스·탭·가상 장치·복수 입력 중 정확한 회의를 판별하고 계속 따라가는 부분은 별도로 검증해야 한다. 세 회의 앱이 자체 설정을 외부 앱에 통일된 방식으로 공개한다는 뜻은 아니다. [Apple API](https://developer.apple.com/documentation/coreaudio/kaudioprocesspropertydevices).

### ScreenCaptureKit 대안

macOS 15부터 ScreenCaptureKit은 시스템 오디오와 별도의 마이크 출력을 제공한다. `microphoneCaptureDeviceID`로 특정 마이크를 선택할 수 있고, 지정하지 않으면 시스템 기본 마이크를 사용한다. 시스템 오디오는 설정된 sample rate/channel count를 따르지만, 마이크 출력은 **선택한 마이크의 native format**을 따른다. 따라서 하나의 API를 써도 두 입력의 포맷이 같다고 가정하면 안 된다. 설치된 SDK `SCStream.h:25–26,298–300,358–365`와 [WWDC24 설명](https://developer.apple.com/videos/play/wwdc2024/10088/)을 대조했다.

Recly의 macOS 최소 버전은 14.4이므로, 마이크까지 ScreenCaptureKit으로 옮기려면 구버전 경로나 최소 버전 정책이 필요하다. API 교체가 Bluetooth 문제를 자동 해결한다는 증거는 없다.

### AirPods를 특정 샘플레이트로 고정하면 안 되는 이유

Apple은 Bluetooth 마이크 활성화 시 통화용 입출력 모드로 바뀌어 듣는 음질이 낮아질 수 있다고 설명한다. 그러나 최신 AirPods와 OS에는 고품질 녹음·통화 개선도 있다. 따라서 AirPods를 언제나 16/24 kHz 장치로 분류하거나 무조건 24 kHz로 다시 해석하는 수정은 부적절하다. [Bluetooth 모드 안내](https://support.apple.com/en-us/102217), [Apple의 고품질 AirPods 녹음 발표](https://www.apple.com/newsroom/2025/06/airpods-now-more-versatile-with-studio-quality-audio-recording-and-camera-remote/).

WWDC25의 `bluetoothHighQualityRecording` 관련 예제를 macOS 해결책으로 그대로 가져올 수도 없다. 설치된 macOS SDK `AVCaptureSession.h:590`에서 `configuresApplicationAudioSessionForBluetoothHighQualityRecording`은 `API_AVAILABLE(ios(26.0)) API_UNAVAILABLE(macos, ...)`로 선언되어 있다. 제품 기능 지원과 특정 플랫폼 API의 사용 가능 여부를 구분해야 한다. [WWDC25 오디오 녹음 API](https://developer.apple.com/videos/play/wwdc2025/251/).

## 오픈소스 구현 비교

아래 링크는 조사 당시 읽은 commit에 고정했다. 소스의 동작과 주석에 적힌 개발자의 의도를 구분했다.

### Chromium: tap-only aggregate와 Bluetooth 전환 검사

`f741c8c27fd492b2f3fae0cfe0d698170ffaebec`

Chromium의 macOS CATap loopback 구현도 aggregate에 tap만 넣고 물리 출력 subdevice는 넣지 않는다. 캡처할 출력 장치는 tap의 대상으로 지정할 수 있으므로, 출력 경로를 모르는 구조라는 뜻은 아니다. [aggregate 구성:817–841](https://chromium.googlesource.com/chromium/src/+/f741c8c27fd492b2f3fae0cfe0d698170ffaebec/media/audio/mac/catap_audio_input_stream.mm#817)

`inInputTime.mHostTime`으로 캡처 시각을 보존하고, Bluetooth 프로필 전환 중 채널 수나 프레임 수가 예상과 다르면 그 버퍼를 사용하지 않는다. aggregate rate·출력 장치 변경을 감지해 재시작을 요청하며, macOS 14용 경로는 실제 출력의 virtual-format rate를 읽어 aggregate 설정에 반영한다. [시간과 버퍼 검사:1071–1142](https://chromium.googlesource.com/chromium/src/+/f741c8c27fd492b2f3fae0cfe0d698170ffaebec/media/audio/mac/catap_audio_input_stream.mm#1071), [변경 처리:1404–1484](https://chromium.googlesource.com/chromium/src/+/f741c8c27fd492b2f3fae0cfe0d698170ffaebec/media/audio/mac/catap_audio_input_stream.mm#1404), [rate 선택:1583–1604](https://chromium.googlesource.com/chromium/src/+/f741c8c27fd492b2f3fae0cfe0d698170ffaebec/media/audio/mac/catap_audio_input_stream.mm#1583)

이 코드는 Chromium의 시스템 오디오 loopback 구현이다. Google Meet의 비공개 서버 녹화나 마이크 처리 전체를 설명하는 코드라고 확대 해석하지 않는다. Recly와 동일한 macOS tap 계열이면서 시간·포맷 전환을 다룬다는 점에서 직접 참고할 가치가 크다.

### OBS Studio: 장치 식별자, 시간 정보, 재초기화

`b5bfb79093e26ce4b0e71d999f59b6b22f961121`

- macOS 마이크 소스에 device UID를 저장한다. `default`는 하나의 선택지이며 특정 장치도 지정할 수 있다. [mac-audio.c:79–123](https://github.com/obsproject/obs-studio/blob/b5bfb79093e26ce4b0e71d999f59b6b22f961121/plugins/mac-capture/mac-audio.c#L79-L123)
- AudioUnit 입력 포맷을 읽고, 오디오와 함께 실제 프레임 수 및 `mHostTime`에서 변환한 timestamp를 전달한다. [포맷](https://github.com/obsproject/obs-studio/blob/b5bfb79093e26ce4b0e71d999f59b6b22f961121/plugins/mac-capture/mac-audio.c#L248-L325), [콜백](https://github.com/obsproject/obs-studio/blob/b5bfb79093e26ce4b0e71d999f59b6b22f961121/plugins/mac-capture/mac-audio.c#L391-L416)
- 장치 상태, physical format, 기본 입력 변경을 감시하고 캡처를 정리·재시도한다. [변경 처리](https://github.com/obsproject/obs-studio/blob/b5bfb79093e26ce4b0e71d999f59b6b22f961121/plugins/mac-capture/mac-audio.c#L452-L508)
- ScreenCaptureKit 오디오는 각 `CMSampleBuffer`의 포맷과 presentation timestamp를 사용한다. [mac-sck-common.m:318–351](https://github.com/obsproject/obs-studio/blob/b5bfb79093e26ce4b0e71d999f59b6b22f961121/plugins/mac-capture/mac-sck-common.m#L318-L351)

Recly에 적용할 패턴은 장치 선택을 식별자로 유지하고, 캡처 시각을 버리지 않으며, 포맷 변경을 새 캡처 구성으로 처리하는 것이다. 이 조사만으로 OBS가 프레임 수/시간으로 모든 잘못된 format descriptor를 검출한다고 주장하지 않는다.

### Screenpipe: 실제 출력 장치를 캡처 aggregate에서 제외

`288989a4aa69574f50c6978de47ca7f70e4eb51a`

- 시스템 오디오는 Core Audio process tap과 **tap만 포함한 private aggregate**로 캡처한다. 실제 출력 장치를 `sub_device_list`에 넣지 않고 `main_sub_device`도 지정하지 않는다. 주석은 실제 스피커를 열고 clocking하면 회의 앱의 장치 시작과 경합할 수 있다는 이유를 설명한다. [macos.rs:833–944](https://github.com/screenpipe/screenpipe/blob/288989a4aa69574f50c6978de47ca7f70e4eb51a/crates/screenpipe-audio/src/core/process_tap/macos.rs#L833-L944)
- 가상 aggregate의 rate를 tap 포맷에 맞춘 뒤 다시 읽어 확인한다. 실제 스피커의 rate를 바꾸는 코드는 아니다. tap 포맷이나 aggregate rate가 바뀌면 해당 캡처를 disconnected로 표시해 새 구성으로 만든다. [리스너](https://github.com/screenpipe/screenpipe/blob/288989a4aa69574f50c6978de47ca7f70e4eb51a/crates/screenpipe-audio/src/core/process_tap/macos.rs#L504-L568)
- 상시 녹음 제품이므로 Bluetooth 마이크는 기본적으로 회의 감지 시에만 연다. 사용자가 항상 녹음하도록 지정하는 예외도 있다. 이는 회의 중 AirPods 마이크를 금지하는 정책이 아니다. [manager.rs:983–994](https://github.com/screenpipe/screenpipe/blob/288989a4aa69574f50c6978de47ca7f70e4eb51a/crates/screenpipe-audio/src/audio_manager/manager.rs#L983-L994)

현재 Recly와 가장 직접적인 구조 차이다. 다만 충돌 방지 효과는 해당 프로젝트의 설계 의도이며, 이번 조사에서 AirPods 실기기로 검증하지 않았다. 이 차이가 9월 18일 녹음이나 상대방의 수신 음질 문제를 일으켰다고 확정할 수 없다. 소스 주석에 있는 다른 비공개 앱의 구현 주장은 별도 확인 없이 인용하지 않았다.

### Meetily: tap 중심 캡처지만 포맷 전환 처리는 별도 검토 필요

`a2cb62e827da7ef59f65064c97233efb2313878e`

시스템 오디오에 process tap을 사용한다. aggregate 설명에서 물리 출력 `sub_device_list`를 제거했으며, 주석은 이전 구성의 중복 캡처/에코 문제를 이유로 든다. `main_sub_device`에는 출력 UID가 남아 있다. **이 키만으로 실제 출력 장치를 추가로 열었다고 단정할 수 없다.** [core_audio.rs:119–145](https://github.com/Zackriya-Solutions/meetily/blob/a2cb62e827da7ef59f65064c97233efb2313878e/frontend/src-tauri/src/audio/capture/core_audio.rs#L119-L145)

IOProc에서 nominal rate가 달라지면 atomic 값은 갱신하지만, 이 콜백에서는 기존 `ctx.format`을 계속 사용하고 input timestamp는 사용하지 않는다. 관찰한 콜백만으로 전체 앱의 포맷 전환을 완전히 검증할 수 없으며, 이 부분을 그대로 안정성 기준으로 삼기는 어렵다. [콜백:158–200](https://github.com/Zackriya-Solutions/meetily/blob/a2cb62e827da7ef59f65064c97233efb2313878e/frontend/src-tauri/src/audio/capture/core_audio.rs#L158-L200)

### tobi/recorder: 원본 트랙 분리와 시스템 IOProc의 파일 쓰기 분리

`f1b5c7455074253605ae6d55b0ef89f34efa3011`

마이크를 별도 AVAudioEngine으로 열어 현재 native format을 사용하며 host time을 보존한다. 시스템 오디오 IOProc는 링 버퍼에 샘플을 넘기고 별도 writer thread가 파일에 쓴다. 시간 정보 보존과 시스템 캡처 콜백의 무거운 작업 분리는 참고할 만하다. [MicCapture.swift:98–144](https://github.com/tobi/recorder/blob/f1b5c7455074253605ae6d55b0ef89f34efa3011/Sources/Recorder/MicCapture.swift#L98-L144), [SystemAudioTap.swift:479–550](https://github.com/tobi/recorder/blob/f1b5c7455074253605ae6d55b0ef89f34efa3011/Sources/Recorder/SystemAudioTap.swift#L479-L550)

반면 마이크는 콜백 안에서 파일을 쓰며, 시스템 tap 재생성 시 기존 파일 포맷을 유지하는 로직도 있다. 이것을 완성된 Bluetooth 포맷 전환 해법으로 복사해서는 안 된다. [MicCapture.swift:162–198](https://github.com/tobi/recorder/blob/f1b5c7455074253605ae6d55b0ef89f34efa3011/Sources/Recorder/MicCapture.swift#L162-L198), [재생성:630–639](https://github.com/tobi/recorder/blob/f1b5c7455074253605ae6d55b0ef89f34efa3011/Sources/Recorder/SystemAudioTap.swift#L630-L639)

이 프로젝트는 설명 주석에 tap-only라는 표현이 있지만, 실제 aggregate 구성에는 물리 출력 subdevice가 들어간다. Screenpipe·Chromium과 상반된 선택이므로 오픈소스 전체가 한 구조로 수렴했다고 말할 수 없다. [실제 구성:330–352](https://github.com/tobi/recorder/blob/f1b5c7455074253605ae6d55b0ef89f34efa3011/Sources/Recorder/SystemAudioTap.swift#L330-L352)

### AudioCap과 CoreAudioTapKit: 예제의 목적을 구분

- AudioCap `6f609e8ad1b1e11fa0e8edbe91864cb099f00de3`은 process tap 생성의 참고 예제다. 생성 시 읽은 포맷을 사용하고 input timestamp를 버리는 경로라, Bluetooth 전환 복구의 기준으로 쓰지 않는다. [ProcessTap.swift:208–241](https://github.com/insidegui/AudioCap/blob/6f609e8ad1b1e11fa0e8edbe91864cb099f00de3/AudioCap/ProcessTap/ProcessTap.swift#L208-L241)
- CoreAudioTapKit `aab395f1e8e5dd1bd369c3bbfcf5c6bdca85e099`은 소리를 가공해 다시 스피커로 내보내는 목적이다. 실제 출력과 tap을 하나의 aggregate에 넣고 원래 출력을 mute한 뒤 같은 콜백에서 재생한다. 통화를 건드리지 않는 녹음 전용 Recly가 그대로 채택할 구조가 아니다. [SystemTapCapture.swift:76–119](https://github.com/CJStanfield/CoreAudioTapKit/blob/aab395f1e8e5dd1bd369c3bbfcf5c6bdca85e099/Sources/CoreAudioTapKit/SystemTapCapture.swift#L76-L119)

## Recly에 적용할 우선순위

1. **AirPods 마이크 선택을 유지한다.** 기본 입력만 따르는 현재 구현에 대해, 회의에서 쓰는 AirPods를 식별하고 유지하는 경로를 설계한다. 명시 선택을 제공한다면 현재 마이크 이름과 간단한 변경 메뉴로 충분하며, 녹음 전 확인 단계를 늘릴 필요는 없다. OS나 회의 앱의 입력 설정을 임의로 바꾸지 않는다.
2. **시스템 캡처가 물리 출력 장치를 불필요하게 열지 않도록 검증한다.** Screenpipe의 tap-only aggregate를 현재 구성과 비교한다. 가상 장치의 포맷과 실제 전달 프레임을 함께 확인하고, 물리 출력 장치의 sample rate를 강제로 변경하지 않는다.
3. **오디오 타임스탬프를 끝까지 유지한다.** 마이크와 시스템 입력의 시간·프레임·포맷을 보존해 정렬하고, 지속적인 부족이나 2:1 속도 차를 일반적인 미세 clock drift와 구분한다. 단순 큐 부족을 무음으로 채우며 계속 정상 녹음으로 취급하는 동작을 보완한다.
4. **포맷 전환과 장치 재연결을 명시적인 상태 전환으로 처리한다.** 기존 포맷으로 새 버퍼를 해석하는 구간을 줄이고, 재구성·재시도와 실패 진단을 남긴다. 콜백에서는 복사/큐 전달 등 최소 작업만 하고 변환·파일 쓰기를 별도 처리하는 방안을 검토한다.
5. **회의 앱과 동시 사용을 수신 측까지 검증한다.** 녹음 파일만 정상인지 확인해서는 통화 무영향을 입증할 수 없다.

지금 단계에서 권하는 방향은 Core Audio tap을 곧바로 전면 교체하는 것이 아니라, **물리 출력과 캡처 분리 + 입력 장치 선택 보존 + timestamp/format 처리 강화**다. ScreenCaptureKit 전환은 별도 비교 후보이며, 현행 최소 OS와 권한 UX를 포함해 평가해야 한다.

## 실기기 검증 항목

동일 AirPods와 Mac에서 Teams·Meet·Zoom 각각을 비교한다. 녹음 시작/중지, 회의 입장/퇴장, 음소거/해제, AirPods 재연결, 출력 변경, 30분 이상 통화가 대상이다. 장치를 실제 사용하지 않는 자동 테스트만으로 완료 판정을 내리지 않는다.

동시에 확인할 결과는 다음과 같다.

- Recly가 선택한 마이크가 AirPods인지와 회의 앱의 선택이 일치하는지.
- 일정 주파수 테스트 신호의 저장 후 음높이, 연속성, mic/sys 시간 정렬.
- 콜백 시각·프레임 수·포맷 변경과 queue underrun/drop 통계.
- 별도 수신 기기에서 Recly 녹음 전후로 들리는 내 목소리의 끊김·음량·음질 변화.

이 실험은 이번 리서치에서 수행하지 않았다. 서버/봇 방식 도입, 라이브러리 교체, 제품 코드 수정도 하지 않았다.

## 확인 명령과 결과

소스는 임시 디렉터리에 내려받아 `git rev-parse HEAD`, `rg -n`, `nl -ba ... | sed -n ...`으로 확인했다. checkout의 프로젝트 코드를 실행하지 않았다.

| 저장소 | 확인한 HEAD |
| --- | --- |
| chromium/src | `f741c8c27fd492b2f3fae0cfe0d698170ffaebec` |
| obsproject/obs-studio | `b5bfb79093e26ce4b0e71d999f59b6b22f961121` |
| screenpipe/screenpipe | `288989a4aa69574f50c6978de47ca7f70e4eb51a` |
| Zackriya-Solutions/meetily | `a2cb62e827da7ef59f65064c97233efb2313878e` |
| tobi/recorder | `f1b5c7455074253605ae6d55b0ef89f34efa3011` |
| insidegui/AudioCap | `6f609e8ad1b1e11fa0e8edbe91864cb099f00de3` |
| CJStanfield/CoreAudioTapKit | `aab395f1e8e5dd1bd369c3bbfcf5c6bdca85e099` |

대표 명령과 확인 결과:

```sh
git -C /tmp/screenpipe-research rev-parse HEAD
# 288989a4aa69574f50c6978de47ca7f70e4eb51a
nl -ba /tmp/screenpipe-research/crates/screenpipe-audio/src/core/process_tap/macos.rs | sed -n '833,945p'
# tap_only_aggregate_desc; virtual aggregate rate set/readback; physical subdevice exclusion
nl -ba /tmp/meetily-research/frontend/src-tauri/src/audio/capture/core_audio.rs | sed -n '119,200p'
# sub_device_list omitted; nominal rate update; existing ctx.format still used
rg -n 'hostTime|mHostTime|startWriterThread' /tmp/tobi-recorder-research/Sources/Recorder/SystemAudioTap.swift
# input host time retained at line 491; separate writer thread starts at line 522
```

Chromium은 전체 clone 대신 Gitiles의 해당 commit 파일을 `?format=TEXT`로 받아 base64 decode한 뒤 `nl -ba`로 확인했다. 출력에서 aggregate의 키는 name, UID, tap list, auto-start, private이며 physical subdevice list가 없었다. `OnCatapSample`은 `mHostTime`을 사용하고 채널·프레임 불일치를 검사했다.

Recly와 대조한 위치:

- `apple/RecKit/Sources/RecKit/Recorder/AudioInput.swift:68,114–135`: OS 기본 입력을 쓰는 AVAudioEngine.
- `apple/RecKit/Sources/RecKit/MacCapture/ProcessTapCapture.swift:224–250`: 실제 출력 UID를 main/subdevice로 포함하는 aggregate.
- `apple/RecKit/Sources/RecKit/MacCapture/ProcessTapCapture.swift:270–275`: IOProc input timestamp를 버리고 캐시된 format 사용.
- `apple/RecKit/Sources/RecKit/MacCapture/DriftCompensator.swift:188–226`: 선언된 rate 기반 진행량 계산과 부족분 무음 채우기.
- `apple/RecKit/Sources/RecKit/Recorder/SegmentedRecorder.swift:356–368`: 입력 콜백에서 변환과 쓰기 실행.

제품 코드 변경 없이 문서 조사와 소스 열람만 수행했다. 빌드·단위 테스트·기기 테스트 통과를 주장하지 않는다.
