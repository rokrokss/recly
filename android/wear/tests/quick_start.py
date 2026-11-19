"""Exercise #28 on a disposable, English Wear OS emulator with the microphone permission granted.

Install the APK with adb install -g. Once the watch face is ready, run
ANDROID_SERIAL=emulator-XXXX python3 android/wear/tests/quick_start.py.
This stops Recly and makes two short recordings. Use an emulator with host audio input disabled.
"""

import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def shell(*args):
    return subprocess.check_output(["adb", "-s", serial, "shell", *args], text=True).strip()


def quick_rec():
    # Same flags as a launcher icon: NEW_TASK | RESET_TASK_IF_NEEDED.
    print(shell("am", "start", "-W", "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER", "-f", "0x10200000",
                "-n", "app.recly/app.recly.wear.entry.QuickStartActivity"), flush=True)


def wait_for_button(label):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        path = "/data/local/tmp/recly-quick-start.xml"
        try:
            report = shell("uiautomator", "dump", path)
        except subprocess.CalledProcessError as error:
            if error.returncode == 137:  # Wear can kill the dump process under memory pressure.
                continue
            raise
        if "dumped to:" not in report:
            continue
        root = ET.fromstring(shell("cat", path))
        for node in root.iter("node"):
            if (node.get("package") == "app.recly" and node.get("content-desc") == label
                    and node.get("enabled") == "true"):
                return [int(n) for n in re.findall(r"\d+", node.attrib["bounds"])]
    raise AssertionError(f"Recly did not show an enabled {label!r} button")


if __name__ == "__main__":
    serial = os.environ.get("ANDROID_SERIAL", "")
    if not serial.startswith("emulator-") or "watch" not in shell("getprop", "ro.build.characteristics"):
        raise SystemExit("Set ANDROID_SERIAL to a disposable Wear OS emulator.")
    shell("am", "force-stop", "app.recly")
    shell("input", "keyevent", "KEYCODE_WAKEUP")
    shell("wm", "dismiss-keyguard")
    home = shell("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
                 "-c", "android.intent.category.HOME").splitlines()[-1]
    for attempt in (1, 2):
        quick_rec()
        left, top, right, bottom = wait_for_button("Stop")
        shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        wait_for_button("Start recording")
        print(f"Recording {attempt} started and stopped.", flush=True)
        shell("input", "keyevent", "KEYCODE_HOME")
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            activities = shell("dumpsys", "activity", "activities").splitlines()
            if any("topResumedActivity=" in line and home in line for line in activities):
                break
            time.sleep(0.1)
        else:
            raise AssertionError("The watch did not return to its home screen")
