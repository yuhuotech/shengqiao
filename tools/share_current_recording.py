"""ADB proof: share the currently open recording to our offline probe only.

Run with text or audio. This validates the UI route, not an on-phone accessibility service.
"""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("serial")
parser.add_argument("mode", choices=["text", "audio"])
parser.add_argument("--adb", default="/Users/hongmw/development/android-sdk/platform-tools/adb")
args = parser.parse_args()

def shell(*command):
    return subprocess.check_output([args.adb, "-s", args.serial, "shell", *command],
                                   text=True, timeout=25)

def snapshot(package):
    window = shell("dumpsys", "window").splitlines()
    focus = next((line for line in window
                  if "mCurrentFocus=" in line), "")
    focused_app = next((line for line in window if "mFocusedApp=" in line), "")
    if package not in focus and not ("PopupWindow:" in focus and package in focused_app):
        raise RuntimeError("Unexpected foreground app; stopped without clicking.")
    shell("uiautomator", "dump", "--compressed", "/data/local/tmp/xiaomi-share-poc.xml")
    nodes = list(ET.fromstring(shell("cat", "/data/local/tmp/xiaomi-share-poc.xml")).iter("node"))
    if not any(n.get("package") == package for n in nodes):
        raise RuntimeError("Unexpected UI tree; stopped without clicking.")
    return nodes

def tap(nodes, *, text=None, resource=None):
    matches = [n for n in nodes if (text is None or n.get("text") == text)
               and (resource is None or n.get("resource-id") == resource)]
    if len(matches) != 1:
        raise RuntimeError(f"Expected one control {text or resource}, got {len(matches)}")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", matches[0].get("bounds")))
    shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.7)

nodes = snapshot("com.android.soundrecorder")
if not any(n.get("resource-id") == "com.android.soundrecorder:id/actionbar_title" for n in nodes):
    raise SystemExit("Open and pause a recording detail first.")
tap(nodes, resource="com.android.soundrecorder:id/more")
tap(snapshot("com.android.soundrecorder"), text="分享")
tap(snapshot("com.android.soundrecorder"), text="分享文本" if args.mode == "text" else "分享音频")
for page in range(18):
    nodes = snapshot("com.android.intentresolver")
    target = next((n for n in nodes if n.get("text") == "录音只读探测"), None)
    if target is not None:
        x1, _, x2, _ = map(int, re.findall(r"\d+", target.get("bounds")))
        if x2 - x1 >= 100:
            tap(nodes, text="录音只读探测")
            if args.mode == "text":
                tap(snapshot("com.android.intentresolver"), resource="android:id/button_once")
            time.sleep(0.7)
            print(shell("run-as", "local.xiaomi.audiosync.probe", "cat", "files/share-report.txt"))
            break
    # Coordinates are specific to this device's measured 1200 x 2670 share sheet.
    if args.mode == "audio":
        shell("input", "touchscreen", "swipe", "600", "2440", "600", "1050", "1000")
    else:
        shell("input", "swipe", "1080", "2100", "170", "2100", "450")
    time.sleep(0.7)
else:
    raise SystemExit("Local probe not found; no other target selected.")
