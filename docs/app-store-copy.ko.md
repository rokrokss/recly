# App Store 재제출 공개 문구

App Store Connect에 입력할 영어 문안은 [app-store-metadata.en.txt](app-store-metadata.en.txt)에 있다. 이 파일을 변경하거나 Git에 푸시해도 App Store Connect의 문구가 자동으로 바뀌지는 않는다.

## 입력 위치

| App Store Connect 위치 | 항목 | 문안 파일의 구역 |
|---|---|---|
| Recly → 앱 정보 → 영어(미국) | 이름 | `NAME` |
| 같은 화면 | 부제 | `SUBTITLE` |
| Recly → iOS 앱 → 0.1.0 → 영어(미국) | 프로모션 텍스트 | `PROMOTIONAL TEXT` |
| 같은 화면 | 설명 | `DESCRIPTION` |
| 같은 화면 | 키워드 | `KEYWORDS` |

각 구역의 제목을 제외하고 본문만 입력한다. 이름·부제가 이미 문안과 같으면 유지한다. 최초 출시 버전의 재심사이므로 업데이트용 `What's New` 항목은 대상이 아니다.

영어가 유일한 등록 언어이면 중국에도 영어 메타데이터가 표시된다. 중국어 현지화만 수정했다고 중국 스토어의 모든 노출 문구가 바뀌는 것은 아니다. 기본 언어와 중국에 표시될 수 있는 현지화를 함께 확인한다.

## 스크린샷과 앱 미리보기

각 기기 크기와 현지화의 이미지·영상에서 ChatGPT/OpenAI/GPT 명칭, 로고, 모델명 또는 해당 연동 화면이 남아 있는지 확인한다. 해당 이미지가 있으면 실제로 제출할 빌드의 녹음·목록·Drive 화면 등으로 교체한다. 새 화면은 실제 앱 동작을 그대로 보여 주어야 한다.

공개 설명 첫 단락은 Google 연결 없이 로컬 녹음·저장·재생이 가능함을 밝힌다. Google 연결은 Drive 접근용이며, 현재 전사 등 Drive 기반 워크플로우에는 연결이 필요하다는 점도 명시한다. 녹음·사용자 Drive 저장·선택 전사·웹훅이 설명의 중심이다. 포괄적인 AI 마케팅을 줄인 것은 문안의 편집 선택이며, 이번 심사 메시지가 `AI`라는 단어 자체를 전면 금지한다고 단정하지 않는다.

## 심사 Notes와 개인정보처리방침

2026-09-14 심사 메시지는 중국 본토에서 해당 기능을 비활성화하고 공개 메타데이터의 ChatGPT/OpenAI/GPT 참조를 제거하도록 요구했다. 기능을 계속 제공하면서 이름만 바꾸는 방식으로 대응하지 않는다. 빌드 13의 기능 제한은 [설계 §15](recly.md#중국-본토-app-store)에 기록돼 있다.

공개 설명의 지역 안내는 `Transcription provider availability varies by region.`이다. 비공개 심사 Notes에는 OpenAI를 명시해 중국에서 무엇을 차단했는지 설명한다. [심사 준비 문서](app-review.md)의 지역 동작 항목을 사용하고, 실제 StoreKit 계정 검증 결과를 반영한다. 개인정보처리방침의 실제 처리 업체·지역 제한 설명도 정확성을 유지한다.

심사용 계정·키는 공개 문안이나 저장소에 포함하지 않는다. 기기에서 확인하지 않은 동작이나 영상을 검증 완료로 표시하지 않는다. 공개 문구만 변경하는 경우와 앱 코드가 변경된 경우를 구분한다. 이번 재제출은 앱 UI·동작 변경이 있으므로 이전 심사 빌드 `0.1.0 (13)`을 그대로 선택하지 않고, 변경을 포함한 새 아카이브를 업로드·선택한다. 버전과 빌드 번호는 App Store Connect에서 확인한다.

## 길이와 확인 범위

현재 영어 문안은 이름 21/30자, 부제 28/30자, 프로모션 118/170자, 설명 1088/4,000자, 키워드 81/100바이트다. 공개 문안에는 ChatGPT/OpenAI/GPT/AI 단어를 넣지 않았다.

실제 스토어의 저장 상태, 업로드된 스크린샷, 중국 본토 StoreKit 계정에서의 동작은 재제출 전에 확인해야 한다.

## 출처

- [Apple 앱 정보 항목](https://developer.apple.com/help/app-store-connect/reference/app-information/app-information/): 이름·부제와 길이 제한.
- [Apple 버전 정보 항목](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/): 공개 문구·스크린샷, 비공개 심사 Notes, 최초 버전의 What's New 제외.
- [Apple 현지화 표시 규칙](https://developer.apple.com/help/app-store-connect/manage-app-information/localize-app-information/): 기본 언어와 사용자 언어에 따른 표시.
