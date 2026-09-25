#!/usr/bin/env bash
set -euo pipefail

PKG="io.github.kitzhn.jevultimate.debug"
APK="app/build/outputs/apk/debug/app-debug.apk"

mkdir -p screenshots demo/relations demo/logs integration-results

trap 'code=$?; adb logcat -d -v time > integration-results/failure-logcat.txt 2>/dev/null || true; exit $code' ERR

adb install -r "$APK"

wait_activity() {
  local component="$1"
  local out
  for _ in $(seq 1 12); do
    out="$(adb shell dumpsys activity activities 2>/dev/null || true)"
    if printf '%s' "$out" | grep -Fq "$component"; then
      return 0
    fi
    sleep .5
  done
  echo "activity not observed: $component" >&2
  return 1
}

mark_stage() {
  printf '%s\n' "$1" > integration-results/stage.txt
}

python3 - <<'PY'
import json, time, os
now = int(time.time() * 1000)
contacts = [
  {
    "id":"demo-a","name":"演示联系人 A","aliases":["A同学"],
    "apps":["com.tencent.mobileqq","com.ss.android.lark"],
    "identities":[
      {"app":"com.tencent.mobileqq","title":"A_QQ","label":"QQ","scope":""},
      {"app":"com.ss.android.lark","title":"A_飞书","label":"飞书","scope":""},
      {"app":"com.tencent.mobileqq","title":"A同学","label":"QQ群成员","scope":"演示旅行群"}
    ],
    "relationship":"朋友","relationshipStage":"亲近",
    "affection":76,"trust":82,"closeness":69,
    "profileTags":["同学","旅行","长期联系"],
    "traits":"性格直接，喜欢轻松沟通",
    "communicationStyle":"适合自然、简洁、不过度正式的表达",
    "boundaries":"不喜欢连续追问",
    "notes":"这是模拟器演示数据，不对应任何真实人物。",
    "strategySelections":{"playful":20,"warm":6,"steady":3,"proactive":1},
    "autoSummary":"","updatedAt":now
  },
  {
    "id":"demo-group","name":"演示旅行群","aliases":[],
    "apps":["com.tencent.mobileqq"],
    "identities":[
      {"app":"com.tencent.mobileqq","title":"演示旅行群","label":"QQ群","scope":""}
    ],
    "relationship":"群聊","relationshipStage":"活跃",
    "affection":50,"trust":50,"closeness":50,
    "profileTags":["群聊","旅行"],
    "traits":"","communicationStyle":"","boundaries":"",
    "notes":"纯模拟器群聊数据。","strategySelections":{},
    "autoSummary":"","updatedAt":now-1000
  },
  {
    "id":"demo-b","name":"演示联系人 B","aliases":[],
    "apps":["com.twitter.android"],
    "identities":[{"app":"com.twitter.android","title":"@demo_b","label":"X"}],
    "relationship":"朋友","relationshipStage":"普通",
    "affection":58,"trust":63,"closeness":51,
    "profileTags":["朋友","项目"],
    "traits":"做事细致",
    "communicationStyle":"适合明确说明背景和结论",
    "boundaries":"","notes":"纯演示联系人。",
    "autoSummary":"","updatedAt":now-3600000
  },
  {
    "id":"demo-c","name":"演示联系人 C","aliases":[],
    "apps":["com.tencent.mobileqq"],
    "identities":[{"app":"com.tencent.mobileqq","title":"C_QQ","label":"QQ"}],
    "relationship":"同事","relationshipStage":"熟悉",
    "affection":67,"trust":71,"closeness":54,
    "profileTags":["同事","协作"],
    "traits":"沟通效率高",
    "communicationStyle":"先说结论，再补充细节",
    "boundaries":"","notes":"纯演示联系人。",
    "autoSummary":"","updatedAt":now-7200000
  }
]
graph = [
  {"id":"edge-ab","fromId":"demo-a","toId":"demo-b","type":"大学同学","strength":84,"note":"曾经同组做项目","updatedAt":now},
  {"id":"edge-ac","fromId":"demo-a","toId":"demo-c","type":"通过朋友认识","strength":62,"note":"偶尔一起参加活动","updatedAt":now-1000}
]
events = [
  {"id":"ev1","ts":now-86400000,"delta":5,"fromScore":66,"toScore":71,"reason":"一次愉快的见面","source":"quick"},
  {"id":"ev2","ts":now-3600000,"delta":5,"fromScore":71,"toScore":76,"reason":"聊得很投机","source":"quick"}
]
logs = [
  {"side":"other","text":"周末有空一起吃饭吗？","ts":now-1800000,"app":"com.tencent.mobileqq"},
  {"side":"me","text":"可以啊，到时候一起看看。","ts":now-1200000,"app":"com.tencent.mobileqq"}
]
json.dump(contacts, open("demo/contacts.json","w"), ensure_ascii=False)
json.dump(graph, open("demo/contact_relations.json","w"), ensure_ascii=False)
json.dump(events, open("demo/relations/demo-a.json","w"), ensure_ascii=False)
json.dump(logs, open("demo/logs/demo-a.json","w"), ensure_ascii=False)
# Seed an oversized legacy v1 array to regression-test MAJOR-02/03/06 migration:
# >5000 detail rows must roll up without changing the monthly request total;
# non-exact vision input must become token-unknown; URL credentials must be scrubbed.
usage = [
  {"ts":now-(5001-i)*1000,"route":"判断接口","baseUrl":"https://openrouter.ai/api","model":"typesafe/jev-1.13",
   "inputTokens":1000,"outputTokens":0,"cachedInputTokens":0,"costCny":0.00028,"exactTokens":True,"pricingKnown":True}
  for i in range(5001)
]
usage += [
  {"ts":now-2400000,"route":"回复接口","baseUrl":"https://openrouter.ai/api/v1","model":"deepseek/deepseek-v4.1-flash",
   "inputTokens":18500,"outputTokens":1450,"cachedInputTokens":3200,"costCny":0.0189,"exactTokens":True,"pricingKnown":True},
  {"ts":now-1800000,"route":"回复接口","baseUrl":"https://api.deepseek.com/v1","model":"deepseek-flash",
   "inputTokens":22000,"outputTokens":1800,"cachedInputTokens":9000,"costCny":0.0202,"exactTokens":True,"pricingKnown":True},
  {"ts":now-1200000,"route":"视觉接口","baseUrl":"https://user:pass@api.deepseek.com/v1?key=SECRET#frag","model":"deepseek-flash",
   "inputTokens":9400,"outputTokens":320,"cachedInputTokens":0,"costCny":0.0120,"exactTokens":False,"pricingKnown":True},
  {"ts":now-600000,"route":"判断接口","baseUrl":"https://openrouter.ai/api","model":"typesafe/jev-1.13",
   "inputTokens":900,"outputTokens":0,"cachedInputTokens":0,"costCny":0.00025,"exactTokens":True,"pricingKnown":True}
]
os.makedirs("demo/usage", exist_ok=True)
json.dump(usage, open("demo/usage/api_usage.json","w"), ensure_ascii=False)
PY

