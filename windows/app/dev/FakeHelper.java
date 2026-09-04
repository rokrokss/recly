import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * A stand-in for the Rust capture helper (docs/14, M6-L2) that speaks the docs/14 JSON-line
 * protocol and writes no audio. It is what `HelperClientTest` runs and what puts the recording path
 * in front of a person on a machine that has no helper binary — the development host this lane is
 * built on (M6-L1 "환경 제약").
 *
 * Java, and a single source file, deliberately: `java path/to/FakeHelper.java` runs it on every
 * platform the app is tested on with no classpath, no build step and no shell.
 *
 *   RECLY_CAPTURE_HELPER="java /abs/path/windows/app/dev/FakeHelper.java" ./gradlew :windows:app:run
 *
 * Arguments (all optional):
 *   parts=N      how many `part_done` events a `start` produces (default 1)
 *   sec=D        the duration each part reports (default 1.0)
 *   die          exit after the parts instead of waiting for `stop` — the helper-death path
 *   micInUse=APP emit one `mic_in_use` (inUse true) when `detect on` arrives
 *   micIdleAfter=D  seconds after that, emit `mic_in_use` inUse false — the 유휴 감지 경로
 *   noise        print a line that is not protocol before the parts
 *   partsOnStop  emit the parts when `stop` arrives instead of when `start` does
 *   hang         never answer `stop` and never exit — the app has to kill it
 *   write        actually write each part to `{dir}/{file}` and report its real sha256
 *
 * `write` is off by default because the tests that assert what a recording leaves on disk expect
 * `meta.json` and nothing else (`WindowsRecorderTest`). It exists for the acceptance harness
 * (`DesktopAcceptance`), which uploads the parts to Drive: a `part_done` naming a file that is not
 * there is a `drive.upload` that cannot size it.
 */
public final class FakeHelper {

    public static void main(String[] args) throws Exception {
        int parts = 1;
        double sec = 1.0;
        boolean die = false;
        boolean noise = false;
        boolean partsOnStop = false;
        boolean hang = false;
        boolean write = false;
        String micInUse = null;
        double micIdleAfter = 0;
        for (String arg : args) {
            if (arg.equals("die")) die = true;
            else if (arg.equals("noise")) noise = true;
            else if (arg.equals("partsOnStop")) partsOnStop = true;
            else if (arg.equals("hang")) hang = true;
            else if (arg.equals("write")) write = true;
            else if (arg.startsWith("parts=")) parts = Integer.parseInt(arg.substring(6));
            else if (arg.startsWith("sec=")) sec = Double.parseDouble(arg.substring(4));
            else if (arg.startsWith("micInUse=")) micInUse = arg.substring(9);
            else if (arg.startsWith("micIdleAfter=")) micIdleAfter = Double.parseDouble(arg.substring(13));
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String start = null;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.contains("\"start\"")) {
                start = line;
                if (noise) {
                    System.out.println("thread 'capture' panicked at src/main.rs:1");
                    System.out.flush();
                }
                if (!partsOnStop) {
                    emitAll(start, parts, sec, write);
                }
                if (die) {
                    // Not a `stop`, no goodbye: the app finds out by the pipe closing.
                    System.exit(9);
                }
            } else if (line.contains("\"detect\"") && micInUse != null && line.contains("true")) {
                emitMic(micInUse, true);
                if (micIdleAfter > 0) {
                    // The meeting going quiet, on a timer: docs/14's 유휴 60초 offer needs the
                    // microphone to be *given back*, which no fake can do by holding still.
                    final String app = micInUse;
                    final long ms = (long) (micIdleAfter * 1000);
                    Thread idle = new Thread(() -> {
                        try {
                            Thread.sleep(ms);
                        } catch (InterruptedException interrupted) {
                            return;
                        }
                        emitMic(app, false);
                    });
                    idle.setDaemon(true);
                    idle.start();
                }
            } else if (line.contains("\"stop\"")) {
                if (partsOnStop && start != null) {
                    emitAll(start, parts, sec, write);
                }
                if (hang) {
                    // stdout stays open, so the app never sees the end of the stream: the only way
                    // out is the kill its stop path is supposed to fall back on.
                    Thread.sleep(60_000);
                }
                return;
            }
        }
    }

    /** Both threads write to stdout, and half a JSON line is a line the app logs and drops. */
    private static synchronized void emitMic(String app, boolean inUse) {
        System.out.println("{\"event\":\"mic_in_use\",\"app\":\"" + app + "\",\"inUse\":" + inUse + "}");
        System.out.flush();
    }

    private static void emitAll(String start, int parts, double sec, boolean write) throws Exception {
        String base = value(start, "base");
        String dir = value(start, "dir");
        for (int part = 1; part <= parts; part++) {
            for (String track : tracks(start)) {
                emitPart(dir, base, part, track, sec, write);
            }
        }
    }

    private static void emitPart(String dir, String base, int part, String track, double sec, boolean write)
        throws Exception {
        String file = String.format("%s_p%03d_%s.m4a", base, part, track);
        long bytes = (long) (sec * 4000);
        String sha = sha(file);
        if (write) {
            byte[] content = new byte[(int) bytes];
            // Deterministic, and different per track: a re-uploaded part keeps the md5 Drive
            // already has, and no two tracks are the same file.
            for (int i = 0; i < content.length; i++) content[i] = (byte) Math.floorMod(i + file.hashCode(), 251);
            Files.write(Path.of(dir, file), content);
            sha = hex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        System.out.println(
            "{\"event\":\"part_done\",\"part\":" + part
                + ",\"track\":\"" + track + "\""
                + ",\"file\":\"" + file + "\""
                + ",\"bytes\":" + bytes
                + ",\"sha256\":\"" + sha + "\""
                + ",\"startOffsetSec\":" + ((part - 1) * sec)
                + ",\"durationSec\":" + sec + "}");
        System.out.flush();
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }

    /** The tracks the `start` command listed, in order. */
    private static String[] tracks(String line) {
        int open = line.indexOf("\"tracks\":[");
        if (open < 0) return new String[] {"mic"};
        String list = line.substring(open + 10, line.indexOf(']', open));
        return list.replace("\"", "").split(",");
    }

    /** Not the file's hash — there is no file. Stable per name, which is all a test can check. */
    private static String sha(String name) {
        StringBuilder out = new StringBuilder();
        long hash = name.hashCode() & 0xffffffffL;
        for (int i = 0; i < 8; i++) {
            out.append(String.format("%08x", (hash * (i + 1)) & 0xffffffffL));
        }
        return out.toString();
    }

    private static String value(String line, String key) {
        int at = line.indexOf("\"" + key + "\":\"");
        if (at < 0) return "rec";
        int start = at + key.length() + 4;
        return line.substring(start, line.indexOf('"', start));
    }
}
