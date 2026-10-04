package co.purrito.myfinances.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/* =====================================================================
 * Separador de miles colombiano para los campos de monto:
 * "1250000" se VE como "1.250.000".
 *
 * Es solo presentación: el estado del campo sigue siendo únicamente
 * dígitos (pesos enteros), así el parseo a centavos no cambia y el
 * cursor se mapea entre ambos textos saltando los puntos.
 * ===================================================================== */

object ThousandsSeparatorTransformation : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val formatted = buildString {
            digits.forEachIndexed { i, c ->
                if (i > 0 && (digits.length - i) % 3 == 0) append('.')
                append(c)
            }
        }

        val mapping = object : OffsetMapping {
            // Puntos insertados antes de la posición `offset` del original
            override fun originalToTransformed(offset: Int): Int {
                val separators = (1..offset).count { i ->
                    i < digits.length && (digits.length - i) % 3 == 0
                }
                return offset + separators
            }

            // Cuenta los caracteres que NO son separador hasta `offset`
            override fun transformedToOriginal(offset: Int): Int =
                formatted.take(offset).count { it != '.' }
        }

        return TransformedText(AnnotatedString(formatted), mapping)
    }
}
