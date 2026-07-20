package com.example.audio

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
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
        audioEngine.sync()
    }

    @Test
    fun testLazyPlayerCreationOnVolumeSet() {
        assertFalse(audioEngine.hasPlayer("rain"))
        audioEngine.start()
        audioEngine.setVolume("rain", 0.5f)
        
        audioEngine.sync()
        assertTrue(audioEngine.hasPlayer("rain"))
    }

    @Test
    fun testVolumeZeroDebounceRelease() {
        audioEngine.start()
        audioEngine.setVolume("rain", 0.5f)
        audioEngine.sync()
        assertTrue(audioEngine.hasPlayer("rain"))

        // Set volume to 0
        audioEngine.setVolume("rain", 0f)
        audioEngine.sync()
        
        // Player should still exist during debounce window before 2.5s delay finishes
        assertTrue(audioEngine.hasPlayer("rain"))

        // Wait past the 2.5s debounce delay
        Thread.sleep(2600)
        runBlocking {
            audioEngine.releaseJobs["rain"]?.join()
        }
        audioEngine.sync()
        assertFalse(audioEngine.hasPlayer("rain"))
    }

    @Test
    fun testVolumeReopenBeforeDebounceCancelsRelease() {
        audioEngine.start()
        audioEngine.setVolume("rain", 0.5f)
        audioEngine.sync()
        assertTrue(audioEngine.hasPlayer("rain"))

        // Set volume to 0
        audioEngine.setVolume("rain", 0f)
        audioEngine.sync()
        assertTrue(audioEngine.hasPlayer("rain"))

        // Re-enable volume before 2.5s window finishes
        audioEngine.setVolume("rain", 0.8f)
        Thread.sleep(2600)
        audioEngine.sync()
        assertTrue(audioEngine.hasPlayer("rain"))
    }
}
