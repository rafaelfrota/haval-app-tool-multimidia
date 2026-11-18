package br.com.redesurftank.havalshisuku.energy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEnergyTest {
    private val prices = TripEnergy.Prices(tariff = 0.91, fuelPrice = 6.79, chargeLoss = 0.10, tankLiters = 55.0)

    private fun sample(t: Long, v: Double, a: Double, kmh: Double, odo: Double?, avg: Double? = null) =
        TripEnergy.Sample(t, v, a, 0.0, kmh, odo, 80.0, 50.0, avg, charging = false)

    @Test
    fun integratesDischargeAndRegenSeparately() {
        var trip = TripEnergy(startedAtMs = 0)
        var prev = 0L
        // 1 hora a 10 kW (400 V x 25 A), depois 6 min regenerando 5 kW.
        for (i in 1..3600) { trip = trip.add(sample(i * 1000L, 400.0, 25.0, 50.0, null), prev, engineOn = false); prev = i * 1000L }
        for (i in 3601..3960) { trip = trip.add(sample(i * 1000L, 400.0, -12.5, 30.0, null), prev, engineOn = false); prev = i * 1000L }
        assertEquals(10.0, trip.usedKwh, 0.01)
        assertEquals(0.5, trip.regenKwh, 0.01)
        val s = trip.summary(prices)
        assertEquals(9.5, s.netKwh, 0.01)
        assertEquals(9.5 / 0.9 * 0.91, s.energyCost, 0.01)
        assertEquals(1.0, s.evShare!!, 0.001)
    }

    @Test
    fun ignoresGapsAndBrokenReadings() {
        var trip = TripEnergy(startedAtMs = 0)
        trip = trip.add(sample(60_000, 400.0, 25.0, 50.0, null), 0, engineOn = false) // buraco de 60 s
        trip = trip.add(sample(61_000, 400.0, 9_999.0, 50.0, null), 60_000, engineOn = false) // 4 MW
        assertEquals(0.0, trip.usedKwh, 1e-9)
    }

    @Test
    fun fuelFromCarAverageAndOdometer() {
        var trip = TripEnergy(startedAtMs = 0)
        trip = trip.add(sample(1000, 400.0, 0.0, 60.0, 10_000.0, avg = 5.0), 0, engineOn = true)
        trip = trip.add(sample(2000, 400.0, 0.0, 60.0, 10_020.0, avg = 5.0), 1000, engineOn = true)
        val s = trip.summary(prices)
        assertEquals(20.0, s.distanceKm, 0.001)
        assertEquals(1.0, s.liters!!, 0.001)
        assertEquals(6.79, s.fuelCost!!, 0.001)
        // Só 1 s de movimento medido: pouco para dizer a parte no elétrico.
        assertNull(s.evShare)
    }

    @Test
    fun parsesCarFuelFormats() {
        assertEquals(6.5, TripEnergy.parseFuelConsumption("{1,6.5}")!!, 1e-9)
        assertEquals(6.5, TripEnergy.parseFuelConsumption("6,5")!!, 1e-9)
        assertNull(TripEnergy.parseFuelConsumption("--"))
        assertNull(TripEnergy.parseFuelConsumption("{2,6.5}"))
    }

    @Test
    fun jsonRoundTrip() {
        val trip = TripEnergy(startedAtMs = 5, updatedAtMs = 9, usedKwh = 1.5, movingKm = 3.0, odoStartKm = 100.0, avgL100 = 4.0)
        assertEquals(trip, TripEnergy.fromJson(trip.toJson()))
        assertTrue(trip.worthKeeping())
    }
}
