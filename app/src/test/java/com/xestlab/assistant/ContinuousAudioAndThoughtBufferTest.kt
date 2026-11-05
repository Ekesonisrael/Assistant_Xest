package com.xestlab.assistant

import com.xestlab.assistant.service.AudioRingBuffer
import com.xestlab.assistant.service.ThoughtActionType
import com.xestlab.assistant.service.ThoughtBufferManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousAudioAndThoughtBufferTest {

    @Test
    fun testAudioRingBufferWriteAndRecentAudio() {
        val sampleRate = 16_000
        val bytesPerSample = 2
        // 2 seconds buffer = 64,000 bytes
        val ringBuffer = AudioRingBuffer(durationSeconds = 2, sampleRate = sampleRate, bytesPerSample = bytesPerSample)
        assertEquals(64_000, ringBuffer.capacity)
        assertEquals(0, ringBuffer.availableBytes())

        // Write 1 second of audio (32,000 bytes)
        val oneSecondAudio = ByteArray(32_000) { (it % 128).toByte() }
        ringBuffer.write(oneSecondAudio)

        assertEquals(32_000, ringBuffer.availableBytes())
        assertEquals(32_000L, ringBuffer.totalBytesRecorded())

        // Extract recent 500ms (16,000 bytes)
        val recent500ms = ringBuffer.getRecentAudio(500)
        assertEquals(16_000, recent500ms.size)

        // Verify the extracted bytes match the tail of written audio
        assertEquals(oneSecondAudio[16_000], recent500ms[0])
        assertEquals(oneSecondAudio[31_999], recent500ms[15_999])
    }

    @Test
    fun testAudioRingBufferCircularOverflow() {
        // 1 second buffer = 32,000 bytes
        val ringBuffer = AudioRingBuffer(durationSeconds = 1, sampleRate = 16_000, bytesPerSample = 2)

        // Write 2.5 seconds of continuous stream (80,000 bytes)
        val overflowAudio = ByteArray(80_000) { (it and 0xFF).toByte() }
        ringBuffer.write(overflowAudio)

        // Capacity is preserved at 32,000 bytes, total count reflects full continuous stream
        assertEquals(32_000, ringBuffer.availableBytes())
        assertEquals(80_000L, ringBuffer.totalBytesRecorded())

        // Reading all available bytes should give exactly 32,000 bytes corresponding to the latest slice
        val unread = ringBuffer.readAvailable()
        assertEquals(32_000, unread.size)
        assertEquals(0, ringBuffer.availableBytes())

        // Oldest byte in the ring should be byte 48,000 from the stream
        assertEquals((48_000 and 0xFF).toByte(), unread[0])
        assertEquals((79_999 and 0xFF).toByte(), unread[31_999])
    }

    @Test
    fun testThoughtBufferSilentAccumulationWithoutTrigger() {
        val manager = ThoughtBufferManager()

        // User thinking out loud, pauses, rambling — assistant must stay silent (return null)
        val chunk1 = manager.appendChunk("I was wondering if we should reach out to Tola")
        assertNull("Assistant must not trigger prematurely", chunk1)
        assertEquals("I was wondering if we should reach out to Tola", manager.getCurrentDraft())

        val chunk2 = manager.appendChunk("about the landing page quote or maybe wait until Tuesday")
        assertNull("Assistant must remain silent during speech pauses", chunk2)

        assertTrue(manager.hasPendingThoughts())
        assertEquals(
            "I was wondering if we should reach out to Tola about the landing page quote or maybe wait until Tuesday",
            manager.getCurrentDraft()
        )
    }

    @Test
    fun testThoughtBufferExplicitExecuteTrigger() {
        val manager = ThoughtBufferManager()

        manager.appendChunk("Schedule a consultation with ABC Corp for tomorrow morning.")
        val finalized = manager.appendChunk("Execute")

        assertNotNull("Explicit trigger 'Execute' must finalize the thought", finalized)
        assertEquals("Schedule a consultation with ABC Corp for tomorrow morning.", finalized?.cleanText)
        assertEquals("Execute", finalized?.triggerKeyword)
        assertEquals(ThoughtActionType.EXECUTE_COMMAND, finalized?.actionType)

        // Buffer must be cleared once triggered
        assertEquals("", manager.getCurrentDraft())
    }

    @Test
    fun testThoughtBufferSaveNoteTrigger() {
        val manager = ThoughtBufferManager()

        manager.appendChunk("Client prefers dark mode UI and purple accents.")
        val finalized = manager.appendChunk("Save note")

        assertNotNull(finalized)
        assertEquals("Client prefers dark mode UI and purple accents.", finalized?.cleanText)
        assertEquals("Save note", finalized?.triggerKeyword)
        assertEquals(ThoughtActionType.SAVE_NOTE, finalized?.actionType)
    }

    @Test
    fun testThoughtBufferAddTaskTrigger() {
        val manager = ThoughtBufferManager()

        manager.appendChunk("Prepare invoice for 250,000 Naira by Friday.")
        val finalized = manager.appendChunk("Add task")

        assertNotNull(finalized)
        assertEquals("Prepare invoice for 250,000 Naira by Friday.", finalized?.cleanText)
        assertEquals("Add task", finalized?.triggerKeyword)
        assertEquals(ThoughtActionType.ADD_TASK, finalized?.actionType)
    }

    @Test
    fun testThoughtBufferForceFinalizeManual() {
        val manager = ThoughtBufferManager()

        manager.appendChunk("Review website design mockup with team")
        val finalized = manager.forceFinalize("Manual Tap")

        assertNotNull(finalized)
        assertEquals("Review website design mockup with team", finalized?.cleanText)
        assertEquals("Manual Tap", finalized?.triggerKeyword)
        assertEquals(ThoughtActionType.PROCESS_GENERAL, finalized?.actionType)
        assertEquals("", manager.getCurrentDraft())
    }

    @Test
    fun testThoughtBufferClear() {
        val manager = ThoughtBufferManager()

        manager.appendChunk("Discarded ramble")
        assertTrue(manager.hasPendingThoughts())

        manager.clear()
        assertEquals("", manager.getCurrentDraft())
    }
}
