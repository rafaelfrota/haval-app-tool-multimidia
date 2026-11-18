package br.com.redesurftank.havalshisuku.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import br.com.redesurftank.havalshisuku.diagnostics.ClusterPersistentEventLogger
import br.com.redesurftank.havalshisuku.managers.AndroidAutoClusterController
import br.com.redesurftank.havalshisuku.managers.AndroidAutoNavigationTelemetry
import br.com.redesurftank.havalshisuku.navigation.CarPlayGuidanceMapper

/**
 * Setas do GPS do CarPlay (Apple Maps, Google Maps, Waze no iPhone) no painel e no HUD.
 *
 * O serviço CarPlay da TS (`com.ts.carplay/.CarPlayService`, o mesmo que o
 * [CarPlayNowPlayingMonitor] já usa para a música) tem `startRouteGuidanceUpdates(callback)` na
 * transação 7. O iPhone manda o RouteGuidanceUpdate do iAP2; a TS entrega como
 * `RouteGuidanceInfo` no `onRouteGuidanceUpdate` (transação 1 do callback). Aqui ele vira o mesmo
 * [AndroidAutoNavigationTelemetry.Directions] do Android Auto e segue pelo caminho que os temas e o
 * HUD já desenham.
 *
 * Só publica "sem navegação" se foi ele quem publicou a rota — nunca apaga uma rota do Android Auto.
 * Cada manobra nova grava uma linha `cp_nav_raw` no log persistente, com os valores crus, para
 * conferir o mapeamento no carro.
 */
object CarPlayGuidanceMonitor {
    private const val TAG = "CarPlayGuidance"
    private const val CARPLAY_PACKAGE = "com.ts.carplay"
    private const val CARPLAY_SERVICE_CLASS = "com.ts.carplay.CarPlayService"
    private const val CARPLAY_SERVICE_ACTION = "com.ts.carplay.action.CarPlayService"
    private const val SERVICE_DESCRIPTOR = "com.ts.carplay.common.aidl.ICarPlayService"
    private const val CALLBACK_DESCRIPTOR = "com.ts.carplay.common.aidl.IRouteGuidanceUpdateCallback"

    private const val TRANSACTION_START_ROUTE_GUIDANCE = 7
    private const val TRANSACTION_STOP_ROUTE_GUIDANCE = 8
    private const val TRANSACTION_ON_ROUTE_GUIDANCE_UPDATE = 1

    private const val WATCHDOG_MS = 10_000L
    /** Sem notícia por esse tempo, assina de novo (o iPhone pode ter reconectado). */
    private const val RESUBSCRIBE_MS = 60_000L
    /** Com rota publicada e mudo por esse tempo, some com a seta. */
    private const val STALE_MS = 120_000L

    private val main = Handler(Looper.getMainLooper())
    private val callback = GuidanceCallback()

