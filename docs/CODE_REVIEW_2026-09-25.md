# Jev Ultimate 2.4 — Code Review

Date: 2026-09-25  
Scope: `app/src/main`, `app/src/debug`, `integration-fixture`, Android manifests/resources, Gradle files, and GitHub Actions workflows.

## Review labels

- **CRITICAL** — data loss, credential exposure, or reliably unsafe behavior.
- **MAJOR** — core correctness / data-model / concurrency / accounting issue. **Requires owner approval before implementation.**
- **MEDIUM** — localized correctness or maintainability issue; safe to fix without architecture changes.
- **MINOR** — cleanup, stale text, CI efficiency, or UI clarity.
- **DEAD-CODE** — no longer on the runtime path but should be removed or quarantined.
- **TEST-GAP** — behavior cannot yet be proven by the current CI environment.
- **OK** — explicitly reviewed and currently acceptable.

---

## Executive status

| ID | Level | Status | Area | Finding |
|---|---|---|---|---|
| MAJOR-01 | MAJOR | **FIXED 2026-09-25** | Async analysis | Async judgment/reply/context deliveries are generation-scoped; conversation/window changes invalidate older callbacks. |
| MAJOR-02 | MAJOR | **FIXED 2026-09-25** | API usage | Detail remains capped at 5000; evicted rows now roll into daily aggregates so current-day/month totals are retained. |
| MAJOR-03 | MAJOR | **FIXED 2026-09-25** | Vision accounting | Vision prompt tokens are now marked unknown when provider usage is missing; Base64 length is never treated as billed image tokens. |
| MAJOR-04 | MAJOR | **FIXED 2026-09-25** | Test architecture | `integration-fixture` is excluded from normal sync and included only when CI sets `JEV_INTEGRATION_FIXTURES=1`. |
| MAJOR-05 | MAJOR / DEAD-CODE | **FIXED 2026-09-25** | Accessibility legacy | Obsolete `SelectToSpeakService` and `config_disguised.xml` have been removed. |
| MAJOR-06 | MAJOR | **FIXED 2026-09-25** | Usage privacy | Persisted base URLs now strip userinfo, query and fragment; legacy records are sanitized during the v2 usage-state migration. |
| MAJOR-07 | MAJOR / SECURITY | **FIXED 2026-09-25** | API key routing | Key inheritance is normalized-host aware; cross-provider routes require an explicit key. |
| MAJOR-08 | MAJOR / SECURITY | **FIXED 2026-09-25** | Transport | Remote API endpoints require HTTPS; plain HTTP is accepted only for loopback development. |
| MAJOR-09 | MAJOR | **FIXED 2026-09-25** | DeepSeek behavior/cost | Official `deepseek-flash` reply/vision calls now send an explicit thinking mode; default is disabled with an advanced user toggle. |
| MAJOR-10 | MAJOR / SECURITY | **FIXED 2026-09-25** | Debug distribution | CI integration controls are non-exported in ordinary Debug builds and exported only when the CI fixture environment flag is set. |
| MEDIUM-01 | MEDIUM | **FIXED** | Reply parsing | Incomplete/malformed candidate output now surfaces a retryable generation error instead of duplicate filler cards. |
| MEDIUM-02 | MEDIUM | FIXED | API usage | A request was marked “exact” if only one of prompt/completion counts was exact. Now both are required. |
| MEDIUM-03 | MEDIUM | FIXED | Jev retry | Knowledge-context fallback retried all 4xx, including 401/403/429. Now only schema-like 400/422 are retried. |
| MEDIUM-04 | MEDIUM | FIXED | Log privacy | Conversation titles and remote API error snippets are no longer written to logcat; logs keep only non-content metadata/status. |
| MEDIUM-05 | MEDIUM | FIXED | OpenRouter metering | Reply/vision OpenRouter requests now explicitly request `usage.include=true` so token/cost data can use server usage instead of local estimates. |
| MINOR-01 | MINOR | FIXED | Release CI | Release workflow previously ran root `assembleRelease`; now it scopes to `:app:assembleRelease`. |
| MINOR-02 | MINOR | FIXED | API dashboard | Unknown pricing could visually look like ¥0.00. Fully unknown rows now display “价格未知”. |
| MINOR-03 | MINOR | FIXED | Comments | “3 candidates” and obsolete “DeepSeek has no vision” comments were corrected. |
| MINOR-04 | MINOR | FIXED | WeChat capture | Removed unused `lastWechatNotificationAt` field. |
| MINOR-05 | MINOR / DEAD-CODE | OPEN | Debug naming | `V23FeatureDemoActivity` and one script comment still use the v2.3 name although current app is v2.4. No runtime effect. |
| MINOR-06 | MINOR | OPEN | Usage I/O | API usage rewrites/parses the full JSON file on every request. Fine for light use, but inefficient near the record cap. |
| MINOR-07 | MINOR / DUPLICATE-TEST | OPEN | Debug tests | `AdaptiveModelDemoActivity` and `V23FeatureDemoActivity` substantially overlap; the latter writes demo contacts without cleanup. |
| TEST-01 | TEST-GAP | IN PROGRESS | QQ | CI now uses a separate APK with runtime package `com.tencent.mobileqq` to test package routing, resource-id capture, group messages, and cross-process input fill. |
| TEST-02 | TEST-GAP | IN PROGRESS | WeChat | CI now uses a separate APK with runtime package `com.tencent.mm` to test notification gate → Accessibility screenshot → local OCR → input fill. This is not a substitute for a real Tencent ARM64 client. |
| TEST-03 | TEST-GAP | OPEN | Real devices | Real QQ / WeChat compatibility still needs ARM64 device regression because CI emulator is x86_64. |
| TEST-04 | TEST-GAP | OPEN | Network API | CI does not currently exercise judge/reply/vision against live paid providers; API parsing has local code coverage only. |
| OK-01 | OK | VERIFIED | Group history | Group history is filtered to `me` + current `speakerName` before writing to a person's history, preventing other members from contaminating that contact. |
| OK-02 | OK | VERIFIED | Sending | Fill path only sets/pastes text. It never invokes the send button. |
| OK-03 | OK | VERIFIED | Notification privacy | WeChat notification listener uses conversation title as a trigger hint and does not persist the notification body. |
| OK-04 | OK | VERIFIED | API keys | Keys remain in app-private SharedPreferences and are not written to usage logs or normal logs. |
| OK-05 | OK | VERIFIED CURRENT | DeepSeek model | Official API model is `deepseek-flash` (V4.1 Flash); OpenRouter slug is `deepseek/deepseek-v4.1-flash`; both support image input as of 2026-09-25. |
| OK-06 | OK | VERIFIED CURRENT | DeepSeek pricing | Current hard-coded official peak/off-peak token prices match DeepSeek's published V4.1 Flash rates as checked on 2026-09-25. |

