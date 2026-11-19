package br.com.redesurftank.havalshisuku.utils

/**
 * Quilometragem no formato brasileiro: 12.000 km, não 12,000 nem 12000 (issue #131).
 *
 * A central vem com o idioma do sistema em inglês em muitos carros, então `String.format("%,d")`
 * saía com vírgula. Aqui o separador é sempre o ponto, independente do idioma.
 */
object KmFormat {
    fun group(digits: String): String {
        val n = digits.length
        if (n <= 3) return digits
        val sb = StringBuilder(n + n / 3)
        for (i in 0 until n) {
            if (i > 0 && (n - i) % 3 == 0) sb.append('.')
            sb.append(digits[i])
        }
        return sb.toString()
    }

    fun format(km: Int): String = if (km < 0) "-" + group((-km).toString()) else group(km.toString())

    /** Posição do cursor no texto com pontos para uma posição no texto só com dígitos. */
    fun toTransformed(formatted: String, originalOffset: Int): Int {
        if (originalOffset <= 0) return 0
        var digits = 0
        for (t in formatted.indices) {
            if (formatted[t] != '.') digits++
            if (digits == originalOffset) return t + 1
        }
        return formatted.length
    }

    /** Posição do cursor no texto só com dígitos para uma posição no texto com pontos. */
    fun toOriginal(formatted: String, transformedOffset: Int): Int {
        val end = transformedOffset.coerceIn(0, formatted.length)
        var digits = 0
        for (t in 0 until end) if (formatted[t] != '.') digits++
        return digits
    }
}
