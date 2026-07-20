package com.example.audio

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AudioEngineTest {

    private lateinit var audioEngine: AudioEngine

    @Before
    fun setUp() {
        audioEngine = AudioEngine(
            ApplicationProvider.getApplicationContext(),
            playerFactory = { _, _ -> mock(ExoPlayer::class.java) }
        )
    }

    @After
    fun tearDown() {
        audioEngine.stop()
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
        
        // Player should still exist during debounce window before 2.5s delay finishes
        assertTrue(audioEngine.hasPlayer("rain"))

        // Wait past the 2.5s debounce delay
        Thread.sleep(3500)
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
        assertTrue(audioEngine.hasPlayer("rain"))

        // Re-enable volume before 2.5s window finishes
        audioEngine.setVolume("rain", 0.8f)
        Thread.sleep(3500) // Wait past original debounce timeout
        assertTrue(audioEngine.hasPlayer("rain"))
    }
}
