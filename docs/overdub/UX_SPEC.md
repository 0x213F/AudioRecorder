# Overdub — UX spec (v2, Compose/Material3)

Synthesized from independent journey proposals across five design lenses (minimal-friction,
dedicated studio, records-list-centric, safety-guided, iterative-takes).

**Verdict:** ship the **minimal in-place mode** as the spine, folded together with the **explicit
Save-or-Retry review** and **one-tap in-recording Redo**, and a **headphone gate that becomes a
mandatory acknowledgement only when on speaker**. Reject the standalone `OverdubScreen` route for
MVP (it duplicates the recorder surface Home already owns). Reject the always-on balance slider and
the live dual-waveform.

Every control cites a real file/line so it can be placed without guessing.

## 1. Recommended flow (one canonical path)

**PRIMARY ENTRY POINT — a new Overdub `IconButton` in the Home active-record transport row.**
In `RecordPlaybackPanel.kt` the prev/play/next `Row` (lines 134–168), placed **between `PlayPanel`
and the Next `IconButton`** → prev · [play/pause/stop] · **overdub** · next. Also wired into the
portrait `PlayPanel` block in `HomeScreen.kt` (lines 329–339).

*Why:* the base record is already loaded and the user is already looking at its transport controls,
so "listening → overdubbing" is one tap with zero navigation, and the result lands back in the same
panel so re-overdubbing is also one tap. Lowest-footprint option that still satisfies the engine.

**SECONDARY ENTRY POINTS (optional, cheap):**
- **Records list per-item overflow menu** — add `OVERDUB` to `RecordDropDownMenuItemId.kt:20`
  (between `SAVE_AS` and `DELETE`), surfaced via `getRecordsDroDownMenuItems()`
  (`RecordsExtensions.kt:28`). Selecting it sets that row active, returns Home, arms overdub.
- **Home TopAppBar overflow menu** — add `OVERDUB` to `HomeDropDownMenuItemId.kt:20` +
  `getHomeDroDownMenuItems()` (`HomeExtensions.kt:22`), handled in `onHomeMenuItemClick`
  (`HomeScreen.kt:245–273`).
- **Record Info screen** — explicitly OUT of MVP.

### Canonical step list

| # | Screen / dialog | UI elements | User action | System response |
|---|---|---|---|---|
| 1 | **Home, normal active-record** | `RecordPlaybackPanel` + new **Overdub IconButton** (`ic_overdub`), visible only when `isShowWaveform && !isRecording()` and an active record exists | Plays base, taps **Overdub** | Fires `OnOverdubClick`. On speaker (no headset) → step 2. Headset present **or** don't-ask set → step 3. |
| 2 | **OverdubHeadphoneDialog** | warning icon, title, body, live routing line, "Don't show again" checkbox, Continue / Cancel | Plugs in headphones or accepts risk; **Continue** | Persists don't-ask if checked; → step 3. Cancel → step 1, nothing created. |
| 3 | **Home, OVERDUB_ARMED** | base waveform dimmed/read-only; header **"Overdub of {name}"**; **routing chip** (Headphones ✓ / Speaker — feedback risk); BottomBar center = single red **Overdub** `CircleButton`; TopAppBar start shows **X (exit)** | Optionally scrubs base; taps **Overdub** circle | Requests `RECORD_AUDIO` via the existing launcher (`HomeScreen.kt:136–165`) if needed. On grant → base playback + mic capture start in sync from 0:00 → step 4. |
| 4 | **Home, OVERDUB_RECORDING** | playhead moves on base; running time; BottomBar center = **OverdubRecordingPanel** (red **Redo** + green **Stop & mix**); `KeepScreenOn` | Performs the layer; **Redo** to restart, **Stop & mix** when done | **Redo** (no dialog): discard mic buffer, reset base to 0:00 → step 3. **Stop & mix** or base end → close capture → step 5. |
| 5 | **Home, OVERDUB_MIXING** | the existing `ProgressPanel` (`HomeScreen.kt:572`) relabeled **"Mixing overdub…"**, gated by new `isShowMixingProgress`; controls hidden | Waits (brief) | Mix offline. Success → step 6. Failure → `ShowErrorSnack` "Mixing failed — your take was kept", return to Review with raw take intact. |
| 6 | **Home, OVERDUB_REVIEW** | mixed result loaded into `RecordPlaybackPanel` (audition it); BottomBar center = **OverdubReviewPanel**: outline **Retry** · green **Save** · red **Discard** | Plays the mix, then Save / Retry / Discard | **Save** → persist as new record "Overdub of {base}", set active, exit → step 7. **Retry** → discard mix, → step 3 same base. **Discard** → DiscardTakeConfirmDialog → exit. |
| 7 | **Home, normal active-record (result)** | new record's waveform + transport row **incl. Overdub button** (stack again); name "Overdub of {base}"; `ShowInfoSnack` "Overdub saved" | Play / share / rename / overdub again | The mix is a first-class record (play/share/rename/delete), also appears in Records list tagged "Overdub of {base}". Base untouched. |

