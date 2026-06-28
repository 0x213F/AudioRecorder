# Overdub — technical design (v2)

> Scope: add an "overdub" capability to the V2 (Kotlin/Compose/Hilt/Room) codebase —
> record a new audio layer in sync over an existing recording, output = the two mixed.
> Constraints: **low footprint** and **easily verifiable**.

Verified against source: `minSdk 26`, `compileSdk 37`; deps include `jaudiotagger` +
`mp4parser.muxer`, **no media3/exoplayer/oboe**; recorder dispatch is
`AudioRecorderDelegate.provideAudioRecorder()` switching on `prefs.settingRecordingFormat`
(M4a→MediaRecorder, Wav→AudioRecord PCM, 3Gp→MediaRecorder); `WavRecorderV2` lines 164–256 are a
self-contained `Dispatchers.IO` coroutine that does `recorder.read()` → `fos.write()` → in-place
WAV-header rewrite; `AudioDecoder.java` lines 218–242 decode true PCM into `outputBuffer` but
**discard it**, emitting only amplitude `gains`. The nav graph file is literally
`RecorerNavigationGraph.kt` (misspelled). `Routes` has no overdub entry.

## 1. Difficulty assessment — **LARGE (L)**

Not because any one piece is hard, but because overdub crosses every layer and the recorder
architecture is split.

