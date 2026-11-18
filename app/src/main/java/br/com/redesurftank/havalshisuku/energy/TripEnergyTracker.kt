package br.com.redesurftank.havalshisuku.energy

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import br.com.redesurftank.App
import br.com.redesurftank.havalshisuku.diagnostics.ClusterPersistentEventLogger
import br.com.redesurftank.havalshisuku.managers.PowerFlowTracker
import br.com.redesurftank.havalshisuku.managers.ServiceManager
import br.com.redesurftank.havalshisuku.models.CarConstants
import br.com.redesurftank.havalshisuku.models.SharedPreferencesKeys
import org.json.JSONArray

/**
 * Lê o carro uma vez por segundo e soma a energia da viagem ([TripEnergy]).
 *
 * Viagem = do momento em que o app sobe (a central liga junto com o carro) até a última leitura.
 * A viagem corrente é guardada a cada 30 s; se o app subir de novo em menos de 10 min (reinício no
 * meio do caminho), ela continua; se não, vai para o histórico (últimas 20) e começa outra.
 */
object TripEnergyTracker {
    private const val TAG = "TripEnergy"
    private const val SAMPLE_MS = 1_000L
    private const val SAVE_EVERY_SAMPLES = 30
    private const val RESUME_WINDOW_MS = 10 * 60_000L
    private const val HISTORY_MAX = 20
    private const val LOG_EVERY_SAMPLES = 60

    const val DEFAULT_TARIFF = 0.91
    const val DEFAULT_FUEL_PRICE = 6.79
    const val DEFAULT_CHARGE_LOSS = 0.10
    const val DEFAULT_TANK_LITERS = 55.0

    private val thread by lazy { HandlerThread("TripEnergy").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }

    @Volatile private var started = false
    @Volatile var current: TripEnergy? = null
        private set
    private var lastSampleAtMs = 0L
    private var engineOn = false
    private var samples = 0

    private fun prefs(): SharedPreferences =
        App.getDeviceProtectedContext().getSharedPreferences("haval_prefs", Context.MODE_PRIVATE)

    fun start() {
        if (started) return
        started = true
        handler.post {
            restoreOrArchive()
            handler.post(sampler)
        }
        Log.w(TAG, "Trip energy tracker started")
    }

    private val sampler =
        object : Runnable {
            override fun run() {
                try {
                    sample()
                } catch (t: Throwable) {
                    Log.w(TAG, "falha na leitura", t)
                }
                handler.postDelayed(this, SAMPLE_MS)
            }
        }

    private fun read(sm: ServiceManager, key: CarConstants): String? = runCatching { sm.getData(key.value) }.getOrNull()

