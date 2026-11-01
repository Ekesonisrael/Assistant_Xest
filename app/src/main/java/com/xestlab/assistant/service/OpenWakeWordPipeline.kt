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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full 3-stage openWakeWord ONNX Runtime preprocessing & wake-word detection pipeline:
 *
 * 1. Audio capture: 16kHz, mono, 16-bit PCM via [AudioRecord] in 1280-sample (80ms) chunks
 *    with a 480-sample STFT overlap (1760 samples per frame -> 8 mel frames x 32 bins).
 * 2. Stage 1 ("melspectrogram.onnx"): Raw PCM samples -> mel-spectrogram -> scaled via `(x / 10f) + 2f`
 *    into a 76-frame sliding window `[1, 76, 32, 1]`.
 * 3. Stage 2 ("embedding_model.onnx"): `[1, 76, 32, 1]` mel window -> 96-dimensional speech embedding
 *    appended into a 16-step rolling sequence `[1, 16, 96]`.
 * 4. Stage 3 ("hey_jarvis_v0.1.onnx"): `[1, 16, 96]` embedding sequence -> probability score `[0.0, 1.0]`.
 *    Triggers immediately when `score > 0.5f`.
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

    private val captureActive = AtomicBoolean(false)

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
     * Captures 16kHz 16-bit mono PCM audio on a background Coroutine, runs every 80ms frame
     * sequentially through `melspectrogram.onnx` -> `embedding_model.onnx` -> `hey_jarvis_v0.1.onnx`,
     * and stops the [AudioRecord] stream + invokes [onDetected] as soon as `score > 0.5f`.
     */
    @SuppressLint("MissingPermission")
    suspend fun runDetectionLoop(
        onScoreUpdate: (Float) -> Unit = {},
        onDetected: (Float) -> Unit
    ) = withContext(Dispatchers.Default) {
        ensureModelsLoaded()

        val melSess = melSession
            ?: throw IllegalStateException("$MEL_MODEL_ASSET session not initialized")
        val embSess = embeddingSession
            ?: throw IllegalStateException("$EMBEDDING_MODEL_ASSET session not initialized")
        val wakeSess = wakeWordSession
            ?: throw IllegalStateException("$WAKE_WORD_MODEL_ASSET session not initialized")

        val minBufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes <= 0) {
            throw IllegalStateException("AudioRecord.getMinBufferSize failed ($minBufBytes) for 16kHz mono PCM")
        }
        val recordBufferBytes = maxOf(minBufBytes * 2, CHUNK_SAMPLES * 2 * 4)

        val recorder = createInitializedAudioRecord(recordBufferBytes)
        activeAudioRecord = recorder
        captureActive.set(true)

        // Pre-allocated rolling buffers matching official dscripka/openWakeWord AudioFeatures:
        val pcmChunk = ShortArray(CHUNK_SAMPLES)
        val overlapTail = ShortArray(OVERLAP_SAMPLES) // Zero-filled on start
        val melAudioInput = FloatArray(MEL_INPUT_SAMPLES)

        // 76 x 32 mel window initialized to 1.0f (matching np.ones((76, 32)) in openWakeWord)
        val melWindow = FloatArray(MEL_WINDOW_FRAMES * MEL_BINS)
        Arrays.fill(melWindow, 1.0f)

        val seqFrames = classifierFrames
        val embeddingWindow = FloatArray(seqFrames * EMBEDDING_DIM)
        val singleEmbedding = FloatArray(EMBEDDING_DIM)

        val melInputShape = longArrayOf(1L, MEL_INPUT_SAMPLES.toLong())
        val embInputShape = longArrayOf(1L, MEL_WINDOW_FRAMES.toLong(), MEL_BINS.toLong(), 1L)
        val wakeInputShape = longArrayOf(1L, seqFrames.toLong(), EMBEDDING_DIM.toLong())

        var chunksProcessed = 0
        var detectedScore: Float? = null

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException(
                    "Microphone unavailable (AudioRecord state=${recorder.recordingState}). Another app may be holding the mic."
                )
            }

            while (currentCoroutineContext().isActive && captureActive.get()) {
                var samplesCollected = 0
                while (samplesCollected < CHUNK_SAMPLES &&
                    currentCoroutineContext().isActive &&
                    captureActive.get()
                ) {
                    val readCount = recorder.read(
                        pcmChunk,
                        samplesCollected,
                        CHUNK_SAMPLES - samplesCollected,
                        AudioRecord.READ_BLOCKING
                    )
                    if (readCount > 0) {
                        samplesCollected += readCount
                    } else if (readCount < 0) {
                        throw IllegalStateException("AudioRecord.read failed with error code $readCount")
                    } else {
                        break
                    }
                }

                if (samplesCollected < CHUNK_SAMPLES || !captureActive.get() || !currentCoroutineContext().isActive) {
                    break
                }

                // Assemble 1760-sample STFT window: [480 previous samples] + [1280 new samples]
                for (i in 0 until OVERLAP_SAMPLES) {
                    melAudioInput[i] = overlapTail[i].toFloat()
                }
                for (i in 0 until CHUNK_SAMPLES) {
                    melAudioInput[OVERLAP_SAMPLES + i] = pcmChunk[i].toFloat()
                }
                // Save trailing 480 samples for next 80ms step
                System.arraycopy(
                    pcmChunk,
                    CHUNK_SAMPLES - OVERLAP_SAMPLES,
                    overlapTail,
                    0,
                    OVERLAP_SAMPLES
                )

                // Stage 1: melspectrogram.onnx + element-wise (value / 10f) + 2f normalization
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
                            // If model returned more than 76 frames, skip older excess frames
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

                // Stage 2: embedding_model.onnx ([1, 76, 32, 1] -> 96-dim feature vector)
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

                // Slide [seqFrames x 96] embedding sequence forward by 1 step
                val keepEmbFloats = embeddingWindow.size - EMBEDDING_DIM
                if (keepEmbFloats > 0) {
                    System.arraycopy(embeddingWindow, EMBEDDING_DIM, embeddingWindow, 0, keepEmbFloats)
                }
                System.arraycopy(singleEmbedding, 0, embeddingWindow, keepEmbFloats, EMBEDDING_DIM)

                chunksProcessed++

                // Stage 3: hey_jarvis_v0.1.onnx ([1, 16, 96] -> wake-word probability score)
                // Wait until the 16-step embedding window is populated with real microphone frames (~1.28s)
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
                        Log.i(TAG, "Wake word 'Hey Jarvis' triggered! Score=$score (> $TRIGGER_THRESHOLD)")
                        detectedScore = score
                        captureActive.set(false)
                        break
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } finally {
            stopAndReleaseRecorder(recorder)
        }

        // Invoke trigger callback AFTER AudioRecord has fully stopped and released the mic
        val triggered = detectedScore
        if (triggered != null) {
            onDetected(triggered)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createInitializedAudioRecord(bufferSizeInBytes: Int): AudioRecord {
        val primary = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeInBytes
        )
        if (primary.state == AudioRecord.STATE_INITIALIZED) {
            return primary
        }
        primary.release()

        val fallback = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeInBytes
        )
        if (fallback.state == AudioRecord.STATE_INITIALIZED) {
            return fallback
        }
        fallback.release()
        throw IllegalStateException("Failed to initialize 16kHz mono 16-bit AudioRecord")
    }

    /**
     * Immediately stops active [AudioRecord] capture so the microphone can be handed over
     * cleanly to Android's SpeechRecognizer.
     */
    fun stopAudioCapture() {
        captureActive.set(false)
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

    private fun stopAndReleaseRecorder(recorder: AudioRecord) {
        captureActive.set(false)
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
