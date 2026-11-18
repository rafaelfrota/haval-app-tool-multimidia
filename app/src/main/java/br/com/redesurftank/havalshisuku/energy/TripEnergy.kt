package br.com.redesurftank.havalshisuku.energy

import org.json.JSONObject
import kotlin.math.abs

/**
 * Uma viagem do ponto de vista da energia: o que saiu e voltou da bateria, quanto rodou no elétrico,
 * quanta gasolina e quanto custou.
 *
 * Tudo aqui é conta pura (testável na JVM); quem lê o carro e guarda é o [TripEnergyTracker].
 *
 * Convenção de potência: kW = tensão da bateria x `cur_charge_current` / 1000, positivo descarregando
 * (empurrando o carro) e negativo regenerando — a mesma do fluxo de energia (PowerFlowMapper).
 */
data class TripEnergy(
    val startedAtMs: Long,
    val updatedAtMs: Long = startedAtMs,
    val usedKwh: Double = 0.0,
    val regenKwh: Double = 0.0,
    val movingKm: Double = 0.0,
    val evKm: Double = 0.0,
    val odoStartKm: Double? = null,
    val odoEndKm: Double? = null,
    val socStart: Double? = null,
    val socEnd: Double? = null,
    val fuelPctStart: Double? = null,
    val fuelPctEnd: Double? = null,
    /** Média de consumo da viagem que o carro mostra (L/100 km), a última lida. */
    val avgL100: Double? = null,
) {
    /** Uma leitura do carro. NaN/null = não veio. */
    data class Sample(
        val nowMs: Long,
        val voltage: Double,
        val current: Double,
        val rpm: Double,
        val speedKmh: Double,
        val odometerKm: Double?,
        val soc: Double?,
        val fuelPct: Double?,
        val avgL100: Double?,
        val charging: Boolean,
    )

    data class Prices(
        /** R$ por kWh na conta de luz. */
        val tariff: Double,
        /** R$ por litro de gasolina. */
        val fuelPrice: Double,
        /** Perda na recarga (0.10 = 10%): para pôr 1 kWh na bateria, sai mais da tomada. */
        val chargeLoss: Double,
        /** Litros do tanque, para o plano B pelo nível. */
        val tankLiters: Double,
    )

    data class Summary(
        val distanceKm: Double,
        val usedKwh: Double,
        val regenKwh: Double,
        val netKwh: Double,
        val evShare: Double?,
        val liters: Double?,
        val energyCost: Double,
        val fuelCost: Double?,
        val totalCost: Double,
        val costPerKm: Double?,
        val kwhPer100Km: Double?,
        val durationMin: Long,
    )

    fun add(sample: Sample, previousAtMs: Long, engineOn: Boolean): TripEnergy {
        val dtMs = sample.nowMs - previousAtMs
        // Buraco grande (app parado, central dormindo): não inventa energia no meio.
        val dtH = if (dtMs in 1..MAX_STEP_MS) dtMs / 3_600_000.0 else 0.0
        var used = usedKwh
        var regen = regenKwh
        var moving = movingKm
        var ev = evKm
        if (dtH > 0 && !sample.charging) {
            val kw = sample.voltage * sample.current / 1000.0
            if (kw.isFinite() && abs(kw) <= MAX_ABS_KW) {
                if (kw > 0) used += kw * dtH else regen += -kw * dtH
            }
            val speed = sample.speedKmh
            if (speed.isFinite() && speed in 0.5..250.0) {
                val km = speed * dtH
                moving += km
                if (!engineOn) ev += km
            }
        }
        val odo = sample.odometerKm?.takeIf { it.isFinite() && it > 0 }
        return copy(
            updatedAtMs = sample.nowMs,
            usedKwh = used,
            regenKwh = regen,
            movingKm = moving,
            evKm = ev,
            odoStartKm = odoStartKm ?: odo,
            odoEndKm = odo ?: odoEndKm,
            socStart = socStart ?: sample.soc?.takeIf { it in 0.0..100.0 },
            socEnd = sample.soc?.takeIf { it in 0.0..100.0 } ?: socEnd,
            fuelPctStart = fuelPctStart ?: sample.fuelPct?.takeIf { it in 0.0..100.0 },
            fuelPctEnd = sample.fuelPct?.takeIf { it in 0.0..100.0 } ?: fuelPctEnd,
            avgL100 = sample.avgL100?.takeIf { it > 0 && it < 40 } ?: avgL100,
        )
    }

    fun summary(prices: Prices): Summary {
        val odoKm = if (odoStartKm != null && odoEndKm != null && odoEndKm >= odoStartKm) odoEndKm - odoStartKm else null
        // O hodômetro anda de km em km; em viagem curta a soma da velocidade é mais fiel.
        val distance = when {
            odoKm == null -> movingKm
            odoKm < 3 -> maxOf(movingKm, odoKm)
            else -> odoKm
        }
        val net = usedKwh - regenKwh
        val liters =
            avgL100?.let { it * distance / 100.0 }
                ?: run {
                    val s = fuelPctStart
                    val e = fuelPctEnd
                    if (s != null && e != null && s > e) (s - e) / 100.0 * prices.tankLiters else null
                }
        val gridKwh = maxOf(net, 0.0) / (1.0 - prices.chargeLoss.coerceIn(0.0, 0.5))
        val energyCost = gridKwh * prices.tariff
        val fuelCost = liters?.let { it * prices.fuelPrice }
        val total = energyCost + (fuelCost ?: 0.0)
        return Summary(
            distanceKm = distance,
            usedKwh = usedKwh,
            regenKwh = regenKwh,
            netKwh = net,
            evShare = if (movingKm > 0.2) (evKm / movingKm).coerceIn(0.0, 1.0) else null,
            liters = liters,
            energyCost = energyCost,
            fuelCost = fuelCost,
            totalCost = total,
            costPerKm = if (distance >= 0.5) total / distance else null,
            kwhPer100Km = if (distance >= 0.5) net / distance * 100.0 else null,
            durationMin = (updatedAtMs - startedAtMs) / 60_000L,
        )
    }

    /** Uma viagem só vale a pena guardar se andou. */
    fun worthKeeping(): Boolean = movingKm >= 0.3 || (odoStartKm != null && odoEndKm != null && odoEndKm - odoStartKm >= 1)

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("startedAtMs", startedAtMs)
            put("updatedAtMs", updatedAtMs)
            put("usedKwh", usedKwh)
            put("regenKwh", regenKwh)
            put("movingKm", movingKm)
            put("evKm", evKm)
            odoStartKm?.let { put("odoStartKm", it) }
            odoEndKm?.let { put("odoEndKm", it) }
            socStart?.let { put("socStart", it) }
            socEnd?.let { put("socEnd", it) }
            fuelPctStart?.let { put("fuelPctStart", it) }
            fuelPctEnd?.let { put("fuelPctEnd", it) }
            avgL100?.let { put("avgL100", it) }
        }

    companion object {
        /** Passo maior que isso entre leituras não conta (central parada, app reiniciado). */
        const val MAX_STEP_MS = 10_000L
        /** A bateria do H6 não passa disso; acima é leitura quebrada. */
        const val MAX_ABS_KW = 250.0

        fun fromJson(o: JSONObject): TripEnergy =
            TripEnergy(
                startedAtMs = o.getLong("startedAtMs"),
                updatedAtMs = o.optLong("updatedAtMs", o.getLong("startedAtMs")),
                usedKwh = o.optDouble("usedKwh", 0.0),
                regenKwh = o.optDouble("regenKwh", 0.0),
                movingKm = o.optDouble("movingKm", 0.0),
                evKm = o.optDouble("evKm", 0.0),
                odoStartKm = o.optNullableDouble("odoStartKm"),
                odoEndKm = o.optNullableDouble("odoEndKm"),
                socStart = o.optNullableDouble("socStart"),
                socEnd = o.optNullableDouble("socEnd"),
                fuelPctStart = o.optNullableDouble("fuelPctStart"),
                fuelPctEnd = o.optNullableDouble("fuelPctEnd"),
                avgL100 = o.optNullableDouble("avgL100"),
            )

        private fun JSONObject.optNullableDouble(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

        /**
         * O carro manda número puro ("6.5") ou o par `{unidade,valor}` ("{1,6.5}"). Mesma leitura
         * do Score de Consistência.
         */
        fun parseFuelConsumption(raw: String?): Double? {
            val value = raw?.trim()?.takeIf { it.isNotBlank() && it != "--" } ?: return null
            if (value.startsWith("{") && value.endsWith("}") && value.contains(",")) {
                val parts = value.substring(1, value.length - 1).split(",")
                if (parts.size >= 2) {
                    val metric = parts[0].trim().toDoubleOrNull()
                    val consumption = parts[1].trim().toDoubleOrNull()
                    if ((metric == 1.0 || metric == 4.0) && consumption != null && consumption > 0.0) return consumption
                }
                return null
            }
            return value.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
        }

        fun parseNumber(raw: String?): Double? = raw?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
