# iOS 신규 제출 심사 준비

현재 코드에 맞춘 재제출 준비 문서. 실제 제출 버전·빌드 번호는 아카이브와 App Store Connect에서 확인해 아래 빈칸에 넣는다. 아래 실기기 확인과 빈칸을 완료한 뒤 같은 내용을 App Store Connect의 심사 답변과 App Review Information → Notes에 넣는다. 영상이나 실기기 검증을 완료했다고 미리 적지 않는다.

공개 이름·부제·설명·키워드는 [스토어 문구 수정 안내](app-store-copy.ko.md)와 [영어 문안](app-store-metadata.en.txt)을 사용한다. 심사용 계정·키는 공개 문안에 넣지 않는다.

## 제출 전에 완료할 것

- 변경한 개인정보 처리방침을 공개 URL에 반영하고, 앱 설정의 영어(`https://recly.dev/policy/privacy-policy`)·한국어(`https://recly.dev/policy/privacy-policy.ko`) 링크에서 실제로 열리는지 확인한다. App Store Connect의 개인정보 처리방침 URL에는 영어 정본 주소를 등록한다.
- iPhone·내장 Watch·위젯의 빌드 번호를 App Store Connect에서 아직 사용하지 않은 동일한 번호로 맞춘 뒤 `make ios-archive`로 새 빌드를 만든다. 현재 프로젝트 기본 `CURRENT_PROJECT_VERSION`은 `19`이며 출시 스크립트가 자동으로 올리지 않는다. 현재 코어를 빌드하고, 아카이브 안의 Google 클라이언트 ID와 콜백 스킴을 검사한다. 이 검사는 Google 콘솔의 iOS 번들 ID·OAuth 게시 상태·테스트 사용자 제한을 확인하는 실기기 로그인을 대신하지 않는다.
- 최신 정식 iOS의 실제 iPhone에서 제출할 빌드를 검증한다. 잠금 중 녹음 → 정지·이름 입력 → 목록 → 재생 → Drive 업로드·열기 → 선택 전사 → 결과 확인까지 수행한다.
- 설정 → 녹음 처리 → 외부 API 저장 시 새 대상의 확인 창 “녹음을 {업체}에 보낼까요?”(허용 안 함이면 저장 안 됨, 허용하고 저장이면 저장), 동일 대상 재저장 시 추가 확인 없음, 설정 가져오기 후 저장 시 같은 확인, 허용 철회 후 작업 대기, 설정에서 다시 허용 후 이전 업로드를 반복하지 않는 동작을 확인한다.
- Google Drive 연결·연결 해제, 녹음의 로컬/Drive 삭제 선택, 녹음 처리 설정의 API 키 개별 삭제를 확인한다. Recly 자체 계정 생성은 없으며, Google 계정 삭제 기능을 제공하는 것도 아니다.
- Apple Watch를 제출에 포함한다면 실제 Watch의 녹음·iPhone 전송과 허용 대기/재개도 검증한다. 지원 기기·OS·앱 버전·빌드 번호와 결과를 기록한다.
- 화면 녹화는 홈 화면에서 앱 실행으로 시작한다. 실제 기본 흐름과 설정된 선택 기능을 보여주고, 비밀번호·API 키·개인 녹음은 노출하지 않는다. 영상 URL은 심사자가 별도 권한 요청 없이 열 수 있는지 확인한다.
- 기본 녹음은 API 키 없이 사용할 수 있다. 전사 심사용으로 업체 계정/API 접근이 필요하면 전용 테스트 자격 증명과 정확한 설정 방법을 App Review Information의 비공개 필드에 제공한다. 키를 이 저장소·설정 내보내기 파일·영상에 넣지 않는다.
- App Privacy 답변을 Google·선택 전사 업체의 실제 데이터 처리 및 보관 조건과 대조한다. “개발자 서버가 없음”만으로 Apple의 “수집 안 함”을 확정하지 않는다.

- 중국 본토(`CHN`)·미국(`USA`)·지역 조회 실패 상태를 검증한다. OpenAI 목록·편집·가져오기·기존 잡 실행 차단, 다른 전사 업체 유지, 이미 완료된 Drive 업로드를 반복하지 않는 대기를 확인한다. StoreKit 실계정/샌드박스 확인은 주입된 지역을 이용한 단위 테스트와 구분해 기록한다.

