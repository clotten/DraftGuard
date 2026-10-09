# Troubleshooting

Every entry below is a bug that actually happened while building DraftGuard, written as
**symptom → root cause → fix**. They're kept because the reasoning is more useful than the
patches.

---

## 1. Nothing at all was recorded, in any app

**Symptom.** Typing in WeChat produced zero records. The app's live preview stayed empty.

**Root cause.** The service only processed a text-change event when the event carried its own
node *and* that node reported `isEditable()`:

```java
node = event.getSource();
if (node == null || !node.isEditable()) return;   // gives up too early
```

On Android 11+ `AccessibilityEvent.getSource()` frequently returns `null`. When it does, the
event was dropped silently — no log, no diagnostic, no hint on screen.

**Fix.** Three fallbacks, tried in order:

1. The node attached to the event (fast path)
2. `getRootInActiveWindow().findFocus(FOCUS_INPUT)` — the focused editable node
3. A depth-limited tree walk for the first visible editable node

Plus a 900 ms polling pass over the active window, because some apps barely emit
`TYPE_VIEW_TEXT_CHANGED` at all. Polling produces no duplicate records since storage de-duplicates
by content.

---

## 2. An app's records were written but could not be searched or listed

**Symptom.** The live preview showed text typed in WeChat, and the day's total row count
increased — but WeChat never appeared in the per-app list, and searching for the exact text
returned nothing.

**Root cause.** The search limit was used as a *per-file* cap while being written as a
*total* cap:

```java
for (File f : files) {
    readFile(f, query, out, limit);
    if (out.size() >= limit) return;   // abandons every remaining file
}
```

The app's own file had accumulated ~190 rows of noise and sorted first. It filled the 60-result
budget on its own, the loop returned, and the WeChat file was never opened.

**Fix.** Treat `limit` as an increment across files: each file only contributes the remaining
budget, and the loop stops only when the budget is globally exhausted.

**Regression test.** `StoreTest` writes 150 noise rows to file A and one WeChat record to file B,
then searches with a limit of 60. The old code could not find file B; it now must.

> Worth noting: the first attempt at this fix deleted the limit check entirely and returned 150
> results. The test caught that too. A regression test that pins down *both* the bug and the
> over-correction is worth the ten lines.

---

## 3. App labels never appeared, and the index vanished on restart

**Symptom.** The per-app list showed `com.tencent.mm` instead of 微信, and after restarting the
app the name index was empty.

**Root causes.** Three separate mistakes stacked:

1. `putAppLabel()` wrote to the file but never updated the in-memory cache, so the next read
   returned the stale value.
2. A first-write placeholder stored an *empty* label (`indexPut(app, "")`), which then blocked
   the real label because "the key already exists".
3. The index was written via `temp file → renameTo(target)`, and the `renameTo` result was
   ignored inside an empty `catch`. On some devices the rename fails; the failure was invisible.

**Fix.** Update the cache on write, never persist empty labels, and write the index directly
(truncate + write + `fsync`) with errors surfaced in the UI instead of swallowed.

---

## 4. Deletions were recorded; the app's own input box was not

**Symptom.** Every backspace created a new record. Meanwhile, typing in DraftGuard's own search
box recorded nothing.

**Root cause.** Two design decisions that turned out wrong:

- Every text change was persisted, so shortening the text counted as a version.
- The app's own package name was in the skip list (to keep diagnostics out of the log), which
  also excluded the user's own typing.

**Fix.** Treat "text got shorter" as a deletion and skip it — with two edge cases handled:
reverse-append IMEs can emit same-length-different-content updates (not a deletion), and a
leading-character mismatch with a length jump is insertion, not deletion. The skip list no longer
contains the app itself; only system UI and the IME's own window events are filtered.

Deleting text does **not** remove the last stored version — if the app crashes right after a
delete, the pre-delete text is still on disk.

---

## 5. Placeholder text and single-character debris filled the log

