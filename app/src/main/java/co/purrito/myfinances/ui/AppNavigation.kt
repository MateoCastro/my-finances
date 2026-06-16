package co.purrito.myfinances.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.remember
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import co.purrito.myfinances.ui.theme.Motion
import co.purrito.myfinances.data.model.Transaction
import co.purrito.myfinances.R
import co.purrito.myfinances.ui.accountdetail.AccountDetailScreen
import co.purrito.myfinances.ui.accounts.AccountFormScreen
import co.purrito.myfinances.ui.accounts.AccountsScreen
import co.purrito.myfinances.ui.addtransaction.AddTransactionSheet
import co.purrito.myfinances.ui.inbox.InboxScreen
import co.purrito.myfinances.ui.inbox.InboxViewModel
import co.purrito.myfinances.ui.more.MoreScreen
import co.purrito.myfinances.ui.stats.StatsScreen
import co.purrito.myfinances.ui.theme.AccentPink
import co.purrito.myfinances.ui.transactions.TransactionsScreen
import co.purrito.myfinances.ui.voice.VoiceCaptureSheet
import androidx.compose.runtime.LaunchedEffect
import com.composables.icons.lucide.ChartColumn
import com.composables.icons.lucide.CreditCard
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.LayoutList
import com.composables.icons.lucide.Lucide

/* =====================================================================
 * v3: la app pasa de estar centrada en cuentas a estar centrada en
 * transacciones (estilo Money Manager):
 *
 *  - Bottom navigation con 3 destinos raíz: Trans. / Stats / Cuentas
 *  - La pantalla principal es el registro mensual de transacciones
 *  - Las rutas de detalle (cuenta, formulario, inbox) ocultan la barra
 *
 * El patrón de navegación entre tabs (popUpTo + saveState/restoreState)
 * es el recomendado: cada tab conserva su estado al cambiar y no se
 * apilan copias en el back stack.
 * ===================================================================== */

object Routes {
    const val TRANSACTIONS = "transactions"
    const val STATS = "stats"
    const val ACCOUNTS = "accounts"

    const val ACCOUNT_DETAIL = "account/{accountId}"
    fun accountDetail(accountId: Long) = "account/$accountId"

    // Formulario crear/editar cuenta. accountId opcional: sin él = crear.
    const val ACCOUNT_FORM = "account_form?accountId={accountId}"
    fun accountForm(accountId: Long? = null) =
        if (accountId == null) "account_form" else "account_form?accountId=$accountId"

    const val INBOX = "inbox"
    const val MORE = "more"
}

/* El formulario de transacción NO es una ruta: es un ModalBottomSheet
 * que se dibuja SOBRE el NavHost (un destino de navegación no tendría
 * la pantalla anterior de fondo). Estado del sheet:        */
private const val SHEET_HIDDEN = -2L
private const val SHEET_NO_ACCOUNT = -1L // abierto, sin cuenta preseleccionada

private data class BottomTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
)

private fun tabIndexOf(route: String?): Int =
    bottomTabs.indexOfFirst { it.route == route }

private val bottomTabs = listOf(
    BottomTab(Routes.TRANSACTIONS, R.string.nav_trans, Lucide.LayoutList),
    BottomTab(Routes.STATS, R.string.nav_stats, Lucide.ChartColumn),
    BottomTab(Routes.ACCOUNTS, R.string.nav_accounts, Lucide.CreditCard),
    BottomTab(Routes.INBOX, R.string.nav_inbox, Lucide.Inbox),
    BottomTab(Routes.MORE, R.string.nav_more, Lucide.Ellipsis)
)

/**
 * Bottom nav custom (en lugar de NavigationBar de Material3) para
 * replicar el mock: ícono + label tintados y UNA SOLA barra fucsia
 * que se DESLIZA hasta la tab activa. Sin ripple: el feedback es el
 * propio movimiento de la barra y el tinte.
 */
@Composable
private fun AppBottomBar(
    currentRoute: String?,
    inboxPendingCount: Int,
    onNavigate: (String) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            val tabWidth = maxWidth / bottomTabs.size
            val selectedIndex = bottomTabs
                .indexOfFirst { it.route == currentRoute }
                .coerceAtLeast(0)
            val indicatorOffset by animateDpAsState(
                targetValue = tabWidth * selectedIndex + (tabWidth - 32.dp) / 2,
                animationSpec = tween(Motion.Nav),
                label = "navIndicatorOffset"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                bottomTabs.forEach { tab ->
                    val selected = currentRoute == tab.route
                    val tint by animateColorAsState(
                        targetValue = if (selected) AccentPink
                                      else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(Motion.Fast),
                        label = "tabTint"
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1.1f else 1f,
                        animationSpec = tween(Motion.Fast),
                        label = "tabIconScale"
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onNavigate(tab.route) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (tab.route == Routes.INBOX && inboxPendingCount > 0) {
                            BadgedBox(
                                badge = {
                                    Badge(
                                        containerColor = Color(0xFFF59E0B),
                                        contentColor = Color.White
                                    ) { Text("$inboxPendingCount") }
                                }
                            ) {
                                Icon(
                                    tab.icon,
                                    contentDescription = stringResource(tab.labelRes),
                                    tint = tint,
                                    modifier = Modifier
                                    .size(20.dp)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    }
                                )
                            }
                        } else {
                            Icon(
                                tab.icon,
                                contentDescription = stringResource(tab.labelRes),
                                tint = tint,
                                modifier = Modifier
                                    .size(20.dp)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    }
                            )
                        }
                        Text(
                            stringResource(tab.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = tint
                        )
                    }
                }
            }

            // La barra indicadora: única, deslizándose entre tabs
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = indicatorOffset)
                    .width(32.dp)
                    .height(2.dp)
                    .background(AccentPink, RoundedCornerShape(1.dp))
            )
        }
        }
    }
}

