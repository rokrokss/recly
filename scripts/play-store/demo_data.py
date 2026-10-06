"""Demo recordings for the Play Store screenshots (en and ko). Sample content, not user data.

Each recording is a list of spoken segments; seed.py turns every segment into TTS audio, so the
transcript's timestamps are the real offsets in the audio the app plays.
"""

# (local date-time in the emulator's time zone, title, segments)
EN = [
    ("2026-10-06 09:30", "Weekly product sync", [
        "Okay, let's get started.",
        "Three things today: the beta feedback, the onboarding flow, and the launch date.",
        "Beta feedback first.",
        "We heard from about two hundred testers this week, and the response is really positive.",
        "Most of them loved that recordings go straight to their own Google Drive.",
        "Nobody has to trust yet another cloud account.",
        "The biggest request was faster transcripts.",
        "That's why on-device transcription is now the default on phones that can run it.",
        "People also liked that they can pick their own provider when they want speaker labels.",
        "One thing to watch: a few testers recorded on the watch and weren't sure the file had reached the phone.",
        "Can we show that more clearly?",
        "Yes. The list will say when a recording is arriving from the watch, and the row turns into a normal recording once it's complete.",
        "Good. What about onboarding? Where are people getting stuck?",
        "Mostly the Drive connection step.",
        "Some people expected to create an account first. There isn't one, so we'll add a short explanation before the permission screen.",
        "Your recordings go to your own Drive, and nothing is stored anywhere else.",
        "I'll draft the copy today and share it in the channel by Thursday.",
        "Please keep it to two sentences. Shorter is better on that screen.",
        "Got it. Next, the launch date. Are we still on track for October fourteenth?",
        "Engineering is on track. The last open item is the help center article, and it's almost done.",
        "And the watch app?",
        "Ready too. It records even when the phone isn't nearby, and the recording moves over on its own later.",
        "Great. Any risks we should write down?",
        "Nothing major. Let's review once more next week.",
        "Perfect. Then the launch stays on October fourteenth. Thanks, everyone.",
    ]),
    ("2026-10-06 08:05", "Morning standup", [
        "Yesterday I finished the export screen.",
        "Today I'm on the settings bugs, and nothing is blocking me.",
    ]),
    ("2026-10-05 10:05", "Customer interview", [
        "Thanks for making time today.",
        "Can you walk me through how you take notes in meetings right now?",
        "I usually type while people talk, so I miss half of what they say.",
        "Then I spend the evening rewriting it from memory.",
        "What would change if the whole meeting were written down for you?",
        "I could actually listen, and ask my assistant for the action items afterwards.",
    ]),
    ("2026-10-05 08:40", "Design review", [
        "The new list reads well.",
        "Let's make the status column a little narrower on small phones.",
    ]),
    ("2026-10-04 11:10", "Biology lecture", [
        "Today we start with cell respiration.",
        "Glycolysis happens in the cytoplasm and splits one glucose molecule into two pyruvate.",
        "Remember the net yield: two ATP and two NADH.",
    ]),
    ("2026-10-04 09:20", "Study group", [
        "Let's split the chapters.",
        "I'll take chapter four, and you take five and six for Thursday.",
    ]),
    ("2026-10-03 11:48", "Podcast idea", [
        "An episode about tools that stay out of your way.",
        "Start with the watch on your wrist that you never think about.",
    ]),
    ("2026-10-03 09:15", "Weekly 1:1", [
        "How is the new project going?",
        "Good. I'd like more time for the testing plan next week.",
    ]),
    ("2026-10-02 09:02", "Book club", [
        "This month's book was shorter than it looked.",
        "Let's pick the next one before we leave.",
    ]),
]

