package com.xestlab.assistant.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.xestlab.assistant.data.SecurePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class WakeWordAudioSource(val androidSource: Int, val label: String) {
    MIC(MediaRecorder.AudioSource.MIC, "MIC"),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED")
}

data class WakeWordLiveTelemetry(
    val micLevel: Int,
    val audioFramesReceived: Long,
    val currentScore: Float,
    val peakScore: Float,
    val audioSourceLabel: String,
    val isSilentFor5Seconds: Boolean
)

/**
 * Resilient 3-stage openWakeWord ONNX Runtime preprocessing & wake-word detection pipeline:
 *
 * - Tries audio sources strictly in order: `MIC` -> `VOICE_RECOGNITION` -> `UNPROCESSED`.
 * - Computes live microphone level (0..100) and emits telemetry 5 times per second (every 200ms).
 * - If mic level stays at 0 for 5 seconds, flags silent-mic warning and restarts AudioRecord with the next source.
 * - Default threshold `0.35`, triggering when 2 of the last 3 ONNX scores exceed the threshold.
 */
class OpenWakeWordPipeline(context: Context) {

    companion object {
        private const val TAG = "OpenWakeWordPipeline"

        const val MEL_MODEL_ASSET = "melspectrogram.onnx"
        const val EMBEDDING_MODEL_ASSET = "embedding_model.onnx"
        const val WAKE_WORD_MODEL_ASSET = "hey_jarvis_v0.1.onnx"

        const val SAMPLE_RATE_HZ = 16_000
        const val CHUNK_SAMPLES = 1_280          // 80ms @ 16kHz
        const val OVERLAP_SAMPLES = 480          // 3 * 160-sample STFT hop overlap -> 8 mel frames/chunk
        const val MEL_INPUT_SAMPLES = OVERLAP_SAMPLES + CHUNK_SAMPLES // 1760 samples

        const val MEL_BINS = 32
        const val MEL_WINDOW_FRAMES = 76
        const val EMBEDDING_DIM = 96
        const val DEFAULT_CLASSIFIER_FRAMES = 16

        const val TRIGGER_THRESHOLD = SecurePreferences.DEFAULT_WAKE_WORD_THRESHOLD
        private const val TELEMETRY_INTERVAL_MS = 200L
        private const val SILENT_MIC_TIMEOUT_MS = 5_000L

        val ORDERED_AUDIO_SOURCES = listOf(
            WakeWordAudioSource.MIC,
            WakeWordAudioSource.VOICE_RECOGNITION,
            WakeWordAudioSource.UNPROCESSED
        )
    }

    private val appContext = context.applicationContext
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    private var melSession: OrtSession? = null
    private var embeddingSession: OrtSession? = null
    private var wakeWordSession: OrtSession? = null

    private var melInputName: String = "input"
    private var embeddingInputName: String = "input_1"
    private var wakeWordInputName: String = "x.1"
    private var classifierFrames: Int = DEFAULT_CLASSIFIER_FRAMES

    private val listeningState = AtomicBoolean(false)

    val isListening: Boolean
        get() = listeningState.get()

    @Volatile
    private var activeAudioRecord: AudioRecord? = null

