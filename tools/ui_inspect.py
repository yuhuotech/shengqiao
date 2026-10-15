"""Print UI controls and text lengths for the explicitly selected Android device."""
import argparse
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("serial")
parser.add_argument("remote_xml")
parser.add_argument("--adb", default="/Users/hongmw/development/android-sdk/platform-tools/adb")
args = parser.parse_args()
data = subprocess.check_output([args.adb, "-s", args.serial, "shell", "cat", args.remote_xml])
root = ET.fromstring(data)
for node in root.iter("node"):
    text = node.get("text", "")
    desc = node.get("content-desc", "")
    if not (text or desc or node.get("clickable") == "true"):
        continue
    # Avoid printing transcript paragraphs in diagnostic logs.
    def label(value):
        if "recognition_content" in node.get("resource-id", ""):
            return f"[transcript length={len(value)}]"
        return value if len(value) <= 45 else f"[text length={len(value)}]"
    print({"text": label(text), "id": node.get("resource-id"), "desc": label(desc),
           "click": node.get("clickable"), "bounds": node.get("bounds")})