KO = [
    ("2026-10-06 09:30", "주간 제품 회의", [
        "시작하겠습니다.",
        "오늘은 베타 피드백, 온보딩, 출시 일정 세 가지를 보겠습니다.",
        "베타 피드백부터요.",
        "이번 주에 테스터 이백 명 정도가 의견을 줬고, 반응이 아주 좋습니다.",
        "녹음이 바로 내 구글 드라이브에 저장되는 점을 가장 좋아했어요.",
        "다른 클라우드 계정을 믿지 않아도 되니까요.",
        "가장 많은 요청은 빠른 전사였어요.",
        "그래서 지원되는 휴대폰에서는 기기 내 전사를 기본값으로 바꿨습니다.",
        "화자 구분이 필요할 때 원하는 업체를 직접 고를 수 있는 점도 좋아했습니다.",
        "하나 챙길 게 있어요. 시계로 녹음한 몇몇 테스터가 파일이 휴대폰에 도착했는지 헷갈려했습니다.",
        "그걸 더 분명하게 보여 줄 수 있을까요?",
        "네. 목록에 시계에서 받는 중이라고 표시하고, 다 받으면 일반 녹음으로 바뀌게 하겠습니다.",
        "좋아요. 온보딩에서는 어디서 막히나요?",
        "대부분 드라이브 연결 단계예요.",
        "먼저 계정을 만들어야 하는 줄 아는 분들이 있었어요. 계정은 없으니까, 권한 화면 전에 짧은 설명을 넣겠습니다.",
        "녹음은 내 드라이브로만 가고, 다른 곳에는 아무것도 저장되지 않는다고요.",
        "문구는 오늘 초안을 만들어서 목요일까지 공유하겠습니다.",
        "두 문장 안으로 부탁해요. 그 화면은 짧을수록 좋아요.",
        "알겠습니다. 다음은 출시 일정이에요. 10월 14일 그대로 갈 수 있나요?",
        "개발은 일정대로예요. 남은 건 도움말 문서 하나인데 거의 끝났습니다.",
        "시계 앱은요?",
        "시계 앱도 준비됐어요. 휴대폰이 옆에 없어도 녹음되고, 나중에 알아서 넘어옵니다.",
        "좋습니다. 기록해 둘 위험 요소가 있을까요?",
        "특별한 건 없어요. 다음 주에 한 번 더 점검하면 될 것 같습니다.",
        "좋습니다. 출시는 10월 14일 그대로 갑니다. 다들 수고하셨습니다.",
    ]),
    ("2026-10-06 08:05", "아침 스탠드업", [
        "어제는 내보내기 화면을 끝냈습니다.",
        "오늘은 설정 화면 버그를 보고, 막힌 건 없습니다.",
    ]),
    ("2026-10-05 10:05", "고객 인터뷰", [
        "오늘 시간 내 주셔서 감사합니다.",
        "지금은 회의 메모를 어떻게 하시는지 이야기해 주시겠어요?",
        "보통 사람들이 말하는 동안 타이핑을 해서, 절반은 놓쳐요.",
        "그리고 저녁에 기억을 더듬어서 다시 정리하죠.",
        "회의 내용이 전부 글로 남는다면 무엇이 달라질까요?",
        "회의에 집중할 수 있고, 끝나고 나서 비서한테 할 일만 뽑아 달라고 하면 되겠죠.",
    ]),
    ("2026-10-05 08:40", "디자인 리뷰", [
        "새 목록 화면은 잘 읽힙니다.",
        "작은 휴대폰에서는 상태 열을 조금 좁히죠.",
    ]),
    ("2026-10-04 11:10", "생물학 강의", [
        "오늘은 세포 호흡부터 시작합니다.",
        "해당과정은 세포질에서 일어나고, 포도당 한 분자를 피루브산 두 분자로 나눕니다.",
        "순생성량을 기억하세요. ATP 두 개와 NADH 두 개입니다.",
    ]),
    ("2026-10-04 09:20", "스터디 모임", [
        "챕터를 나눠서 보죠.",
        "저는 4장을 맡고, 5장과 6장은 목요일까지 부탁해요.",
    ]),
    ("2026-10-03 11:48", "팟캐스트 아이디어", [
        "방해하지 않는 도구들에 대한 에피소드.",
        "평소에 의식하지 않는 손목 위 시계 이야기로 시작하자.",
    ]),
    ("2026-10-03 09:15", "주간 1:1", [
        "새 프로젝트는 어떻게 돼 가요?",
        "좋아요. 다음 주에는 테스트 계획에 시간을 더 쓰고 싶어요.",
    ]),
    ("2026-10-02 09:02", "독서 모임", [
        "이번 달 책은 생각보다 짧았어요.",
        "다음 책은 오늘 가기 전에 정하죠.",
    ]),
]

SETS = {"en": (EN, "Samantha", "en"), "ko": (KO, "Yuna", "ko")}
