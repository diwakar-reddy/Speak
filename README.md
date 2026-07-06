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
  asr/                             AsrEngine/AsrSession interfaces + SherpaAsrEngine (VAD -> speaker gate -> Parakeet -> punct)
  format/                          TranscriptFormatter + RuleBasedFormatter + GeminiNanoFormatter + FormattingPipeline + FormatController
  speaker/                         SpeakerVerifier (CAM++ gate) + SpeakerMath/SpeakerPolicy (pure) + SpeakerProfileStore + SpeakerVad + EnrollmentRecorder
  insert/                          TextInserter interface + AccessibilityTextInserter (ACTION_SET_TEXT)
  util/                            AccessibilityUtils (service-enabled check)
  ui/theme/                        Minimal Compose Material3 theme
app/src/debug/                     DEBUG_TAP / DEBUG_TRANSCRIBE_WAV / DEBUG_*_FORMAT / DEBUG_INSERT_TEXT / DEBUG_*_SPEAKER broadcast receivers (debug builds only)
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

### Push commands — canonical recipe (mkdir → push → chmod)

`adb push <dir>` into `Android/data/...` fails with
`remote secure_mkdirs failed: Operation not permitted` because it tries to
create nested subdirectories. Create the subdirectories first with
`adb shell mkdir`, push the **files** into the existing directories, then
**`chmod -R o+rX` the whole models tree** — this last step is mandatory:

```bash
PKG=com.apps.dsimpletools.speak
DEST=/sdcard/Android/data/$PKG/files/models
PK=sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8
PU=sherpa-onnx-online-punct-en-2024-08-06

# 1. Install the app first so its data dir exists, then create the subdirs.
adb shell mkdir -p "$DEST/$PK/test_wavs" "$DEST/$PU"

# 2. Push the files into the existing directories.
adb push models/silero_vad.onnx                  "$DEST/silero_vad.onnx"
adb push models/$PK/encoder.int8.onnx            "$DEST/$PK/encoder.int8.onnx"
adb push models/$PK/decoder.int8.onnx            "$DEST/$PK/decoder.int8.onnx"
adb push models/$PK/joiner.int8.onnx             "$DEST/$PK/joiner.int8.onnx"
adb push models/$PK/tokens.txt                   "$DEST/$PK/tokens.txt"
adb push models/$PK/test_wavs/0.wav              "$DEST/$PK/test_wavs/0.wav"  # for the WAV test hook
# Punctuation model is optional (disabled by default):
adb push models/$PU/model.int8.onnx              "$DEST/$PU/model.int8.onnx"
adb push models/$PU/bpe.vocab                    "$DEST/$PU/bpe.vocab"

# 3. REQUIRED: make the whole tree world-readable/traversable.
adb shell chmod -R o+rX "$DEST"
```

**Why the `chmod` is required (do not skip it):** directories created by
`adb shell mkdir` under `Android/data/<pkg>/files` are owned by the shell user
and land in the `ext_data_rw` group with mode `2770` — i.e. **no world (other)
access at all**. The app runs as its own distinct uid, which is neither the
owner nor a member of `ext_data_rw`, so without world bits the app process
cannot even traverse into the directories: `initialize()` reports
`MODELS_MISSING` even though the files are visibly present on disk.
`chmod -R o+rX` adds world read on files and world read+execute (traverse) on
directories, which is exactly what the app uid needs. Re-run it any time you
re-`mkdir`/re-push (adb-shell-created dirs always land group-only again).

### run-as fallback (if direct push to Android/data is denied)

On stricter builds that refuse the direct push, stage into the always-writable
`/data/local/tmp` and copy across as the app uid via `run-as` (the app owns its
own `Android/data/<pkg>/files` tree, so files it creates are already readable by
the app uid and need no `chmod`):

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

## Speaker isolation (Step 3)

Only the enrolled owner's speech is transcribed. Other voices (people nearby, a
TV, cross-talk) are dropped **before** the recogniser, per VAD segment, so they
never turn into inserted text. Everything is on-device.

