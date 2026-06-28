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

package com.dimowner.audiorecorder.v2.audio.overdub

import android.media.AudioFormat
import android.media.AudioRecord
import com.dimowner.audiorecorder.AppConstants.RECORDING_VISUALIZATION_INTERVAL_NEW
import com.dimowner.audiorecorder.audio.sumOfAmplitudes
import com.dimowner.audiorecorder.exception.AlreadyRecordingException
import com.dimowner.audiorecorder.exception.InvalidOutputFile
import com.dimowner.audiorecorder.exception.RecorderInitException
import com.dimowner.audiorecorder.v2.audio.RecorderEvent
import com.dimowner.audiorecorder.v2.audio.RecorderV2
import com.dimowner.audiorecorder.v2.audio.createWavHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures the new overdub layer as a standalone 16-bit PCM/WAV file.
 *
 * It is a focused fork of [com.dimowner.audiorecorder.v2.audio.WavRecorderV2]: same proven
 * AudioRecord read-loop and in-place WAV-header rewrite, but deliberately **independent of**
 * `AudioRecorderDelegate` and `prefs.settingRecordingFormat`. Overdub must always capture raw PCM
 * (so it can be mixed by [PcmMixer]) regardless of the user's normal recording format — for an M4A
 * user the delegate would otherwise return a MediaRecorder, whose encoded output cannot be mixed.
 *
 * Pause/resume are intentionally not supported for overdub capture (the UX is stop + redo only);
 * the corresponding [RecorderV2] methods are no-ops here.
 *
 * Time alignment of the layer against the base track is the caller's responsibility: the
 * ViewModel records the base player's position at the moment [startRecording] is invoked and passes
 * it to [OverdubMixer] as a leading offset. This class only produces a clean WAV of the mic input.
 */
