/*
 * Copyright 2026 Dmytro Ponomarenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dimowner.audiorecorder.v2.app.overdub

/**
 * State / action / event contract for the overdub flow, mirroring the V2 ViewModel convention
 * (immutable state via mutableStateOf, one-shot events via SharedFlow, actions via onAction).
 */

/** Where the overdub flow currently is. See UX spec §4 (state machine). */
enum class OverdubStage {
    LOADING,    // fetching the base record
    ARMED,      // ready; tapping record starts capture
    CAPTURING,  // base plays for monitoring while the mic layer records
    MIXING,     // offline mix in progress
    REVIEW,     // auditioning the mixed result; Save / Retry / Discard
    SAVING,     // persisting the result
}

/** How the base track is being monitored — drives the routing chip and feedback warning. */
enum class MonitoringRoute {
    SPEAKER,     // feedback risk
    HEADPHONES,
    BLUETOOTH,
}

data class OverdubState(
    val baseRecordId: Long = -1L,
    val baseRecordName: String = "",
    val baseDurationMills: Long = 0L,
    val stage: OverdubStage = OverdubStage.LOADING,
    /** Running capture time while CAPTURING; playback position while REVIEW. */
    val positionMills: Long = 0L,
    val isReviewPlaying: Boolean = false,
    val monitoring: MonitoringRoute = MonitoringRoute.SPEAKER,
    val showHeadphoneDialog: Boolean = false,
    val showDiscardDialog: Boolean = false,
) {
    val isFeedbackRisk: Boolean get() = monitoring == MonitoringRoute.SPEAKER
}

sealed class OverdubAction {
    /** Arm screen → request permission / begin capture. */
    object OnRecordClick : OverdubAction()
    object OnPermissionGranted : OverdubAction()
    object OnPermissionDenied : OverdubAction()
    object OnStopClick : OverdubAction()
    object OnRedoClick : OverdubAction()
    object OnSaveClick : OverdubAction()
    object OnRetryClick : OverdubAction()
    object OnDiscardClick : OverdubAction()
    object OnExitClick : OverdubAction()
    object OnToggleReviewPlay : OverdubAction()
    object DismissHeadphoneDialog : OverdubAction()
    data class ConfirmHeadphoneDialog(val dontAskAgain: Boolean) : OverdubAction()
    object DismissDiscardDialog : OverdubAction()
    object ConfirmDiscard : OverdubAction()
}

sealed class OverdubEvent {
    /** Mix saved as a new record; navigate back so Home shows it as active. */
    data class Saved(val recordId: Long) : OverdubEvent()
    /** Leave overdub with nothing saved. */
    object Exit : OverdubEvent()
    /** Ask the screen to launch the RECORD_AUDIO permission request. */
    object RequestRecordPermission : OverdubEvent()
    data class ShowError(val messageRes: Int) : OverdubEvent()
}