    private var appContext: Context? = null
    @Volatile private var binder: IBinder? = null
    private var bound = false
    private var started = false
    private var lastSubscribeAtMs = 0L
    @Volatile private var lastUpdateAtMs = 0L
    /** Foi o CarPlay quem publicou a rota que esta na tela. */
    @Volatile private var ownsDirections = false
    @Volatile private var lastRawSignature: String? = null

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service
                subscribe("connected")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                end("service disconnected")
            }

            override fun onBindingDied(name: ComponentName?) {
                binder = null
                bound = false
                end("binding died")
            }
        }

    private val watchdog =
        object : Runnable {
            override fun run() {
                try {
                    tick()
                } catch (t: Throwable) {
                    Log.w(TAG, "watchdog falhou", t)
                }
                main.postDelayed(this, WATCHDOG_MS)
            }
        }

    fun start(context: Context) {
        main.post {
            if (started) return@post
            started = true
            appContext = context.applicationContext
            Log.w(TAG, "Starting CarPlay route guidance monitor")
            bind()
            main.postDelayed(watchdog, WATCHDOG_MS)
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val b = binder
        if (b == null || !b.isBinderAlive) {
            bind()
            return
        }
        if (ownsDirections && lastUpdateAtMs > 0 && now - lastUpdateAtMs > STALE_MS) {
            end("no route guidance for ${STALE_MS} ms")
        }
        val quietFor = if (lastUpdateAtMs > 0) now - lastUpdateAtMs else Long.MAX_VALUE
        if (quietFor > RESUBSCRIBE_MS && now - lastSubscribeAtMs > RESUBSCRIBE_MS) {
            subscribe("watchdog")
        }
    }

    private fun bind() {
        val ctx = appContext ?: return
        if (bound) {
            runCatching { ctx.unbindService(connection) }
            bound = false
        }
        val explicit = Intent().apply { component = ComponentName(CARPLAY_PACKAGE, CARPLAY_SERVICE_CLASS) }
        bound = runCatching { ctx.bindService(explicit, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) {
            val byAction = Intent(CARPLAY_SERVICE_ACTION).apply { setPackage(CARPLAY_PACKAGE) }
            bound = runCatching { ctx.bindService(byAction, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        }
        if (!bound) Log.w(TAG, "bindService do CarPlay recusado; tento de novo em ${WATCHDOG_MS} ms")
    }

    private fun subscribe(reason: String) {
        val b = binder ?: return
        lastSubscribeAtMs = SystemClock.elapsedRealtime()
        transactCallback(b, TRANSACTION_STOP_ROUTE_GUIDANCE)
        val ok = transactCallback(b, TRANSACTION_START_ROUTE_GUIDANCE)
        Log.w(TAG, "startRouteGuidanceUpdates ($reason) ok=$ok")
        ClusterPersistentEventLogger.log("cp_nav_subscribe", mapOf("reason" to reason, "ok" to ok))
    }

    private fun transactCallback(b: IBinder, code: Int): Boolean {
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeStrongBinder(callback)
            b.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (t: Throwable) {
            Log.w(TAG, "transact $code falhou", t)
            false
        } finally {
            data.recycle()
        }
    }

    private fun end(reason: String) {
        if (!ownsDirections) return
        ownsDirections = false
        lastRawSignature = null
        Log.w(TAG, "Clearing CarPlay directions: $reason")
        AndroidAutoClusterController.onExternalNavigationUpdate(AndroidAutoNavigationTelemetry.inactive())
    }

    private fun onUpdate(raw: CarPlayGuidanceMapper.Raw) {
        lastUpdateAtMs = SystemClock.elapsedRealtime()
        logRawOnChange(raw)
        when (val outcome = CarPlayGuidanceMapper.map(raw)) {
            is CarPlayGuidanceMapper.Outcome.Publish -> {
                ownsDirections = true
                AndroidAutoClusterController.onExternalNavigationUpdate(outcome.directions)
            }
            CarPlayGuidanceMapper.Outcome.End -> end("state=${raw.state}")
            CarPlayGuidanceMapper.Outcome.Keep -> Unit
        }
    }

    private fun logRawOnChange(raw: CarPlayGuidanceMapper.Raw) {
        // Distância muda a cada metro; a assinatura é só o que identifica a manobra.
        val signature = "${raw.state}|${raw.maneuverType}|${raw.afterManeuverRoad}|${raw.junctionType}|${raw.exitAngle}"
        if (signature == lastRawSignature) return
        lastRawSignature = signature
        Log.w(TAG, "CP_NAV $raw")
        ClusterPersistentEventLogger.log(
            "cp_nav_raw",
            mapOf(
                "state" to raw.state,
                "maneuver" to raw.maneuverType,
                "dist" to raw.distanceStr,
                "dist_units" to raw.distanceUnits,
                "road" to raw.afterManeuverRoad,
                "remaining" to raw.remainingStr,
                "remaining_units" to raw.remainingUnits,
                "time" to raw.timeRemaining,
                "junction" to raw.junctionType,
                "side" to raw.drivingSide,
                "angle" to raw.exitAngle,
            ),
        )
    }

    private fun read(parcel: Parcel): CarPlayGuidanceMapper.Raw =
        CarPlayGuidanceMapper.Raw(
            maneuverType = parcel.readInt(),
            distanceStr = parcel.readString(),
            distanceUnits = parcel.readInt(),
            afterManeuverRoad = parcel.readString(),
            remainingStr = parcel.readString(),
            remainingUnits = parcel.readInt(),
            timeRemaining = parcel.readLong(),
            state = parcel.readInt(),
            junctionType = parcel.readInt(),
            drivingSide = parcel.readInt(),
            exitAngle = parcel.readInt(),
        )

    private class GuidanceCallback : Binder() {
        private val localInterface =
            object : IInterface {
                override fun asBinder(): IBinder = this@GuidanceCallback
            }

        init {
            attachInterface(localInterface, CALLBACK_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(CALLBACK_DESCRIPTOR)
                    true
                }
                TRANSACTION_ON_ROUTE_GUIDANCE_UPDATE -> {
                    try {
                        data.enforceInterface(CALLBACK_DESCRIPTOR)
                        if (data.readInt() != 0) {
                            val raw = read(data)
                            main.post { onUpdate(raw) }
                        } else {
                            Log.w(TAG, "onRouteGuidanceUpdate sem dados")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "onRouteGuidanceUpdate ilegível", t)
                        ClusterPersistentEventLogger.log("cp_nav_parse_failed", mapOf("error" to (t.message ?: t.javaClass.simpleName)))
                    }
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }
}
