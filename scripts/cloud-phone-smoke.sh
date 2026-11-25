#!/usr/bin/env bash
set -euo pipefail

package=ai.novaclaw
activity="$package/.ui.MainActivity"
service="$package/.a11y.NovaAccessibilityService"
model=gemma-4-E2B-it.litertlm
mkdir -p smoke-results
capture() {
  adb logcat -d > smoke-results/logcat.txt || true
  adb exec-out screencap -p > smoke-results/final-screen.png || true
  adb shell dumpsys activity activities > smoke-results/activities.txt || true
}
trap capture EXIT

adb install -r app/build/outputs/apk/debug/app-debug.apk
if ! adb shell pm list packages | grep -Eiq '^package:.*calculator'; then
  adb install -r smoke-inputs/calculator.apk
fi
adb shell am start -W -n "$activity"
adb shell settings put secure enabled_accessibility_services "$service"
adb shell settings put secure accessibility_enabled 1
sleep 3
adb shell run-as "$package" mkdir -p shared_prefs "/sdcard/Android/data/$package/files/models"
printf '%s\n' '<?xml version="1.0" encoding="utf-8"?><map><boolean name="external_automation_enabled" value="true"/></map>' |
  adb exec-in "run-as $package sh -c 'cat > shared_prefs/nova_settings.xml'"
adb exec-in "run-as $package sh -c 'cat > /sdcard/Android/data/$package/files/models/$model'" < "smoke-inputs/$model"
adb shell am force-stop "$package"
adb shell am start -W -n "$activity"
sleep 4
goal=$(printf %s 'Open Calculator and calculate 24 times 17' | base64 -w0)
adb shell am broadcast -a ai.novaclaw.RUN_TASK -n "$package/.automation.ExternalAutomationReceiver" \
  --es task_b64 "$goal" --es request_id cloud-calculator
for attempt in $(seq 1 120); do
  if adb shell uiautomator dump /sdcard/novaclaw-smoke.xml >/dev/null 2>&1; then
    adb exec-out cat /sdcard/novaclaw-smoke.xml > smoke-results/calculator.xml
    if python3 - <<'PY'
import sys
import xml.etree.ElementTree as ET
try:
    nodes = ET.parse("smoke-results/calculator.xml").iter("node")
    sys.exit(not any("calculator" in n.get("package", "").lower() and
                     (n.get("text") == "408" or n.get("content-desc") == "408") for n in nodes))
except ET.ParseError:
    sys.exit(1)
PY
    then
      adb logcat -d -s NovaClaw/AgentLoop | grep -F 'engine ready backend=CPU'
      echo 'PASS: on-device model ran and Calculator displayed 408'
      exit 0
    fi
  fi
  sleep 4
done
echo 'FAIL: Calculator never displayed 408' >&2
exit 1
