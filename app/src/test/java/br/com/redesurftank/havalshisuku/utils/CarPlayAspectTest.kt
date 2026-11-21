package br.com.redesurftank.havalshisuku.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayAspectTest {
    @Test
    fun keepsAspectRatioWithoutPane() {
        val o = CarPlayAspect.overscanFor(60, 1920, 720, 0)
        assertEquals(CarPlayAspect.Overscan(80, 0, 80, 60), o)
        val w = 1920 - o.left - o.right
        val h = 720 - o.bottom
        assertEquals(1920.0 / 720, w.toDouble() / h, 0.01)
    }

    @Test
    fun keepsAspectRatioWithPane() {
        val o = CarPlayAspect.overscanFor(60, 1920, 720, 128)
        val w = 1920 - 128 - o.left - o.right
        val h = 720 - o.bottom
        assertEquals(1792.0 / 720, w.toDouble() / h, 0.01)
    }

    @Test
    fun zeroBarMeansNoOverscan() {
        assertEquals(CarPlayAspect.Overscan(0, 0, 0, 0), CarPlayAspect.overscanFor(0, 1920, 720, 0))
    }

    @Test
    fun parsesDumpsys() {
        val dump = "  mDisplayInfo=DisplayInfo{\"Built-in Screen\", uniqueId \"local:0\", app 1920 x 660, overscan (80,0,80,60), rotation 0}"
        assertEquals(CarPlayAspect.Overscan(80, 0, 80, 60), CarPlayAspect.parseOverscan(dump))
        assertNull(CarPlayAspect.parseOverscan("nothing here"))
    }
}
