package com.panmusic.carplayer.visualizer

import com.panmusic.carplayer.visualizer.AudioAnalyzer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AudioAnalyzerTest {
    private lateinit var analyzer: AudioAnalyzer

    @Before fun setUp() { analyzer = AudioAnalyzer() }

    @Test fun `empty FFT returns zeros`() {
        val r = analyzer.analyze(ByteArray(0))
        assertEquals(0f, r.bass); assertEquals(0f, r.volume); assertFalse(r.isPeak)
    }

    @Test fun `silent FFT returns near zero`() {
        val r = analyzer.analyze(ByteArray(256))
        assertEquals(0f, r.bass, 0.01f); assertFalse(r.isPeak)
    }

    @Test fun `low frequency produces high bass`() {
        val fft = ByteArray(256)
        for (i in 0 until 6) { fft[i * 2] = 100; fft[i * 2 + 1] = 0 }
        val r = analyzer.analyze(fft)
        assertTrue("bass=${r.bass}", r.bass > 0.3f)
        assertTrue(r.bass > r.treble)
    }

    @Test fun `high frequency produces high treble`() {
        val fft = ByteArray(256)
        for (i in 32 until 64) { fft[i * 2] = 100; fft[i * 2 + 1] = 0 }
        val r = analyzer.analyze(fft)
        assertTrue("treble=${r.treble}", r.treble > 0.1f)
        assertTrue(r.treble > r.bass)
    }

    @Test fun `uniform FFT produces balanced output`() {
        val fft = ByteArray(256)
        for (i in 0 until 128) { fft[i * 2] = 50; fft[i * 2 + 1] = 0 }
        val r = analyzer.analyze(fft)
        assertTrue(r.bass > 0f); assertTrue(r.mid > 0f); assertTrue(r.treble > 0f)
    }

    @Test fun `higher amplitude produces higher volume`() {
        val soft = ByteArray(256); val loud = ByteArray(256)
        for (i in 0 until 128) { soft[i * 2] = 10; loud[i * 2] = 100 }
        val a1 = AudioAnalyzer(); val a2 = AudioAnalyzer()
        assertTrue(a2.analyze(loud).volume > a1.analyze(soft).volume)
    }

    @Test fun `volume spike triggers peak`() {
        analyzer.analyze(ByteArray(256))
        val high = ByteArray(256)
        for (i in 0 until 128) { high[i * 2] = 127 }
        assertTrue(analyzer.analyze(high).isPeak)
    }

    @Test fun `peak detection has cooldown`() {
        val high = ByteArray(256)
        for (i in 0 until 128) { high[i * 2] = 127 }
        assertTrue(analyzer.analyze(high).isPeak)
        assertFalse(analyzer.analyze(high).isPeak)
    }

    @Test fun `all outputs clamped to 0_1`() {
        val fft = ByteArray(256)
        for (i in 0 until 128) { fft[i * 2] = 127; fft[i * 2 + 1] = 127 }
        val r = analyzer.analyze(fft)
        assertTrue(r.bass in 0f..1f); assertTrue(r.treble in 0f..1f)
        assertTrue(r.volume in 0f..1f)
    }

    @Test fun `raw spectrum length matches FFT size`() {
        assertEquals(128, analyzer.analyze(ByteArray(256)).rawSpectrum.size)
    }
}