    private fun sample() {
        val sm = ServiceManager.getInstance() ?: return
        val now = System.currentTimeMillis()
        val rpm = TripEnergy.parseNumber(read(sm, CarConstants.CAR_BASIC_ENGINE_SPEED)) ?: Double.NaN
        engineOn = PowerFlowTracker.rpmHysteresis(engineOn, rpm)
        val s =
            TripEnergy.Sample(
                nowMs = now,
                voltage = TripEnergy.parseNumber(read(sm, CarConstants.CAR_EV_INFO_POWER_BATTERY_VOLTAGE)) ?: Double.NaN,
                current = TripEnergy.parseNumber(read(sm, CarConstants.CAR_EV_INFO_CUR_CHARGE_CURRENT)) ?: Double.NaN,
                rpm = rpm,
                speedKmh = TripEnergy.parseNumber(read(sm, CarConstants.CAR_BASIC_VEHICLE_SPEED)) ?: Double.NaN,
                odometerKm = TripEnergy.parseNumber(read(sm, CarConstants.CAR_BASIC_TOTAL_ODOMETER)),
                soc = TripEnergy.parseNumber(read(sm, CarConstants.CAR_EV_INFO_CUR_BATTERY_POWER_PERCENTAGE)),
                fuelPct = TripEnergy.parseNumber(read(sm, CarConstants.CAR_BASIC_REMAIN_FUEL_PERCENTAGE)),
                avgL100 = TripEnergy.parseFuelConsumption(read(sm, CarConstants.CAR_BASIC_CUR_JOURNEY_AVG_FUEL_CONSUME)),
                charging = read(sm, CarConstants.CAR_EV_INFO_CHARGING_STATE)?.trim() == "1",
            )
        val trip = current ?: TripEnergy(startedAtMs = now)
        val previous = if (lastSampleAtMs > 0) lastSampleAtMs else now
        current = trip.add(s, previous, engineOn)
        lastSampleAtMs = now
        samples++
        if (samples % SAVE_EVERY_SAMPLES == 0) save()
        if (samples % LOG_EVERY_SAMPLES == 0) {
            // Para conferir as unidades no carro (tensão, corrente, consumo médio).
            ClusterPersistentEventLogger.log(
                "trip_energy_sample",
                mapOf(
                    "v" to s.voltage,
                    "a" to s.current,
                    "rpm" to s.rpm,
                    "kmh" to s.speedKmh,
                    "odo" to s.odometerKm,
                    "soc" to s.soc,
                    "fuel_pct" to s.fuelPct,
                    "avg_l100" to s.avgL100,
                    "used_kwh" to current?.usedKwh,
                    "regen_kwh" to current?.regenKwh,
                ),
            )
        }
    }

    private fun restoreOrArchive() {
        val raw = prefs().getString(SharedPreferencesKeys.TRIP_ENERGY_CURRENT.key, null) ?: return
        val saved = runCatching { TripEnergy.fromJson(org.json.JSONObject(raw)) }.getOrNull() ?: return
        val age = System.currentTimeMillis() - saved.updatedAtMs
        if (age in 0..RESUME_WINDOW_MS) {
            current = saved
            Log.w(TAG, "continuando a viagem de ${saved.startedAtMs}")
        } else {
            archive(saved)
            prefs().edit().remove(SharedPreferencesKeys.TRIP_ENERGY_CURRENT.key).apply()
        }
    }

    private fun save() {
        val trip = current ?: return
        prefs().edit().putString(SharedPreferencesKeys.TRIP_ENERGY_CURRENT.key, trip.toJson().toString()).apply()
    }

    private fun archive(trip: TripEnergy) {
        if (!trip.worthKeeping()) return
        val list = history().toMutableList()
        list.add(0, trip)
        val arr = JSONArray()
        list.take(HISTORY_MAX).forEach { arr.put(it.toJson()) }
        prefs().edit().putString(SharedPreferencesKeys.TRIP_ENERGY_HISTORY.key, arr.toString()).apply()
    }

    fun history(): List<TripEnergy> {
        val raw = prefs().getString(SharedPreferencesKeys.TRIP_ENERGY_HISTORY.key, null) ?: return emptyList()
        return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { runCatching { TripEnergy.fromJson(arr.getJSONObject(it)) }.getOrNull() }
            }
            .getOrDefault(emptyList())
    }

    fun prices(): TripEnergy.Prices {
        val p = prefs()
        fun d(key: SharedPreferencesKeys, fallback: Double) =
            p.getString(key.key, null)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it >= 0 } ?: fallback
        return TripEnergy.Prices(
            tariff = d(SharedPreferencesKeys.TRIP_ENERGY_TARIFF, DEFAULT_TARIFF),
            fuelPrice = d(SharedPreferencesKeys.TRIP_ENERGY_FUEL_PRICE, DEFAULT_FUEL_PRICE),
            chargeLoss = d(SharedPreferencesKeys.TRIP_ENERGY_CHARGE_LOSS, DEFAULT_CHARGE_LOSS * 100) / 100.0,
            tankLiters = DEFAULT_TANK_LITERS,
        )
    }

    fun savePrice(key: SharedPreferencesKeys, value: String) {
        prefs().edit().putString(key.key, value).apply()
    }
}
