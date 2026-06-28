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

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dimowner.audiorecorder.ARApplication
import com.dimowner.audiorecorder.AppConstants
import com.dimowner.audiorecorder.R
import com.dimowner.audiorecorder.app.DecodeService
import com.dimowner.audiorecorder.audio.player.AudioPlayerNew
import com.dimowner.audiorecorder.audio.player.PlayerContractNew
import com.dimowner.audiorecorder.exception.AppException
import com.dimowner.audiorecorder.v2.audio.RecorderEvent
import com.dimowner.audiorecorder.v2.audio.overdub.OverdubMixer
import com.dimowner.audiorecorder.v2.audio.overdub.OverdubRecorder
import com.dimowner.audiorecorder.v2.data.FileDataSource
import com.dimowner.audiorecorder.v2.data.PrefsV2
import com.dimowner.audiorecorder.v2.data.RecordsDataSource
import com.dimowner.audiorecorder.v2.data.model.Record
import com.dimowner.audiorecorder.v2.data.model.RecordingFormat
import com.dimowner.audiorecorder.v2.di.qualifiers.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Drives the overdub flow end to end (see UX spec): monitor the base track while capturing a new
 * mic layer, mix offline on stop, audition, and save the result as a new record.
 *
 * Architecture note: this is a dedicated, self-contained screen + ViewModel rather than an in-place
 * mode on HomeScreen. The UX spec preferred an in-place mode, but HomeViewModel/HomeScreen are very
 * large; isolating overdub here keeps the feature contained and reviewable and matches the recon's
 * code-organization recommendation. The entry point on Home simply navigates to this screen.
 *
 * MVP simplifications (documented in docs/overdub):
 *  - Monitoring uses a private [AudioPlayerNew] instance (no cross-talk with Home's shared player).
 *  - Capture runs without a dedicated foreground service; the screen keeps the screen on. A killed
 *    capture loses the take. Moving capture to a foreground service is a follow-up.
 *  - Capture starts at the base's 0:00, so the mix offset is 0.
 *  - The "don't ask again" headphone choice is session-scoped (no PrefsV2 change yet).
 *  - Lineage is encoded in the record name ("Overdub of X"); no parentRecordId column yet.
 */
@HiltViewModel
class OverdubViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val recordsDataSource: RecordsDataSource,
    private val fileDataSource: FileDataSource,
    private val prefs: PrefsV2,
    private val overdubRecorder: OverdubRecorder,
    private val overdubMixer: OverdubMixer,
) : ViewModel() {

    private val _state = mutableStateOf(OverdubState())
    val state: State<OverdubState> = _state

    private val _event = MutableSharedFlow<OverdubEvent>(extraBufferCapacity = 1)
    val event: SharedFlow<OverdubEvent> = _event.asSharedFlow()

    /** Private monitoring/audition player so we never disturb Home's shared player. */
    private val player: PlayerContractNew.Player = AudioPlayerNew()

    private var baseRecord: Record? = null
    private var layerFile: File? = null
    private var resultFile: File? = null
    private var mixResult: OverdubMixer.Result? = null
    private var dontAskHeadphones: Boolean = false
    private var initialized: Boolean = false

    init {
        subscribeRecorderEvents()
        subscribePlayer()
    }

    /** Called once from the screen with the base record id from the nav argument. */
    fun init(baseRecordId: Long) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            val record = withContext(ioDispatcher) { recordsDataSource.getRecord(baseRecordId) }
            if (record == null) {
                emit(OverdubEvent.ShowError(R.string.error_unknown))
                emit(OverdubEvent.Exit)
                return@launch
            }
            baseRecord = record
            _state.value = _state.value.copy(
                baseRecordId = baseRecordId,
                baseRecordName = record.name,
                baseDurationMills = record.durationMills,
                stage = OverdubStage.ARMED,
                monitoring = detectMonitoring(),
            )
        }
    }

    fun onAction(action: OverdubAction) {
        when (action) {
            OverdubAction.OnRecordClick -> onRecordClick()
            OverdubAction.OnPermissionGranted -> startCapture()
            OverdubAction.OnPermissionDenied -> { /* stay armed; screen shows a snackbar */ }
            OverdubAction.OnStopClick -> stopCapture()
            OverdubAction.OnRedoClick -> redo()
            OverdubAction.OnSaveClick -> saveResult()
            OverdubAction.OnRetryClick -> retry()
            OverdubAction.OnDiscardClick -> onDiscardClick()
            OverdubAction.OnExitClick -> onExitClick()
            OverdubAction.OnToggleReviewPlay -> toggleReviewPlay()
            OverdubAction.DismissHeadphoneDialog ->
                _state.value = _state.value.copy(showHeadphoneDialog = false)
            is OverdubAction.ConfirmHeadphoneDialog -> {
                dontAskHeadphones = action.dontAskAgain
                _state.value = _state.value.copy(showHeadphoneDialog = false)
                emit(OverdubEvent.RequestRecordPermission)
            }
            OverdubAction.DismissDiscardDialog ->
                _state.value = _state.value.copy(showDiscardDialog = false)
            OverdubAction.ConfirmDiscard -> {
                _state.value = _state.value.copy(showDiscardDialog = false)
                cleanupTransient()
                emit(OverdubEvent.Exit)
            }
        }
    }

    private fun onRecordClick() {
        // Refresh routing in case headphones were (un)plugged on the arm screen.
        val monitoring = detectMonitoring()
        _state.value = _state.value.copy(monitoring = monitoring)
        if (monitoring == MonitoringRoute.SPEAKER && !dontAskHeadphones) {
            _state.value = _state.value.copy(showHeadphoneDialog = true)
        } else {
            emit(OverdubEvent.RequestRecordPermission)
        }
    }

    private fun startCapture() {
        val record = baseRecord ?: return
        try {
            val layer = File(context.cacheDir, "overdub_layer_${System.nanoTime()}.wav").also {
                it.delete()
                it.createNewFile()
            }
            layerFile = layer
            val channelCount = record.channelCount.coerceAtLeast(1)
            val sampleRate = if (record.sampleRate > 0) record.sampleRate else AppConstants.RECORD_SAMPLE_RATE_44100
            val maxDuration = record.durationMills
                .coerceAtMost(AppConstants.RECORD_MAX_DURATION.toLong())
                .coerceAtLeast(1L)
                .toInt()

            // Start base playback for monitoring (from 0), then begin mic capture in sync.
            player.play(record.path)
            val started = overdubRecorder.startRecording(
                outputFile = layer,
                channelCount = channelCount,
                sampleRate = sampleRate,
                bitrate = 0,
                maxRecordingDurationMills = maxDuration,
                audioSource = MediaRecorder.AudioSource.MIC,
            )
            if (started) {
                _state.value = _state.value.copy(stage = OverdubStage.CAPTURING, positionMills = 0L)
            } else {
                player.stop()
                emit(OverdubEvent.ShowError(R.string.error_unknown))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start overdub capture")
            emit(OverdubEvent.ShowError(R.string.error_unknown))
        }
    }

    private fun stopCapture() {
        if (_state.value.stage != OverdubStage.CAPTURING) return
        player.stop()
        // OverdubRecorder emits OnStopRecording when the WAV is finalized → startMixing().
        overdubRecorder.stopRecording()
        _state.value = _state.value.copy(stage = OverdubStage.MIXING)
    }

    private fun redo() {
        if (_state.value.stage != OverdubStage.CAPTURING) return
        player.stop()
        // Move to ARMED first so the pending OnStopRecording (which fires after the recorder
        // finishes writing the WAV header) is ignored and does not start a mix.
        _state.value = _state.value.copy(stage = OverdubStage.ARMED, positionMills = 0L)
        overdubRecorder.stopRecording()
        // Do NOT delete the layer file here: the recorder coroutine is still finalizing it after
        // stopRecording() returns. The next startCapture() allocates a fresh file; the orphan is a
        // cache temp the OS reclaims. onCleared() deletes the current layer reference.
    }

    private fun startMixing() {
        val record = baseRecord ?: return
        val layer = layerFile ?: return
        // Ensure monitoring playback is stopped (covers auto-stop at base end as well as manual stop).
        player.stop()
        viewModelScope.launch {
            try {
                val output = withContext(ioDispatcher) {
                    fileDataSource.createRecordFile(overdubFileName(record.name))
                }
                resultFile = output
                val result = overdubMixer.mix(
                    baseFile = File(record.path),
                    layerWavFile = layer,
                    outputWavFile = output,
                    playbackStartOffsetMills = 0L,
                    workDir = context.cacheDir,
                )
                mixResult = result
                _state.value = _state.value.copy(
                    stage = OverdubStage.REVIEW,
                    positionMills = 0L,
                    isReviewPlaying = false,
                )
            } catch (e: Exception) {
                Timber.e(e, "Overdub mix failed")
                cleanupResult()
                emit(OverdubEvent.ShowError(R.string.error_unknown))
                // Mix failed: drop back to ARMED so the user can record a fresh take. (The raw
                // captured layer is kept on disk but not reused; a future enhancement could offer
                // "retry mix" without re-recording.)
                _state.value = _state.value.copy(stage = OverdubStage.ARMED)
            }
        }
    }

    private fun toggleReviewPlay() {
        val result = resultFile ?: return
        if (_state.value.isReviewPlaying) {
            player.pause()
            _state.value = _state.value.copy(isReviewPlaying = false)
        } else {
            if (player.isPaused()) player.unpause() else player.play(result.absolutePath)
            _state.value = _state.value.copy(isReviewPlaying = true)
        }
    }

    private fun saveResult() {
        val record = baseRecord ?: return
        val output = resultFile ?: return
        val meta = mixResult ?: return
        player.stop()
        _state.value = _state.value.copy(stage = OverdubStage.SAVING, isReviewPlaying = false)
        viewModelScope.launch {
            try {
                val newId = withContext(ioDispatcher) {
                    val newRecord = Record(
                        id = 0,
                        name = overdubRecordName(record.name),
                        durationMills = meta.durationMills,
                        created = output.lastModified(),
                        added = System.currentTimeMillis(),
                        removed = Long.MAX_VALUE,
                        path = output.absolutePath,
                        format = RecordingFormat.Wav.value,
                        size = output.length(),
                        sampleRate = meta.sampleRate,
                        channelCount = meta.channelCount,
                        bitrate = 0,
                        isBookmarked = false,
                        isWaveformProcessed = false,
                        isMovedToRecycle = false,
                        amps = IntArray(ARApplication.longWaveformSampleCount),
                        description = overdubRecordName(record.name),
                    )
                    recordsDataSource.insertRecord(newRecord)
                }
                prefs.activeRecordId = newId
                prefs.recordedRecordId = -1
                // Let the existing decode pipeline produce the real waveform, exactly like a
                // normal recording.
                DecodeService.startNotificationV2(context, newId, output.absolutePath, meta.durationMills)
                layerFile?.delete()
                emit(OverdubEvent.Saved(newId))
            } catch (e: Exception) {
                Timber.e(e, "Failed to save overdub")
                emit(OverdubEvent.ShowError(R.string.error_unknown))
                _state.value = _state.value.copy(stage = OverdubStage.REVIEW)
            }
        }
    }

    private fun retry() {
        player.stop()
        cleanupResult()
        cleanupLayer()
        _state.value = _state.value.copy(
            stage = OverdubStage.ARMED,
            positionMills = 0L,
            isReviewPlaying = false,
        )
    }

    private fun onDiscardClick() {
        if (hasUnsavedWork()) {
            _state.value = _state.value.copy(showDiscardDialog = true)
        } else {
            emit(OverdubEvent.Exit)
        }
    }

    private fun onExitClick() {
        if (hasUnsavedWork()) {
            _state.value = _state.value.copy(showDiscardDialog = true)
        } else {
            cleanupTransient()
            emit(OverdubEvent.Exit)
        }
    }

    private fun hasUnsavedWork(): Boolean {
        val stage = _state.value.stage
        return stage == OverdubStage.CAPTURING ||
            stage == OverdubStage.REVIEW ||
            stage == OverdubStage.MIXING
    }

    private fun subscribeRecorderEvents() {
        viewModelScope.launch {
            overdubRecorder.subscribeRecorderEvents().collect { e ->
                when (e) {
                    is RecorderEvent.OnRecordingProgress ->
                        if (_state.value.stage == OverdubStage.CAPTURING) {
                            _state.value = _state.value.copy(positionMills = e.durationMills)
                        }
                    RecorderEvent.OnStopRecording, RecorderEvent.OnMaxDurationReached ->
                        // Begin mixing only when a stop/auto-stop concludes a capture we still own.
                        if (_state.value.stage == OverdubStage.MIXING ||
                            _state.value.stage == OverdubStage.CAPTURING
                        ) {
                            _state.value = _state.value.copy(stage = OverdubStage.MIXING)
                            startMixing()
                        }
                    is RecorderEvent.OnError -> {
                        emit(OverdubEvent.ShowError(R.string.error_unknown))
                        _state.value = _state.value.copy(stage = OverdubStage.ARMED)
                    }
                    else -> { /* start/pause/resume: ignored for overdub */ }
                }
            }
        }
    }

    private fun subscribePlayer() {
        player.addPlayerCallback(object : PlayerContractNew.PlayerCallback {
            override fun onStartPlay() {}
            override fun onPlayProgress(mills: Long) {
                if (_state.value.stage == OverdubStage.REVIEW) {
                    _state.value = _state.value.copy(positionMills = mills)
                }
            }
            override fun onPausePlay() {}
            override fun onSeek(mills: Long) {}
            override fun onStopPlay() {
                if (_state.value.stage == OverdubStage.REVIEW) {
                    _state.value = _state.value.copy(isReviewPlaying = false, positionMills = 0L)
                }
            }
            override fun onError(throwable: AppException) {
                Timber.e(throwable, "Overdub monitoring player error")
            }
        })
    }

    private fun detectMonitoring(): MonitoringRoute {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (d in devices) {
                when (d.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> return MonitoringRoute.HEADPHONES
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> return MonitoringRoute.BLUETOOTH
                }
            }
            MonitoringRoute.SPEAKER
        } catch (e: Exception) {
            Timber.e(e, "Failed to detect monitoring route")
            MonitoringRoute.SPEAKER
        }
    }

    private fun overdubRecordName(baseName: String): String =
        context.getString(R.string.overdub_of_record, baseName)

    private fun overdubFileName(baseName: String): String {
        val safe = baseName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        return "${safe}_overdub.wav"
    }

    private fun cleanupLayer() {
        layerFile?.let { if (it.exists()) it.delete() }
        layerFile = null
    }

    private fun cleanupResult() {
        resultFile?.let { if (it.exists()) it.delete() }
        resultFile = null
        mixResult = null
    }

    private fun cleanupTransient() {
        cleanupLayer()
        cleanupResult()
    }

    private fun emit(e: OverdubEvent) {
        viewModelScope.launch { _event.emit(e) }
    }

    override fun onCleared() {
        super.onCleared()
        if (overdubRecorder.isRecording) overdubRecorder.stopRecording()
        player.release()
        cleanupTransient()
    }
}
