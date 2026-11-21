package br.com.redesurftank.havalshisuku.utils

/**
 * Overscan that makes room for the bottom bar without stretching CarPlay (fork rafaelfrota).
 *
 * `wm overscan 0,0,0,B` only takes height away, so CarPlay's video is squeezed vertically:
 * circles turn into ovals. Taking the same proportion from the sides keeps the picture's
 * aspect ratio; CarPlay just gets smaller, with thin black strips left and right.
 */
object CarPlayAspect {
    data class Overscan(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun asWmArg(): String = "$left,$top,$right,$bottom"
    }

    /**
     * @param bottomPx height the bar needs, in pixels
     * @param displayWidth full display width (1920 on the H6)
     * @param displayHeight full display height (720 on the H6)
     * @param leftPaneWidth width the car's left navigation pane takes while CarPlay shows (0 if hidden)
     */
    fun overscanFor(bottomPx: Int, displayWidth: Int, displayHeight: Int, leftPaneWidth: Int): Overscan {
        if (bottomPx <= 0 || displayWidth <= 0 || displayHeight <= 0) return Overscan(0, 0, 0, 0)
        val bottom = bottomPx.coerceAtMost(displayHeight / 3)
        val contentWidth = (displayWidth - leftPaneWidth.coerceAtLeast(0)).coerceAtLeast(1)
        val ratio = contentWidth.toDouble() / displayHeight
        val horizontal = Math.round(bottom * ratio).toInt()
        val left = horizontal / 2
        val right = horizontal - left
        return Overscan(left, 0, right, bottom)
    }

    /** Reads "overscan (l,t,r,b)" from `dumpsys window displays` output; null if absent. */
    fun parseOverscan(dump: String): Overscan? {
        val m = Regex("""overscan \((-?\d+),(-?\d+),(-?\d+),(-?\d+)\)""").find(dump) ?: return null
        val (l, t, r, b) = m.destructured
        return Overscan(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }
}