The gate uses a wespeaker **CAM++** speaker-embedding model
(`wespeaker_en_voxceleb_CAM++_LM.onnx`, 512-dim, 16 kHz) via sherpa-onnx's
`SpeakerEmbeddingExtractor`. The owner profile keeps the raw per-utterance
embeddings of up to **10** enrollment utterances plus their L2-normalised mean;
each VAD segment is scored with a **composite** cosine similarity (see
[Scoring](#scoring-composite)). Despite the "en_voxceleb" training set, CAM++
voiceprints separate speakers regardless of the spoken language (verified below
on Mandarin-speech samples).

### Enrollment

In the app's **Voice** section: *Enroll* (or *Re-enroll*) opens a guided dialog
with 3 read-aloud sentences (~8–12 s each). Each utterance is recorded with the
same capture config as dictation (`VOICE_RECOGNITION`, 16 kHz mono) + noise
suppression, silence-trimmed with the Silero VAD, and requires **≥ 4 s of net
speech** or it re-prompts. The 3 embeddings become the owner profile, persisted,
and the **"Only my voice"** gate is switched on (it is only effective once
enrolled; default ON after a successful enrollment). *Clear* forgets the profile.

**Add voice sample** (shown only when already enrolled) records ONE additional
guided utterance — same flow, same ≥ 4 s net-speech retry — and **appends** it to
the profile (never replaces), rebuilding the mean and bumping the shown utterance
count. The prompt sentence rotates through 5 different texts so repeated adds
don't all read the same words. The profile is capped at **10 utterances**: at the
cap the **oldest** utterance rotates out first (the original 3 enrollment
utterances may rotate out too — deliberate, the freshest samples reflect the
owner's current voice best). Enrollment quality is the main accuracy lever: add
samples **in the places you actually dictate** (desk, walking, car) so the
profile spans your real acoustic conditions — the dialog reminds you of this.

The raw per-utterance embeddings are persisted to
`filesDir/speaker_profile.bin` (a small length-prefixed big-endian binary:
magic/version/dim/count then `count × dim` float32s), and the mean is rebuilt
from them on every start. The format (SPK1 v1) is unchanged by append-enrollment
— the count field was always generic, so profiles written before it existed load
identically. sherpa-onnx's `SpeakerEmbeddingManager` is deliberately **not** used
for persistence (its store is in-memory native and returns only a boolean) —
computing cosine ourselves keeps the score loggable.

### Scoring (composite)

A segment embedding `e` is scored against the profile as

```
score = max( cosine(e, mean),  max_i cosine(e, utterance_i) )
```

i.e. the better of the blended mean-profile match and the **best single
enrollment utterance** match. The mean captures the owner's average voice; the
per-utterance max lets a segment that closely resembles *one* enrollment
condition (say, the car sample) pass even when the blended mean dilutes that
condition. Both components are logged per gated segment
(`SPEAKER_SCORE: mean=… bestUtt=… used=…`, debug level) and the
`SPEAKER_ACCEPT`/`REJECT`/`BYPASS` `sim=` value **is** the composite (`used`).

**Honest caveat:** composite max-scoring only pays off once the enrollment
utterances span varied conditions. With 3 same-sitting utterances they all point
nearly the same way, so `bestUtt ≈ mean` and the composite ≈ the old mean score —
add samples in varied places to give the max something to work with. Emulator
calibration (2-utterance profile, same-speaker test clip): owner long segment
`mean=0.724 bestUtt=0.831 used=0.831` (composite +0.107 over the mean) vs a
foreign speaker's long segment `mean=0.546 bestUtt=0.559 used=0.559` — still
rejected at the 0.60 threshold. The impostor score rises only marginally because
a stranger resembles one of your utterances about as much as he resembles their
mean; genuine matches gain much more headroom.

### Gate semantics

Per VAD segment, before decode:

- **Accepted** (`SPEAKER_ACCEPT: sim=<x.xxx> dur=<s.s>s`) — a long segment
  (≥ `MIN_GATED_SEGMENT_SEC`) that matched the owner → decoded and appended as usual.
- **Rejected** (`SPEAKER_REJECT: sim=<x.xxx> dur=<s.s>s`) — a long segment that did
  **not** match → dropped, not decoded.
- **Bypass-held** (`SPEAKER_BYPASS: sim=<x.xxx> dur=<s.s>s (held)`) — a short segment
  (< `MIN_GATED_SEGMENT_SEC`) that is **not** gated standalone → decoded (decode is cheap)
  but its text is *held*, to be included or dropped at session end (see below).

**Fail-open** cases always accept (dictation is never blocked by the gate):

- Not enrolled, or the "Only my voice" toggle is off → accepted (no gate log).
- CAM++ model file missing → gate disabled, `SPEAKER_MODEL_MISSING` logged once,
  all segments accepted.
- Embedding compute throws → `SPEAKER_ERROR` logged, segment accepted.

### Threshold + short-segment (held) policy

Long segments accept when the [composite score](#scoring-composite)
`≥ threshold`. The threshold defaults to sherpa-onnx's documented **0.60**
(`SpeakerPolicy.DEFAULT_THRESHOLD`) and is persisted-configurable.

**Short segments are never gated by similarity.** CAM++ voiceprints on sub-~2 s
speech carry too little speaker information to classify: on real hardware the
owner's own single words ("much", "better", "good") scored `sim ≈ 0.28–0.32`,
squarely inside the same 0.26–0.55 band strangers land in, while the same
speaker's 3 s sentence scored `0.66`. No threshold can separate the owner's short
words from a stranger's — so a per-segment relaxed threshold (the previous
`0.60 − 0.05` rule) was useless and has been removed. Instead:

- A segment **< `MIN_GATED_SEGMENT_SEC` (2.0 s)** is decoded unconditionally and
  its text is **held** (logged `SPEAKER_BYPASS … (held)`), not appended yet.
- A segment **≥ 2.0 s** is gated by similarity exactly as before
  (`SPEAKER_ACCEPT` / `SPEAKER_REJECT`).

At session finish, `SessionGatePolicy` decides the held short segments' fate from
what the (reliably gated) long segments in the same session decided, and logs one
summary line
`SPEAKER_SESSION: longAccepted=<n> longRejected=<n> shortHeld=<n> -> included|dropped`:

- **≥ 1 long segment accepted** as the owner → the owner was clearly present →
  held shorts are **included**.
- **No long segments at all** (e.g. a single-word "much" dictation — a deliberate
  tap by the owner) → held shorts are **included**.
- **Long segments present but ALL rejected** (sustained foreign speech) → held
  shorts are **dropped** too.

The final transcript preserves original segment order: accepted-long and included
short segments are interleaved by their VAD-segment index. Held text only appears
at finish; accepted-long text is visible immediately (`finalizedText`).

**Honest trade-off:** a brief foreign interjection (a short word from someone else)
during a session where the owner is otherwise accepted can ride along into the
transcript — the cost of never dropping the owner's own short words. Sustained
foreign speech (which produces rejected long segments and no accepted ones) is
still fully blocked, held shorts included.

### Noise suppression

`NoiseSuppressor` (and `AcousticEchoCanceler` when available) are attached to
**both** capture paths — live dictation and enrollment recording — whenever
`NoiseSuppressor.isAvailable()`, and released with the recorder. Each capture
session logs `NS_ATTACHED <true|false>` once. (Pixel 10 Pro: `NS_ATTACHED true`.)

### Model provisioning

Push the CAM++ model to the same external models dir as the ASR models, then
`chmod` (same reason as the ASR models — see
[the canonical recipe](#push-commands--canonical-recipe-mkdir--push--chmod)):

```bash
PKG=com.apps.dsimpletools.speak
DEST=/sdcard/Android/data/$PKG/files/models
adb push "models/wespeaker_en_voxceleb_CAM++_LM.onnx" "$DEST/wespeaker_en_voxceleb_CAM++_LM.onnx"
adb shell chmod -R o+rX "$DEST"   # REQUIRED (adb-pushed files are otherwise not world-readable)
```

If the file is absent the gate simply stays disabled (logs `SPEAKER_MODEL_MISSING`
once); dictation still works, ungated.

### Log markers

- `SPEAKER_INIT: dim=<n> model=<file>` — CAM++ extractor loaded.
- `SPEAKER_MODEL_MISSING expected=<path>` — model absent; gate disabled (once).
- `SPEAKER_ENROLLED utterances=<n> gate=on threshold=<t>` — enrollment succeeded.
- `SPEAKER_APPENDED added=<k> utterances=<n> cap=10 threshold=<t>` — extra
  sample(s) appended to the profile (oldest rotated out beyond the cap).
- `SPEAKER_CLEARED` — profile forgotten.
- `SPEAKER_SCORE: mean=<x.xxx> bestUtt=<x.xxx> used=<x.xxx>` — (debug level) the
  composite-score decomposition for a gated segment; `used` is what the decision
  lines report as `sim=`.
- `SPEAKER_ACCEPT` / `SPEAKER_REJECT: sim=<x.xxx> dur=<s.s>s` — per-segment gate
  (long segments ≥ 2.0 s); `sim` is the composite `used` score.
- `SPEAKER_BYPASS: sim=<x.xxx> dur=<s.s>s (held)` — short segment decoded but held.
- `SPEAKER_SESSION: longAccepted=<n> longRejected=<n> shortHeld=<n> -> included|dropped`
  — session-end resolution of the held short segments.
- `SPEAKER_ERROR` — embedding compute failed on a long segment → fail-open accept.
- `SPEAKER_STATUS enrolled=… utterances=… threshold=… gate=… modelFilePresent=…
  modelLoaded=… dim=…` — full state dump.
- `NS_ATTACHED <true|false>` — noise suppressor attached for this capture session.

### Debug broadcasts (debug builds only)

```bash
# Enroll the owner from WAV files (VAD-trims each; replaces any existing profile):
adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_ENROLL_WAV \
  -p com.apps.dsimpletools.speak --es paths "/sdcard/.../a.wav,/sdcard/.../b.wav"

# APPEND extra utterances to the existing profile (no replacement; capped at 10,
# oldest rotated out — same pipeline as the in-app "Add voice sample"):
adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_APPEND_ENROLL_WAV \
  -p com.apps.dsimpletools.speak --es paths "/sdcard/.../d.wav"

# Verify a WAV against the current profile (logs per-segment SPEAKER_ACCEPT/REJECT;
# no enrollment change, no insert):
adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_VERIFY_WAV \
  -p com.apps.dsimpletools.speak --es path /sdcard/.../c.wav

# Set the gate flag and/or threshold (both extras optional):
adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_SET_SPEAKER \
  -p com.apps.dsimpletools.speak --es gate on|off --ef threshold 0.55

# Dump the gate state to logcat:
adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_SPEAKER_STATUS \
  -p com.apps.dsimpletools.speak
```

`DEBUG_TRANSCRIBE_WAV` runs the full VAD → gate → ASR pipeline, so the speaker
gate applies there too: while enrolled with speaker A, transcribing a different
speaker's **sustained (long-segment) speech** drops every segment and yields an
empty `ASR_FINAL`; toggling the gate off restores the full transcript. A
**short-only** WAV (< 2.0 s of speech, no long segments) is bypass-held and, per
`SessionGatePolicy`, included — so a single-word clip transcribes even when it is
not the enrolled owner (short segments are unclassifiable, so they are kept).
`DEBUG_VERIFY_WAV` reports the would-be per-segment outcome (`SPEAKER_ACCEPT` /
`SPEAKER_REJECT` / `SPEAKER_BYPASS`) plus the `SPEAKER_SESSION` resolution, without
decoding or enrolling — the clean read for threshold/duration calibration.

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
microphone needed — logs `ASR_FINAL`, then feeds that transcript through the
`FormattingPipeline` at the current persisted level and logs `FORMAT_RESULT`.
This makes the hook a full **text-quality probe (ASR → format)** with no mic.
It never inserts text. Requires the accessibility service to be enabled (it
owns the warm engine). If a live dictation session is active the engine rejects
the request (`ASR_BUSY`) and the live session is left untouched — the hook can
never steal or corrupt a running session:

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

- Insertion is now **cursor-aware**: the transcript is spliced in at the field's
  current caret position (via `textSelectionStart/End`), or *replaces* the
  selected range when there is a selection, and the caret is then moved to just
  after the inserted text (`ACTION_SET_SELECTION`). Separating spaces are added
  only where needed (no stray space before clinging punctuation). If a field
  exposes no usable selection (`-1`), it degrades to appending at the end.
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
