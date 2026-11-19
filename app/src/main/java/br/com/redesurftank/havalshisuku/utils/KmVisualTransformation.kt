package br.com.redesurftank.havalshisuku.utils

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Mostra os dígitos digitados com pontos de milhar, sem mudar o valor guardado no campo. */
class KmVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val formatted = KmFormat.group(text.text)
        return TransformedText(
            AnnotatedString(formatted),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = KmFormat.toTransformed(formatted, offset)

                override fun transformedToOriginal(offset: Int): Int = KmFormat.toOriginal(formatted, offset)
            },
        )
    }
}
