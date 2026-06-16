package co.purrito.myfinances.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/* =====================================================================
 * Formas — verificadas contra el inspector del mock (--radius: 12px):
 *  - small  (≈ radius-sm):  chips de categoría y de día
 *  - medium (= radius-xl,  16.8px): cards generales (días del registro,
 *           cuentas, stats, inbox, entradas de Más)
 *  - large  (= radius-2xl, 21.6px): cards de categorías, botones pill
 * El FAB es circular (lo da el componente, no esta escala).
 * ===================================================================== */

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(17.dp),
    large = RoundedCornerShape(22.dp)
)