    @Synchronized
    fun ensureModelsLoaded() {
        if (melSession != null && embeddingSession != null && wakeWordSession != null) {
            return
        }

        closeSessionsInternal()

        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        try {
            val melBytes = appContext.assets.open(MEL_MODEL_ASSET).use { it.readBytes() }
            val embBytes = appContext.assets.open(EMBEDDING_MODEL_ASSET).use { it.readBytes() }
            val wakeBytes = appContext.assets.open(WAKE_WORD_MODEL_ASSET).use { it.readBytes() }

            val loadedMel = ortEnv.createSession(melBytes, sessionOptions)
            val loadedEmb = ortEnv.createSession(embBytes, sessionOptions)
            val loadedWake = ortEnv.createSession(wakeBytes, sessionOptions)

            melInputName = loadedMel.inputNames.first()
            embeddingInputName = loadedEmb.inputNames.first()
            wakeWordInputName = loadedWake.inputNames.first()

            val wakeInputShape =
                (loadedWake.inputInfo[wakeWordInputName]?.info as? TensorInfo)?.shape
            classifierFrames = if (
                wakeInputShape != null &&
                wakeInputShape.size >= 3 &&
                wakeInputShape[1] > 0L
            ) {
                wakeInputShape[1].toInt()
            } else {
                DEFAULT_CLASSIFIER_FRAMES
            }

            melSession = loadedMel
            embeddingSession = loadedEmb
            wakeWordSession = loadedWake

            Log.i(
                TAG,
                "Loaded openWakeWord ONNX models: mel($melInputName), emb($embeddingInputName), " +
                    "wake($wakeWordInputName, frames=$classifierFrames)"
            )
        } catch (e: Exception) {
            closeSessionsInternal()
            throw e
        } finally {
            try {
                sessionOptions.close()
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun runDetectionLoop(
        thresholdProvider: () -> Float = { TRIGGER_THRESHOLD },
        onTelemetryUpdate: (WakeWordLiveTelemetry) -> Unit = {},
        onSourceSwitched: (fromSource: String, toSource: String) -> Unit = { _, _ -> },
        onStreamRecovering: (Throwable) -> Unit = {},
        onDetected: (Float) -> Unit
    ) = withContext(Dispatchers.Default) {
        listeningState.set(true)
        var detectedScore: Float? = null

        var preferredSourceIndex = 0
        var totalAudioFramesReceived = 0L
        var currentScore = 0.0f
        var peakScore = 0.0f
        var isSilentWarningLatched = false

        while (isListening && currentCoroutineContext().isActive) {
            var recorder: AudioRecord? = null
            try {
                ensureModelsLoaded()

                val melSess = melSession
                    ?: throw IllegalStateException("$MEL_MODEL_ASSET session not initialized")
                val embSess = embeddingSession
                    ?: throw IllegalStateException("$EMBEDDING_MODEL_ASSET session not initialized")
                val wakeSess = wakeWordSession
                    ?: throw IllegalStateException("$WAKE_WORD_MODEL_ASSET session not initialized")

                val rawMinBufferBytes = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (rawMinBufferBytes == AudioRecord.ERROR ||
                    rawMinBufferBytes == AudioRecord.ERROR_BAD_VALUE ||
                    rawMinBufferBytes <= 0
                ) {
                    throw IllegalStateException(
                        "AudioRecord.getMinBufferSize returned invalid value ($rawMinBufferBytes)"
                    )
                }

                val recordBufferBytes = maxOf(rawMinBufferBytes * 2, CHUNK_SAMPLES * 2 * 4)
                val dynamicReadBufferSizeShorts = maxOf(rawMinBufferBytes / 2, CHUNK_SAMPLES)
                val dynamicReadBuffer = ShortArray(dynamicReadBufferSizeShorts)

                val (createdRecorder, selectedIndex) = createInitializedAudioRecord(
                    bufferSizeInBytes = recordBufferBytes,
                    startIndex = preferredSourceIndex
                )
                recorder = createdRecorder
                preferredSourceIndex = selectedIndex
                val activeSource = ORDERED_AUDIO_SOURCES[selectedIndex]
                activeAudioRecord = recorder

                val pcmChunk = ShortArray(CHUNK_SAMPLES)
                var pcmChunkFill = 0
                val overlapTail = ShortArray(OVERLAP_SAMPLES)
                val melAudioInput = FloatArray(MEL_INPUT_SAMPLES)

                val melWindow = FloatArray(MEL_WINDOW_FRAMES * MEL_BINS)
                Arrays.fill(melWindow, 1.0f)

                val seqFrames = classifierFrames
                val embeddingWindow = FloatArray(seqFrames * EMBEDDING_DIM)
                val singleEmbedding = FloatArray(EMBEDDING_DIM)

                val melInputShape = longArrayOf(1L, MEL_INPUT_SAMPLES.toLong())
                val embInputShape = longArrayOf(1L, MEL_WINDOW_FRAMES.toLong(), MEL_BINS.toLong(), 1L)
                val wakeInputShape = longArrayOf(1L, seqFrames.toLong(), EMBEDDING_DIM.toLong())

                var chunksProcessed = 0
                val lastThreeScores = FloatArray(3)
                var scoreHistoryCount = 0

                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException(
                        "AudioRecord (${activeSource.label}) failed to enter RECORDSTATE_RECORDING (state=${recorder.recordingState})"
                    )
                }

                Log.i(
                    TAG,
                    "AudioRecord active [source=${activeSource.label}, minBuffer=${rawMinBufferBytes}B, ringBuffer=${recordBufferBytes}B]"
                )

                var silentZeroStartMs = System.currentTimeMillis()
                var lastTelemetryEmitMs = 0L
                var windowSquaresSum = 0.0
                var windowSampleCount = 0
                var windowPeakAbs = 0
                var rotateSourceAfterSilence = false

                // Read 320 samples (20ms) per step so 200ms (5Hz) mic level updates are smooth and responsive
                val stepReadShorts = 320.coerceAtMost(dynamicReadBuffer.size)

                while (isListening && currentCoroutineContext().isActive) {
                    val shortsRead = recorder.read(
                        dynamicReadBuffer,
                        0,
                        stepReadShorts
                    )

                    if (!isListening || !currentCoroutineContext().isActive) {
                        break
                    }

                    val now = System.currentTimeMillis()

                    if (shortsRead == 0) {
                        if (silentZeroStartMs == 0L) {
                            silentZeroStartMs = now
                        }
                        if (now - lastTelemetryEmitMs >= TELEMETRY_INTERVAL_MS) {
                            lastTelemetryEmitMs = now
                            val silentElapsed = now - silentZeroStartMs
                            if (silentElapsed >= SILENT_MIC_TIMEOUT_MS) {
                                isSilentWarningLatched = true
                                rotateSourceAfterSilence = true
                            }
                            onTelemetryUpdate(
                                WakeWordLiveTelemetry(
                                    micLevel = 0,
                                    audioFramesReceived = totalAudioFramesReceived,
                                    currentScore = currentScore,
                                    peakScore = peakScore,
                                    audioSourceLabel = activeSource.label,
                                    isSilentFor5Seconds = isSilentWarningLatched
                                )
                            )
                            if (rotateSourceAfterSilence) {
                                val nextIndex = (preferredSourceIndex + 1) % ORDERED_AUDIO_SOURCES.size
                                val nextSource = ORDERED_AUDIO_SOURCES[nextIndex]
                                Log.w(
                                    TAG,
                                    "Mic level 0 for 5s on ${activeSource.label} — rotating to ${nextSource.label}"
                                )
                                preferredSourceIndex = nextIndex
                                onSourceSwitched(activeSource.label, nextSource.label)
                                break
                            }
                        }
                        delay(10L)
                        continue
                    }

                    if (shortsRead < 0) {
                        throw IllegalStateException(
                            "AudioRecord.read (${activeSource.label}) returned error code $shortsRead"
                        )
                    }

                    for (i in 0 until shortsRead) {
                        val s = dynamicReadBuffer[i].toInt()
                        val absVal = abs(s)
                        if (absVal > windowPeakAbs) {
                            windowPeakAbs = absVal
                        }
                        windowSquaresSum += (s.toDouble() * s.toDouble())
                    }
                    windowSampleCount += shortsRead

                    var cursor = 0
                    while (cursor < shortsRead && isListening && currentCoroutineContext().isActive) {
                        val needed = CHUNK_SAMPLES - pcmChunkFill
                        val toCopy = minOf(needed, shortsRead - cursor)
                        System.arraycopy(dynamicReadBuffer, cursor, pcmChunk, pcmChunkFill, toCopy)
                        pcmChunkFill += toCopy
                        cursor += toCopy

                        if (pcmChunkFill == CHUNK_SAMPLES) {
                            pcmChunkFill = 0
                            totalAudioFramesReceived++

                            for (i in 0 until OVERLAP_SAMPLES) {
                                melAudioInput[i] = overlapTail[i].toFloat()
                            }
                            for (i in 0 until CHUNK_SAMPLES) {
                                melAudioInput[OVERLAP_SAMPLES + i] = pcmChunk[i].toFloat()
                            }
                            System.arraycopy(
                                pcmChunk,
                                CHUNK_SAMPLES - OVERLAP_SAMPLES,
                                overlapTail,
                                0,
                                OVERLAP_SAMPLES
                            )

                            // Stage 1: melspectrogram.onnx + (value / 10f) + 2f scaling
                            OnnxTensor.createTensor(
                                ortEnv,
                                FloatBuffer.wrap(melAudioInput),
                                melInputShape
                            ).use { melInputTensor ->
                                melSess.run(mapOf(melInputName to melInputTensor)).use { melResult ->
                                    val melOutTensor = melResult[0] as OnnxTensor
                                    val rawMelBuf = melOutTensor.floatBuffer
                                    val newFloats = rawMelBuf.remaining()

                                    if (newFloats > 0 && newFloats % MEL_BINS == 0) {
                                        val shift = newFloats.coerceAtMost(melWindow.size)
                                        val keep = melWindow.size - shift
                                        if (keep > 0) {
                                            System.arraycopy(melWindow, shift, melWindow, 0, keep)
                                        }
                                        if (newFloats > shift) {
                                            rawMelBuf.position(rawMelBuf.position() + (newFloats - shift))
                                        }
                                        for (idx in keep until melWindow.size) {
                                            val rawVal = rawMelBuf.get()
                                            melWindow[idx] = (rawVal / 10.0f) + 2.0f
                                        }
                                    }
                                }
                            }

                            // Stage 2: embedding_model.onnx ([1, 76, 32, 1] -> 96-dim speech embedding)
                            OnnxTensor.createTensor(
                                ortEnv,
                                FloatBuffer.wrap(melWindow),
                                embInputShape
                            ).use { embInputTensor ->
                                embSess.run(mapOf(embeddingInputName to embInputTensor)).use { embResult ->
                                    val embOutTensor = embResult[0] as OnnxTensor
                                    val embBuf = embOutTensor.floatBuffer
                                    val count = minOf(embBuf.remaining(), EMBEDDING_DIM)
                                    for (i in 0 until count) {
                                        singleEmbedding[i] = embBuf.get()
                                    }
                                    for (i in count until EMBEDDING_DIM) {
                                        singleEmbedding[i] = 0.0f
                                    }
                                }
                            }

                            val keepEmbFloats = embeddingWindow.size - EMBEDDING_DIM
                            if (keepEmbFloats > 0) {
                                System.arraycopy(
                                    embeddingWindow,
                                    EMBEDDING_DIM,
                                    embeddingWindow,
                                    0,
                                    keepEmbFloats
                                )
                            }
                            System.arraycopy(
                                singleEmbedding,
                                0,
                                embeddingWindow,
                                keepEmbFloats,
                                EMBEDDING_DIM
                            )

                            chunksProcessed++

                            // Stage 3: hey_jarvis_v0.1.onnx ([1, 16, 96] -> probability score)
                            if (chunksProcessed >= seqFrames) {
                                val score: Float = OnnxTensor.createTensor(
                                    ortEnv,
                                    FloatBuffer.wrap(embeddingWindow),
                                    wakeInputShape
                                ).use { wakeInputTensor ->
                                    wakeSess.run(mapOf(wakeWordInputName to wakeInputTensor)).use { wakeResult ->
                                        val wakeOutTensor = wakeResult[0] as OnnxTensor
                                        val scoreBuf = wakeOutTensor.floatBuffer
                                        if (scoreBuf.remaining() > 0) scoreBuf.get() else 0.0f
                                    }
                                }

                                currentScore = score
                                if (score > peakScore) {
                                    peakScore = score
                                }

                                // Shift 3-score history and trigger when 2 of the last 3 scores exceed threshold
                                lastThreeScores[0] = lastThreeScores[1]
                                lastThreeScores[1] = lastThreeScores[2]
                                lastThreeScores[2] = score
                                if (scoreHistoryCount < 3) {
                                    scoreHistoryCount++
                                }

                                val activeThreshold = thresholdProvider().coerceIn(0.05f, 0.95f)
                                var exceedCount = 0
                                val startIdx = 3 - scoreHistoryCount
                                for (k in startIdx until 3) {
                                    if (lastThreeScores[k] > activeThreshold) {
                                        exceedCount++
                                    }
                                }

                                if (scoreHistoryCount >= 2 && exceedCount >= 2) {
                                    Log.i(
                                        TAG,
                                        "Wake word 'Hey Jarvis' triggered (2 of last 3 > $activeThreshold)! " +
                                            "Scores=[${lastThreeScores[0]}, ${lastThreeScores[1]}, ${lastThreeScores[2]}]"
                                    )
                                    detectedScore = score
                                    listeningState.set(false)
                                    break
                                }
                            }
                        }
                    }

                    if (!isListening) {
                        break
                    }

                    // Emit 5 updates per second (every 200ms)
                    val emitNow = System.currentTimeMillis()
                    if (emitNow - lastTelemetryEmitMs >= TELEMETRY_INTERVAL_MS) {
                        lastTelemetryEmitMs = emitNow
                        val micLevel = computeMicLevel0To100(
                            squaresSum = windowSquaresSum,
                            sampleCount = windowSampleCount,
                            peakAbs = windowPeakAbs
                        )
                        windowSquaresSum = 0.0
                        windowSampleCount = 0
                        windowPeakAbs = 0

                        if (micLevel == 0) {
                            if (silentZeroStartMs == 0L) {
                                silentZeroStartMs = emitNow
                            }
                            if (emitNow - silentZeroStartMs >= SILENT_MIC_TIMEOUT_MS) {
                                isSilentWarningLatched = true
                                rotateSourceAfterSilence = true
                            }
                        } else {
                            silentZeroStartMs = 0L
                            isSilentWarningLatched = false
                        }

                        onTelemetryUpdate(
                            WakeWordLiveTelemetry(
                                micLevel = micLevel,
                                audioFramesReceived = totalAudioFramesReceived,
                                currentScore = currentScore,
                                peakScore = peakScore,
                                audioSourceLabel = activeSource.label,
                                isSilentFor5Seconds = isSilentWarningLatched
                            )
                        )

                        if (rotateSourceAfterSilence) {
                            val nextIndex = (preferredSourceIndex + 1) % ORDERED_AUDIO_SOURCES.size
                            val nextSource = ORDERED_AUDIO_SOURCES[nextIndex]
                            Log.w(
                                TAG,
                                "Mic level stayed at 0 for 5s on ${activeSource.label} — restarting with ${nextSource.label}"
                            )
                            preferredSourceIndex = nextIndex
                            onSourceSwitched(activeSource.label, nextSource.label)
                            break
                        }
                    }
                }
            } catch (ce: CancellationException) {
                listeningState.set(false)
                throw ce
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Wake word AudioRecord/ONNX exception (${e.javaClass.simpleName}: ${e.message}) — advancing source & restarting",
                    e
                )
                preferredSourceIndex = (preferredSourceIndex + 1) % ORDERED_AUDIO_SOURCES.size
                onStreamRecovering(e)
                if (isListening && currentCoroutineContext().isActive) {
                    delay(350L)
                }
            } finally {
                if (recorder != null) {
                    releaseRecorderInstance(recorder)
                }
            }
        }

        val triggered = detectedScore
        if (triggered != null) {
            onDetected(triggered)
        }
    }

    private fun computeMicLevel0To100(
        squaresSum: Double,
        sampleCount: Int,
        peakAbs: Int
    ): Int {
        if (sampleCount <= 0 || peakAbs <= 2) return 0
        val rms = sqrt(squaresSum / sampleCount.toDouble())
        if (rms < 3.0) return 0
        val scaled = ((rms / 4200.0) * 100.0).roundToInt()
        return if (scaled == 0 && peakAbs > 20) 1 else scaled.coerceIn(0, 100)
    }

    @SuppressLint("MissingPermission")
    private fun createInitializedAudioRecord(
        bufferSizeInBytes: Int,
        startIndex: Int
    ): Pair<AudioRecord, Int> {
        val count = ORDERED_AUDIO_SOURCES.size
        for (offset in 0 until count) {
            val idx = (startIndex + offset) % count
            val sourceEntry = ORDERED_AUDIO_SOURCES[idx]
            var candidate: AudioRecord? = null
            try {
                candidate = AudioRecord(
                    sourceEntry.androidSource,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSizeInBytes
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    return candidate to idx
                }
                candidate.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord init failed for source=${sourceEntry.label}: ${e.message}")
                try {
                    candidate?.release()
                } catch (_: Exception) {
                }
            }
        }
        throw IllegalStateException("Failed to initialize 16kHz mono 16-bit AudioRecord across MIC, VOICE_RECOGNITION, UNPROCESSED")
    }

    fun stopAudioCapture() {
        listeningState.set(false)
        val recorder = activeAudioRecord
        if (recorder != null) {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun releaseRecorderInstance(recorder: AudioRecord) {
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        } catch (_: Exception) {
        }
        try {
            recorder.release()
        } catch (_: Exception) {
        }
        if (activeAudioRecord === recorder) {
            activeAudioRecord = null
        }
    }

    @Synchronized
    fun close() {
        stopAudioCapture()
        closeSessionsInternal()
    }

    private fun closeSessionsInternal() {
        try {
            wakeWordSession?.close()
        } catch (_: Exception) {
        } finally {
            wakeWordSession = null
        }

        try {
            embeddingSession?.close()
        } catch (_: Exception) {
        } finally {
            embeddingSession = null
        }

        try {
            melSession?.close()
        } catch (_: Exception) {
        } finally {
            melSession = null
        }
    }
}
