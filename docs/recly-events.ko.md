# recly-events 직접 실행하기

[English](recly-events.md)

`recly-events`는 새 Recly 녹취록이 생길 때마다 ChatGPT 에이전트에게 알려, 에이전트가 알아서 회의록을 쓰게 하는 작은
프로그램입니다([동작 방식](agent.ko.md)). Recly Mac·Windows 앱에는 이 프로그램이 들어 있어서 앱이 대신 실행합니다.
그 앱을 쓴다면 [ChatGPT 에이전트로 자동 회의록](agent.ko.md)을 따르세요. 서버, Linux, Recly 앱이 없는 컴퓨터에서는
recly-events를 직접 실행합니다.

recly-events는 그 컴퓨터에서 돌고, 네트워크 포트를 열지 않으며, Google과 OpenAI로 나가는 연결만 만듭니다. Drive에
새로 생긴 녹취록의 이름, ID, 링크만 읽고 대화 내용은 읽지 않습니다.

## 필요한 것

- **Recly가 녹음을 Google Drive에 저장할 것.** iCloud나 로컬 폴더에 저장한 녹음은 보지 못합니다.
- **이벤트를 구독할 수 있는 ChatGPT 에이전트**: dot, 또는 ChatGPT 웹의 Work 채팅. 이 글을 쓰는 시점에 dot은 ChatGPT
  Business Premium, 또는 EEA·스위스·영국 밖의 ChatGPT Pro가 필요합니다.
