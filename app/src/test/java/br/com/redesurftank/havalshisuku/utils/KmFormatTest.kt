package br.com.redesurftank.havalshisuku.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class KmFormatTest {
    @Test
    fun groupsThousandsWithDots() {
        assertEquals("0", KmFormat.format(0))
        assertEquals("999", KmFormat.format(999))
        assertEquals("12.000", KmFormat.format(12000))
        assertEquals("1.234.567", KmFormat.format(1234567))
    }

    @Test
    fun cursorMapsBothWays() {
        val f = KmFormat.group("12000") // 12.000
        assertEquals(0, KmFormat.toTransformed(f, 0))
        assertEquals(2, KmFormat.toTransformed(f, 2))
        assertEquals(4, KmFormat.toTransformed(f, 3))
        assertEquals(6, KmFormat.toTransformed(f, 5))
        assertEquals(2, KmFormat.toOriginal(f, 3))
        assertEquals(5, KmFormat.toOriginal(f, 6))
        for (o in 0..5) assertEquals(o, KmFormat.toOriginal(f, KmFormat.toTransformed(f, o)))
    }
}