**Symptom.** Rows like `搜索记录过的文字…` (the search box's hint) and hundreds of one-character
entries appeared as if they were user input.

**Root cause.** When a field is empty, some OEM accessibility implementations return the *hint*
from `getText()` instead of an empty string. And short flickers — type one character, delete it —
still counted as activity.

**Fix.** If `getText()` is empty but `getHintText()` is not, treat the field as empty. Known
placeholder prefixes are filtered, and a configurable minimum length (default 2 chars) applies to
freshly-seen fields.

---

## 6. System-level: MIUI/HyperOS delivered no events from other apps

**Symptom.** Diagnostics showed plenty of events — but all from the input method and the app
itself. `com.tencent.mm` never appeared. Later, after permissions were granted, WeChat events
started arriving normally.

**Root cause.** Not a code bug. MIUI blocks third-party accessibility services from reading other
apps until the user grants extra permissions. The tell-tale sign: **events arrive from the IME
window but never from any ordinary app.**

**Fix (user action).**

1. Settings → Apps → DraftGuard → ⋮ → **Allow restricted settings** (Android 13+)
2. Settings → Apps → DraftGuard → **Autostart**: on; **Battery saver**: unrestricted
3. Re-check the accessibility toggle, then reboot the phone — MIUI sometimes applies the grant
   only after a restart

The in-app **diagnostic panel** distinguishes these cases directly: it lists every package that
has sent events, and reports the WeChat package specifically.

---

## Android pitfalls worth remembering

- **`getSource()` is unreliable on Android 11+.** Always have a fallback to the active window.
- **`isPassword()` is the reliable password signal.** The OS also masks the text, but checking
  first means the content never reaches app memory.
- **`android.jar` is stubs.** Calling `org.json` (or anything else framework-provided) on a plain
  JVM throws `RuntimeException: Stub!`. DraftGuard ships its own minimal JSON writer/parser so the
  storage layer stays testable off-device.
- **`renameTo` can fail silently.** Check the return value, or don't use it for small files.

## Build pitfalls (no-Gradle toolchain)

- **`sources.txt` must have no BOM.** PowerShell 5.1's `Set-Content -Encoding UTF8` adds one, and
  `javac` reports `invalid flag` / "invalid file name". Use
  `[IO.File]::WriteAllLines(path, lines, UTF8Encoding($false))`.
- **`core-lambda-stubs.jar` belongs on the bootclasspath** or lambda expressions fail to compile
  with `cannot find symbol: method metafactory`.
- **`d8` requires its output directory to already exist** (`Invalid output` otherwise).
- **Do not set `$ErrorActionPreference='Stop'`** in the build script: PowerShell 5.1 turns a native
  tool's ordinary stderr (keytool and aapt2 both write to it) into a terminating exception.
- **`javac` parses `\u` inside comments** and fails with "illegal unicode escape".
- **Mind CRLF in the project files** when doing multi-line string replacement from PowerShell.

---

## Versioning

`versionCode` is derived from the version name: `major*10000 + minor*100 + patch`
(so `2.0.1` → `20001`). Both `build-apk.ps1` and `release.ps1` use the same derivation, because a
manually-chosen `versionCode` that doesn't grow with the version name causes Android to reject the
install as a downgrade — an easy mistake to make when the two numbers are maintained separately.

Also worth knowing: **the package name is part of an app's identity.** Renaming
`com.typelog.recorder` → `com.draftguard` made every earlier build a different app, so it cannot be
upgraded in place; the old one must be uninstalled first.
---

## 7. WeChat: events arrive with full text, but nothing is recorded

This was the hardest one, and it invalidated several earlier assumptions.

**Symptom.** WeChat sent plenty of events — the per-package counter showed
`(text) com.tencent.mm = 9` — but `captured` stayed at 0 and no rows were written. The
diagnostic read `notEditable=3`.

**What the raw event dump showed** (read it carefully, everything is there):

```
ev: 16 com.tencent.mm android.widget.EditText [hell deepseekWECHAT_TEST_123]
ev: 16 com.tencent.mm android.widget.EditText [he deepseekWECHAT_TEST_123]
ev: 16 com.tencent.mm android.widget.EditText []
ev: 16 com.tencent.mm android.widget.EditText [H]
ev: 16 com.tencent.mm android.widget.EditText [HE]
```

`16` is `TYPE_VIEW_TEXT_CHANGED`. The class *is* `android.widget.EditText`. **And the event
itself carries the input field's complete text.**

**Root cause.** In WeChat, `event.getSource()` returns `null`, and
`getRootInActiveWindow()` returns `null` too — on MIUI this combination is common. Every
piece of node-based logic was therefore operating on nothing, no matter how permissive the
node checks were. The text was sitting in the event all along.

**Fix.** Add a final fallback that records from the **event's own text**, with no node at all:

```java
String evText = eventText(event);          // event.getText().get(0)
if (evText != null && !evText.isEmpty()) {
    handleText(pkg, null, evText);         // node == null is a supported path
    return;
}
```

Consequences of passing `node == null`:

- the focus gate must pass (with no node, focus cannot be verified — and receiving a
  text-change event with text *is* the signal that the user is typing)
- `isComposing()` and `fieldKey()` must tolerate a null node; the field key becomes
  `pkg + "#@event"`

**Verification.** Typing `WX——OK我喜欢你deepseek最喜欢最喜欢你了` in WeChat produced seven
records with monotonically growing text — Chinese and Latin mixed — all written to
`com.tencent.mm.jsonl`. The delete filtering also did its job: `delete=7`, and none of those
produced rows.

**Lesson.** When a node-based path fails, dump the *event* before adding more node
heuristics. All accessibility data that matters may be in the event, and an
`AccessibilityEvent` needs no window access at all.
---

## Data safety: when records survive an update, and when they don't

A question worth answering precisely, because the failure mode is silent and total.

| Action | Records |
|---|---|
| Install a **newer APK with the same signature** (`adb install -r` or tapping the APK) | **Preserved** — this is the normal update path |
| Change the signing key between builds | **Lost** — Android sees a different app; the install fails or requires uninstalling first |
| Uninstall the app | **Lost** |
| Settings → Clear data | **Lost** |
| Settings → Clear all records (in-app) | **Backed up first**, then cleared |
| Device factory reset | **Lost** |

**What this means in practice:** as long as builds are signed with the same keystore, updating the
app never touches the records — they live in app-private storage and are not part of the APK.
So **the keystore is as important as the data**: lose it and you can no longer ship an update that
installs over the existing app, which forces a fresh install and takes the records with it.

### Protections added after a real incident

During development, records were wiped by issuing the diagnostic probe's `clear` command while
testing. Three protections now exist:

1. **The probe's `clear` requires an explicit confirmation**:
   `--es cmd clear --ez confirm true`. Without it, the command is refused and logged as such.
2. **Any clear backs up first**, to `Download/DraftGuard/backup/backup-<time>-preclear.zip`.
   Deliberately in the **public** Downloads folder — a backup inside app-private storage cannot be
   retrieved by the user (or by `adb`), which makes it worthless as a backup.
3. The in-app confirmation dialog states that a backup will be made and where it goes.

**Lesson:** any irreversible operation needs both a confirmation gate and a recoverable artifact.
"Clearing is easy to test with" is exactly how user data gets destroyed.
---

## 8. Why some submit buttons cannot be detected

Message segmentation relies on a real signal: *the user tapped a submit button*. That works for
WeChat, QQ, Douyin and Larus, because their buttons are ordinary views that emit
`TYPE_VIEW_CLICKED` with a readable label.

It does **not** work everywhere. Observed case: tapping Bilibili's 发布 (publish) button produced
**no click event at all** — not merely an unlabelled one. The likely reason is that the screen is
built with Jetpack Compose, where a Button is a single canvas node and taps are handled internally
rather than surfaced as per-button accessibility click events. (Switching to another activity also
tore down the view tree, so timing may contribute.)

**Consequence:** for such apps there is no submit signal, and segmentation falls back to text
shape (common-prefix). That is usually right, but it cannot distinguish "continue typing the same
sentence" from "submit, then type a similar new one" — the two are identical in text.

**What would fix it properly**, in increasing order of intrusiveness:

1. Listen for `TYPE_VIEW_KEY` / IME action events (`IME_ACTION_SEARCH` etc.) — needs
   `flagRequestFilterKeyEvents` and, on modern Android, a restricted permission.
2. Observe the input field losing focus (`TYPE_VIEW_FOCUSED` transitions) — submit usually moves
   focus away. Cheap to try, not yet attempted.
3. Detect the *effect* of submission: the message list grows, or the edit field resets. Requires
   window-content monitoring, which is noisy.

**Practical stance:** the fallback is good enough for reading and recovering drafts, which is the
app's purpose. Perfect message-level segmentation on every UI toolkit is a much larger problem
than draft preservation, and should not be traded against it.
---

## 9. "Same opening but not merged" — two traps behind one symptom

User report: two entries in Doubao started with identical text but were shown as two segments.

### Trap 1 — the accessibility service was silently running stale code

`adb install -r` replaces the APK, but a bound `AccessibilityService` **keeps running the old
dex**. It is not an Activity, so it is not restarted on update, and it is not started again
automatically. The result is the worst possible situation for debugging: *the APK on disk is
new, the process is old, and nothing says so.*

Observed sequence: a rule added and built into v2.15.1 appeared to have no effect at all, three
times in a row, with correct code.

**Fix / procedure:** after every install that changes capture or segmentation logic, restart the
service explicitly and confirm it bound:

```bash
adb shell settings put secure enabled_accessibility_services com.draftguard/com.draftguard.TypelogService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys accessibility | grep 'Bound services'
```

### Trap 2 — `am force-stop` also kills the accessibility service

Using `am force-stop` to "restart the app" **disables the service**, and it does not come back on
its own. The app then records nothing at all, which looks exactly like a capture bug.

**Fix:** never use `force-stop` on this app while the service matters; relaunch the activity, or
re-enable via the settings command above, then verify the counter
(`events: all=… text=…`) is moving.

### Trap 3 — the field key was never persisted

Segmentation groups records by "which input field", but that key was **recomputed at render time
and never stored**. Exported data had no `field` at all, so offline analysis (which treated all
records as one field) merged more aggressively than the app and disagreed with it — the
disagreement itself was the clue.

**Fix:** write `field` into every record and segment on the stored value. Records from before the
change have no `field`; an empty value is treated as "same field as anything", so old data still
merges with itself.

Verification: with the fix, a 2-minute pause mid-message produced **54 versions → 1 segment**, and
phone-side segmentation matched offline analysis (190 vs 191 segments on a 1014-version day,
previously materially different).

### Trap 4 — version numbers never reached the APK

`aapt2` in build-tools 36.1.0 **silently ignores** `--version-code` / `--version-name`: the output
is byte-for-byte the manifest's hardcoded values (confirmed by linking with and without the flags).
Every build therefore shipped `versionCode=1, versionName=2.0.0`, so the package manager saw "no
change" on upgrade and the installed version could not be identified from `dumpsys`.

**Fix:** substitute the version into the manifest before linking, then **assert the built APK
reports the expected version** and fail the build otherwise.

**Lesson:** a flag that is silently ignored is worse than one that errors. Any property you depend
on for correctness needs a post-build assertion.
---

## 10. Refreshing content above the scroll position throws the user off

Reported as: "whenever I take a screenshot or an event arrives, the screen jumps back to the
statistics area, no matter where I scrolled to."

The mechanism is worth naming, because it looks like a scroll bug and is not one:

- Every accessibility event rewrote the top cards (status + live preview).
- The live-preview card shows up to 200 characters, so its **height changes** on each event.
- Content below the user's scroll position therefore **shifts**, and the viewport ends up
  showing whatever now occupies that offset — which reads as "the app jumped".
- A screenshot produces a window event, which goes down the same refresh path, hence
  "it jumps whenever I screenshot".

`showToday()` / `scrollToResults()` were not involved — they only run on button taps.

**Fix:** before updating a card, check whether it is **fully visible** inside the ScrollView's
viewport (`[scrollY, scrollY + height]` vs the view's top/bottom). If not, skip the update —
refreshing something nobody can see has no upside and shifts what they *are* looking at.
Skipped refreshes are remembered and applied on the next scroll, so scrolling back up never
shows stale data.

**Lesson:** a scroll position is a promise to the user. Anything that changes the height of
content *above* that position breaks it. When a list re-renders in place, gate updates on
visibility, or the UI will appear to move on its own.