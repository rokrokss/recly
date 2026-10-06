#!/bin/sh
# Clean status bar for captures: 9:41, full battery and Wi-Fi, no mobile signal, no notifications.
A=${ADB:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb}
$A shell settings put global sysui_demo_allowed 1
b() { $A shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
b enter
b clock -e hhmm 0941
b battery -e level 100 -e plugged false
b network -e wifi show -e level 4 -e fully true
b network -e mobile hide
b notifications -e visible false