---

## MAJOR findings — implementation record

### MAJOR-01 — stale async result can cross conversation boundaries — FIXED

**Resolution (approved 2026-09-25):** each analysis run captures a monotonically increasing generation token. Conversation/window/OCR snapshot changes invalidate the current generation and clear `analyzing`; every context, judgment and reply delivery verifies the token before touching the overlay. Judgment failure no longer prematurely opens an overlap window while reply work is still in flight.

**Files:**  
- `app/src/main/java/com/jev/probe/capture/ChatCaptureService.kt`

`runAnalysis()` launches the judgment task and the reply+rank task concurrently. The service tracks only one Boolean (`analyzing`) and does not attach a generation/session identifier to the snapshot.

Risk sequence:

1. Conversation A starts analysis.
2. User switches to Conversation B before A's network requests finish.
3. UI is reset for B.
4. A's old callback returns later and can still call `showJudgment` / `showReplies`.
5. If A's judgment fails first, it also sets `analyzing=false` while A's reply task may still be running, allowing another analysis to overlap.

**Recommended fix:** introduce monotonically increasing `analysisGeneration` / conversation signature token. Every async branch captures it and checks it again on main-thread delivery. Only the current generation may update overlay state or clear `analyzing`.

**Approval:** granted 2026-09-25; implemented.

