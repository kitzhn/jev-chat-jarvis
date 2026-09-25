#!/usr/bin/env bash
set -euo pipefail

PKG="io.github.kitzhn.jevultimate.debug"
APK="app/build/outputs/apk/debug/app-debug.apk"

mkdir -p screenshots demo/relations demo/logs

adb install -r "$APK"

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
PY

adb shell "run-as $PKG mkdir -p files/kb/logs files/kb/relations"
adb push demo/contacts.json /data/local/tmp/contacts.json >/dev/null
adb push demo/contact_relations.json /data/local/tmp/contact_relations.json >/dev/null
adb push demo/relations/demo-a.json /data/local/tmp/demo-a-relations.json >/dev/null
adb push demo/logs/demo-a.json /data/local/tmp/demo-a-log.json >/dev/null
adb shell "run-as $PKG cp /data/local/tmp/contacts.json files/kb/contacts.json"
adb shell "run-as $PKG cp /data/local/tmp/contact_relations.json files/kb/contact_relations.json"
adb shell "run-as $PKG cp /data/local/tmp/demo-a-relations.json files/kb/relations/demo-a.json"
adb shell "run-as $PKG cp /data/local/tmp/demo-a-log.json files/kb/logs/demo-a.json"

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
    if mode == "wait":
        time.sleep(.5)
    else:
        subprocess.run(["adb","shell","input","swipe","540","1800","540","650","350"])
        time.sleep(.6)
raise SystemExit(f"{mode} text not found: {target}")
PY

adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/com.jev.probe.MainActivity" >/dev/null
sleep 2
adb exec-out screencap -p > screenshots/01-home.png

adb shell am start -n "$PKG/com.jev.probe.SettingsActivity" >/dev/null
python3 /tmp/ui_text.py wait "设置"
adb exec-out screencap -p > screenshots/02-settings.png

adb shell am start -n "$PKG/com.jev.probe.KnowledgeActivity" >/dev/null
python3 /tmp/ui_text.py wait "知识库与联系人"
python3 /tmp/ui_text.py exact "联系人"
adb exec-out screencap -p > screenshots/03-contacts.png

python3 /tmp/ui_text.py exact "关系网"
sleep 1
adb exec-out screencap -p > screenshots/04-relationship-graph.png

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


# Relationship-aware GalGame cards are already captured in 05/06. Now verify
# the pure WeChat OCR geometry stage with deterministic OCR boxes.
adb shell am start -W -n "$PKG/com.jev.probe.WeChatOcrDemoActivity" >/dev/null
sleep 1
python3 /tmp/ui_text.py wait "PASS · other → me → other"
adb exec-out screencap -p > screenshots/07-wechat-ocr-geometry.png

# Show the opt-in WeChat auto-OCR setting in the real SettingsActivity.
adb shell am start -n "$PKG/com.jev.probe.SettingsActivity" >/dev/null
sleep 1
python3 /tmp/ui_text.py contains "微信优先用新消息通知触发 OCR"
adb exec-out screencap -p > screenshots/08-wechat-auto-ocr-setting.png

# Grant notification-listener access to the real production service declaration,
# then run a debug-only integration page that exercises the actual group context,
# scene detector, learned strategy counts and notification matching gate.
adb shell cmd notification allow_listener "$PKG/com.jev.probe.capture.WeChatNotificationListener" 0 || true
sleep 1
adb shell am start -W -n "$PKG/com.jev.probe.AdaptiveModelDemoActivity" >/dev/null
sleep 1
python3 /tmp/ui_text.py wait "ALL CORE CHECKS PASS"
adb exec-out screencap -p > screenshots/09-adaptive-core.png

# Final v2.3 adaptive regression on current main.
