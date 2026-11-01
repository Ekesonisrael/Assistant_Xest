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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resilient 3-stage openWakeWord ONNX Runtime preprocessing & wake-word detection pipeline:
 *
 * - Runs an infinite `while (isListening)` loop inside a background Coroutine.
 * - Dynamically queries `AudioRecord.getMinBufferSize(16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)`
 *   on every recording start.
 * - Never breaks on 0/empty/partial reads; accumulates PCM samples cleanly into 1280-sample
 *   (80ms) ONNX frames with a 480-sample STFT overlap (`[1, 1760]`).
 * - Catches and logs any `AudioRecord` or ONNX exception, releases the previous `AudioRecord`,
 *   and immediately restarts recording inside `while (isListening)` so it never stops listening.
 * - Pipeline stages:
 *   1. "melspectrogram.onnx" -> scaled via `(x / 10f) + 2f` into `[1, 76, 32, 1]`
 *   2. "embedding_model.onnx" -> 96-dim speech embedding into `[1, 16, 96]`
 *   3. "hey_jarvis_v0.1.onnx" -> probability score `[0.0, 1.0]`, triggering when `score > 0.5f`.
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

        const val TRIGGER_THRESHOLD = 0.5f
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

    /**
     * Loads the 3 ONNX models ("melspectrogram.onnx", "embedding_model.onnx", "hey_jarvis_v0.1.onnx")
     * from `app/src/main/assets/` into [OrtSession] instances if not already loaded.
     */
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

    /**
     * Runs an infinite `while (isListening)` recording & ONNX inference loop inside a Coroutine.
     *
     * - Reads buffer size dynamically via `AudioRecord.getMinBufferSize()`.
     * - Never breaks on 0/empty reads (`readCount == 0` yields briefly and continues).
     * - If any `AudioRecord` or ONNX exception occurs, catches it, logs it, releases the old
     *   `AudioRecord`, and immediately restarts recording so wake-word listening never drops out.
     * - Only exits when `score > 0.5f` (to hand the mic to SpeechRecognizer) or when
     *   [stopAudioCapture] / Coroutine cancellation explicitly stops listening.
     */
    @SuppressLint("MissingPermission")
    suspend fun runDetectionLoop(
        onScoreUpdate: (Float) -> Unit = {},
        onStreamRecovering: (Throwable) -> Unit = {},
        onDetected: (Float) -> Unit
    ) = withContext(Dispatchers.Default) {
        listeningState.set(true)
        var detectedScore: Float? = null

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

                // Dynamically read hardware minimum buffer size on every recording session start
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

                // Allocate a generous OS ring buffer (>= 2x minBufferSize) and a dynamic short read buffer
                val recordBufferBytes = maxOf(rawMinBufferBytes * 2, CHUNK_SAMPLES * 2 * 4)
                val dynamicReadBufferSizeShorts = maxOf(rawMinBufferBytes / 2, CHUNK_SAMPLES)
                val dynamicReadBuffer = ShortArray(dynamicReadBufferSizeShorts)

                recorder = createInitializedAudioRecord(recordBufferBytes)
                activeAudioRecord = recorder

                // Rolling STFT & ONNX feature buffers
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

                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException(
                        "AudioRecord failed to enter RECORDSTATE_RECORDING (state=${recorder.recordingState})"
                    )
                }

                Log.i(
                    TAG,
                    "AudioRecord infinite loop active (minBuffer=${rawMinBufferBytes}B, " +
                        "ringBuffer=${recordBufferBytes}B, readArray=${dynamicReadBufferSizeShorts} shorts)"
                )

                // Infinite inner audio read loop — never breaks on 0/empty reads
                while (isListening && currentCoroutineContext().isActive) {
                    val shortsRead = recorder.read(
                        dynamicReadBuffer,
                        0,
                        CHUNK_SAMPLES.coerceAtMost(dynamicReadBuffer.size)
                    )

                    if (!isListening || !currentCoroutineContext().isActive) {
                        break
                    }

                    if (shortsRead == 0) {
                        // Transient empty/null HAL read — do NOT break; yield briefly and continue listening
                        delay(10L)
                        continue
                    }

                    if (shortsRead < 0) {
                        throw IllegalStateException(
                            "AudioRecord.read returned error code $shortsRead (recordingState=${recorder.recordingState})"
                        )
                    }

                    var cursor = 0
                    while (cursor < shortsRead && isListening && currentCoroutineContext().isActive) {
                        val needed = CHUNK_SAMPLES - pcmChunkFill
                        val toCopy = minOf(needed, shortsRead - cursor)
                        System.arraycopy(dynamicReadBuffer, cursor, pcmChunk, pcmChunkFill, toCopy)
                        pcmChunkFill += toCopy
                        cursor += toCopy

                        if (pcmChunkFill == CHUNK_SAMPLES) {
                            pcmChunkFill = 0

                            // Assemble 1760-sample STFT window: [480 overlap] + [1280 new PCM samples]
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

                                onScoreUpdate(score)

                                if (score > TRIGGER_THRESHOLD) {
                                    Log.i(
                                        TAG,
                                        "Wake word 'Hey Jarvis' triggered! Score=$score (> $TRIGGER_THRESHOLD)"
                                    )
                                    detectedScore = score
                                    listeningState.set(false)
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                listeningState.set(false)
                throw ce
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Wake word AudioRecord/ONNX exception (${e.javaClass.simpleName}: ${e.message}) — restarting recording loop immediately",
                    e
                )
                onStreamRecovering(e)
                if (isListening && currentCoroutineContext().isActive) {
                    delay(400L)
                }
            } finally {
                if (recorder != null) {
                    releaseRecorderInstance(recorder)
                }
            }
        }

        // Invoke trigger callback AFTER AudioRecord has stopped and released the mic
        val triggered = detectedScore
        if (triggered != null) {
            onDetected(triggered)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createInitializedAudioRecord(bufferSizeInBytes: Int): AudioRecord {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            var candidate: AudioRecord? = null
            try {
                candidate = AudioRecord(
                    source,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSizeInBytes
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    return candidate
                }
                candidate.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord init failed for source=$source: ${e.message}")
                try {
                    candidate?.release()
                } catch (_: Exception) {
                }
            }
        }
        throw IllegalStateException("Failed to initialize 16kHz mono 16-bit AudioRecord")
    }

    /**
     * Immediately stops active [AudioRecord] capture so the microphone can be handed over
     * cleanly to Android's SpeechRecognizer.
     */
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