**Note on Review (step 6):** the one deliberate departure from pure minimalism. The offline mix is
the expensive, surprising part of the flow, so an explicit Save/Retry/Discard gate prevents a
surprising auto-committed result and gives the cheap retry loop. No pre-record countdown and no
confirm-before-mix dialog — Review-after-mix covers the same safety need with one fewer interruption.

## 2. Concrete layout

**Overdub is an in-place MODE on `HomeScreen`. No new route. No bottom sheet.** It reuses the exact
recorder primitives Home already owns (synchronized playback, the `RecordingProgressPanel`
pause/stop pattern, the `ProgressPanel` processing pattern, the mic-permission launcher, the
waveform view, the snackbar event bus). A separate `OverdubScreen` would re-implement all of these
and risk drift. Mode is driven by a single new `overdubState` field on `HomeScreenState` plus three
new `BottomBarState` values.

1. **Overdub IconButton (new)** — `RecordPlaybackPanel.kt`, transport `Row` 134–168: insert after
   `PlayPanel` (150–157) + trailing `Spacer`, before Next `IconButton` (159). 42.dp `IconButton`,
   `ic_overdub` (layers icon), wrapped in `onDebounceClick`. Also wire into the portrait `PlayPanel`
   block in `HomeScreen.kt:329–339`. Visible when `isShowWaveform && !isRecording() && overdubState == Idle`.
2. **`BottomBarState` extension** — add `OVERDUB_ARMED, OVERDUB_RECORDING, OVERDUB_REVIEW`. Add three
   branches to `when (bottomBarState)` in `BottomBar` (`HomeComponents.kt:313–336`):
   - `OVERDUB_ARMED` → single red `CircleButton` text "Overdub".
   - `OVERDUB_RECORDING` → **OverdubRecordingPanel** (clone of `RecordingProgressPanel`: Redo
     IconButton + green Stop&mix circle).
   - `OVERDUB_REVIEW` → **OverdubReviewPanel** (clone of `RecordingPausePanel` 3-slot: Retry / Save /
     Discard).
   `BottomBar`'s signature gains `onOverdubClick`, `onOverdubRedoClick`, `onOverdubStopClick`,
   `onOverdubSaveClick`, `onOverdubRetryClick`, `onOverdubDiscardClick` (wired in `HomeScreen.kt:367–378`).
3. **Routing/monitoring chip (new)** — rendered in `statusPanels()` (`HomeScreen.kt:279–306`) while
   `overdubState != Idle`, reusing `BluetoothMicSelector`'s surface styling. States:
   Headphones (ok tint) / Bluetooth {name} / Speaker — feedback risk (warning tint).
4. **"Overdub of {name}" header** — reuse the `TimePanel` record-name slot (`HomeComponents.kt:558–586`);
   no new container. While armed/recording, swap `recordName` for the overdub label.
