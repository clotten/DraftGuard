# DraftGuard

[![test](https://github.com/clotten/DraftGuard/actions/workflows/test.yml/badge.svg)](https://github.com/clotten/DraftGuard/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![minSdk](https://img.shields.io/badge/minSdk-26-green.svg)](#requirements)

**Never lose a draft to an app crash again.**

DraftGuard is an Android app that continuously records the text you type into *any* app's input
field, stores it on your device in per-minute snapshots, and lets you recover it after a crash,
a swipe-away, or a page reload. One character at a time, no cloud, no account, no network code.

```
WeChat ─┐
QQ     ─┤                                 files/logs/2026-10-08/
Tavern ─┼──►  AccessibilityService  ──►     ├── com.tencent.mm.jsonl
Browser─┤     (350 ms debounce, fsync)       ├── com.draftguard.jsonl
Notes  ─┘                                   └── index.json
```

---

## Why

Writing a long message on a phone is fragile. The app gets killed in the background, the WebView
reloads, the browser discards the tab — and the paragraph you spent ten minutes on is gone,
because it only ever lived in a text field's memory.

DraftGuard's answer: **the text should hit disk while you type, not when you hit send.**

- **Every keystroke is captured.** Local-first, debounced at 350 ms, `fsync`'d on write.
- **Bucketed by minute.** Each app gets its own file per day, every version carries a `minute`
  field, so you can reconstruct exactly what was written when.
- **Snapshots, not keystroke deltas.** Every record stores the *complete* text of the field at
  that moment. A lost write means one fewer version — it can never scramble your text.
- **Composition state included.** Text not yet committed by the IME (pinyin candidates) is
  recorded too, flagged with `comp: true`.
- **Password fields are untouchable.** The system masks them; DraftGuard also checks
  `isPassword()` and skips them before reading anything into memory.
- **Messages are split by the real signal, not guesswork.** Tapping *send* / *search* / *publish*
  is detected from the accessibility click event, so each submitted message becomes its own entry
  instead of every consecutive message collapsing into the last one.
- **Only the field you are typing in** (input focus) is recorded — background fields, stale views
  and empty boxes showing placeholder text are ignored.
- **Placeholder text is never logged.** Handles both `getHintText()` and the OEMs that return the
  hint straight from `getText()` (Xiaomi Notes, Bilibili), plus a "first text in a field is the
  hint" fallback.
- **Deletions do not create records.** Type a typo, delete it, retype — the final text is what gets
  shown, while the raw versions stay on disk.
- **Keep-alive** via a foreground service with a persistent notification (can be turned off).
- **Clearing records backs up first**, to `Download/DraftGuard/backup/`.
- **A diagnostic probe** (`ProbeReceiver`) lets you read the app's internal state over `adb`.

### Pages

- **Records** — one line of status up top, a search box, three controls, then the records
  themselves. Each entry is a card with the app's icon.
- **Apps** — every app that has been recorded, with its icon, name and row count; tap to read
  that app's full history grouped by day.
- **Tools** — capture status, live preview, today's stats and the app list, plus collapsible
  **Settings** (booleans are real switches) and **Diagnostics** (all 41 internal counters,
  including the last 25 raw accessibility events).

## Privacy

| | |
|---|---|
| Permissions requested | **none** |
| Network access | **none** — there is no `INTERNET` permission in the manifest |
| Where data lives | app-private storage, unreadable by other apps |
| Password fields | masked by the system, actively skipped by the app |
| Uninstalling | removes all recorded data |

The only thing that leaves the app is a ZIP you export yourself through the system share sheet.

**Data survives updates** as long as builds are signed with the same key — records live in
app-private storage, not in the APK. Uninstalling, clearing app data, changing the signing key, or
a factory reset will lose them. See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) § Data safety.

## Requirements

- Android 8.0 (API 26) or newer
- Android 13+: **"Allow restricted settings"** must be enabled for sideloaded builds
  (Settings → Apps → DraftGuard → ⋮ → Allow restricted settings), otherwise the accessibility
  toggle stays greyed out.

## Install

```bash
adb install -r DraftGuard-2.27.1.apk
```

Or copy the APK to the phone and tap it. Then:

1. Open **DraftGuard**
2. Tap **去开启 / 检查服务** (Open accessibility settings)
3. Find **DraftGuard · 文本采集** in the accessibility list and turn it on
4. Type something in any app, come back — the live preview should show it immediately

> Installing via `adb` avoids the "restricted settings" flag that sideloaded installs get on
> Android 13+.

## Verified behaviour

A storage-layer test suite runs the real `LogStore` on a plain JVM — no device needed:

```bash
javac -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR;$LAMBDA_STUBS" -classpath "$ANDROID_JAR" -nowarn \
  -d build/test android/java/com/draftguard/LogStore.java \
    android/java/com/draftguard/Record.java \
    android/java/com/draftguard/Json.java test/StoreTest.java

java -cp "build/test;$ANDROID_JAR" com.draftguard.StoreTest
```

22 checks covering: write → read back → reopen, cross-app isolation, escape/unescape of
newlines and quotes, search hits and misses, result-count limits, the name index, minute
bucketing, and a regression case for a search bug that silently skipped entire files.

## Build

> This repository ships **source only** — no prebuilt APKs are committed or attached to releases.
> Build locally with the script below (no Gradle, no network).


No Gradle, no network, no AndroidX — just the SDK's own tools:

```powershell
powershell -ExecutionPolicy Bypass -File build-apk.ps1 -OutName 'DraftGuard-2.27.1.apk'
```

`aapt2 compile` → `aapt2 link` → `javac` → `d8` → repack → `zipalign` → `apksigner` → verify.

Override the SDK location if yours differs:

```powershell
.\build-apk.ps1 -Sdk "C:\Android\Sdk" -Bt "35.0.0" -Plat "android-36"
```

## Data safety

Records live in app-private storage, separate from the APK. **Updating to a newer build signed
with the same key preserves them**; uninstalling, clearing app data, or changing the signing key
does not. The in-app "clear all records" action backs up to `Download/DraftGuard/backup/` first.

## Data format

```
files/logs/
└── 2026-10-08/
    ├── com.tencent.mm.jsonl        one file per app per day
    ├── com.draftguard.jsonl
    └── index.json                  package name → human-readable label
```

One JSON object per line, one line per version:

```json
{"ts":"2026-10-08T15:36:34.418","ms":1791444994418,"day":"2026-10-08","minute":"15:36",
 "bucket":"2026-10-08 15:36","app":"com.tencent.mm","ev":"text","chars":9,"delta":1,
 "comp":false,"text":"这是一段示例文本"}
```

| field | meaning |
|---|---|
| `ts` / `ms` | millisecond-accurate timestamp |
| `minute` / `bucket` | the minute this version belongs to |
| `chars` / `delta` | length, and change from the previous version |
| `comp` | `true` = IME text not yet committed (pinyin candidate stage) |
| `text` | **the complete field contents** at that moment |

## Documentation

- [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) — real bugs hit during development,
  each as symptom → root cause → fix, plus the Android/MIUI pitfalls behind them
- [`docs/LESSONS.md`](docs/LESSONS.md) — one-page digest of the lessons worth remembering
- [`docs/UI.md`](docs/UI.md) — interface design and the UI pitfalls hit along the way
- [docs/KASPERSKY-EXCLUSIONS.md](docs/KASPERSKY-EXCLUSIONS.md) — why an AV flags this project's tooling, and the exact exclusions to add
- [`docs/SEGMENTATION.md`](docs/SEGMENTATION.md) — the complete segmentation rules: thresholds, decision order, and the user report behind each one — why accessibility, why snapshots, why per-minute
- [`docs/RETRO.md`](docs/RETRO.md) — a development retrospective: what worked, what repeatedly went wrong, and where the time actually went
- [`docs/REMOTE-DEBUG.md`](docs/REMOTE-DEBUG.md) — connecting over ADB wireless debugging, plus the in-app diagnostic probe that makes remote triage possible
- [`PROJECT_STATE.md`](PROJECT_STATE.md) — current status, verified list, known issues, roadmap

## Known limitations

- **Password / secure-keyboard fields** — masked by the OS. Nothing can read them; this is by design.
- **The IME's own candidate bar** — that's the keyboard's text, not your input field's. Skipped.
- **Some vendor ROMs** (MIUI/HyperOS in particular) block third-party accessibility services from
  reading other apps until you grant autostart, unrestricted battery, and "allow restricted
  settings". See the troubleshooting doc.
- **The app's own search box** is recorded on purpose, so you can self-test without leaving the app.

## Features

- **Focus-only recording** (default on). Accessibility fires text-change events for *every*
  editable field on screen — background fields, stale views re-reported after a redraw, and empty
  boxes showing nothing but placeholder text. DraftGuard records only the field that actually has
  input focus: the one you're typing into.
- **Clear all records** in one tap, with the record count shown before you confirm.
- **Keep-alive** (optional, on by default). A foreground service with a persistent notification
  keeps the process from being reclaimed under memory pressure — an accessibility service alone
  is not guaranteed to stay resident. Turn it off in Settings if you would rather not have the
  notification.
- Per-app files, minute buckets, full snapshots, IME composition state, deletion filtering,
  placeholder/noise filtering, full-text search, and ZIP export.

## Roadmap

- [ ] Tune the focus filter on more devices (a fallback path covers ROMs where `isFocused()` lies)
- [ ] Foreground service + boot receiver for longer survival
- [ ] Verify ZIP export on-device
- [ ] Optional IME-based capture mode, for devices where accessibility is locked down entirely

## License

[MIT](LICENSE)
