package com.xestlab.assistant.service

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * Thread-safe rolling ring buffer for raw 16kHz 16-bit Mono PCM audio.
 *
 * Allows continuous streaming audio to be captured indefinitely in the background
 * without cutting off or dropping frames when silence occurs. Oldest audio frames
 * are automatically overwritten if the buffer reaches maximum capacity.
 *
 * @param durationSeconds Maximum audio history to retain in memory (default 30 seconds).
 * @param sampleRate Sample rate in Hz (default 16,000Hz).
 * @param bytesPerSample Bytes per sample (default 2 for 16-bit PCM mono).
 */
class AudioRingBuffer(
    durationSeconds: Int = 30,
    val sampleRate: Int = 16_000,
    val bytesPerSample: Int = 2
) {
    val capacity: Int = durationSeconds * sampleRate * bytesPerSample
    private val buffer: ByteArray = ByteArray(capacity)
    private val lock = ReentrantLock()

    private var writePos: Int = 0
    private var availableBytes: Int = 0
    private var totalBytesRecorded: Long = 0L

    /**
     * Writes raw audio PCM bytes into the rolling ring buffer.
     * If the buffer overflows, oldest bytes are automatically evicted to maintain a continuous stream.
     */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (length <= 0) return
        lock.withLock {
            var srcOffset = offset
            var remaining = length

            while (remaining > 0) {
                val bytesToEnd = capacity - writePos
                val chunk = min(remaining, bytesToEnd)
                System.arraycopy(data, srcOffset, buffer, writePos, chunk)

                writePos = (writePos + chunk) % capacity
                srcOffset += chunk
                remaining -= chunk
            }

            availableBytes = min(capacity, availableBytes + length)
            totalBytesRecorded += length
        }
    }

    /**
     * Retrieves the most recent [durationMs] milliseconds of recorded audio as a contiguous ByteArray.
     * Useful for extracting pre-trigger context or sending audio slices to speech recognizers.
     */
    fun getRecentAudio(durationMs: Int): ByteArray {
        val requestedBytes = (durationMs.toLong() * sampleRate * bytesPerSample / 1000L).toInt()
        return lock.withLock {
            val bytesToRead = min(requestedBytes, availableBytes)
            val result = ByteArray(bytesToRead)
            if (bytesToRead == 0) return@withLock result

            // Calculate start position in the circular buffer
            var readPos = (writePos - bytesToRead + capacity) % capacity
            var dstOffset = 0
            var remaining = bytesToRead

            while (remaining > 0) {
                val bytesToEnd = capacity - readPos
                val chunk = min(remaining, bytesToEnd)
                System.arraycopy(buffer, readPos, result, dstOffset, chunk)

                readPos = (readPos + chunk) % capacity
                dstOffset += chunk
                remaining -= chunk
            }

            result
        }
    }

    /**
     * Reads and extracts all currently available unread audio bytes up to [maxBytes].
     */
    fun readAvailable(maxBytes: Int = availableBytes): ByteArray {
        return lock.withLock {
            val bytesToRead = min(maxBytes, availableBytes)
            val result = ByteArray(bytesToRead)
            if (bytesToRead == 0) return@withLock result

            var readPos = (writePos - availableBytes + capacity) % capacity
            var dstOffset = 0
            var remaining = bytesToRead

            while (remaining > 0) {
                val bytesToEnd = capacity - readPos
                val chunk = min(remaining, bytesToEnd)
                System.arraycopy(buffer, readPos, result, dstOffset, chunk)

                readPos = (readPos + chunk) % capacity
                dstOffset += chunk
                remaining -= chunk
            }

            availableBytes -= bytesToRead
            result
        }
    }

    /**
     * Returns the total number of bytes currently buffered.
     */
    fun availableBytes(): Int = lock.withLock { availableBytes }

    /**
     * Returns the lifetime count of bytes written since last clear.
     */
    fun totalBytesRecorded(): Long = lock.withLock { totalBytesRecorded }

    /**
     * Clears all buffered audio without reallocating memory.
     */
    fun clear() {
        lock.withLock {
            writePos = 0
            availableBytes = 0
            totalBytesRecorded = 0L
        }
    }
}