adb shell "run-as $PKG mkdir -p files/kb/logs files/kb/relations files/usage"
adb push demo/contacts.json /data/local/tmp/contacts.json >/dev/null
adb push demo/contact_relations.json /data/local/tmp/contact_relations.json >/dev/null
adb push demo/relations/demo-a.json /data/local/tmp/demo-a-relations.json >/dev/null
adb push demo/logs/demo-a.json /data/local/tmp/demo-a-log.json >/dev/null
adb push demo/usage/api_usage.json /data/local/tmp/api_usage.json >/dev/null
adb shell "run-as $PKG cp /data/local/tmp/contacts.json files/kb/contacts.json"
adb shell "run-as $PKG cp /data/local/tmp/contact_relations.json files/kb/contact_relations.json"
adb shell "run-as $PKG cp /data/local/tmp/demo-a-relations.json files/kb/relations/demo-a.json"
adb shell "run-as $PKG cp /data/local/tmp/demo-a-log.json files/kb/logs/demo-a.json"
adb shell "run-as $PKG cp /data/local/tmp/api_usage.json files/usage/api_usage.json"

cat > /tmp/ui_text.py <<'PY'
import subprocess, xml.etree.ElementTree as ET, re, sys, time

mode, target = sys.argv[1], sys.argv[2]

