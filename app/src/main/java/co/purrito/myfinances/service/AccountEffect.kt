package co.purrito.myfinances.service

import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.data.model.TransactionType

/**
 * Efecto de una transacción sobre el saldo de `accountId` (positivo =
 * entra plata / baja la deuda). Una TRANSFER sale de su cuenta origen y
 * entra a la destino. Lo comparten la reconciliación de extractos y la
 * recuperación de SMS para decidir si dos movimientos "van en el mismo
 * sentido" (una compra no es un abono del mismo valor).
 */
internal fun Transaction.effectOn(accountId: Long): Long = when (type) {
    TransactionType.INCOME -> if (this.accountId == accountId) amountMinor else 0
    TransactionType.EXPENSE -> if (this.accountId == accountId) -amountMinor else 0
    TransactionType.TRANSFER -> when (accountId) {
        this.accountId -> -amountMinor
        counterAccountId -> amountMinor
        else -> 0
    }
}
