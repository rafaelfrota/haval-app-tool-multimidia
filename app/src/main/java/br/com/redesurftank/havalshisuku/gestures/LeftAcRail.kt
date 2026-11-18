package br.com.redesurftank.havalshisuku.gestures

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import br.com.redesurftank.App
import br.com.redesurftank.havalshisuku.listeners.IDataChanged
import br.com.redesurftank.havalshisuku.managers.ServiceManager
import br.com.redesurftank.havalshisuku.models.CarConstants
import br.com.redesurftank.havalshisuku.models.SharedPreferencesKeys
import br.com.redesurftank.havalshisuku.utils.ShizukuUtils
import java.util.Locale
import kotlin.math.abs

/**
 * Barra do ar que sai da BORDA ESQUERDA da tela central.
 *
 * Pensada para quem esconde o painel lateral do carro ("Manter o painel lateral esquerdo oculto"):
 * o CarPlay ganha a largura toda e o ar continua a um deslize de distancia, sem abrir menu nenhum.
 *
 * Sao duas janelas do WindowManager (nao Compose, para nao precisar de lifecycle de Activity):
 *
 *  - a ALCA: uma faixa estreita, colada na borda esquerda, no meio da altura. Ela pega o toque
 *    (precisa, senao nao veria o deslize), por isso e fina e nao cobre a tela inteira — o resto da
 *    borda continua indo para o app de baixo. Deslizar para a direita ou tocar abre o painel.
 *  - o PAINEL: temperatura do motorista e do passageiro, ventilacao, liga/desliga, A/C, Auto,
 *    Circular, Voltar e Inicio. Fecha sozinho depois de alguns segundos sem toque, ao tocar fora ou
 *    no X.
 *
 * Se o painel lateral do carro estiver visivel, a barra de navegacao dele fica por cima da alca —
 * nao atrapalha, so nao aparece. Por isso o texto da configuracao recomenda usar as duas juntas.
 */
object LeftAcRail {

    private const val TAG = "LeftAcRail"

    private const val TEMP_MIN = 16.0f
    private const val TEMP_MAX = 32.0f
    private const val TEMP_STEP = 0.5f
    private const val FAN_MAX = 7

    private const val AUTO_HIDE_MS = 8_000L
    private const val HANDLE_WIDTH_DP = 14
    private const val HANDLE_HEIGHT_FRACTION = 0.5f
    private const val PANEL_WIDTH_DP = 250
    private const val SWIPE_OPEN_DP = 18

    private const val COLOR_PANEL = 0xF0141A22.toInt()
    private const val COLOR_BUTTON = 0xFF232C38.toInt()
    private const val COLOR_ACTIVE = 0xFF2F8CFF.toInt()
    private const val COLOR_TEXT = 0xFFF2F5F8.toInt()
    private const val COLOR_MUTED = 0xFF93A1B2.toInt()
    private const val COLOR_HANDLE = 0x66FFFFFF

    private val KEY_DRIVER = CarConstants.CAR_HVAC_DRIVER_TEMPERATURE.value
    private val KEY_PASSENGER = CarConstants.CAR_HVAC_PASS_TEMPERATURE.value
    private val KEY_FAN = CarConstants.CAR_HVAC_FAN_SPEED.value
    private val KEY_POWER = CarConstants.CAR_HVAC_POWER_MODE.value
    private val KEY_AC = CarConstants.CAR_HVAC_AC_ENABLE.value
    private val KEY_AUTO = CarConstants.CAR_HVAC_AUTO_ENABLE.value
    private val KEY_CYCLE = CarConstants.CAR_HVAC_CYCLE_MODE.value
    private val WATCHED = setOf(KEY_DRIVER, KEY_PASSENGER, KEY_FAN, KEY_POWER, KEY_AC, KEY_AUTO, KEY_CYCLE)

    private val main = Handler(Looper.getMainLooper())
    private val worker: Handler by lazy {
        val t = HandlerThread("LeftAcRail")
        t.start()
        Handler(t.looper)
    }