5. **X / exit affordance** — `TopAppBar` (`HomeComponents.kt:81`) start position, replacing the
   Import `IconButton` (96–109) while `overdubState != Idle`.
6. **"Mixing overdub…" panel** — reuse the existing private `ProgressPanel` (`HomeScreen.kt:572–596`)
   in `statusPanels()`, gated by new `isShowMixingProgress`, identical wiring to `isShowRecordProcessing`.
7. **Menu items** — `RecordDropDownMenuItemId.kt:20` (between SAVE_AS and DELETE) and
   `HomeDropDownMenuItemId.kt:20`; builders `RecordsExtensions.kt:28` / `HomeExtensions.kt:22`.

## 3. Dialogs & copy

All AlertDialogs follow `BrokenRecordDialog.kt` (warning icon in title Row, body 16.sp, confirm/
dismiss `TextButton`s). All strings go in `strings.xml`.

**A. OverdubHeadphoneDialog** (new file in `app/home/`)
- Title: **Use headphones**
- Body: *"Playing the base track through the speaker can feed back into your recording. For a clean
  overdub, plug in or pair headphones before you start."* + a live routing line:
  *"Output: Phone speaker"* / *"Output: Headphones ✓"*
- Checkbox: **Don't show again** (pattern from `UpdateNameAndDescriptionDialog`, `HomeScreen.kt:539`)
- Buttons: confirm **Start overdub** · dismiss **Cancel**
- Shown only when no headset is detected at overdub start and don't-ask is unset.

**B. Microphone permission** — system dialog via the existing `recordAudioPermissionLauncher`
(`HomeScreen.kt:136`). On denial, the existing `msg_permission_microphone_denied` snackbar.

**C. DiscardTakeConfirmDialog** (new, reuse `DeleteDialog` styling)
- Title: **Discard this take?**
- Body: *"Your recorded layer hasn't been saved. Discard it and leave overdub?"*
- Buttons: confirm **Discard** · dismiss **Keep editing**
- Shown when the user taps X or Discard while an unsaved take/mix exists. If no take recorded yet,
  exit silently with no dialog.

**D. Mixing progress** — not a dialog; the inline `ProgressPanel` relabeled **"Mixing overdub…"**.
No cancel in MVP.

**E. Mix-failed** — `ShowErrorSnack`: *"Mixing failed — your take was kept."* Returns to Review with
the raw take preserved.

**F. Result confirmation** — `ShowInfoSnack`: *"Overdub saved."* Rename uses the existing
`RenameAlertDialog`. Default name **"Overdub of {base}"** applied automatically at save.

## 4. State machine

One new field `overdubState` on `HomeScreenState`; `BottomBarState` carries the visible control set.

```
Idle (normal active record; Overdub button visible)
  │  OnOverdubClick
  ▼
ArmCheck (OverdubHeadphoneDialog) ──Cancel──▶ Idle
  │  Continue / headset present / don't-ask
  ▼
Armed  [BottomBarState.OVERDUB_ARMED]
   • base waveform dimmed, "Overdub of {name}", routing chip, red Overdub circle, X to exit
  │  tap Overdub → permission check
  ▼
Capturing  [BottomBarState.OVERDUB_RECORDING]
   • playhead moves on base, running time, Redo + Stop&mix
  │                         │ Redo → Armed (instant, no dialog)
  │ Stop&mix / base end
  ▼
Mixing  [isShowMixingProgress]
   • "Mixing overdub…" spinner, controls hidden
  │ success                          │ failure → ShowErrorSnack → Review (raw take kept)
  ▼
Review  [BottomBarState.OVERDUB_REVIEW]
   • mixed result auditioned in RecordPlaybackPanel; Retry / Save / Discard
  │ Save → Result        │ Retry → Armed (same base)     │ Discard → DiscardTakeConfirm → Idle
  ▼
Result = Idle with the new mixed record active + "Overdub saved" snackbar
```

Interrupt overlays from Capturing: **Paused-by-interruption** (auto-pause), **PermissionDenied**
(stay Armed). Exit (X / back) from Armed with no take → Idle silently; from Capturing/Review with a
take → DiscardTakeConfirm.