## 심사 답변 초안

아래 대괄호 항목을 실제 검증 결과로 채우고, 해당 빌드에서 검증하지 않은 기능을 시연했다고 쓰지 않는다.

1. **Physical-device demonstration**

   Video: [accessible video URL]. Tested build: [version/build], device: [physical device], OS: [version]. The recording starts with launching the app and demonstrates [the flows actually shown].

2. **Purpose and audience**

   Recly is a general-purpose audio recorder for individuals who want to retain recordings in their own Google Drive and optionally transcribe them on device or with a selected external API. It supports personal notes, interviews and meetings that the user is authorized to record. Recly does not operate a developer-hosted backend or a Recly account system.

3. **Access and setup**

   Launch the app, grant microphone access when prompted, record, stop and enter a title. Open the recording list to play the recording and view its processing status. Use Settings → Google Drive → Connect Drive to authorize uploads to your own Drive. Recording processing offers local transcription, an external API or Off; the user selects the provider and explicitly permits transmission on iPhone before those requests are sent. Permission can be withdrawn in Settings → Privacy.

   Optional-feature review setup and credentials: [exact steps and location of private demo credentials, or explain which optional features are configured for review]. Required sample files: [none for microphone recording; list any files actually used for review]. Settings → Google Drive → Disconnect Drive revokes Google access and clears this device’s connection after one confirmation, while retaining recordings and settings. API keys can be removed individually from the Settings → Recording processing → External API → API keys; recordings can be deleted from the recording list.

4. **External services**

   Google OAuth provides authorization to the user's Google Drive. Google Drive stores recordings, metadata and any transcription result files. Optional speech-to-text integrations are AssemblyAI, NAVER Clova Speech, RTZR, OpenAI, Groq, Together AI, Mistral, ElevenLabs, Deepgram, Microsoft Azure Speech, Daglo, Speechmatics, Rev AI and Gladia. Only the provider selected in processing settings or an existing queued recording receives the transcription request. Apple WatchConnectivity transfers recordings between the user's paired Watch and iPhone. There is no Recly-operated payment processor or in-app purchase flow in the current code; external providers may charge the user's own account for API usage.

5. **Regional behavior**

   The iOS app disables the OpenAI transcription integration for the China mainland App Store storefront. It checks the StoreKit storefront before transcription and before each provider request; existing jobs and imported settings cannot bypass the restriction. If the storefront cannot be determined, the affected step waits without transmitting to the provider. The provider is also unavailable in the provider picker and save flow. Apple Watch recordings are processed by the paired iPhone under the same restriction. Local recording and Google Drive upload are independent of this restriction. The interface is available in 12 languages (English, Korean, Japanese, Simplified and Traditional Chinese, Spanish, French, German, Portuguese, Arabic, Hindi and Russian). [Verify CHN, a supported non-China storefront and unavailable-storefront behavior on the submitted build before sending.]

6. **Regulated services and third-party material**

   Recly is a general-purpose recording utility and does not itself provide medical, financial or other regulated professional services. Recordings are supplied by the user. Open-source dependency notices are included with the app. [Attach any additional authorization documents if the submitted app or store metadata contains protected third-party material requiring them.]

## 근거