### MAJOR-02 — monthly usage can silently undercount — FIXED

**Resolution (approved 2026-09-25):** usage storage is migrated to an atomic v2 state containing the latest 5000 detail rows plus daily rollups for evicted rows (400-day retention). Today/current-month totals and breakdowns combine detail + rollups.

**File:**  
- `app/src/main/java/com/jev/probe/core/usage/ApiUsageStore.kt`

Current detail storage is capped at 5000 records and older records are deleted. One complete analysis typically creates three API records. At 100 analyses/day, a 30-day month is roughly 9000 records.

The dashboard still labels the remaining sum as “本月”, so old requests can disappear without warning.

**Recommended fix:** keep a bounded recent-detail log plus persistent daily/monthly aggregates. Removing detail must never remove aggregate totals.

**Approval required:** yes. Storage schema / migration change.

### MAJOR-03 — vision fallback cost estimate can be misleading — FIXED

**Resolution (approved 2026-09-25):** when a vision response lacks prompt token usage, image input tokens are stored as unknown rather than estimated from serialized Base64. Legacy non-exact vision rows are migrated to unknown-token semantics.

**File:**  
- `app/src/main/java/com/jev/probe/core/usage/ApiUsageStore.kt`

When an API response lacks `usage`, fallback input tokens are estimated from the serialized request body. A vision request can contain a large Base64 image string; Base64 character length is not a reliable proxy for billed visual tokens.

**Recommended fix options:**
1. Safest: for vision calls without provider usage, store token/cost as unknown rather than estimate.
2. Provider-specific image token estimator, only where the provider publishes a stable formula.

**Approval required:** yes. Changes dashboard accounting semantics.

### MAJOR-04 — integration fixture is part of the normal root project — FIXED

**Resolution (approved 2026-09-25):** `integration-fixture` is included only when `JEV_INTEGRATION_FIXTURES=1`; the emulator workflow sets this CI environment flag.

**Files:**  
- `settings.gradle.kts`
- `integration-fixture/**`

The test module intentionally builds APKs whose package names are `com.tencent.mobileqq` and `com.tencent.mm`. Production build/release workflows are now explicitly scoped to `:app`, so they are not uploaded, but the fixture remains part of normal IDE/Gradle project sync.

**Recommended fix:** include the fixture only when an environment/property flag is enabled by the emulator workflow, e.g. `JEV_INTEGRATION_FIXTURES=1`.

**Approval required:** yes. Build-structure change.

### MAJOR-05 — legacy accessibility bypass artifacts remain as dead code — FIXED

**Resolution (approved 2026-09-25):** both obsolete artifacts were deleted; production continues to use the normal `ChatCaptureService` and current notification/OCR path.

**Files:**  
- `app/src/main/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt`
- `app/src/main/res/xml/config_disguised.xml`

The production Manifest now registers the normal `ChatCaptureService`, and WeChat uses the notification + screenshot/OCR path. These legacy artifacts are no longer needed at runtime, but their source comments explicitly describe an old node-obfuscation bypass approach.

**Recommended fix:** delete both legacy files after confirming no historical build variant references them.

**Approval:** granted 2026-09-25; implemented.

### MAJOR-06 — raw custom base URL can persist embedded credentials — FIXED

**Resolution (approved 2026-09-25):** usage persistence sanitizes URLs to scheme + host + port + path, stripping userinfo/query/fragment. Existing rows are scrubbed when the usage state migrates.

**File:**  
- `app/src/main/java/com/jev/probe/core/usage/ApiUsageStore.kt`

Normal API keys are sent in the Authorization header and are not written to usage data. However, the usage record currently stores the supplied `baseUrl` verbatim.

A custom endpoint such as:

```
https://example.invalid/v1?key=SECRET
```

would therefore persist the query string in `files/usage/api_usage.json`. The same concern applies to URL userinfo.

**Recommended fix:** sanitize the URL before persistence: remove userinfo, query and fragment, retaining only scheme + host + port + non-sensitive path/provider identity.

**Approval required:** yes. This changes persisted audit data and the privacy contract.

### MAJOR-07 — cross-provider key fallback can disclose credentials — FIXED