@Singleton
class OverdubRecorder @Inject constructor(
    private val coroutineScope: CoroutineScope,
) : RecorderV2 {

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    @Volatile private var _isRecording: Boolean = false

    override val isRecording: Boolean
        get() = _isRecording

    // Overdub capture is stop-or-redo only; there is no user-facing pause.
    override val isPaused: Boolean
        get() = false

    private var sampleRateConfig: Int = 44100
    private var channelCountConfig: Int = 1
    private var maxDurationMills: Int = Int.MAX_VALUE

    @Volatile private var lastNonZeroAmplitude: Int = 0

    private val _event = MutableSharedFlow<RecorderEvent>()
    override fun subscribeRecorderEvents(): Flow<RecorderEvent> = _event

    /**
     * Starts capturing the mic to [outputFile] as a WAV.
     *
     * Callers should pass the **base record's** [channelCount] and [sampleRate] so the captured
     * layer lines up positionally with the decoded base PCM and [PcmMixer] can mix without a
     * resampler or channel re-map. [bitrate] and [audioSource] mirror the [RecorderV2] contract;
     * bitrate is ignored for PCM.
     */
    override fun startRecording(
        outputFile: File,
        channelCount: Int,
        sampleRate: Int,
        bitrate: Int,
        maxRecordingDurationMills: Int,
        audioSource: Int,
    ): Boolean {
        Timber.d(
            "Overdub startRecording outputFile: ${outputFile.absolutePath} channelCount: $channelCount" +
                " sampleRate: $sampleRate maxRecordingDurationMills: $maxRecordingDurationMills" +
                " audioSource: $audioSource"
        )
        if (_isRecording) {
            Timber.e("Overdub recording is already in progress.")
            emitEvent(RecorderEvent.OnError(AlreadyRecordingException()))
            return false
        }
        if (!outputFile.exists() || !outputFile.isFile) {
            emitEvent(RecorderEvent.OnError(InvalidOutputFile()))
            return false
        }
        amplitudesBuffer.clear()
        lastNonZeroAmplitude = 0

        sampleRateConfig = sampleRate
        channelCountConfig = channelCount
        maxDurationMills = maxRecordingDurationMills

        val channelConfig = if (channelCount == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }
        val bitsPerSample = 16
        val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Timber.e("Invalid buffer size: $bufferSize")
            emitEvent(RecorderEvent.OnError(RecorderInitException()))
            return false
        }

        // Read in ~20 ms chunks so duration advances and progress events fire at least that often.
        val frameSize = channelCount * (bitsPerSample / 8)
        val readChunkSize = ((sampleRate * RECORDING_VISUALIZATION_INTERVAL_NEW / 1000) * frameSize)
            .coerceAtLeast(frameSize)
            .coerceAtMost(bufferSize)

        val recorder = try {
            AudioRecord(audioSource, sampleRate, channelConfig, audioEncoding, bufferSize)
        } catch (e: SecurityException) {
            Timber.e(e, "AudioRecord creation failed due to missing permission")
            emitEvent(RecorderEvent.OnError(RecorderInitException()))
            return false
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "AudioRecord creation failed")
            emitEvent(RecorderEvent.OnError(RecorderInitException()))
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("AudioRecord initialization failed")
            recorder.release()
            emitEvent(RecorderEvent.OnError(RecorderInitException()))
            return false
        }
        audioRecord = recorder

        // Placeholder 44-byte WAV header; overwritten with real sizes when capture stops.
        try {
            FileOutputStream(outputFile).use { fos -> fos.write(ByteArray(44)) }
        } catch (e: IOException) {
            Timber.e(e, "Failed to write placeholder WAV header")
            recorder.release()
            audioRecord = null
            emitEvent(RecorderEvent.OnError(RecorderInitException()))
            return false
        }

        try {
            recorder.startRecording()
        } catch (e: IllegalStateException) {
            Timber.e(e, "startRecording() failed")
            recorder.release()
            audioRecord = null
            emitEvent(RecorderEvent.OnError(RecorderInitException()))
            return false
        }

        _isRecording = true
        emitEvent(RecorderEvent.OnStartRecording)

        recordingJob = coroutineScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            var fos: FileOutputStream? = null
            var totalBytesWritten = 0L
            val bytesPerSecond = sampleRate * channelCount * (bitsPerSample / 8)
            var maxDurationReached = false
            var lastEmittedDurationMills = -1L

            try {
                fos = FileOutputStream(outputFile, true) // append after the placeholder header
                while (isActive && _isRecording) {
                    val readResult = recorder.read(buffer, 0, readChunkSize)
                    if (readResult > 0) {
                        fos.write(buffer, 0, readResult)
                        totalBytesWritten += readResult

                        val durationMills = (totalBytesWritten * 1000L) / bytesPerSecond

                        var amp = calculateAmplitude(buffer, readResult)
                        if (amp == 0) amp = lastNonZeroAmplitude else lastNonZeroAmplitude = amp
                        if (durationMills != lastEmittedDurationMills) {
                            lastEmittedDurationMills = durationMills
                            emitEvent(RecorderEvent.OnRecordingProgress(durationMills, amp))
                        }

                        if (maxDurationMills > 0 && durationMills >= maxDurationMills) {
                            Timber.d("Overdub max duration reached. Stop capture")
                            maxDurationReached = true
                            _isRecording = false
                            stopHardware()
                            break
                        }
                    } else if (readResult == AudioRecord.ERROR_INVALID_OPERATION) {
                        Timber.e("AudioRecord read error: ERROR_INVALID_OPERATION")
                        break
                    } else if (readResult == AudioRecord.ERROR_BAD_VALUE) {
                        Timber.e("AudioRecord read error: ERROR_BAD_VALUE")
                        break
                    }
                }
            } catch (e: IOException) {
                Timber.e(e, "Error writing overdub PCM data")
                emitEvent(RecorderEvent.OnError(RecorderInitException()))
            } finally {
                try {
                    fos?.close()
                } catch (e: IOException) {
                    Timber.e(e, "Error closing overdub output stream")
                }
            }

            // Write the real WAV header in-place now that the final length is known.
            if (outputFile.exists()) {
                try {
                    val totalAudioLen = totalBytesWritten
                    val totalDataLen = totalAudioLen + 36
                    val byteRate = (sampleRateConfig * channelCountConfig * bitsPerSample / 8).toLong()
                    RandomAccessFile(outputFile, "rw").use { raf ->
                        raf.seek(0)
                        val headerStream = FileOutputStream(raf.fd)
                        headerStream.write(
                            createWavHeader(
                                totalAudioLen = totalAudioLen,
                                totalDataLen = totalDataLen,
                                sampleRate = sampleRateConfig,
                                channels = channelCountConfig,
                                byteRate = byteRate,
                            )
                        )
                        headerStream.flush()
                    }
                    if (maxDurationReached) {
                        emitEvent(RecorderEvent.OnMaxDurationReached)
                    } else {
                        emitEvent(RecorderEvent.OnStopRecording)
                    }
                } catch (e: IOException) {
                    Timber.e(e, "Error writing overdub WAV header")
                    emitEvent(RecorderEvent.OnError(RecorderInitException()))
                }
            }
        }
        return true
    }

    override fun stopRecording(): Boolean {
        if (!_isRecording) {
            Timber.e("Overdub recording has already stopped or hasn't started")
            return false
        }
        _isRecording = false
        // Tearing down the hardware lets the capture coroutine finish its current read(), flush,
        // rewrite the WAV header in-place, and then emit OnStopRecording.
        return stopHardware()
    }

    // Overdub capture has no user-facing pause/resume — stop + redo only (see class doc).
    override fun pauseRecording(): Boolean = false

    override fun resumeRecording(): Boolean = false

    private fun stopHardware(): Boolean {
        return try {
            audioRecord?.let {
                it.stop()
                it.release()
                true
            } ?: false
        } catch (e: IllegalStateException) {
            Timber.e(e, "stopHardware() problems")
            audioRecord?.release()
            false
        } finally {
            audioRecord = null
        }
    }

    private fun calculateAmplitude(buffer: ByteArray, bytesRead: Int): Int {
        if (bytesRead <= 0) return 0
        val sum = buffer.sumOfAmplitudes(bytesRead)
        return (sum / (bytesRead / 16 + 1)).toInt()
    }

    private fun emitEvent(event: RecorderEvent) {
        coroutineScope.launch { _event.emit(event) }
    }
}
