# Overdub feature — design package

This folder holds the design + implementation package for adding an **Overdub** feature to
the Audio Recorder app: record a new audio layer in time with an existing recording, then
save the two **mixed** together as a new record.

It was produced by a fan-out of research/design agents over the real V2 codebase, then
synthesized. Everything here cites real files so it can be implemented without re-discovery.

## Contents

| File | What it is |
|---|---|
| [`TECHNICAL_DESIGN.md`](./TECHNICAL_DESIGN.md) | How to build it: difficulty, chosen strategy, exact change set, hazard map, verifiability plan. |
| [`UX_SPEC.md`](./UX_SPEC.md) | The user journey: entry points, button placement, dialogs + copy, state machine, edge cases, resolved product decisions, scope cutline. |

## The one-paragraph version

Overdub is **LARGE** but tractable because every seam it needs already exists. The recorder
is split — M4A/3GP go through `MediaRecorder` (encodes straight to a container, **cannot be
sample-mixed**), only WAV uses `AudioRecord`→raw PCM. So the overdub **capture path is always
PCM**, independent of the user's normal format setting. The chosen strategy is
**hybrid offline-mix**: while the base track plays back for monitoring, capture the new layer
as its own WAV; on stop, mix the two files **offline** on a background thread, output WAV,
persist as a new record, and let the existing `DecodeService` regenerate the waveform. The
mixing step is therefore **pure, deterministic, file-in/file-out math** that runs with no audio
hardware — which is what makes the feature unit-testable on CI. UX-wise it is an **in-place mode
on `HomeScreen`** (no new screen): an Overdub button in the active-record transport row drives an
`Armed → Capturing → Mixing → Review → Result` flow with an explicit Save/Retry/Discard review.

## What is in this branch as working code

- **`v2/audio/overdub/PcmMixer.kt`** — the keystone: pure 16-bit-PCM mixer (sum + per-layer gain
  + clipping clamp + time-offset alignment). No Android/coroutine/file dependencies.
- **`v2/audio/overdub/PcmMixerTest.kt`** (test source set) — JUnit4 tests proving summation,
  clipping, alignment, gain, odd-byte robustness, and a two-tone "both layers survive" check.

Run the test with:

```bash
./gradlew testDebugConfigDebugUnitTest --tests "*PcmMixerTest"
```

## Verification status — read this

The `PcmMixer` logic was authored and hand-traced for correctness, **but nothing in this branch
was compiled or run in the environment that produced it** (no JDK / Kotlin / Android SDK was
available there). Before relying on it:

1. Run the unit test above.
2. Treat the remaining integration code in `TECHNICAL_DESIGN.md` §3 / `UX_SPEC.md` §2 as
   **paste-ready specifications, not yet-compiled files** — they were deliberately kept out of the
   source tree so a not-yet-compiled file (Hilt/Room annotation processing scans everything) can't
   break your build. Implement them screen-by-screen and compile as you go.
3. The single biggest unknown is the **device-feasibility spike**: confirm `AudioRecord` capture
   works while `AudioPlaybackService` plays, with both foreground services alive, on API 26 / 33 /
   35. Do this **first** — see `TECHNICAL_DESIGN.md` §6.
