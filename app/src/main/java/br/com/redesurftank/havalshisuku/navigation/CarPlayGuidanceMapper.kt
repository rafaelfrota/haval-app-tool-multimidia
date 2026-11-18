package br.com.redesurftank.havalshisuku.navigation

import br.com.redesurftank.havalshisuku.managers.AndroidAutoNavigationTelemetry as Nav
import java.util.Calendar
import java.util.Locale

/**
 * Converte o `RouteGuidanceInfo` do serviço CarPlay da TS (iAP2 RouteGuidanceUpdate) para o mesmo
 * [Nav.Directions] que o Android Auto já publica — assim o painel (tema do cluster) e o HUD
 * mostram as setas do CarPlay sem nenhuma mudança neles.
 *
 * Campos, na ordem do Parcel (ver docs/architecture/carplay-tbt-preparation.md do upstream):
 * maneuverType, distanceBetweenManeuverStr, distanceBetweenManeuverUnits, afterManeuverRoadName,
 * distanceRemainingStr, distanceRemainingUnits, timeRemainingToDestination, routeGuidanceState,
 * junctionType, drivingSide, junctionElementExitAngle.
 *
 * O enum de manobra é o ManeuverType do iAP2 (0..53). Cada valor vira o número de manobra do AA
 * (o mesmo `turnId` que os temas já desenham); o valor cru do CarPlay vai para o log, para conferir
 * no carro.
 */
object CarPlayGuidanceMapper {

    data class Raw(
        val maneuverType: Int,
        val distanceStr: String?,
        val distanceUnits: Int,
        val afterManeuverRoad: String?,
        val remainingStr: String?,
        val remainingUnits: Int,
        val timeRemaining: Long,
        val state: Int,
        val junctionType: Int,
        val drivingSide: Int,
        val exitAngle: Int,
    )

    // RouteGuidanceState do iAP2.
    const val STATE_NO_ROUTE = 0
    const val STATE_ROUTE_SET = 1
    const val STATE_ARRIVED = 2
    const val STATE_LOADING = 3
    const val STATE_LOCATING = 4
    const val STATE_REROUTING = 5
    const val STATE_PROCEED_TO_ROUTE = 6

    // Unidades do iAP2.
    const val UNIT_KM = 0
    const val UNIT_MILES = 1
    const val UNIT_M = 2
    const val UNIT_YARDS = 3
    const val UNIT_FT = 4

    /** Resultado: null = nao mexer no que esta publicado (carregando/localizando). */
    sealed class Outcome {
        data class Publish(val directions: Nav.Directions) : Outcome()
        object End : Outcome()
        object Keep : Outcome()
    }

    fun map(raw: Raw, nowMs: Long = System.currentTimeMillis()): Outcome {
        return when (raw.state) {
            STATE_NO_ROUTE, STATE_ARRIVED -> Outcome.End
            STATE_ROUTE_SET, STATE_REROUTING, STATE_PROCEED_TO_ROUTE -> Outcome.Publish(directions(raw, nowMs))
            else -> Outcome.Keep
        }
    }

    fun directions(raw: Raw, nowMs: Long): Nav.Directions {
        val leftHandTraffic = raw.drivingSide == 1
        val maneuver = aaManeuver(raw.maneuverType, leftHandTraffic)
        val exit = roundaboutExitNumber(raw.maneuverType)
        val roundabout = maneuver != Nav.MANEUVER_UNKNOWN && Nav.isRoundabout(maneuver)
        val remainingS = remainingSeconds(raw.timeRemaining)
        return Nav.Directions(
            active = true,
            street = raw.afterManeuverRoad?.trim().orEmpty(),
            distance = displayDistance(raw.distanceStr, raw.distanceUnits),
            distanceM = meters(raw.distanceStr, raw.distanceUnits),
            turn = Nav.turnFromManeuver(maneuver),
            turnId = maneuver.takeIf { it != Nav.MANEUVER_UNKNOWN },
            remainingM = meters(raw.remainingStr, raw.remainingUnits),
            remainingS = remainingS,
            eta = remainingS?.let { etaClock(nowMs, it) }.orEmpty(),
            exitNumber = if (roundabout) exit else null,
            roundaboutClockwise = if (roundabout) leftHandTraffic else null,
        )
    }

