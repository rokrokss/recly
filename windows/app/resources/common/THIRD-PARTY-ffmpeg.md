# ffmpeg (LGPL v2.1+)

ADR-019: Recly's Windows capture helper encodes with a **bundled ffmpeg** to produce the ADR-006
format (16 kHz mono 32 kbps AAC). The Media Foundation AAC encoder does not accept this format.

The bundled `ffmpeg.exe` and its DLLs are a build distributed under **LGPL v2.1 or later** (a
shared-library build configured with `--disable-gpl` and `--disable-nonfree`). Recly only runs
ffmpeg as a separate process and does not statically link the ffmpeg libraries.

- Original: <https://ffmpeg.org/>
- Full license text: <https://www.ffmpeg.org/legal.html> · LGPL v2.1 <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>
- Source code: the source of the build used is available from the <https://github.com/BtbN/FFmpeg-Builds>
  release and the ffmpeg revision it points to. The same source is provided on request.

The LGPL requires that the user be able to **replace** ffmpeg with a version they built themselves.
This app launches the helper with `--ffmpeg <path>`, so if you replace `ffmpeg.exe` in the
installation folder with another LGPL build of the same name, the app uses it as is
(`app/resources/`).
