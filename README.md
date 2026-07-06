# Speak

A system-wide voice-dictation utility for Android (Wispr Flow-style): an
`AccessibilityService` watches for focused text fields anywhere on the
device and shows a floating mic bubble next to them. Tapping the bubble
starts/stops dictation and inserts text into whatever field is focused.

**Status: Step 1 (this repo's current state)** — real on-device speech
recognition. The accessibility service + floating bubble + microphone
foreground service from Step 0 now feed captured PCM into a
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) pipeline: Silero VAD
segments the stream and each closed speech segment is decoded by the NeMo
Parakeet-TDT-0.6b-v2 (int8) offline transducer. Tapping the bubble a second
time flushes the tail, finalises the transcript off the main thread, and
inserts the **actual recognised text** into the focused field (Step 0's fixed
`"speak ✓ HH:mm:ss"` smoke-test string is gone).

Recognition is fully on-device and offline. The Parakeet model emits
punctuation and capitalisation natively, so the bundled English punctuation
model is left disabled by default (the code path is kept, gated behind a
flag — see `SherpaAsrEngine.ENABLE_PUNCTUATION`).

**Step 2 (transcript formatting)** is now layered on top: between the ASR
`finish()` and the `TextInserter`, the raw transcript is run through a
formatting pipeline that strips spoken-language artifacts (filler words,
duplicated words) and — optionally — an on-device LLM cleanup pass. See
[Transcript formatting](#transcript-formatting-step-2) below. Everything stays
on-device; no cloud, no API keys.

## Project structure

```
app/src/main/kotlin/com/apps/dsimpletools/speak/
  SpeakApplication.kt              Application subclass (no DI framework - manual singletons)
  MainActivity.kt                  Compose screen: permission/service status + 2 test fields
  accessibility/                   DictationAccessibilityService - focus tracking, bubble show/hide
  overlay/                         BubbleController (WindowManager overlay window) + BubbleView + BubblePositioner
  capture/                         CaptureController - FGS + AudioRecord, feeds PCM to the ASR session
  asr/                             AsrEngine/AsrSession interfaces + SherpaAsrEngine (VAD -> Parakeet -> punct)
  format/                          TranscriptFormatter + RuleBasedFormatter + GeminiNanoFormatter + FormattingPipeline + FormatController
  insert/                          TextInserter interface + AccessibilityTextInserter (ACTION_SET_TEXT)
  util/                            AccessibilityUtils (service-enabled check)
  ui/theme/                        Minimal Compose Material3 theme
app/src/debug/                     DEBUG_TAP + DEBUG_TRANSCRIBE_WAV broadcast receivers (debug builds only)
```

Package: `com.apps.dsimpletools.speak` · single `:app` module · Kotlin +
coroutines/Flow · no Hilt (manual singletons, e.g. the accessibility
service's companion `instance`) · classic Android `View`s for the overlay
bubble (a `WindowManager` overlay can't host a `ComposeView` without extra
plumbing) · Jetpack Compose for the in-app activity screen.

## Requirements

- Java 17
- Android SDK with `compileSdk`/`targetSdk` 35 installed (`ANDROID_HOME` set)
- No system Gradle needed — use the wrapper (`./gradlew`)

## Build

```bash
./gradlew assembleDebug
```

## Install & permission setup (emulator or device)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Runtime permissions
adb shell pm grant com.apps.dsimpletools.speak android.permission.RECORD_AUDIO
adb shell pm grant com.apps.dsimpletools.speak android.permission.POST_NOTIFICATIONS

# Enable the accessibility service (equivalent to toggling it on in Settings)
adb shell settings put secure enabled_accessibility_services \
  com.apps.dsimpletools.speak/com.apps.dsimpletools.speak.accessibility.DictationAccessibilityService
adb shell settings put secure accessibility_enabled 1

# Launch
adb shell monkey -p com.apps.dsimpletools.speak -c android.intent.category.LAUNCHER 1
```

## Model provisioning (required for dictation)

The ASR models are far too large to ship inside the APK (the int8 encoder
alone is 622 MB), so they are loaded at runtime from the app's external files
directory:

```
/sdcard/Android/data/com.apps.dsimpletools.speak/files/models/
```

The directory names must match the ones under the repo-root `models/`:

```
models/
  silero_vad.onnx
  sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/
    encoder.int8.onnx   decoder.int8.onnx   joiner.int8.onnx   tokens.txt
  sherpa-onnx-online-punct-en-2024-08-06/     # only needed if ENABLE_PUNCTUATION=true
    model.int8.onnx   bpe.vocab
```

If the required files are missing, `initialize()` fails gracefully: the app
does **not** crash, a bubble tap shows a `Speak: ASR models not installed`
toast, and `MODELS_MISSING expected=<path> missing=[...]` is logged.

### Push commands (verified on emulator-5554, API 33)

`adb push <dir>` into `Android/data/...` fails with
`remote secure_mkdirs failed: Operation not permitted` because it tries to
create nested subdirectories. Create the subdirectories first with
`adb shell mkdir`, then push the **files** into the existing directories:

```bash
PKG=com.apps.dsimpletools.speak
DEST=/sdcard/Android/data/$PKG/files/models
PK=sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8
PU=sherpa-onnx-online-punct-en-2024-08-06

# (install the app first so its data dir exists)
adb shell mkdir -p "$DEST/$PK/test_wavs" "$DEST/$PU"

adb push models/silero_vad.onnx                  "$DEST/silero_vad.onnx"
adb push models/$PK/encoder.int8.onnx            "$DEST/$PK/encoder.int8.onnx"
adb push models/$PK/decoder.int8.onnx            "$DEST/$PK/decoder.int8.onnx"
adb push models/$PK/joiner.int8.onnx             "$DEST/$PK/joiner.int8.onnx"
adb push models/$PK/tokens.txt                   "$DEST/$PK/tokens.txt"
adb push models/$PK/test_wavs/0.wav              "$DEST/$PK/test_wavs/0.wav"  # for the WAV test hook
# Punctuation model is optional (disabled by default):
adb push models/$PU/model.int8.onnx              "$DEST/$PU/model.int8.onnx"
adb push models/$PU/bpe.vocab                    "$DEST/$PU/bpe.vocab"
```

### run-as fallback (if direct push to Android/data is denied)

On stricter builds (e.g. Android 16 may refuse even the above), push into the
always-writable `/data/local/tmp` and copy across as the app uid via `run-as`
(the app owns its own `Android/data/<pkg>/files` tree):

```bash
adb push models/$PK/encoder.int8.onnx /data/local/tmp/encoder.int8.onnx
adb shell run-as $PKG sh -c \
  "mkdir -p $DEST/$PK && cp /data/local/tmp/encoder.int8.onnx $DEST/$PK/encoder.int8.onnx"
adb shell rm /data/local/tmp/encoder.int8.onnx   # reclaim space
# ...repeat per file...
```

## Transcript formatting (Step 2)

Raw dictation contains spoken-language artifacts — filler words ("um", "uh"),
stutter/duplicated words, and grammar slips. A formatting layer sits between the
ASR `finish()` and the `TextInserter` and cleans the transcript, Wispr-Flow
style, fully **on-device** (no cloud, no API keys).

### Levels

A single level is persisted in `SharedPreferences` (`speak_format` /
`format_level`) and selectable from the in-app screen (Off / Light / Full):

- **OFF** — no cleanup; the raw ASR transcript is inserted verbatim.
- **LIGHT** — the deterministic, instant `RuleBasedFormatter` only. Strips
  standalone fillers (`um`, `uh`, `uhm`, `er`, `erm`, `ah`, `hmm`, `mm-hmm`
  variants) as whole words (never inside a word, and handling attached commas
  + re-capitalisation), collapses immediate duplicate words (`the the` → `the`;
  a small whitelist — `had`, `that`, `is`, `do`, `can` — is left alone),
  normalises whitespace/punctuation, and repairs sentence-initial
  capitalisation. Phrase-level repeats (`we should we should`) are intentionally
  left for the Full path.
- **FULL** — rules first, then an on-device **Gemini Nano** proofreading pass
  (ML Kit GenAI). The LLM only *cleans* (grammar/fillers/self-corrections); it
  never paraphrases or adds content. It is hard-timed at **2000 ms** and its
  output is discarded (keeping the rule-based text) if it is empty or deviates
  more than **±40 %** in length from its input. Any failure falls back to the
  rule-based text — the LLM can never break or block dictation.

**Default:** FULL when the device reports the Nano feature as
Available/Downloadable, else LIGHT.

### Gemini Nano (Full mode)

Full mode uses the ML Kit GenAI **Proofreading** API
(`com.google.mlkit:genai-proofreading:1.0.0-beta1`, `InputType.VOICE`), which
runs Gemini Nano through AICore. Requires a supported device (e.g. Pixel 9/10
series); on unsupported devices it reports `Unavailable` and Full mode simply
behaves like Light. The in-app status line and the runtime check surface one of:

- **Available** — model present; the proofreading pass runs.
- **Downloadable** — supported but not yet downloaded; a background download is
  kicked off (`FORMAT_LLM_DOWNLOADING`) and this call falls back to rule output.
- **Downloading** — download in progress; falls back to rule output.
- **Unavailable** — device/OS doesn't support it; Full == Light.

### Debug broadcasts (debug builds only)

Set the formatting level (persisted):

```bash
adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_SET_FORMAT_LEVEL \
  -p com.apps.dsimpletools.speak --es level OFF|LIGHT|FULL
```

Run the full pipeline on arbitrary text (no dictation needed — lets the LLM
path be exercised directly; logs `FORMAT_RESULT` + any `FORMAT_LLM_*`):

```bash
adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_FORMAT_TEXT \
  -p com.apps.dsimpletools.speak \
  --es text "Um, so I think, uh, we should we should meet at at 3pm, er, tomorrow."
```

### Log markers

- `FORMAT_RESULT: level=<L> ruleMs=<x> llmMs=<y> "<raw>" -> "<final>"` — one
  line per formatted transcript.
- `FORMAT_LLM_DOWNLOADING` — Nano feature download kicked off / in progress.
- `FORMAT_LLM_TIMEOUT` — the 2000 ms LLM budget was exceeded; rule text kept.
- `FORMAT_LLM_ERROR` — the LLM path threw; rule text kept.
- `FORMAT_LLM_REJECTED` — LLM output was empty or over-edited (>±40 % length);
  rule text kept (logs both strings).
- `FORMAT_LLM_UNAVAILABLE` — Nano not supported on this device; rule text kept.

## Manual test flow

1. Launch the app, confirm all three status rows can be driven to "Enabled"
   (accessibility service / mic permission / notifications).
2. Tap into either test field (classic `EditText` or Compose
   `OutlinedTextField`) — the mic bubble should appear next to it without
   covering it.
3. Tap the bubble once — it should turn red (pulsing) and start listening.
   Watch logcat for `FGS_SPIKE_RESULT` and `MIC_RMS` lines (see below).
4. Tap the bubble again — it turns orange (processing) while the transcript
   is finalised, then back to blue (idle), and the focused field should now
   contain what you actually said. Watch logcat for `ASR_SEGMENT`,
   `ASR_FINAL`, `ASR_TIMING` and `INSERT_RESULT`. Saying nothing shows a
   `Speak: no speech detected` toast and inserts nothing; tapping before the
   models finish loading shows `Speak: still loading models`.
5. Repeat in another app (browser address bar, Settings search box, etc.) to
   confirm the bubble works system-wide, not just in-app.

### Backup trigger (debug builds only)

If a scripted real tap on the bubble is inconvenient, the same code path can
be invoked directly:

```bash
adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_TAP \
  -p com.apps.dsimpletools.speak
```

### Deterministic WAV test hook (debug builds only)

Runs the full VAD → ASR → (punct) pipeline over a WAV file on disk — no
microphone needed — and logs `ASR_FINAL`. Requires the accessibility service
to be enabled (it owns the warm engine). Great for verifying the model
pipeline in isolation:

```bash
adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_TRANSCRIBE_WAV \
  -p com.apps.dsimpletools.speak \
  --es path /sdcard/Android/data/com.apps.dsimpletools.speak/files/models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/test_wavs/0.wav
```

Expected `ASR_FINAL` for the bundled `test_wavs/0.wav`:
`Well, I don't wish to see it any more, observed Phoebe, turning away her
eyes. It is certainly very like the old portrait.`

### Useful logcat filters

```bash
adb logcat | grep -E "BUBBLE|FGS_SPIKE_RESULT|MIC_RMS|INSERT_RESULT|CAPTURE|DEBUG_TAP"
# ASR pipeline (Step 1):
adb logcat | grep -E "ASR_INIT|ASR_SEGMENT|ASR_FINAL|ASR_TIMING|MODELS_MISSING"
# Formatting (Step 2):
adb logcat | grep -E "FORMAT_RESULT|FORMAT_LLM_|FORMAT_LEVEL|FORMAT_DEFAULT"
adb shell dumpsys notification | grep -i speak
```

`ASR_INIT: <ms>` is the model load time; `ASR_SEGMENT: <n> <durMs>ms -> "<text>"`
is logged per VAD segment; `ASR_TIMING: audioMs=<x> decodeMs=<y> rtf=<z>` gives
the real-time factor of a dictation.

`BUBBLE_POS: x,y w,h` lines give the bubble's current absolute screen
position/size, which is what a test script should feed into
`adb shell input tap <x+w/2> <y+h/2>` to hit the bubble deterministically.

## Known limitations / next-step TODOs

- `TextInserter`/`InsertMode.APPEND` appends at the end of the field's
  existing text, not at the actual cursor position (no
  `ACTION_ARGUMENT_SELECTION_START/END_INT` handling yet). Cursor-aware
  insertion of the transcript is a later step.
- Recognition is segment-based (VAD closes a segment → decode), so there is
  no live in-field partial preview while speaking — the full transcript
  appears on stop-tap. No streaming/partial UI yet.
- The engine is loaded eagerly on service connect and held resident (~1 GB
  with the int8 encoder). There is no idle-timeout unload yet.
- No fallback path yet for devices/OS versions where the foreground-service
  self-promotion fails.
- Automated tests cover `RuleBasedFormatter` only (plain JVM unit tests under
  `app/src/test`, run with `./gradlew test`). The ASR and overlay paths are
  still verified manually against emulator-5554 API 33 via the WAV test hook;
  the physical device is validated by the orchestrator.