- **ChatGPT의 [Google Drive 앱](https://help.openai.com/en/articles/10929079-google-drive-app-and-setup-in-chatgpt)**.
  Recly가 업로드하는 Google 계정으로 연결되어 있어야 합니다.
- **OpenAI Platform 계정**([platform.openai.com](https://platform.openai.com)). 터널과 키를 만들 때 씁니다.
- **켜 두는 컴퓨터 한 대.** 잠자기 중에도 잃는 것은 없고, 깨어나면 밀린 것을 따라잡습니다.

## 1. 설치하기

가장 새 [`events-v…` 릴리스](https://github.com/rokrokss/recly/releases?q=events-v&expanded=true)에서 내 컴퓨터에
맞는 파일을 받습니다.

| 컴퓨터 | 파일 |
|---|---|
| Mac(Apple 실리콘, Intel) | `recly-events_<버전>_darwin_universal.pkg`. Developer ID로 서명되고 공증받은 설치 파일 |
| Linux | `recly-events_<버전>_linux_amd64.tar.gz` 또는 `…_linux_arm64.tar.gz` |
| Windows | `recly-events_<버전>_windows_amd64.zip`. 코드 서명이 없고, 아직 Windows PC에서 시험하지 않았습니다 |

받은 파일을 확인하려면 같은 릴리스의 `SHA256SUMS`를 옆에 두고 `shasum -a 256 -c SHA256SUMS --ignore-missing`을
실행합니다.

- **Mac:** `.pkg`를 열고 설치 프로그램을 따라가면 됩니다. 암호를 묻습니다. `recly-events`는 `PATH`에 들어 있는
  `/usr/local/bin`에, 라이선스 파일은 `/usr/local/share/doc/recly-events`에 설치됩니다. 명령줄로 설치하려면:

  ```sh
  sudo installer -pkg recly-events_*_darwin_universal.pkg -target /
  ```

- **Linux:** 압축을 풀고 프로그램을 계속 둘 곳에 놓습니다. 서비스가 프로그램 위치를 기록하기 때문입니다.

  ```sh
  tar -xzf recly-events_*_linux_*.tar.gz
  mkdir -p ~/.local/bin && cp recly-events_*/recly-events ~/.local/bin/
  ```

- **Windows:** `.zip`을 풀고 `recly-events.exe`를 계속 둘 폴더에 놓습니다.

recly-events는 명령줄 프로그램이라 터미널(셸)에서 실행합니다. 설치를 확인하려면:

```sh
recly-events version
```

## 2. OpenAI 터널과 키 만들기

[에이전트 안내의 1단계](agent.ko.md#1-openai-터널과-키-만들기)를 따르세요. 터널 ID(`tunnel_…`)와, 권한을 제한한
OpenAI API 키인 터널 키를 얻습니다.

## 3. Google Drive와 터널 연결하기

```sh
recly-events init --google --tunnel-id tunnel_…
```

1. 브라우저에 Google 로그인이 열립니다. Recly가 업로드하는 계정으로 로그인하고, Recly가 만든 Drive 파일을 보도록
   허용합니다. Recly 앱이 묻는 것과 같은 권한입니다.
2. 터널 키를 물으면 붙여 넣습니다. 입력한 내용은 화면에 보이지 않습니다.
3. `init`이 둘 다 확인합니다. `Google Drive: connected`가 나오고, 30초쯤 뒤 `Tunnel: ready tunnel_…`이 나옵니다.

**브라우저가 없는 서버**(예: SSH로 접속하는 서버)에서는 `init`이 Google 로그인 주소를 대신 출력합니다. 아무 컴퓨터의
브라우저에서 그 주소를 열어 로그인하세요. 그 브라우저는 열리지 않는 `http://127.0.0.1:…` 페이지로 이동하는데, 주소창의
주소 전체를 복사해 `init`에 붙여 넣으면 됩니다. 데스크톱 환경이 없는 Linux에서는 저절로 이렇게 동작하고, 다른 곳에서는
`--no-browser`를 붙입니다.

나중에 한 부분만 바꾸려면 그 옵션만 붙여 `init`을 다시 실행합니다. 다시 로그인은 `--google`, 다른 터널은
`--tunnel-id`, 새 키는 `--tunnel-key-file 파일`입니다.

## 4. 실행하기

```sh
recly-events service install
```

- **Mac:** 로그인할 때 시작하고, 서버가 멈추면 다시 띄우는 launch agent를 만듭니다.
- **Linux:** systemd 사용자 서비스를 만듭니다. 서버에서는 `loginctl enable-linger`도 한 번 실행하세요(거부되면
  `sudo loginctl enable-linger $USER`). 그래야 로그아웃한 뒤에도 돌고 부팅할 때 시작합니다. 필요하면
  `service install`이 알려 줍니다.
- **Windows:** `service install`을 지원하지 않습니다. 로그온할 때 `recly-events serve`를 실행하는 작업 스케줄러 작업을
  만드세요.

또는 터미널에서 `recly-events serve`로 실행하고 Ctrl-C로 멈춥니다. 시작하고 30초쯤 지나면 `recly-events status`에
`Tunnel: … ready`가 보입니다.

Recly Mac·Windows 앱의 에이전트 스위치도 켜 두었다면 둘 중 하나만 쓰세요. 앱은 직접 띄운 recly-events를 건드리지 않고
**Recly 밖에서 이미 실행 중입니다**라고 표시합니다.

## 5. ChatGPT에 앱 추가하고 에이전트에게 말하기

서버를 켜 둔 채로 에이전트 안내의 [3단계](agent.ko.md#3-chatgpt에-앱-추가하기)와
[4단계](agent.ko.md#4-에이전트에게-한-번만-말하기)를 따르세요. 내 터널로 ChatGPT에 `Recly events` 앱을 추가하고, dot이나
Work 채팅에 구독 문구를 한 번 보내면 됩니다. 에이전트가 구독하면 `recly-events status`에 구독이 보입니다.

## 6. 시험하기

```sh
recly-events test
```

Drive에서 가장 최근 Recly 녹취록을 다시 알리고, 1분 안에 에이전트가 움직여야 합니다. 이미 처리한 녹음이면 그렇다고만
말할 수도 있는데, 그래도 전체 경로가 동작한다는 뜻입니다. 진짜 시험은 다음 녹음입니다.

## 평소에 쓰는 명령

```sh
recly-events status          # 서버, 터널, Google, 마지막 Drive 확인, 구독, 전달 상태
recly-events test            # 가장 최근 녹취록 다시 알리기
recly-events version
```

로그인 정보, 터널 키, 상태는 내 사용자 계정만 읽을 수 있는 폴더 하나에 있습니다. Mac은
`~/Library/Application Support/recly-events`, Linux는 `~/.config/recly-events`, Windows는 `%AppData%\recly-events`입니다.
서버 로그는 Mac에서는 그 폴더의 `logs/serve.log`, Linux에서는 `journalctl --user -u recly-events`로 봅니다.

## 문제가 있을 때

먼저 `recly-events status`를 보고, 그다음 로그를 보세요.

| 보이는 것 | 할 일 |
|---|---|
| `serve`: "already running" | 다른 `recly-events serve`(예: 서비스)가 같은 폴더를 쓰고 있습니다. 하나를 멈추세요. |
| ChatGPT에 터널이 나오지 않음 | 터널이 내 ChatGPT 워크스페이스에 연결되지 않았거나, 만든 지 30초가 안 됐거나, 다른 조직의 터널입니다. OpenAI Platform에서 고치세요. |
| ChatGPT에서 앱을 만들 수 없음 | 서버가 꺼져 있거나 터널이 아직 준비되지 않았습니다. `status`에 `Tunnel: … ready`가 보여야 합니다. |
| 로그에 `invalid_grant`와 함께 `drive.poll.failed` | Google이 로그인을 끝냈습니다. 어느 Recly 앱에서든 Google Drive 연결을 해제하면 이렇게 됩니다. recly-events가 같은 Recly 로그인을 쓰기 때문입니다. `recly-events init --google`을 다시 실행하세요. |
| `status`: "the subscription ended" | 에이전트에게 다시 구독해 달라고 말하세요. 그사이의 이벤트는 기다리고 있습니다. |
| 어떤 녹음에 이벤트가 오지 않음 | Google Drive에 저장됐는지, recly-events를 처음 시작한 뒤의 녹음인지, 말소리가 있어 전사됐는지 확인하세요. `status`에 마지막으로 Drive를 확인한 때가 나옵니다. |

recly-events를 멈추려고 [myaccount.google.com/permissions](https://myaccount.google.com/permissions)에서 Recly를 지우지
마세요. 모든 기기의 모든 Recly 앱 연결까지 끊깁니다.

## 지우기

1. 멈춥니다. `recly-events service uninstall`을 실행하거나, `serve`를 멈추거나, Windows 작업을 지웁니다.
2. 폴더를 지웁니다([평소에 쓰는 명령](#평소에-쓰는-명령) 참고). Google 로그인과 터널 키도 함께 지워집니다.
3. 프로그램을 지웁니다. Mac에서 설치 파일로 설치했다면:

   ```sh
   sudo rm /usr/local/bin/recly-events
   sudo rm -r /usr/local/share/doc/recly-events
   sudo pkgutil --forget dev.recly.events
   ```

4. OpenAI Platform에서 터널과 키를, ChatGPT에서 앱을 지웁니다.

`config.json`, 직접 빌드하기, 내 Google 클라이언트 쓰기까지 담긴 전체 설명은
[events/README.md](https://github.com/rokrokss/recly/blob/main/events/README.md)(영어)에 있습니다.