**Resolution (approved 2026-09-25):** reply/vision key inheritance now requires the source and destination endpoints to share the same normalized scheme/host/port. Cross-provider routing with a blank route key stops locally with a configuration error instead of forwarding another provider's bearer token.

**Files:**  
- `app/src/main/java/com/jev/probe/core/Prefs.kt`
- `app/src/main/java/com/jev/probe/SettingsActivity.kt`

Current helpers:

```
effectiveReplyKey() = replyKey.ifBlank { judgeKey }
effectiveVisionKey() = visionKey.ifBlank { effectiveReplyKey() }
```

This is convenient when all routes use one OpenRouter key, but unsafe when hosts differ.

Example:
1. Judge provider = OpenRouter and `judgeKey` contains an OpenRouter credential.
2. User switches reply/vision base to DeepSeek official.
3. Reply/vision key is still blank.
4. The request can send the OpenRouter bearer token to `api.deepseek.com`.

The same class of issue exists for arbitrary custom hosts.

**Recommended fix:** key inheritance must be host/provider-aware. Only inherit a key when the source and destination belong to the same normalized provider/host. Otherwise require an explicit route key and block the request with a clear configuration error.

**Approval:** granted 2026-09-25; implemented.

### MAJOR-08 — custom non-HTTPS endpoint can transmit secrets and chat text in plaintext — FIXED

**Resolution (approved 2026-09-25):** `HttpJson` rejects non-HTTPS remote endpoints before opening a connection. `http://localhost`, `127.0.0.1` and loopback IPv6 remain available for local development only.

**Files:**  
- `app/src/main/java/com/jev/probe/jev/HttpJson.kt`
- `app/src/main/java/com/jev/probe/SettingsActivity.kt`

`HttpJson.post()` accepts any URL supported by `java.net.URL`, including `http://`. A custom endpoint therefore can receive:
- bearer API key;
- current conversation text;
- contact context/history;
- optional image data;

without transport encryption.

**Recommended fix:** require HTTPS for non-local endpoints. Optionally allow `http://127.0.0.1`, `http://localhost`, or private LAN endpoints only behind an explicit advanced warning/opt-in.

**Approval:** granted 2026-09-25; implemented.

### MAJOR-09 — DeepSeek Flash defaults to high-effort thinking for simple reply/OCR calls — FIXED

**Resolution (approved 2026-09-25):** official `api.deepseek.com` + `deepseek-flash` calls now explicitly send `thinking.type`; default is `disabled` for reply/vision/OCR and an advanced shared toggle can enable it. When enabled, Jev omits `temperature` because DeepSeek ignores it in thinking mode.

**Files:**  
- `app/src/main/java/com/jev/probe/jev/ReplyClient.kt`
- `app/src/main/java/com/jev/probe/jev/VisionClient.kt`

DeepSeek's current official API documents `deepseek-flash` as thinking-enabled by default, with default reasoning effort high. In thinking mode, `temperature` is ignored.

Current Jev reply generation sends `temperature=0.85` expecting varied GalGame candidates, but on DeepSeek official this parameter does not control sampling while default thinking remains enabled. Short reply generation and OCR also pay for unnecessary reasoning tokens/latency.

**Recommended fix:** make thinking mode explicit and configurable. Suggested default for Jev's reply drafting / summarization / OCR is non-thinking (`thinking.type=disabled`), with an advanced option to enable reasoning for users who prefer it. Do not assume the same wire parameter for every OpenAI-compatible provider.

**Approval required:** yes. This changes model behavior, cost and potentially reply quality.

### MAJOR-10 — exported CI controls are unsafe in distributed Debug APKs — FIXED

**Resolution (approved 2026-09-25):** the two CI command components use a manifest placeholder that defaults to `exported=false`; the Android emulator workflow sets `JEV_INTEGRATION_FIXTURES=1`, which makes them exportable only in that CI build. Ordinary downloadable Debug and Release builds do not expose these controls.

**Files:**  
- `app/src/debug/AndroidManifest.xml`
- `app/src/debug/java/com/jev/probe/IntegrationCommandReceiver.kt`
- `app/src/debug/java/com/jev/probe/IntegrationSetupActivity.kt`

