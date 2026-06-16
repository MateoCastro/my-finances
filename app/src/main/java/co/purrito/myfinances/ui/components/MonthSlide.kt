package co.purrito.myfinances.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import co.purrito.myfinances.ui.theme.Motion
import java.time.YearMonth

/**
 * Transición lateral direccional al cambiar de mes: avanzar desliza
 * el contenido desde la derecha, retroceder desde la izquierda.
 * Lo usan el registro, las estadísticas y el detalle de cuenta.
 */
@Composable
fun MonthSlide(
    month: YearMonth,
    modifier: Modifier = Modifier,
    content: @Composable (YearMonth) -> Unit
) {
    AnimatedContent(
        targetState = month,
        modifier = modifier,
        transitionSpec = {
            val forward = targetState > initialState
            (slideInHorizontally(tween(Motion.Normal)) { w -> if (forward) w else -w } +
                fadeIn(tween(Motion.Normal))) togetherWith
                (slideOutHorizontally(tween(Motion.Normal)) { w -> if (forward) -w else w } +
                    fadeOut(tween(Motion.Normal)))
        },
        label = "monthSlide"
    ) { current ->
        content(current)
    }
}