    @Volatile private var enabled = false
    private var handleView: View? = null
    private var panel: Panel? = null

    /** Ultimos valores conhecidos (lidos ao abrir e atualizados pelo listener do carro). */
    private val values = HashMap<String, String>()

    private val hideRunnable = Runnable { closePanel() }

    private val listener =
        object : IDataChanged {
            override fun onDataChanged(key: String, value: String?) {
                if (value == null || key !in WATCHED) return
                main.post {
                    values[key] = value
                    panel?.refresh()
                }
            }
        }

    // ---------------------------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------------------------

    fun onServicesReady() {
        setEnabled(prefEnabled())
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        main.post {
            if (value) addHandle() else {
                closePanel()
                removeHandle()
            }
        }
    }

    fun prefEnabled(): Boolean =
        runCatching {
                App.getDeviceProtectedContext()
                    .getSharedPreferences("haval_prefs", Context.MODE_PRIVATE)
                    .getBoolean(SharedPreferencesKeys.LEFT_AC_RAIL_ENABLED.key, false)
            }
            .getOrDefault(false)

    private fun wm(): WindowManager =
        App.getContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v.toFloat(),
                App.getContext().resources.displayMetrics,
            )
            .toInt()

    // ---------------------------------------------------------------------------------------
    // Alca
    // ---------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun addHandle() {
        if (handleView != null || !enabled) return
        try {
            val ctx = App.getContext()
            val frame = FrameLayout(ctx)
            val pill =
                View(ctx).apply {
                    background =
                        GradientDrawable().apply {
                            setColor(COLOR_HANDLE)
                            cornerRadius = dp(3).toFloat()
                        }
                }
            frame.addView(
                pill,
                FrameLayout.LayoutParams(dp(5), dp(72), Gravity.START or Gravity.CENTER_VERTICAL)
                    .apply { leftMargin = dp(3) },
            )
            var downX = 0f
            var downY = 0f
            var opened = false
            frame.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        opened = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!opened && ev.rawX - downX > dp(SWIPE_OPEN_DP)) {
                            opened = true
                            openPanel()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val moved = abs(ev.rawX - downX) + abs(ev.rawY - downY)
                        if (!opened && moved < dp(12)) openPanel()
                    }
                }
                true
            }
            val height =
                (ctx.resources.displayMetrics.heightPixels * HANDLE_HEIGHT_FRACTION).toInt()
            val lp =
                WindowManager.LayoutParams(
                    dp(HANDLE_WIDTH_DP),
                    height,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                )
            lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            lp.x = 0
            lp.title = "ImpulseLeftAcRailHandle"
            wm().addView(frame, lp)
            handleView = frame
            Log.w(TAG, "alca da barra do ar adicionada")
        } catch (t: Throwable) {
            Log.e(TAG, "nao deu para adicionar a alca", t)
            handleView = null
        }
    }

    private fun removeHandle() {
        val v = handleView ?: return
        handleView = null
        runCatching { wm().removeView(v) }
    }

    // ---------------------------------------------------------------------------------------
    // Painel
    // ---------------------------------------------------------------------------------------

    private fun openPanel() {
        if (panel != null) {
            touched()
            return
        }
        try {
            val ctx = App.getContext()
            val p = Panel(ctx)
            val lp =
                WindowManager.LayoutParams(
                    dp(PANEL_WIDTH_DP),
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                )
            lp.gravity = Gravity.START or Gravity.TOP
            lp.title = "ImpulseLeftAcRailPanel"
            wm().addView(p.root, lp)
            panel = p
            p.slideIn()
            ServiceManager.getInstance()?.addDataChangedListener(listener)
            readAll()
            touched()
        } catch (t: Throwable) {
            Log.e(TAG, "nao deu para abrir o painel do ar", t)
            panel = null
        }
    }

    private fun closePanel() {
        main.removeCallbacks(hideRunnable)
        val p = panel ?: return
        panel = null
        runCatching { ServiceManager.getInstance()?.removeDataChangedListener(listener) }
        p.slideOut { runCatching { wm().removeView(p.root) } }
    }

    /** Qualquer toque no painel adia o fechamento automatico. */
    private fun touched() {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, AUTO_HIDE_MS)
    }

    private fun readAll() {
        worker.post {
            val sm = ServiceManager.getInstance() ?: return@post
            val read = HashMap<String, String>()
            for (k in WATCHED) {
                runCatching { sm.getData(k) }.getOrNull()?.let { read[k] = it.trim() }
            }
            main.post {
                values.putAll(read)
                panel?.refresh()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Acoes (mesmas regras do card de A/C da barra inferior)
    // ---------------------------------------------------------------------------------------

    private fun write(key: String, value: String) {
        values[key] = value
        panel?.refresh()
        worker.post {
            runCatching { ServiceManager.getInstance()?.updateData(key, value) }
                .onFailure { Log.e(TAG, "falhou ao escrever $key=$value", it) }
        }
    }

    private fun isOn(): Boolean = values[KEY_POWER] != "0"

    /** Igual ao ensurePowerOn da barra: ligar um recurso com o ar desligado liga o ar antes. */
    private fun ensurePowerOn() {
        if (isOn()) return
        write(KEY_POWER, "1")
        if ((values[KEY_FAN]?.toIntOrNull() ?: 0) == 0) write(KEY_FAN, "1")
    }

    private fun temp(key: String): Float = values[key]?.toFloatOrNull() ?: 22.0f

    private fun stepTemp(key: String, delta: Int) {
        val next = (temp(key) + delta * TEMP_STEP).coerceIn(TEMP_MIN, TEMP_MAX)
        write(key, String.format(Locale.US, "%.1f", next))
    }

    private fun stepFan(delta: Int) {
        val current = values[KEY_FAN]?.toIntOrNull() ?: 0
        val next = (current + delta).coerceIn(0, FAN_MAX)
        if (next == current) return
        write(KEY_FAN, next.toString())
        // Cruzar o zero liga/desliga o HVAC, como no card de A/C e nos gestos.
        if (next == 0 && isOn()) write(KEY_POWER, "0")
        else if (next > 0 && !isOn()) write(KEY_POWER, "1")
    }

    private fun togglePower() {
        if (isOn()) write(KEY_POWER, "0") else ensurePowerOn()
    }

    private fun toggleFlag(key: String) {
        ensurePowerOn()
        write(key, if (values[key] == "1") "0" else "1")
    }

    /** No H6, cycle_mode "0" = recirculando (o mesmo mapeamento da barra inferior). */
    private fun isRecirc(): Boolean = values[KEY_CYCLE] == "0"

    private fun toggleRecirc() {
        ensurePowerOn()
        write(KEY_CYCLE, if (isRecirc()) "1" else "0")
    }

    private fun sendKey(code: Int) {
        closePanel()
        worker.post {
            runCatching { ShizukuUtils.runCommandAndGetOutput(arrayOf("input", "keyevent", code.toString())) }
                .onFailure { Log.e(TAG, "falhou keyevent $code", it) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Visual
    // ---------------------------------------------------------------------------------------

    private class Stepper(val label: TextView, val value: TextView)

    private class Toggle(val view: TextView, val isActive: () -> Boolean)

    private class Panel(ctx: Context) {
        val root: FrameLayout
        private val content: LinearLayout
        private val driver: Stepper
        private val passenger: Stepper
        private val fan: Stepper
        private val toggles = ArrayList<Toggle>()

        init {
            root =
                object : FrameLayout(ctx) {
                    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                            closePanel()
                            return true
                        }
                        touched()
                        return super.dispatchTouchEvent(ev)
                    }
                }
            content =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background =
                        GradientDrawable().apply {
                            setColor(COLOR_PANEL)
                            val r = dp(22).toFloat()
                            cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                        }
                }
            val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
            val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            scroll.addView(column)
            content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            // Cabecalho
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(
                text(ctx, "Ar-condicionado", 17f, COLOR_TEXT, bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            header.addView(button(ctx, "✕", 18f) { closePanel() }, LinearLayout.LayoutParams(dp(44), dp(40)))
            column.addView(header)

            driver = stepper(ctx, column, "Motorista", { stepTemp(KEY_DRIVER, -1) }, { stepTemp(KEY_DRIVER, +1) })
            passenger = stepper(ctx, column, "Passageiro", { stepTemp(KEY_PASSENGER, -1) }, { stepTemp(KEY_PASSENGER, +1) })
            fan = stepper(ctx, column, "Ventilação", { stepFan(-1) }, { stepFan(+1) })

            val row1 = row(ctx, column)
            toggles += toggle(ctx, row1, "Ligado", { isOn() }) { togglePower() }
            toggles += toggle(ctx, row1, "A/C", { isOn() && values[KEY_AC] == "1" }) { toggleFlag(KEY_AC) }
            val row2 = row(ctx, column)
            toggles += toggle(ctx, row2, "Auto", { isOn() && values[KEY_AUTO] == "1" }) { toggleFlag(KEY_AUTO) }
            toggles += toggle(ctx, row2, "Circular", { isOn() && isRecirc() }) { toggleRecirc() }
            val row3 = row(ctx, column)
            row3.addView(button(ctx, "Voltar", 15f) { sendKey(4) }, weighted())
            row3.addView(button(ctx, "Início", 15f) { sendKey(3) }, weighted())

            refresh()
        }

        fun refresh() {
            driver.value.text = String.format(Locale.US, "%.1f°", temp(KEY_DRIVER))
            passenger.value.text = String.format(Locale.US, "%.1f°", temp(KEY_PASSENGER))
            val fanValue = values[KEY_FAN]?.toIntOrNull()
            fan.value.text = if (!isOn()) "desl." else fanValue?.toString() ?: "–"
            for (t in toggles) {
                val active = t.isActive()
                (t.view.background as? GradientDrawable)?.setColor(if (active) COLOR_ACTIVE else COLOR_BUTTON)
            }
        }

        fun slideIn() {
            content.translationX = -dp(PANEL_WIDTH_DP).toFloat()
            content.animate().translationX(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        }

        fun slideOut(done: () -> Unit) {
            content.animate()
                .translationX(-dp(PANEL_WIDTH_DP).toFloat())
                .setDuration(150)
                .withEndAction(done)
                .start()
        }

        private fun weighted() =
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            }

        private fun row(ctx: Context, parent: LinearLayout): LinearLayout {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            parent.addView(
                r,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) },
            )
            return r
        }

        private fun stepper(
            ctx: Context,
            parent: LinearLayout,
            title: String,
            minus: () -> Unit,
            plus: () -> Unit,
        ): Stepper {
            val label = text(ctx, title, 13f, COLOR_MUTED)
            parent.addView(
                label,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) },
            )
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            r.addView(button(ctx, "−", 24f, minus), LinearLayout.LayoutParams(dp(58), dp(48)))
            val value = text(ctx, "–", 22f, COLOR_TEXT, bold = true).apply { gravity = Gravity.CENTER }
            r.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(button(ctx, "+", 24f, plus), LinearLayout.LayoutParams(dp(58), dp(48)))
            parent.addView(r)
            return Stepper(label, value)
        }

        private fun toggle(
            ctx: Context,
            parent: LinearLayout,
            title: String,
            isActive: () -> Boolean,
            onClick: () -> Unit,
        ): Toggle {
            val b = button(ctx, title, 15f, onClick)
            parent.addView(b, weighted())
            return Toggle(b, isActive)
        }

        private fun text(ctx: Context, s: String, size: Float, color: Int, bold: Boolean = false) =
            TextView(ctx).apply {
                text = s
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }

        private fun button(ctx: Context, s: String, size: Float, onClick: () -> Unit) =
            text(ctx, s, size, COLOR_TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                background =
                    GradientDrawable().apply {
                        setColor(COLOR_BUTTON)
                        cornerRadius = dp(12).toFloat()
                    }
                isClickable = true
                setOnClickListener {
                    alpha = 0.6f
                    animate().alpha(1f).setDuration(120).start()
                    onClick()
                }
            }
    }
}