    /** ManeuverType do iAP2 -> manobra do AA (com o lado da via ja embutido). */
    fun aaManeuver(type: Int, leftHandTraffic: Boolean): Int {
        val uTurn = if (leftHandTraffic) Nav.MANEUVER_U_TURN_RIGHT else Nav.MANEUVER_U_TURN_LEFT
        val ringEnter = if (leftHandTraffic) Nav.MANEUVER_ROUNDABOUT_ENTER_CW else Nav.MANEUVER_ROUNDABOUT_ENTER_CCW
        val ringExit = if (leftHandTraffic) Nav.MANEUVER_ROUNDABOUT_EXIT_CW else Nav.MANEUVER_ROUNDABOUT_EXIT_CCW
        val ringFull =
            if (leftHandTraffic) Nav.MANEUVER_ROUNDABOUT_ENTER_AND_EXIT_CW
            else Nav.MANEUVER_ROUNDABOUT_ENTER_AND_EXIT_CCW
        val offRamp = if (leftHandTraffic) Nav.MANEUVER_OFF_RAMP_NORMAL_LEFT else Nav.MANEUVER_OFF_RAMP_NORMAL_RIGHT
        val onRamp = if (leftHandTraffic) Nav.MANEUVER_ON_RAMP_NORMAL_LEFT else Nav.MANEUVER_ON_RAMP_NORMAL_RIGHT
        return when (type) {
            0, 3, 5 -> Nav.MANEUVER_STRAIGHT // sem curva / em frente / siga a via
            1, 20 -> Nav.MANEUVER_TURN_NORMAL_LEFT
            2, 21 -> Nav.MANEUVER_TURN_NORMAL_RIGHT
            4, 18, 26 -> uTurn
            6 -> ringEnter
            7 -> ringExit
            8 -> offRamp
            9 -> onRamp
            10, 12, 27 -> Nav.MANEUVER_DESTINATION
            11 -> Nav.MANEUVER_DEPART
            13, 52 -> Nav.MANEUVER_KEEP_LEFT
            14, 53 -> Nav.MANEUVER_KEEP_RIGHT
            15, 16, 17 -> Nav.MANEUVER_FERRY_BOAT
            19 -> ringFull
            22 -> Nav.MANEUVER_OFF_RAMP_NORMAL_LEFT
            23 -> Nav.MANEUVER_OFF_RAMP_NORMAL_RIGHT
            24 -> Nav.MANEUVER_DESTINATION_LEFT
            25 -> Nav.MANEUVER_DESTINATION_RIGHT
            in 28..46 -> ringFull
            47 -> Nav.MANEUVER_TURN_SHARP_LEFT
            48 -> Nav.MANEUVER_TURN_SHARP_RIGHT
            49 -> Nav.MANEUVER_TURN_SLIGHT_LEFT
            50 -> Nav.MANEUVER_TURN_SLIGHT_RIGHT
            51 -> Nav.MANEUVER_MERGE_SIDE_UNSPECIFIED
            else -> Nav.MANEUVER_UNKNOWN
        }
    }

    /** RoundaboutExit1..19 = 28..46. */
    fun roundaboutExitNumber(type: Int): Int? = if (type in 28..46) type - 27 else null

    fun unitSuffix(units: Int): String = when (units) {
        UNIT_KM -> "km"
        UNIT_MILES -> "mi"
        UNIT_M -> "m"
        UNIT_YARDS -> "yd"
        UNIT_FT -> "ft"
        else -> ""
    }

    fun displayDistance(value: String?, units: Int): String {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return ""
        val suffix = unitSuffix(units)
        return if (suffix.isEmpty()) v else "$v $suffix"
    }

    /** "1,2" km -> 1200. Null quando a string nao e numero. */
    fun meters(value: String?, units: Int): Int? {
        val number = value?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
        if (number < 0) return null
        val factor = when (units) {
            UNIT_KM -> 1000.0
            UNIT_MILES -> 1609.344
            UNIT_M -> 1.0
            UNIT_YARDS -> 0.9144
            UNIT_FT -> 0.3048
            else -> return null
        }
        return Math.round(number * factor).toInt()
    }

    /**
     * O iAP2 manda segundos. Se vier em milissegundos (alguma pilha converte), corrige; acima de
     * dois dias e lixo.
     */
    fun remainingSeconds(raw: Long): Int? {
        if (raw <= 0) return null
        val twoDays = 2L * 24 * 3600
        val seconds = when {
            raw <= twoDays -> raw
            raw <= twoDays * 1000 -> raw / 1000
            else -> return null
        }
        return seconds.toInt()
    }

    fun etaClock(nowMs: Long, remainingS: Int): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs + remainingS * 1000L
        return String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
}
