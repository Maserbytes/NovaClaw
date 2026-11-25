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
calculator=$(adb shell pm list packages | tr -d '\r' | sed -n 's/^package:\(.*calculator.*\)$/\1/p' | head -n 1)
if test -z "$calculator"; then
  adb install -r smoke-inputs/calculator.apk
  calculator=org.fossify.math
fi
adb shell run-as "$package" mkdir -p shared_prefs
printf '%s\n' '<?xml version="1.0" encoding="utf-8"?><map><boolean name="external_automation_enabled" value="true"/></map>' |
  adb exec-in "run-as $package sh -c 'cat > shared_prefs/nova_settings.xml'"
adb shell am start -W -n "$activity"
adb shell settings put secure enabled_accessibility_services "$service"
adb shell settings put secure accessibility_enabled 1
adb shell run-as "$package" mkdir -p files/models
adb exec-in "run-as $package sh -c 'cat > files/models/$model'" < "smoke-inputs/$model"
sleep 4
run_task() {
  local prompt="$1" expected="$2" label="$3" timeout="$4"
  adb shell am start -W -n "$activity"
  adb logcat -c
  local goal
  goal=$(printf %s "$prompt" | base64 -w0)
  adb shell am broadcast -a ai.novaclaw.RUN_TASK -n "$package/.automation.ExternalAutomationReceiver" \
    --es task_b64 "$goal" --es request_id "$label"
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    adb logcat -d -s NovaClaw/Smoke > "smoke-results/$label-task.log"
    if grep -Fq 'Task halted' "smoke-results/$label-task.log"; then
      adb shell am start -W -n "$activity"
      sleep 2
      echo "FAIL: NovaClaw reported a task error during $label" >&2
      return 1
    fi
    if grep -Fq 'Task completed' "smoke-results/$label-task.log" &&
        timeout 15 adb shell uiautomator dump /sdcard/novaclaw-smoke.xml >/dev/null 2>&1; then
      adb exec-out cat /sdcard/novaclaw-smoke.xml > "smoke-results/$label.xml"
      adb logcat -d -s NovaClaw/AgentLoop > "smoke-results/$label-agent.log"
      python3 - "$expected" "$calculator" "smoke-results/$label.xml" <<'PY' || return 1
import sys
import xml.etree.ElementTree as ET
expected, package, xml = sys.argv[1:]
nodes = ET.parse(xml).iter("node")
if not any(n.get("package") == package and
           n.get("clickable") == "false" and
           (n.get("text") == expected or n.get("content-desc") == expected) for n in nodes):
    sys.exit(f"Calculator never displayed {expected} as a readout")
PY
      adb exec-out screencap -p > "smoke-results/$label-screen.png"
      echo "PASS: $label displayed $expected and NovaClaw completed"
      return 0
    fi
    sleep 3
  done
  echo "FAIL: $label timed out" >&2
  return 1
}

status=0
run_task 'Open Calculator and calculate 24 times 17' 408 calculator 90 || status=1
if run_task 'Open Calculator and clear it, then tap the digit 7' 7 gemma-tap 300; then
  grep -F 'engine ready backend=CPU' smoke-results/gemma-tap-agent.log || status=1
else
  status=1
fi

# Verify the CPU Qwen model boots independently of Gemma.
adb shell am force-stop "$package"
adb shell run-as "$package" rm "files/models/$model"
qwen=Qwen3.5-2B_int8.litertlm
adb exec-in "run-as $package sh -c 'cat > files/models/$qwen'" < "smoke-inputs/$qwen"
adb shell am start -W -n "$activity"
adb shell settings put secure enabled_accessibility_services "$service"
sleep 2
adb logcat -c
goal=$(printf %s 'What is 2 plus 2? Answer with only the number.' | base64 -w0)
adb shell am broadcast -a ai.novaclaw.RUN_TASK -n "$package/.automation.ExternalAutomationReceiver" \
  --es task_b64 "$goal" --es request_id qwen-chat
for attempt in $(seq 1 80); do
  adb logcat -d -s NovaClaw/Smoke NovaClaw/AgentLoop > smoke-results/qwen.log
  if grep -Fq 'Task halted' smoke-results/qwen.log; then
    echo 'FAIL: Qwen task stopped with an error' >&2
    exit 1
  fi
  if grep -Fq 'Task completed' smoke-results/qwen.log; then
    grep -F 'Task started with qwen3.5-2b' smoke-results/qwen.log
    adb shell "run-as $package sh -c 'cat files/conversations/*.md'" > smoke-results/qwen-response.txt
    grep -Eq '^nova: .*\b4\b' smoke-results/qwen-response.txt
    echo 'PASS: Qwen 3.5 2B answered using the CPU runtime'
    exit "$status"
  fi
  sleep 3
done
echo 'FAIL: Qwen task timed out' >&2
exit 1