def dump():
    import os
    for _ in range(8):
        try:
            if os.path.exists("/tmp/window.xml"):
                os.remove("/tmp/window.xml")
            d = subprocess.run(
                ["adb","shell","uiautomator","dump","/sdcard/window.xml"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if d.returncode != 0:
                time.sleep(.5)
                continue
            p = subprocess.run(
                ["adb","pull","/sdcard/window.xml","/tmp/window.xml"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if p.returncode == 0 and os.path.exists("/tmp/window.xml"):
                return ET.parse("/tmp/window.xml").getroot()
        except Exception:
            pass
        time.sleep(.5)
    raise RuntimeError("uiautomator window dump not ready")

for attempt in range(10):
    root = dump()
    matches = []
    for n in root.iter("node"):
        text = n.attrib.get("text","")
        desc = n.attrib.get("content-desc","")
        ok = (text == target or desc == target) if mode == "exact" else (target in text or target in desc)
        if ok:
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.attrib.get("bounds",""))
            if m:
                matches.append(tuple(map(int,m.groups())))
    if matches:
        if mode == "wait":
            sys.exit(0)
        x1,y1,x2,y2 = matches[-1]
        subprocess.run(["adb","shell","input","tap",str((x1+x2)//2),str((y1+y2)//2)], check=True)
        time.sleep(1.0)
        sys.exit(0)
    if mode == "optional":
        sys.exit(0)
    if mode == "wait":
        time.sleep(.5)
    else:
        subprocess.run(["adb","shell","input","swipe","540","1800","540","650","350"])
        time.sleep(.6)
raise SystemExit(f"{mode} text not found: {target}")
PY

mark_stage "01-home"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/com.jev.probe.MainActivity" >/dev/null
wait_activity "$PKG/com.jev.probe.MainActivity"
sleep 1
adb exec-out screencap -p > screenshots/01-home.png

mark_stage "02-settings"
adb shell am start -W -n "$PKG/com.jev.probe.SettingsActivity" >/dev/null
wait_activity "$PKG/com.jev.probe.SettingsActivity"
sleep 1
adb exec-out screencap -p > screenshots/02-settings.png

# Emulator images can occasionally show a Pixel Launcher ANR dialog over our
# activity after cold boot. It is unrelated to Jev; dismiss it if present so
# UI assertions inspect the app rather than the system dialog.
python3 /tmp/ui_text.py optional "Wait"
python3 /tmp/ui_text.py optional "等待"

# MAJOR-09: the advanced DeepSeek thinking control exists and defaults to OFF.
python3 /tmp/ui_text.py contains "DeepSeek 官方：启用 thinking（高级）"
adb exec-out screencap -p > screenshots/02b-deepseek-thinking.png

mark_stage "03-knowledge"
adb shell am start -W -n "$PKG/com.jev.probe.KnowledgeActivity" >/dev/null
wait_activity "$PKG/com.jev.probe.KnowledgeActivity"
sleep 1
python3 /tmp/ui_text.py exact "联系人"
adb exec-out screencap -p > screenshots/03-contacts.png

python3 /tmp/ui_text.py exact "关系网"
sleep 1
adb exec-out screencap -p > screenshots/04-relationship-graph.png

mark_stage "05-galgame"
# Debug-only overlay demo: this exercises the real OverlayController with
# GalGame-style option cards, then taps one option and verifies the chosen text
# appears in the mock chat input box.
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
echo "Overlay permission:"
adb shell appops get "$PKG" SYSTEM_ALERT_WINDOW || true
adb shell am start -W -n "$PKG/com.jev.probe.OverlayDemoActivity"
sleep 3
adb exec-out screencap -p > screenshots/05-galgame-options.png

# TYPE_APPLICATION_OVERLAY is not consistently exposed by uiautomator on API 35.
# The demo uses a fixed Pixel 6 profile and OverlayController's deterministic
# top-left layout, so tap the center of option A directly. The result is then
# verified through the ordinary EditText hierarchy after the overlay collapses.
adb shell input tap 430 1320
sleep 1
python3 /tmp/ui_text.py wait "好啊，那周末见～你想吃什么？"
adb exec-out screencap -p > screenshots/06-galgame-filled.png

adb shell "run-as $PKG cat files/kb/contacts.json" > /tmp/contacts-after-choice.json
python3 - <<'PY'
import json
data=json.load(open("/tmp/contacts-after-choice.json"))
a=next(x for x in data if x.get("id")=="demo-a")
assert a.get("strategySelections",{}).get("playful")==21, a.get("strategySelections")
print("strategy learning persisted:", a["strategySelections"])
PY


mark_stage "07-wechat-ocr-geometry"
# Relationship-aware GalGame cards are already captured in 05/06. Now verify
# the pure WeChat OCR geometry stage with deterministic OCR boxes.
adb shell am start -W -n "$PKG/com.jev.probe.WeChatOcrDemoActivity" >/dev/null
sleep 1
python3 /tmp/ui_text.py wait "PASS · other → me → other"
adb exec-out screencap -p > screenshots/07-wechat-ocr-geometry.png

mark_stage "08-wechat-settings"
# Show the opt-in WeChat auto-OCR setting in the real SettingsActivity.
adb shell am start -n "$PKG/com.jev.probe.SettingsActivity" >/dev/null
sleep 1
python3 /tmp/ui_text.py contains "微信优先用新消息通知触发 OCR"
adb exec-out screencap -p > screenshots/08-wechat-auto-ocr-setting.png

mark_stage "09-adaptive-core"
# Grant notification-listener access to the real production service declaration,
# then run a debug-only integration page that exercises the actual group context,
# scene detector, learned strategy counts and notification matching gate.
adb shell cmd notification allow_listener "$PKG/com.jev.probe.capture.WeChatNotificationListener" 0 || true
sleep 1
adb shell am start -W -n "$PKG/com.jev.probe.AdaptiveModelDemoActivity" >/dev/null
sleep 1
python3 /tmp/ui_text.py wait "ALL CORE CHECKS PASS"
adb exec-out screencap -p > screenshots/09-adaptive-core.png

# Final v2.4 adaptive regression on current main.


mark_stage "10-api-dashboard"
# API usage dashboard: real production activity reading local-only usage records.
adb shell am start -W -n "$PKG/com.jev.probe.ApiUsageActivity" >/dev/null
sleep 2
python3 /tmp/ui_text.py wait "API 消耗仪表盘"
python3 /tmp/ui_text.py wait "5005 次请求"
python3 /tmp/ui_text.py wait "1 次图片 token 未知"
adb exec-out screencap -p > screenshots/10-api-usage-dashboard.png

# Verify migration/persistence, not just the rendered labels.
adb shell "run-as $PKG cat files/usage/api_usage.json" > integration-results/api-usage-v2.json
python3 - <<'PY'
import json
p = "integration-results/api-usage-v2.json"
data = json.load(open(p))
assert data["version"] == 2, data.get("version")
assert len(data["records"]) == 5000, len(data["records"])
assert sum(x["requests"] for x in data["rollups"]) == 5, data["rollups"]
raw = open(p, encoding="utf-8").read()
assert "SECRET" not in raw and "user:pass" not in raw and "?key=" not in raw
vision = [x for x in data["records"] if x["route"] == "视觉接口"]
assert len(vision) == 1, len(vision)
assert vision[0]["baseUrl"] == "https://api.deepseek.com/v1", vision[0]["baseUrl"]
assert vision[0]["tokenKnown"] is False
assert vision[0]["inputTokens"] == 0
open("integration-results/api-usage-check.txt","w").write(
    "PASS: 5005 monthly requests preserved; 5 rows rolled up; vision tokens unknown; URL credentials scrubbed.\n"
)
PY

# ---------------------------------------------------------------------------
mark_stage "11-cross-app-install"
# Cross-app integration fixtures. These are separate APKs whose runtime package
# names match QQ / WeChat, so the real AccessibilityService and
# NotificationListener exercise cross-process routing rather than an in-process
# demo. They contain no Tencent code and need no account login.
QQ_FIXTURE="integration-fixture/build/outputs/apk/qq/debug/integration-fixture-qq-debug.apk"
WECHAT_FIXTURE="integration-fixture/build/outputs/apk/wechat/debug/integration-fixture-wechat-debug.apk"
adb install -r "$QQ_FIXTURE"
adb install -r "$WECHAT_FIXTURE"
adb shell pm grant com.tencent.mm android.permission.POST_NOTIFICATIONS || true

SERVICE="$PKG/com.jev.probe.capture.ChatCaptureService"
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener "$PKG/com.jev.probe.capture.WeChatNotificationListener" 0 || true
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb shell am start -W -n "$PKG/com.jev.probe.IntegrationSetupActivity" >/dev/null
sleep 2

mark_stage "11-qq-accessibility"
# QQ: real package routing + resource-id adapter + group speaker extraction.
adb logcat -c
adb shell am start -W -n "com.tencent.mobileqq/com.jev.fixture.FixtureActivity" >/dev/null
sleep 3
adb logcat -d -v brief -s JEVASSIST:D > integration-results/qq-accessibility.txt
grep -E 'snapshot\[com\.tencent\.mobileqq\].*n=3' integration-results/qq-accessibility.txt
adb exec-out screencap -p > screenshots/11-qq-cross-app.png

mark_stage "12-qq-fill"
# Exercise the real fillInput path against QQ's separate-process EditText.
adb shell am broadcast   -n "$PKG/com.jev.probe.IntegrationCommandReceiver"   -a "io.github.kitzhn.jevultimate.debug.FILL_FOR_TEST"   --es text "QQ跨进程填入成功" >/dev/null
sleep 2
python3 /tmp/ui_text.py wait "QQ跨进程填入成功"
adb exec-out screencap -p > screenshots/12-qq-fill.png

mark_stage "13-wechat-notification-ocr"
# WeChat: notification -> title gate -> Accessibility screenshot -> local ML Kit
# OCR. Message nodes are not consumed by an adapter in this path.
adb shell am start -W -n "$PKG/com.jev.probe.IntegrationSetupActivity" >/dev/null
adb logcat -c
adb shell am start -W -n "com.tencent.mm/com.jev.fixture.FixtureActivity" >/dev/null
sleep 6
adb logcat -d -v brief -s JEVASSIST:I > integration-results/wechat-ocr.txt
grep -E 'ocr\[com\.tencent\.mm\] msgs=[1-9]' integration-results/wechat-ocr.txt
adb exec-out screencap -p > screenshots/13-wechat-notification-ocr.png

mark_stage "14-wechat-fill"
# Fill is still user-triggered; verify the same cross-app input path works.
adb shell am broadcast   -n "$PKG/com.jev.probe.IntegrationCommandReceiver"   -a "io.github.kitzhn.jevultimate.debug.FILL_FOR_TEST"   --es text "微信跨进程填入成功" >/dev/null
sleep 2
python3 /tmp/ui_text.py wait "微信跨进程填入成功"
adb exec-out screencap -p > screenshots/14-wechat-fill.png

mark_stage "PASS"
printf '%s\n'   'QQ: package routing + accessibility adapter + 3 messages + fill PASS'   'WeChat: notification gate + accessibility screenshot + local OCR + fill PASS'   > integration-results/summary.txt