The emulator needs externally launchable controls, so the Debug manifest exports them. However the current downloadable artifact is also a Debug APK. Any other app on the same phone can therefore invoke the exported broadcast receiver and ask Jev to exercise its fill path, or invoke the setup activity that alters test settings.

**Recommended fix:** separate CI instrumentation from the user-facing Debug build. Preferred design is an `integrationTest` product flavor/build type (or a dedicated test APK) that alone contains/export these controls. The ordinary Debug APK and Release APK should not contain them.

**Approval:** granted 2026-09-25; implemented with CI-only manifest export gating.

---

## MEDIUM / MINOR open items

### MEDIUM-01 — duplicate filler replies on malformed generation — FIXED

**File:** `ReplyClient.kt`

If the model returns fewer than four parseable items, the code pads multiple strategies with the same filler text. A user can then see several strategy labels over identical text.

Recommended low-risk fix: return only valid items and show a partial-generation warning, or use deterministic strategy-specific safe fallbacks.

### MINOR-05 — stale v2.3 debug naming

Debug-only file/class names and one script comment still say v2.3. This is not a runtime defect, but it makes reviews harder.

### MINOR-06 — usage log O(n) rewrite

Every API usage record parses and rewrites the whole JSON array. With light personal use this is acceptable, but it scales poorly toward the 5000-record cap. This can be solved together with MAJOR-02.

### MINOR-07 — overlapping debug integration activities

`AdaptiveModelDemoActivity` and `V23FeatureDemoActivity` both exercise group-person context, scene detection, strategy learning, and WeChat notification integration. `V23FeatureDemoActivity` also writes demo contacts to the Debug knowledge store without deleting them.

Recommended cleanup after the current regression stabilizes: keep one canonical integration page and remove/merge the other. No Release runtime impact.

---

## CI / build review

### Current intended workflow split

- **Build Android Debug APK** — ARM64, `:app:assembleDebug` only.
- **Android 15 Emulator Regression** — x86_64 app + QQ/WeChat integration fixtures.
- **Build Signed Ultimate Release** — manual only, signed ARM64 `:app:assembleRelease`.

Older cancelled runs after rapid commits are expected because each workflow uses `cancel-in-progress: true`. A cancelled superseded run is not treated as a product failure.

### Current known fixed build failures

1. Removed resource-link failure caused by legacy `config_disguised.xml` pointing to renamed `a11y_desc_disguised`.
2. Enabled app `BuildConfig` generation for the guarded debug integration hook.
3. Debug-only activities are exported only in `src/debug/AndroidManifest.xml`; production activities remain non-exported.

---

## External model verification (2026-09-25)

Verified against current DeepSeek / OpenRouter docs:

- DeepSeek official: `deepseek-flash` = DeepSeek V4.1 Flash.
- Native image input is supported.
- OpenRouter: `deepseek/deepseek-v4.1-flash`.
- DeepSeek official pricing currently matches the peak/off-peak constants used by `ApiUsageStore`.

These are time-sensitive and should be rechecked when updating model presets or pricing.

---

## Owner approval checklist

No MAJOR item below should be implemented until explicitly approved. Checked items were explicitly approved by the owner on 2026-09-25 and implemented on the review branch:

- [ ] MAJOR-01 — add analysis generation/session guard.
- [x] MAJOR-02 — redesign usage storage to retain monthly aggregates.
- [x] MAJOR-03 — change vision missing-usage accounting semantics.
- [x] MAJOR-04 — conditionally include the integration fixture module.
- [ ] MAJOR-05 — delete legacy disguised accessibility artifacts.
- [x] MAJOR-06 — sanitize persisted API base URLs.
- [ ] MAJOR-07 — make API key inheritance provider/host-aware.
- [ ] MAJOR-08 — require HTTPS or explicit local-network opt-in for custom endpoints.
- [x] MAJOR-09 — make DeepSeek thinking mode explicit/configurable for reply and vision calls.
- [ ] MAJOR-10 — move exported CI controls out of the distributable Debug APK.

