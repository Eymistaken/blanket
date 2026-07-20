package com.example.audio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AudioEngineTest {

    private lateinit var audioEngine: AudioEngine

    @Before
    fun setUp() {
        audioEngine = AudioEngine(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun testLazyPlayerCreationOnVolumeSet() {
        assertFalse(audioEngine.hasPlayer("rain"))
        audioEngine.start()
        audioEngine.setVolume("rain", 0.5f)
        
        Thread.sleep(200)
        assertTrue(audioEngine.hasPlayer("rain"))
    }

    @Test
    fun testVolumeZeroDebounceRelease() {
        audioEngine.start()
        audioEngine.setVolume("rain", 0.5f)
        Thread.sleep(200)
        assertTrue(audioEngine.hasPlayer("rain"))

        // Set volume to 0
        audioEngine.setVolume("rain", 0f)
        
        // Immediately after setting volume to 0, player should still exist during debounce
        Thread.sleep(200)
        assertTrue(audioEngine.hasPlayer("rain"))

        // Wait past the 2.5s debounce delay
        Thread.sleep(3000)
        assertFalse(audioEngine.hasPlayer("rain"))
    }

    @Test
    fun testVolumeReopenBeforeDebounceCancelsRelease() {
        audioEngine.start()
        audioEngine.setVolume("rain", 0.5f)
        Thread.sleep(200)
        assertTrue(audioEngine.hasPlayer("rain"))

        // Set volume to 0
        audioEngine.setVolume("rain", 0f)
        Thread.sleep(1000) // 1 second into debounce
        assertTrue(audioEngine.hasPlayer("rain"))

        // Re-enable volume before 2.5s window finishes
        audioEngine.setVolume("rain", 0.8f)
        Thread.sleep(2000) // Wait past original debounce timeout
        assertTrue(audioEngine.hasPlayer("rain"))
    }
}
