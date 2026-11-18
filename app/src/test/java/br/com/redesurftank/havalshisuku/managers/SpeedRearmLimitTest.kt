package br.com.redesurftank.havalshisuku.managers

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedRearmLimitTest {
    @Test
    fun lowThresholdsKeepTheOriginalTenKmhFloor() {
        assertEquals(10f, ServiceManager.speedRearmLimit(15f), 0.001f)
        assertEquals(10f, ServiceManager.speedRearmLimit(30f), 0.001f)
    }

    @Test
    fun highwayThresholdsRearmTwentyKmhBelowTheLimit() {
        assertEquals(100f, ServiceManager.speedRearmLimit(120f), 0.001f)
        assertEquals(60f, ServiceManager.speedRearmLimit(80f), 0.001f)
    }
}