@Composable
fun AppNavigation(
    voiceRequest: Boolean = false,
    onVoiceRequestHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomTabs.map { it.route }

    var addSheetAccountId by rememberSaveable { mutableStateOf(SHEET_HIDDEN) }

    // Captura por voz: la abre el botón de micrófono (inbox/formulario) o
    // el Tile de Ajustes Rápidos (vía voiceRequest desde MainActivity).
    var showVoiceCapture by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(voiceRequest) {
        if (voiceRequest) {
            showVoiceCapture = true
            onVoiceRequestHandled()
        }
    }

    // Transacción en edición (abre el mismo sheet con la data cargada).
    // remember a propósito: Transaction no es parcelable; al rotar, el
    // sheet se cierra — comportamiento aceptable para un modal.
    var editingTransaction by remember { mutableStateOf<Transaction?>(null) }

    // Para el badge del inbox en el bottom nav (instancia propia,
    // scoped a la Activity; observa el mismo Flow que la pantalla)
    val inboxViewModel: InboxViewModel = viewModel()
    val pendingCount by inboxViewModel.pending.collectAsState()

    Scaffold(
        // Sin insets aquí: cada pantalla tiene su propio Scaffold que ya
        // aplica el inset del status bar. Si este Scaffold externo también
        // lo aplicara, el padding superior quedaría duplicado.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                AppBottomBar(
                    currentRoute = currentRoute,
                    inboxPendingCount = pendingCount.size,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.TRANSACTIONS,
            // imePadding GLOBAL: como TODAS las pantallas pasan por este
            // NavHost, basta aplicarlo aquí una vez para que el teclado
            // nunca tape un input — ninguna pantalla nueva necesita
            // volver a manejarlo. (Los ModalBottomSheet se dibujan en su
            // propia ventana y aplican imePadding por su cuenta.)
            modifier = Modifier
                .padding(innerPadding)
                // Consumir el innerPadding (incluye la barra inferior)
                // ANTES de imePadding: así imePadding añade SOLO el
                // sobrante del teclado en vez de sumarse al padding ya
                // puesto — eso dejaba una banda del alto de la barra
                // entre el contenido y el teclado.
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            // Transiciones laterales: entre tabs la dirección sigue su
            // orden en la barra; hacia rutas de detalle es un "push"
            // clásico desde la derecha.
            enterTransition = {
                val from = tabIndexOf(initialState.destination.route)
                val to = tabIndexOf(targetState.destination.route)
                if (from >= 0 && to >= 0) {
                    slideInHorizontally(tween(Motion.Nav)) { w ->
                        if (to > from) w else -w
                    } + fadeIn(tween(Motion.Nav))
                } else {
                    slideInHorizontally(tween(Motion.Nav)) { it }
                }
            },
            exitTransition = {
                val from = tabIndexOf(initialState.destination.route)
                val to = tabIndexOf(targetState.destination.route)
                if (from >= 0 && to >= 0) {
                    slideOutHorizontally(tween(Motion.Nav)) { w ->
                        if (to > from) -w else w
                    } + fadeOut(tween(Motion.Nav))
                } else {
                    slideOutHorizontally(tween(Motion.Nav)) { -it / 4 } +
                        fadeOut(tween(Motion.Nav))
                }
            },
            popEnterTransition = {
                slideInHorizontally(tween(Motion.Nav)) { -it / 4 } +
                    fadeIn(tween(Motion.Nav))
            },
            popExitTransition = {
                slideOutHorizontally(tween(Motion.Nav)) { it }
            }
        ) {
            composable(Routes.TRANSACTIONS) {
                TransactionsScreen(
                    onEditTransaction = { editingTransaction = it },
                    onAddTransaction = {
                        addSheetAccountId = SHEET_NO_ACCOUNT
                    }
                )
            }

            composable(Routes.STATS) {
                StatsScreen()
            }

            composable(Routes.ACCOUNTS) {
                AccountsScreen(
                    onAccountClick = { accountId ->
                        navController.navigate(Routes.accountDetail(accountId))
                    },
                    onAddAccount = { navController.navigate(Routes.accountForm()) },
                    onEditAccount = { accountId ->
                        navController.navigate(Routes.accountForm(accountId))
                    }
                )
            }

            composable(Routes.INBOX) {
                InboxScreen(onVoiceCapture = { showVoiceCapture = true })
            }

            composable(Routes.MORE) {
                MoreScreen()
            }

            composable(
                route = Routes.ACCOUNT_DETAIL,
                arguments = listOf(
                    navArgument("accountId") { type = NavType.LongType }
                )
            ) { entry ->
                val accountId = entry.arguments?.getLong("accountId")
                    ?: return@composable
                AccountDetailScreen(
                    accountId = accountId,
                    onBack = { navController.popBackStack() },
                    onAddTransaction = {
                        addSheetAccountId = accountId
                    },
                    onEditTransaction = { editingTransaction = it }
                )
            }

            composable(
                route = Routes.ACCOUNT_FORM,
                arguments = listOf(
                    navArgument("accountId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    }
                )
            ) { entry ->
                val accountId = entry.arguments?.getLong("accountId") ?: -1L
                AccountFormScreen(
                    accountId = accountId,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        if (addSheetAccountId != SHEET_HIDDEN || editingTransaction != null) {
            AddTransactionSheet(
                preselectedAccountId = addSheetAccountId,
                editing = editingTransaction,
                onDismiss = {
                    addSheetAccountId = SHEET_HIDDEN
                    editingTransaction = null
                }
            )
        }

        if (showVoiceCapture) {
            VoiceCaptureSheet(onDismiss = { showVoiceCapture = false })
        }
    }
}
