"""Locate only our local offline probe in Xiaomi's share sheet; never select other apps."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = "/Users/hongmw/development/android-sdk/platform-tools/adb"
SERIAL = "5a4ca054"

def shell(*args):
    return subprocess.check_output([ADB, "-s", SERIAL, "shell", *args], text=True, timeout=25)

for step in range(18):
    focus = shell("dumpsys", "window")
    if "com.android.intentresolver" not in next(
            (line for line in focus.splitlines() if "mCurrentFocus=" in line), ""):
        raise SystemExit("Share sheet is not focused; stopped without clicking.")
    shell("uiautomator", "dump", "/data/local/tmp/xiaomi-probe-resolver.xml")
    root = ET.fromstring(shell("cat", "/data/local/tmp/xiaomi-probe-resolver.xml"))
    nodes = list(root.iter("node"))
    names = [n.get("text") for n in nodes if n.get("resource-id", "").endswith(":id/text1")]
    print(f"page {step}: {names}", flush=True)
    target = next((n for n in nodes if n.get("text") == "录音只读探测"), None)
    if target is not None:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", target.get("bounds")))
        if x2 - x1 < 100:
            shell("input", "swipe", "1080", "2100", "170", "2100", "450")
            time.sleep(0.7)
            continue
        shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        print("Selected local offline probe only.", flush=True)
        raise SystemExit(0)
    shell("input", "swipe", "1080", "2100", "170", "2100", "450")
    time.sleep(0.7)
raise SystemExit("Probe not found; stopped without selecting any app.")
