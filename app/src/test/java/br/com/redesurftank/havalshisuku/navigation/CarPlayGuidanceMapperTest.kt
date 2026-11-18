package br.com.redesurftank.havalshisuku.navigation

import br.com.redesurftank.havalshisuku.managers.AndroidAutoNavigationTelemetry as Nav
import br.com.redesurftank.havalshisuku.navigation.CarPlayGuidanceMapper as M
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayGuidanceMapperTest {
    private fun raw(man: Int, d: String?, du: Int, road: String?, state: Int = 1, side: Int = 0) =
        M.Raw(man, d, du, road, "12,4", M.UNIT_KM, 600, state, 0, side, 181)

    private fun publish(r: M.Raw) = (M.map(r) as M.Outcome.Publish).directions

    @Test
    fun rightTurnWithMeters() {
        val d = publish(raw(2, "300", M.UNIT_M, " Av. Djalma Batista "))
        assertEquals("TURN_RIGHT", d.turn)
        assertEquals(Nav.MANEUVER_TURN_NORMAL_RIGHT, d.turnId)
        assertEquals("300 m", d.distance)
        assertEquals(300, d.distanceM)
        assertEquals("Av. Djalma Batista", d.street)
        assertEquals(12400, d.remainingM)
        assertEquals(600, d.remainingS)
    }

    @Test
    fun roundaboutExitNumberAndDirection() {
        val d = publish(raw(30, "1,2", M.UNIT_KM, "Rua X"))
        assertEquals("ROUNDABOUT", d.turn)
        assertEquals(3, d.exitNumber)
        assertEquals(false, d.roundaboutClockwise)
        assertEquals(1200, d.distanceM)
    }

    @Test
    fun leftHandTrafficUturn() {
        assertEquals("U_TURN_RIGHT", publish(raw(4, "50", M.UNIT_M, null, side = 1)).turn)
    }

    @Test
    fun statesEndKeepOrPublish() {
        assertEquals(M.Outcome.End, M.map(raw(12, null, M.UNIT_M, null, state = M.STATE_ARRIVED)))
        assertEquals(M.Outcome.End, M.map(raw(0, null, M.UNIT_M, null, state = M.STATE_NO_ROUTE)))
        assertEquals(M.Outcome.Keep, M.map(raw(0, null, M.UNIT_M, null, state = M.STATE_LOADING)))
        assertTrue(M.map(raw(1, "10", M.UNIT_M, null, state = M.STATE_REROUTING)) is M.Outcome.Publish)
    }

    @Test
    fun unknownManeuverHasNoTurn() {
        val d = publish(raw(99, "1", M.UNIT_KM, null))
        assertEquals("", d.turn)
        assertNull(d.turnId)
    }

    @Test
    fun everyIap2ManeuverIsMapped() {
        assertTrue((0..53).all { M.aaManeuver(it, false) != Nav.MANEUVER_UNKNOWN })
    }

    @Test
    fun remainingSecondsSanity() {
        assertEquals(600, M.remainingSeconds(600))
        assertEquals(600, M.remainingSeconds(600_000))
        assertNull(M.remainingSeconds(0))
    }
}
