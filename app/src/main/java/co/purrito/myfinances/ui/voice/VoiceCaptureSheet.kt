package co.purrito.myfinances.ui.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import android.speech.SpeechRecognizer
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import co.purrito.myfinances.R
import co.purrito.myfinances.ui.theme.AccentPink
import co.purrito.myfinances.ui.theme.ExpenseRed
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.MicOff
import com.composables.icons.lucide.X
import kotlinx.coroutines.launch

/* =====================================================================
 * Hoja de captura por voz (Hito 6).
 *
 * Al abrir, pide permiso (si hace falta) y empieza a escuchar. Muestra
 * la transcripción parcial en vivo; al terminar, parsea + guarda como
 * PENDING (source=VOICE) y se cierra — la confirmación ocurre en el
 * inbox, igual que con SMS. Mínima fricción: el usuario solo dicta.
 * ===================================================================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCaptureSheet(
    onDismiss: () -> Unit,
    viewModel: VoiceCaptureViewModel = viewModel()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var errorCode by remember { mutableStateOf<Int?>(null) }

    val animatedDismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    val controller = rememberVoiceInput(
        onResult = { transcript ->
            errorCode = null
            viewModel.saveAsync(transcript) { animatedDismiss() }
        },
        onError = { code -> errorCode = code }
    )

    // Arrancar al abrir (pide permiso si hace falta).
    LaunchedEffect(Unit) {
        if (controller.available) controller.launch()
        else errorCode = UNAVAILABLE
    }

    ModalBottomSheet(
        onDismissRequest = { controller.cancel(); animatedDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.voice_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { controller.cancel(); animatedDismiss() }) {
                    Icon(
                        Lucide.X,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            MicCircle(listening = controller.listening, error = errorCode != null)

            when {
                errorCode != null -> {
                    Text(
                        errorMessage(errorCode!!),
                        color = ExpenseRed,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (errorCode != UNAVAILABLE) {
                        Button(
                            onClick = { errorCode = null; controller.launch() },
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPink, contentColor = Color.White
                            )
                        ) { Text(stringResource(R.string.retry)) }
                    }
                }
                controller.listening -> {
                    Text(
                        stringResource(R.string.voice_listening),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AccentPink,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        controller.partial.ifBlank { stringResource(R.string.voice_hint) },
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Text(
                        stringResource(R.string.voice_hint),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun MicCircle(listening: Boolean, error: Boolean) {
    val pulse = rememberInfiniteTransition(label = "micPulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (listening) 1.15f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "micScale"
    )
    val tint = when {
        error -> ExpenseRed
        listening -> AccentPink
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(96.dp)
            .scale(if (listening) scale else 1f)
            .background(tint.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (error) Lucide.MicOff else Lucide.Mic,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(40.dp)
        )
    }
}

@Composable
private fun errorMessage(code: Int): String = stringResource(
    when (code) {
        UNAVAILABLE -> R.string.voice_unavailable
        PERMISSION_DENIED, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            R.string.voice_permission_denied
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            R.string.voice_no_language
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            R.string.voice_network
        else -> R.string.voice_error
    }
)

private const val UNAVAILABLE = -2
