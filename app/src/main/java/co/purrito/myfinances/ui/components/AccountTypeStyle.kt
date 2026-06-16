package co.purrito.myfinances.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import co.purrito.myfinances.R
import co.purrito.myfinances.data.model.AccountType
import co.purrito.myfinances.ui.theme.ChipColors
import com.composables.icons.lucide.Banknote
import com.composables.icons.lucide.CreditCard
import com.composables.icons.lucide.Landmark
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PiggyBank

/**
 * Identidad visual de cada tipo de cuenta (etiqueta + ícono + tinte),
 * compartida por la lista de cuentas y el detalle de cuenta.
 */
data class AccountTypeStyle(
    val labelRes: Int,
    val icon: ImageVector,
    val colors: ChipColors
)

fun accountTypeStyle(type: AccountType): AccountTypeStyle = when (type) {
    // Colores del mock: tailwind 500 al 15% de fondo + 400 de texto
    AccountType.CASH -> AccountTypeStyle(
        R.string.type_cash, Lucide.Banknote,
        ChipColors(container = Color(0x26F59E0B), content = Color(0xFFFBBF24)) // amber
    )
    AccountType.BANK -> AccountTypeStyle(
        R.string.type_bank, Lucide.Landmark,
        ChipColors(container = Color(0x263B82F6), content = Color(0xFF60A5FA)) // blue
    )
    AccountType.SAVINGS -> AccountTypeStyle(
        R.string.type_savings, Lucide.PiggyBank,
        ChipColors(container = Color(0x2614B8A6), content = Color(0xFF2DD4BF)) // teal
    )
    AccountType.CREDIT_CARD -> AccountTypeStyle(
        R.string.type_cards, Lucide.CreditCard,
        ChipColors(container = Color(0x268B5CF6), content = Color(0xFFA78BFA)) // violet
    )
}
