# iOS 신규 제출 심사 준비

2026-09-10 기준 코드에 맞춘 준비 문서. 아래 실기기 확인과 빈칸을 완료한 뒤 같은 내용을 App Store Connect의 심사 답변과 App Review Information → Notes에 넣는다. 영상이나 실기기 검증을 완료했다고 미리 적지 않는다.

## 제출 전에 완료할 것

- 변경한 개인정보 처리방침을 공개 URL에 반영하고, 앱 설정의 영어(`https://recly.dev/policy/privacy-policy`)·한국어(`https://recly.dev/policy/privacy-policy.ko`) 링크에서 실제로 열리는지 확인한다. App Store Connect의 개인정보 처리방침 URL에는 영어 정본 주소를 등록한다.
- iPhone·내장 Watch·위젯의 빌드 번호를 App Store Connect에서 아직 사용하지 않은 동일한 번호로 맞춘 뒤 `make ios-archive`로 새 빌드를 만든다. 현재 프로젝트 기본 `CURRENT_PROJECT_VERSION`은 `12`이며 출시 스크립트가 자동으로 올리지 않는다. 현재 코어를 빌드하고, 아카이브 안의 Google 클라이언트 ID와 콜백 스킴을 검사한다. 이 검사는 Google 콘솔의 iOS 번들 ID·OAuth 게시 상태·테스트 사용자 제한을 확인하는 실기기 로그인을 대신하지 않는다.
- 최신 정식 iOS의 실제 iPhone에서 제출할 빌드를 검증한다. 잠금 중 녹음 → 정지·이름 입력 → 목록 → 재생 → Drive 업로드·열기 → 선택 전사 → 결과 확인까지 수행한다.
- 새 전사/웹훅 대상의 “허용하고 저장”, 동일 대상 재사용 시 추가 확인 없음, 가져오기에서 새 대상만 표시, 허용 철회 후 작업 대기, 설정에서 다시 허용 후 이전 업로드를 반복하지 않는 동작을 확인한다.
- Google 로그인·로그아웃·연결 해제, 녹음의 로컬/Drive 삭제 선택, 시크릿 목록의 API 키 개별 삭제를 확인한다. Recly 자체 계정 생성은 없으며, Google 계정 삭제 기능을 제공하는 것도 아니다.
- Apple Watch를 제출에 포함한다면 실제 Watch의 녹음·iPhone 전송과 허용 대기/재개도 검증한다. 지원 기기·OS·앱 버전·빌드 번호와 결과를 기록한다.
- 화면 녹화는 홈 화면에서 앱 실행으로 시작한다. 실제 기본 흐름과 설정된 선택 기능을 보여주고, 비밀번호·API 키·개인 녹음은 노출하지 않는다. 영상 URL은 심사자가 별도 권한 요청 없이 열 수 있는지 확인한다.
- 기본 녹음은 API 키 없이 사용할 수 있다. 전사 심사용으로 업체 계정/API 접근이 필요하면 전용 테스트 자격 증명과 정확한 설정 방법을 App Review Information의 비공개 필드에 제공한다. 키를 이 저장소·워크플로우 내보내기 파일·영상에 넣지 않는다.
- App Privacy 답변을 Google·선택 전사 업체·웹훅의 실제 데이터 처리 및 보관 조건과 대조한다. “개발자 서버가 없음”만으로 Apple의 “수집 안 함”을 확정하지 않는다.

## 심사 답변 초안

아래 대괄호 항목을 실제 검증 결과로 채우고, 해당 빌드에서 검증하지 않은 기능을 시연했다고 쓰지 않는다.

1. **Physical-device demonstration**

   Video: [accessible video URL]. Tested build: [version/build], device: [physical device], OS: [version]. The recording starts with launching the app and demonstrates [the flows actually shown].

2. **Purpose and audience**

   Recly is a general-purpose audio recorder for individuals who want to retain recordings in their own Google Drive and optionally automate transcription or send webhook notifications. It supports personal notes, interviews and meetings that the user is authorized to record. Recly does not operate a developer-hosted backend or a Recly account system.

3. **Access and setup**

   Launch the app, grant microphone access when prompted, select the default Memo workflow, record, stop and enter a title. Open the recording list to play the recording and view its processing status. Sign in with Google from Settings to enable uploads to your own Drive. Transcription and webhooks are optional workflow steps; the user selects the provider or destination and explicitly permits transmission on iPhone before those requests are sent. Permission can be withdrawn in Settings → Privacy.

   Optional-feature review setup and credentials: [exact steps and location of private demo credentials, or explain which optional features are configured for review]. Required sample files: [none for microphone recording; list any files actually used for review]. Google sign-out and Disconnect are available in Settings. API keys can be removed individually from the workflow screen's secret list; recordings can be deleted from the recording list.

4. **External services**

   Google Sign-In provides authorization to the user's Google Drive. Google Drive stores recordings, metadata and any transcription result files. Optional speech-to-text integrations are AssemblyAI, NAVER Clova Speech, RTZR, OpenAI, Groq, Together AI, Mistral, ElevenLabs, Deepgram, Microsoft Azure Speech, Daglo, Speechmatics, Rev AI and Gladia. Only the provider selected in a workflow receives the transcription request. Optional webhooks send recording metadata and Drive links to a user-configured URL. Apple WatchConnectivity transfers recordings between the user's paired Watch and iPhone. There is no Recly-operated payment processor or in-app purchase flow in the current code; external providers may charge the user's own account for API usage.

5. **Regional behavior**

   The app's recording and workflow behavior is not intentionally varied by App Store region. The interface is available in English and Korean. Availability, supported transcription languages, processing region and pricing of external services depend on the selected provider, endpoint and the user's account. [Confirm the submitted storefront availability and any applicable service restrictions before sending.]

6. **Regulated services and third-party material**

   Recly is a general-purpose recording utility and does not itself provide medical, financial or other regulated professional services. Recordings are supplied by the user. Open-source dependency notices are included with the app. [Attach any additional authorization documents if the submitted app or store metadata contains protected third-party material requiring them.]

## 근거

- [Apple 심사 가이드라인의 개인정보 보호 조항](https://developer.apple.com/app-store/review/guidelines/#privacy): 앱 안에서 접근 가능한 개인정보 처리방침과 제3자·AI 서비스 공유 전 명시적 허용 요구.
- [Apple App Privacy 세부 설명](https://developer.apple.com/app-store/app-privacy-details/): 앱과 제3자의 실제 데이터 처리에 따른 수집 항목 검토.
- 코드·제품 계약: [설계 §15](recly.md), [개인정보 처리방침](policy/privacy-policy.ko.md), [개발·빌드 명령](development.md).
