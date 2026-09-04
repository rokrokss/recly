# ffmpeg (LGPL v2.1+)

ADR-019: Recly의 Windows 캡처 헬퍼는 ADR-006 포맷(16 kHz 모노 32 kbps AAC)을 내기 위해 **번들
ffmpeg**로 인코딩한다. Media Foundation의 AAC 인코더는 이 포맷을 받지 않는다.

동봉된 `ffmpeg.exe`와 그 DLL은 **LGPL v2.1 이상**으로 배포되는 빌드다(`--disable-gpl`,
`--disable-nonfree`로 구성한 공유 라이브러리 빌드). Recly는 ffmpeg를 별도 프로세스로 실행할 뿐이며
ffmpeg 라이브러리를 정적으로 링크하지 않는다.

- 원본: <https://ffmpeg.org/>
- 라이선스 전문: <https://www.ffmpeg.org/legal.html> · LGPL v2.1 <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>
- 소스 코드: 사용한 빌드의 소스는 <https://github.com/BtbN/FFmpeg-Builds> 릴리스와 그것이 가리키는
  ffmpeg 리비전에서 받을 수 있다. 요청 시 같은 소스를 제공한다.

LGPL은 사용자가 ffmpeg를 자신이 만든 버전으로 **교체**할 수 있을 것을 요구한다. 이 앱은 헬퍼에
`--ffmpeg <경로>`를 넘겨 실행하므로, 설치 폴더의 `ffmpeg.exe`를 같은 이름의 다른 LGPL 빌드로
바꿔 넣으면 그대로 쓰인다(`app/resources/`).