- [Apple 심사 가이드라인의 개인정보 보호 조항](https://developer.apple.com/app-store/review/guidelines/#privacy): 앱 안에서 접근 가능한 개인정보 처리방침과 제3자·AI 서비스 공유 전 명시적 허용 요구.
- [Apple App Privacy 세부 설명](https://developer.apple.com/app-store/app-privacy-details/): 앱과 제3자의 실제 데이터 처리에 따른 수집 항목 검토.
- 코드·제품 계약: [설계 §15](recly.md), [개인정보 처리방침](policy/privacy-policy.ko.md), [개발·빌드 명령](development.md).


## 2026-09-18 · Guideline 4.8 재검토 답변

실제로 제출할 빌드에서 Google 미연결 녹음·저장·재생을 실제 iPhone으로 확인한 뒤 아래 문안을 보낸다. `[submitted version/build]`를 실제 번호로 바꾸고, 로그인하지 않은 상태에서 촬영한 시연을 첨부한다. 영상과 실기기 검증을 완료했다고 미리 주장하지 않는다. iPhone·Mac은 Google 로그인 SDK를 제거하고 `drive.file`만 요청하는 OAuth로 변경했다. 계정 식별 권한(`openid`·`email`·`profile`)은 요청하지 않고, Drive 권한·토큰 저장이 확인된 뒤에만 연결 완료로 처리한다. 이 변경은 앱 계정 로그인과 Drive 권한 승인을 구분하기 위한 것이며 승인 보장이 아니다.

> Hello App Review Team,
>
> Thank you for your feedback. Recly does not create or authenticate a Recly user account. Google OAuth authorizes access to the user's own Google Drive for storing and accessing recording files. The iPhone and Mac apps request only the `drive.file` scope, without `openid`, `email`, or `profile`, and do not create a Recly login session.
>
> Users can record and play locally stored audio without connecting Google. Google authorization is required for Drive features and recording processing that depends on Drive, including transcription in the current version.
>
> In the submitted build [submitted version/build], Settings identifies this integration as Google Drive and states that users can record locally and connect Drive to upload. The connection is configured in Settings, and the recording list distinguishes local storage from pending Drive uploads.
>
> To verify, launch without connecting Google, record and save audio, then open it from the List tab for playback. Settings → Google Drive is where the optional storage connection is configured.
>
> We respectfully request reconsideration of Guideline 4.8 because Google authentication authorizes access to the user’s own Drive content and does not establish or authenticate a primary account with Recly.

시연 자료: [실제로 검증한 제출 빌드·기기·OS와 접근 가능한 영상 URL을 기입].


## 2026-09-26 · Guideline 5.1.1(i)·5.1.2(i) 답변 (제3자 AI 서비스 전송)

지적: 제3자 AI 서비스로 개인정보(녹음)를 보내기 전에 앱이 무엇을·누구에게 보내는지 밝히고 허락을 받지 않는다. 방침도 수집·사용·공유와 제3자의 동등한 보호를 밝혀야 한다.

원인: 심사 빌드(17·18)는 워크플로우 편집기 안에서만 “허용하고 저장”을 물었고, 2026-09-25 고정 처리 계획 전환에서 그 확인이 빠졌다(녹음 뒤 대기 작업에서만 허용). 2026-09-26 설정 저장 시점의 확인 창을 복원·강화했다(§15).

보내기 전에 새 빌드로 실제 iPhone에서 확인 창을 촬영하고 `[ ]`를 채운다. API 키 없이도 확인 창까지 볼 수 있다.

> Hello App Review Team,
>
> Thank you for the review. Recly sends a recording to a third-party AI service only when the user chooses one: a speech-to-text provider such as OpenAI or ElevenLabs, selected in Settings → Recording processing → External API and used with the user's own API key. New installations use on-device transcription or no transcription, and send nothing to any AI service.
>
> In build [version/build], saving External API with a provider the user has not yet allowed on this iPhone first shows “Send recordings to [Provider]?”. This screen:
> - identifies the recipient: the provider by name, described as a third-party AI speech recognition service that Recly does not operate, with its endpoint;
> - explains what is sent and why: the full audio of each recording, with the language and speaker settings, for transcription; retention and training depend on the provider;
> - links to the provider's privacy policy and explains how to withdraw permission (Settings → Privacy);
> - asks for permission with “Don't allow” and “Allow & save”. If the user does not allow, the settings are not saved and nothing is sent.
>
> The app also checks this permission before every request to a provider, including for recordings received from Apple Watch, so no audio reaches a provider the user has not allowed. Permission can be withdrawn at any time in Settings → Privacy.
>
> Our privacy policy (https://recly.dev/policy/privacy-policy), section 3(2), identifies these providers as third-party AI services and describes what is sent, how, and why, the in-app permission, and [the protection statement for third parties].
>
> To see the permission screen: Settings → Recording processing → External API → choose a provider → Save. No API key is needed to reach it. [Screenshot or video URL]

App Review Information → Notes에도 같은 요지(외부 API 선택 시에만 제3자 AI 전송, 저장 시 허락, 확인 경로)를 넣는다.

