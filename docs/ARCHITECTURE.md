# Architecture

## Why an accessibility service

DraftGuard needs to read text out of *other apps'* input fields. On Android there is exactly one
supported channel for that: an `AccessibilityService` with `canRetrieveWindowContent`.

Things that do **not** work, and why:

| Approach | Why it fails |
|---|---|
| Reading the clipboard | Android 10+ blocks background clipboard reads; the user would have to copy manually |
| A custom keyboard (IME) | Only sees what it types itself; requires switching keyboards, and can't observe text pasted or edited elsewhere |
| `ContentObserver` / files | Input fields are not files |
| Hooking or root | Out of scope for an installable app |

An accessibility service is also where the OS enforces the password boundary: password fields are
masked before the service can see them. DraftGuard additionally checks `isPassword()` and returns
before reading anything, so masked content never enters app memory.

**Cost:** the user must enable the service manually, and some vendor ROMs add extra hoops
(see `TROUBLESHOOTING.md` §6). That's the price of the only supported route.

## Capture pipeline

```
AccessibilityEvent(TYPE_VIEW_TEXT_CHANGED)
        │
        ├─ filter: system UI · IME window · user-ignored package
        ├─ filter: isPassword()  → skip, never read
        ├─ resolve node: event source → focused editable → tree walk
        ├─ read text: node.getText() → child nodes → event text
        └─ de-duplicate & debounce (350 ms per field)
                │
                └─ HandlerThread("draftguard-io")
                        └─ LogStore.append()  →  <day>/<package>.jsonl  (fsync)
```

A 900 ms polling pass over the active window runs alongside the event path, for apps that emit
few or no text-change events. Because storage decides whether a given version is new by comparing
content, polling cannot create duplicates — it only closes gaps.

All disk I/O happens on a dedicated `HandlerThread`. The main thread does cheap checks only, so
typing never stutters.

## Why snapshots instead of keystroke deltas

Every record stores the **complete text of the field**, not the characters that changed.

- A dropped or out-of-order write costs one version — it can never corrupt the reconstruction.
- Recovery is trivial: the newest version *is* the answer. No replay, no merge.
- Compression is unnecessary: chat-sized text is a few KB, and identical content is not rewritten.

The trade-off is write volume. It's mitigated by de-duplication (identical content in the same
minute is not re-written) and by the fact that only the last version of a burst is persisted.

## Why per-minute, per-app files

```
files/logs/2026-10-08/com.tencent.mm.jsonl
```

- One file per app keeps a noisy app from burying a quiet one (this was learned the hard way —
  see `TROUBLESHOOTING.md` §2).
- `minute` and `bucket` live on each record rather than in the filename, so a minute's versions
  can be grouped without parsing paths, and files don't fragment into 1,440 tiny files per day.
- A day's directory is the natural unit for retention cleanup.

## Directory layout

```
android/
├── AndroidManifest.xml                     no permissions declared
├── java/com/draftguard/
│   ├── TypelogService.java                 capture: events, debounce, fallbacks, polling
│   ├── LogStore.java                       storage: files, minute buckets, index, search
│   ├── Json.java                           minimal JSON writer/parser (replaces org.json)
│   ├── MainActivity.java                   UI: status, preview, search, diagnostics, settings
│   ├── Prefs.java                          settings
│   ├── ImeFilter.java                      identifies input-method packages via the system API
│   ├── LogFileProvider.java                content provider for ZIP export
│   └── Record.java                         one record
├── res/xml/accessibility_service_config.xml  event scope: text changed + window state
└── res/values/strings.xml
test/StoreTest.java                         off-device storage tests (22 checks)
docs/                                       troubleshooting + this file
build-apk.ps1                               no-Gradle build
```

### Why no AndroidX, and why layout is built in code

Everything uses framework APIs only, and the UI is assembled programmatically (`MainActivity`)
except for a trivial `ScrollView` layout. Consequences:

- The build needs nothing but the SDK's own tools — no Gradle, no network, no dependency
  resolution, and an APK under 50 KB.
- The whole storage layer can be compiled and tested on a plain JVM, which is how most of the bugs
  in `TROUBLESHOOTING.md` were found.

`Json.java` exists for the same reason: `android.jar` ships stubs for `org.json`, so any code
touching it becomes untestable off-device.

## Focus-only recording

Accessibility is generous: it fires `TYPE_VIEW_TEXT_CHANGED` for every editable field in the
window, re-reports text after redraws and resumes, and on some OEM implementations even reports
the *hint* as the field's text. Recording all of it produces exactly the wrong log — repeated
placeholder lines and stale copies of fields you aren't touching.

So the capture path gates on focus:

```java
node.isFocused()                                 // direct signal
  || root.findFocus(FOCUS_INPUT).equals(node)    // fallback for ROMs that lie
```

The fallback matters: `isFocused()` is unreliable on some devices, and a strict check would
silently record nothing at all — the same failure mode described in `TROUBLESHOOTING.md` §1.
The polling pass is narrowed the same way: focused node only, never any editable node it can find.

The gate is a preference (`Prefs.focusOnly`, default on), because a device that misreports focus
would otherwise stop capturing entirely.

## Package name

The package is `com.draftguard`, matching the product. It changed from an earlier
`com.typelog.recorder`, so builds are **not** in-place upgrades of that earlier package — Android
identifies apps by package name. Uninstall the old one first.

## Privacy posture

- No `INTERNET` permission — the capability does not exist, rather than merely being unused.
- Data lives in app-private storage; nothing else can read it without root.
- Password fields are actively skipped.
- The only egress is a ZIP the user exports through the system share sheet.