- **The recorder split is the dominant cost.** M4A/3GP go through `MediaRecorder`, which encodes
  straight to a container and **cannot be sample-mixed**. Only the WAV path (`WavRecorderV2`,
  AudioRecord→raw PCM) gives you samples. Any overdub that mixes audio is therefore a **PCM-only**
  path. Supporting overdub for all three formats at the recording layer is an XL refactor; scoping
  the *capture* to PCM (independent of the user's normal recording-format setting) collapses it
  back to L.
- **Concurrent record+playback is unproven in this codebase** and has real OS hazards (no audio
  focus anywhere, dual foreground-service-types on API 33+, acoustic feedback). These are
  device-verification items, not just code.
- **What keeps it at L, not XL:** every architectural seam already exists — Hilt singletons,
  `ServiceConnection` binding (HomeViewModel already binds two services), the `RecorderV2`
  interface, `RecordsDataSource`, Room `Converters` for `IntArray`, `AudioDecoder` for post-hoc
  waveform, the `MediaMuxer` reference code in `BrokenRecordRestorer`, and a WAV writer to fork.
  You are recombining existing primitives, not inventing them.

Realistic MVP (WAV capture, offline mix, single base waveform): **~1–1.5 weeks** of engineering
plus device testing. Full live dual-waveform + M4A/3GP base support: meaningfully more.

## 2. Recommended implementation — **hybrid offline-mix**

Record the new layer as its own raw PCM/WAV file while the base track plays back through the
existing player for the performer's monitoring; when the user stops, mix the two files **offline**
on a background dispatcher.

Alternatives considered and rejected:

- **Acoustic** (play through speaker, record both via mic) — lowest code, but unverifiable and bad
  UX: output quality depends on room acoustics/feedback/mic placement; you can't unit-test "did it
  mix."
- **True real-time digital mix** (decode base PCM + mic PCM and sum inside the `AudioRecord.read()`
  loop) — best UX but highest risk: sample-accurate sync inside a ~20 ms budget, jitter, drift, and
  `AudioDecoder` would need rewriting to *stream* PCM. Highest footprint, hardest to verify.

Hybrid offline-mix is the lowest-footprint + most-verifiable option for *this* codebase because the
mixing step becomes **pure, deterministic, file-in/file-out math** that runs without any audio
hardware → fully unit-testable (that pure piece is shipped as `PcmMixer`).

### Concrete pipeline

1. **Capture path — PCM, regardless of the user's format setting.** A small `OverdubRecorder`
   reuses the `WavRecorderV2` AudioRecord read-loop (lines 164–256: read chunk → write to a temp
   `overdub_layer.wav` → accumulate amplitude → in-place header on stop). Do **not** route through
   `AudioRecorderDelegate` (it would hand back a `MediaRecorder` for M4A users). Overdub always
   captures 16-bit PCM mono at the base record's sample rate so mixing is trivial.
2. **Playback-during-record (monitoring).** Reuse the existing `AudioPlaybackService` /
   `AudioPlayerNew` to play the base record so the performer hears it. **Critical:** do **not**
   reuse `HomeViewModel.handleStartRecordingClick()` — it calls `audioPlayer.stop()` first
   (the deliberate mutual-exclusion guard). Overdub needs a parallel start that leaves playback
   running. Capture `player.getCurrentPosition()` at the instant `AudioRecord` actually starts and
   store it as `playbackStartOffsetMills` for alignment.
3. **Mix + encode step (offline, on stop).** A new `OverdubMixer` runs on a background dispatcher:
   - Decode the base track to PCM. The base may be M4A/3GP/WAV, so you need streamed PCM.
     `AudioDecoder.java` already decodes to PCM but throws the buffer away — add a PCM sink (see
     change set) that writes `outputBuffer` to a temp `base.pcm` instead of computing gains.
   - Match channels/rate (MVP: force overdub capture to base's sample rate + mono → no resampler).
   - Mix sample-by-sample with **clipping clamp** via `PcmMixer`, applying `playbackStartOffsetMills`
     as a leading offset so the layers line up.
   - **Encode the result as WAV** (reuse the top-level `createWavHeader()` from `WavRecorderV2.kt`).
     WAV avoids `MediaMuxer`/`MediaCodec`-encode complexity entirely. (For later M4A output, the
     `MediaMuxer` pattern in `BrokenRecordRestorer.kt` ~lines 299–387 is the reference.)
4. **Persist + waveform.** Insert a new `Record` (id=0) pointing at the mixed WAV via
   `recordsDataSource.insertRecord(...)`, set `prefs.activeRecordId` to it, then let the **existing**
   `DecodeService`/`AudioDecoder` produce the final `amps: IntArray` exactly as a normal recording
   does. No new waveform code needed for MVP.

Net: the only genuinely new audio code is one AudioRecord loop (forked) + one PCM mixer (pure math,
already shipped) + one decoder PCM sink. Everything else is wiring.

## 3. Exact change set

### New files (under `app/src/main/java/com/dimowner/audiorecorder/v2/`)

| Path | Purpose | Status |
|---|---|---|
| `audio/overdub/PcmMixer.kt` | Pure 16-bit PCM mix (gain + clip + offset). No Android APIs → unit-testable. | **Done (in branch)** |
| `audio/overdub/OverdubRecorder.kt` | Forks the `WavRecorderV2` AudioRecord read-loop to capture the new layer as a standalone PCM/WAV temp file; emits `RecorderEvent`s; does **not** go through `AudioRecorderDelegate`. | Spec below |
| `audio/overdub/PcmDecodeSink.kt` (or extend `AudioDecoder`) | Captures the decoded `outputBuffer` `AudioDecoder` currently discards (lines 218–242) and writes raw PCM to a temp file, to feed the mixer's base decode. | Spec below |
| `audio/overdub/OverdubMixer.kt` | Orchestrates the offline mix on a background dispatcher: decode base→PCM, call `PcmMixer`, write result WAV via `createWavHeader`, emit progress. | Spec below |
| `app/home/OverdubHeadphoneDialog.kt` | Headphone/feedback advisory AlertDialog (modeled on `BrokenRecordDialog`). | See UX spec §3 |

### Existing files to modify

| Path | Change |
|---|---|
| `v2/app/components/RecordPlaybackPanel.kt` | Add an Overdub `IconButton` to the transport row (between PlayPanel and Next). |
| `v2/app/home/HomeComponents.kt` | Add `OVERDUB_ARMED/RECORDING/REVIEW` branches to `BottomBar`'s `when`; add `OverdubRecordingPanel` + `OverdubReviewPanel` (clones of `RecordingProgressPanel`/`RecordingPausePanel`). |
| `v2/app/home/HomeScreen.kt` / `HomeViewModel.kt` | Add overdub mode state + actions; wire monitoring playback + `OverdubRecorder` via the existing dual-`ServiceConnection` pattern; trigger `OverdubMixer` on stop; **do not** reuse `handleStartRecordingClick` (keeps its `audioPlayer.stop()` guard for normal recording). |
| `audio/AudioDecoder.java` | Add an optional PCM-output sink so the decoded `outputBuffer` (lines 218–242) can be written to a file instead of only feeding `gains`. |
| `v2/data/model/Record.kt` + `room/RecordEntity.kt` + `Mappers.kt` | Add `parentRecordId: Long? = null` (+ optional `isOverdub: Boolean = false`) for lineage; map both ways. |
| `v2/data/room/AppDatabase.kt` (+ a `Migration_N`) | Bump version; additive migration adding the two nullable columns with defaults (additive ⇒ safe). |
| `AndroidManifest.xml` | No new permissions (`RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, both FGS types already declared). **Verify dual-FGS behavior on device.** |
| `v2/di/AppModule.kt` | Only if you add a dedicated overdub dispatcher (recommended, to avoid `@IoDispatcher` starvation during capture+mix). |

**Reused as-is:** `AudioPlaybackService`/`AudioPlayerNew` (monitoring), `createWavHeader`
(output WAV), `DecodeService`/`AudioDecoder` gains path (final waveform), `BrokenRecordRestorer`
(recovery), `FileDataSource`/`FileExtensions.kt` (temp files), `RecordTagWriter` (tag the result).

## 4. Hazard map

| Hazard | Severity | Mitigation |
|---|---|---|
| **Recorder split** — M4A/3GP can't be sample-mixed | High | Overdub capture path is **always PCM** via `OverdubRecorder`, independent of `prefs.settingRecordingFormat`; ignore `AudioRecorderDelegate` for overdub. |
| **No audio focus anywhere** — concurrent mic+playback unarbitrated; calls/notifications cause undefined routing | High | Add an `AudioFocusRequest` (API 26+) in the monitoring path with an `OnAudioFocusChangeListener` that pauses **both** capture and playback atomically on loss. |
| **Acoustic feedback** — mic picks up base playback through speaker | High | Default to recommending headphones; route monitoring to earpiece via the existing `AudioManagerHelper`; attach `AcousticEchoCanceller` if `AudioEffect` reports it available. Hybrid keeps the *recorded* layer clean regardless — feedback is a monitoring-UX problem, not data corruption. |
| **Dual foreground-service-type on API 33/34** — OS may downgrade/kill one | High | Device-verify both `microphone` + `mediaPlayback` FGS running together on API 33–35; fall back to a single unified service if downgraded. |
| **Sync drift / alignment** | Medium | Hybrid sidesteps real-time drift: record both as files, capture `playbackStartOffsetMills` at the instant `AudioRecord` starts, apply it as a fixed offset in `PcmMixer`. Verify with a click-track device test. |
| **Partial-file corruption on kill** | Medium | `OverdubRecorder` reuses the WAV in-place-header pattern; set `prefs.recordedRecordId` so `BrokenRecordRestorer` handles a killed capture; only `insertRecord` the final mix after `OverdubMixer` succeeds (delete temps on failure → no orphan records). |
| **Permissions revoked at runtime** | Medium | Check `RECORD_AUDIO` (+`MODIFY_AUDIO_SETTINGS`) before starting, via the existing launcher, not just at the UI layer. |
| **`@IoDispatcher` starvation during mix** | Low–Med | MVP outputs WAV (no encode). Run capture and mix on a dedicated dispatcher, not the shared `@IoDispatcher`. |
| **16-bit overflow when summing layers** | Low | `PcmMixer` clamps to `[-32768, 32767]`. Covered by unit test. |

## 5. Verifiability plan

**Pure unit tests (JVM, no device):**
- `PcmMixer` is the keystone — shipped with `PcmMixerTest`. Smallest proof: mix a 220 Hz base with
  a 660 Hz layer at half gain, assert both frequency components survive (correlation > 0) and no
  sample exceeds the 16-bit range. Plus summation, offset-alignment, clipping, gain, odd-byte cases.
- `Record`/`RecordEntity` mapper round-trips for the new `parentRecordId`/`isOverdub` fields.
- Room migration test (Room `MigrationTestHelper`) confirming the additive columns.

**Robolectric:** ViewModel action/event/state transitions and `ServiceConnection` wiring.

**Requires device/emulator (instrumented):**
- `OverdubRecorder` AudioRecord lifecycle + valid WAV output (real mic).
- `AudioDecoder` PCM-sink decoding a real M4A/3GP base to PCM.
- Concurrent playback+record actually running (the OS-feasibility question) on API 26, 33, 35.
- End-to-end: base click-track + recorded layer → mixed WAV → re-decode → assert alignment within
  tolerance.

The verifiability win of hybrid-offline: the entire correctness-critical mixing logic is provable
on CI with no device.

## 6. Open questions / risks (mostly resolved in the UX spec)

The product/UX questions (output format, headphone policy, pause-vs-stop, lineage display,
multi-layer, gain control, sample-rate policy) are answered in [`UX_SPEC.md`](./UX_SPEC.md) §6.

The one purely-technical risk that must be settled first:

> **Device-feasibility gate.** Before committing to UI, run a half-day spike proving `AudioRecord`
> capture works while `AudioPlaybackService` plays, with **both** foreground services alive, on
> API 26 / 33 / 35. If the OS blocks it on target devices, the whole feature must fall back to
> acoustic. This is the single biggest unknown — spike it **before** building anything else. Every
> other item in this plan is low-risk wiring once that's green.