## 5. Edge cases

| Case | UX response |
|---|---|
| **Incoming call / audio-focus loss mid-capture** | Auto-pause base playback + mic capture. Snackbar "Recording paused". On focus return, stay paused in a resumable state; user resumes or Redo. Take preserved. |
| **App backgrounded mid-capture (ON_STOP)** | Treat as pause — `ComposableLifecycle` ON_STOP already wired (`HomeScreen.kt:114`). Hold the take; on ON_START return to paused Capturing. No silent loss. |
| **Headphones unplugged mid-capture** | Do **not** auto-stop. Flip routing chip to "Speaker — feedback risk" + transient snackbar. User decides to continue or Redo. |
| **Mic permission denied at arm** | Existing `msg_permission_microphone_denied` snackbar; stay Armed so user can grant and retry. No capture starts. |
| **Base record broken / lost** | Overdub entry disabled for broken/lost rows; if active record is broken when Overdub tapped, show the existing `BrokenRecordDialog` and do not arm. |
| **App killed during capture, before mix** | Captured WAV temp retained and surfaced on next launch via the existing lost/recovery path. Not silently dropped. |
| **Mixing fails (I/O, decode/storage)** | `ShowErrorSnack` "Mixing failed — your take was kept"; return to Review with the raw take intact. No broken record written. |
| **Low storage at save** | Check free space before mixing; block with an error snackbar before producing a truncated file. |
| **New layer vs base length** | Capture auto-stops at base end; mix length = base length. If stopped early, mix trimmed to recorded length. No timeline/offset UI (MVP). |
| **Overdubbing an overdub** | Fully allowed; result is "Overdub of {that record}". Layers flattened each mix; no depth limit. |

## 6. Resolved product decisions

| Question | MVP decision |
|---|---|
| **Headphone policy** | **Advisory, escalating to mandatory acknowledgement only on speaker.** Headset detected → skip dialog, show "ok" routing chip. On speaker → `OverdubHeadphoneDialog` with "Don't show again"; capture still permitted (user choice) but the warning routing chip persists through capture. |
| **Pause vs stop-only** | **Stop-only, plus one-tap Redo.** No user pause/resume during capture; "abandon and restart" (Redo) is the real need for a short take. (Interruptions still auto-pause internally — system-driven, not a user control.) |
| **Gain / balance control** | **No.** Mix at fixed sensible gain. A Base↔Mic slider bakes a choice in before the user hears the result, and Retry already lets them redo. Revisit post-MVP as a re-mix control. |
| **Lineage display** | **Tagged "Overdub of X".** Default name = "Overdub of {base}", shown in Records list subtitle + Record Info. No parent-link graph in MVP. |
| **Result auto-saved vs confirm** | **Confirm via Review.** After mixing, audition then explicitly Save / Retry / Discard. The one accepted extra step, because the offline mix is the expensive/surprising part. |

## 7. Scope cutline (explicitly OUT of MVP)

- No standalone `OverdubScreen` route — overdub is an in-place Home mode; do not touch `Routes.kt`.
- Multi-layer / true multitrack — only one base + one new mic layer per pass (stack by overdubbing
  the result again, flattened each time). No per-layer mute/solo.
- Live dual-waveform (base + incoming layer as two traces) — show only the base waveform + playhead.
- Live monitoring/VU meter — the routing chip is the only live indicator.
- Base↔Mic balance slider and post-hoc re-mix.
- 3-2-1 count-in.
- Confirm-before-mix / pre-roll dialog — Review-after-mix covers it.
- Pause/Resume during capture (Stop + Redo only).
- Cancel button during Mixing.
- Record Info entry point and multi-select action-bar entry.
- Offset / trim / tail-beyond-base / layer time-shifting — layer anchored to base start, auto-stopped
  at base end.
- M4A or any non-WAV overdub output — mix output stays WAV; format conversion is out.
