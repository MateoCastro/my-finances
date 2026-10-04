# MyFinances — App Android de finanzas personales

## Stack
- Kotlin + Jetpack Compose (Material3)
- Room (ORM local, offline-first) con KSP
- Arquitectura: MVVM — UI → ViewModel → Repository → DAO → Room
- Dinero: siempre `Long` en centavos. Nunca Double/Float.
- Fechas: epoch millis (`Long`)
- Navigation Compose para ruteo declarativo

## Estructura de paquetes
```
co.purrito.myfinances/
├── data/
│   ├── model/        ← Entidades Room (Entities.kt)
│   ├── dao/          ← DAOs con queries reactivas (Daos.kt)
│   └── AppDatabase.kt
├── ui/
│   ├── Format.kt             ← formatCop(), formatDate(), formatMonth() compartidos
│   ├── AppNavigation.kt      ← Bottom nav (Trans./Stats/Cuentas) + rutas de detalle
│   ├── theme/                ← Paletas oscura (diseño de referencia) y clara vía AppColors/LocalAppColors + tipografía compacta
│   ├── transactions/         ← Pantalla principal: registro mensual por días
│   ├── stats/                ← Dona de composición por categoría (Canvas puro)
│   ├── accounts/             ← Lista de cuentas con saldos calculados
│   ├── accountdetail/        ← Movimientos de una cuenta + FAB
│   ├── addtransaction/       ← Formulario manual (con fecha editable)
│   └── inbox/                ← (Hito 2) Transacciones PENDING por confirmar
└── service/                  ← (Hito 2) BroadcastReceiver de SMS
```

---

## Modelo de datos — decisiones clave

### Dinero y deuda
- Todo monto es `Long` en **centavos** y siempre positivo. El signo lo da
  `TransactionType`, nunca el campo `amountMinor`.
- `Account.type = CREDIT_CARD`: su saldo calculado es negativo (= deuda).
  Las compras suman deuda; el pago (TRANSFER) la reduce. No hay lógica
  especial: la misma fórmula SQL de `observeAllWithBalance()` funciona para
  todos los tipos de cuenta.

### Transferencias vs. gastos
- `TransactionType.TRANSFER` modela movimientos entre cuentas propias
  (ej: pago de tarjeta de crédito, traspaso entre cuentas).
- Las TRANSFER **no tienen `categoryId`** y están **excluidas de todos los
  reportes de gastos/ingresos** a nivel de query SQL, no de filtro en UI.
  Esto es estructural: es imposible que un pago de TC contamine las
  categorías de gastos.
- El signo de una TRANSFER es relativo a la cuenta desde donde se mira:
  sale (-) de `accountId` y entra (+) a `counterAccountId`. Esto ya está
  implementado en `TransactionRow` de `AccountDetailScreen`.

### Compras diferidas (cuotas de TC)
- Hay dos "verdades" simultáneas que el modelo soporta:
  1. **Verdad de categorías**: la compra completa (ej: $1.200.000 en
     "Tecnología") se registra como `Transaction(EXPENSE)` en la fecha de
     compra. Aquí vive la categoría. No se reparte entre cuotas.
  2. **Verdad de flujo de caja**: `DeferredPurchase` guarda el plan de
     cuotas (n, monto por cuota, cuotas ya facturadas). Las cuotas se
     **calculan**, no se almacenan como transacciones adicionales.
- La compra original apunta a su plan: `Transaction.deferredPurchaseId`.
- `billedInstallments` se incrementa al importar extractos (Hito 4).
- `installmentAmountMinor` puede ser null al crear la compra (se estima
  como total/n); se actualiza con el valor real del extracto.

### Inbox y deduplicación
- Toda transacción capturada automáticamente (SMS, OCR, extracto) nace
  con `status = PENDING`. Solo las `CONFIRMED` afectan saldos.
- `externalRef` es la huella de deduplicación (índice UNIQUE en Room):
  - SMS: `hash(remitente + cuerpo + timestamp)`
  - Extracto: `hash(cuenta + fecha + monto + descripción + nro línea)`
- Si `externalRef` ya existe en la BD, se descarta silenciosamente.

### Diccionario de alias
- `MerchantAlias` mapea substrings del texto crudo del SMS/extracto a
  nombres legibles y categoría sugerida.
  - Ejemplo: "PAYU*RAPPI BOG" → displayName="Rappi", categoría=Domicilios
- Aprende de las correcciones del usuario: cuando confirma una transacción
  PENDING y edita el nombre del comercio, se crea/actualiza el alias.
- Al parsear un SMS, después de extraer `merchantRaw` se consulta
  `MerchantAliasDao.findMatch(merchantRaw)` para pre-rellenar descripción
  y categoría sugerida en el inbox.

### Plantillas SMS (`SmsTemplate`)
- Cada banco tiene una o más plantillas con:
  - `senderPattern`: regex sobre el remitente del SMS
  - `bodyPattern`: regex sobre el cuerpo con **grupos nombrados**:
    - `(?<amount>...)` — obligatorio, captura el monto
    - `(?<merchant>...)` — opcional, nombre del comercio
    - `(?<lastFour>...)` — opcional, últimos 4 dígitos para enrutar a
      la cuenta correcta cuando hay varias del mismo banco
- Se prueban en orden; la primera que coincide produce la transacción.
- Están en la BD (no hardcodeadas) para poder actualizarlas desde la app
  cuando el banco cambie el formato, sin recompilar.

---

## Hitos de desarrollo

### ✅ Hito 0 — Modelo + pantalla mínima
- Entidades, DAOs, AppDatabase compilando
- `AccountsScreen` lista cuentas con saldo calculado reactivamente
- Datos semilla: Efectivo, Bancolombia, TC Visa + transacciones de prueba
- Validado: TC Visa muestra −$985.000 (compras − pago), saldo Bancolombia
  refleja la transferencia del pago

### ✅ Hito 1 — CRUD manual de transacciones
- `AccountDetailScreen`: movimientos de una cuenta, signos relativos,
  badge "Por confirmar" para PENDING
- `AddTransactionScreen`: formulario con selector de tipo
  (Gasto/Ingreso/Transfer.), dropdown de cuentas y categorías,
  validación derivada (no almacenada)
- El formulario es polimórfico: Transfer. muestra cuenta destino en lugar
  de categoría, y fuerza `categoryId = null` al guardar
- Navigation Compose con rutas parametrizadas por `accountId`

### ✅ Hito 2 — Captura automática por SMS
**Completado.** SmsReceiver + SmsParser (puro, con 27 tests JUnit sobre SMS
reales) + InboxScreen con aprendizaje de MerchantAlias. Las plantillas de
Bancolombia (14) y Davivienda (2) viven en `service/DefaultSmsTemplates.kt`
y se insertan con el seed. `SmsTemplate.counterAccountId` permite que pagos
de TC y retiros de cajero nazcan como TRANSFER con destino correcto.
Detalle del plan original abajo, por referencia:
**Archivos a crear:**
- `service/SmsReceiver.kt`: `BroadcastReceiver` que escucha
  `android.provider.Telephony.SMS_RECEIVED`
- `service/SmsParser.kt`: lógica pura (sin Android) que recibe remitente
  + cuerpo, prueba las `SmsTemplate` activas, y devuelve una
  `Transaction?` con status PENDING
- `ui/inbox/InboxViewModel.kt` + `InboxScreen.kt`: lista de PENDING con
  swipe-to-confirm y swipe-to-discard. Al confirmar permite editar
  categoría y nombre antes de guardar.

**Permisos necesarios en AndroidManifest.xml:**
```xml
<uses-permission android:name="android.permission.RECEIVE_SMS"/>
<uses-permission android:name="android.permission.READ_SMS"/>
<!-- El receiver debe declararse con el intent-filter correspondiente -->
```

**Notas de implementación:**
- `SmsParser` no debe tener dependencias de Android para ser testeable
  con JUnit puro. Recibe `(sender: String, body: String, templates: List<SmsTemplate>)`
  y devuelve `ParsedSms?` (data class intermedia antes de crear la Transaction).
- El monto en los SMS colombianos viene como "25.000,00" o "25,000.00"
  según el banco — normalizar antes de parsear a Long.
- Google Play restringe el permiso RECEIVE_SMS. La app es de uso personal:
  instalar por APK directo (no publicar en Play Store) para evitar esa
  restricción.
- Adicionalmente, considerar `NotificationListenerService` para apps como
  Nequi/Daviplata que notifican por push en lugar de SMS.

**SMS de prueba (simular en el emulador):**
- Android Studio → Extended Controls (⋮ en el emulador) → Phone →
  ingresar remitente y cuerpo manualmente para disparar el receiver

### ✅ Hito 3 — Compras diferidas (cuotas de TC)
**Completado.** `AddTransactionSheet` muestra toggle "Compra diferida"
+ número de cuotas (con cuota estimada en vivo) cuando la cuenta TC
participa como **origen de la deuda**: tipo `EXPENSE` con cuenta
`CREDIT_CARD` (compra), o tipo `TRANSFER` con origen `CREDIT_CARD`
(avance). En ambos casos, al guardar inserta el `DeferredPurchase`
primero y liga la `Transaction` vía `deferredPurchaseId`. También al
EDITAR una transacción sin plan se puede agregar el diferido (ej: un
avance guardado sin esa info); editar un plan ya existente llegará con
el extracto del Hito 4.
Nota: `DeferredPurchase` modela el plan de facturación de una deuda,
no una compra — el nombre es histórico. No hubo pantalla nueva: `AccountDetailScreen` de
una TC tiene PillTabs **Movimientos / Diferidos**; el tab Diferidos
lista las compras abiertas (comercio, fecha, total, cuota, barra de
progreso de cuotas facturadas, pendiente estimado) sin depender del mes
seleccionado. Detalle del cálculo, por referencia:

**Cálculo de cuota mensual:**
- Si `installmentAmountMinor` es conocido: usarlo directamente
- Si es null (compra recién creada): estimar como `totalAmountMinor / totalInstallments`
- Nota: en Colombia las cuotas con intereses NO son iguales (sistema
  francés). La estimación es solo referencial; el valor real llega con
  el extracto (Hito 4).

### ✅ Hito 4 — Importación de extractos + reconciliación
**Completado (2026-06-13).** El importador enruta por
formato (firma de bytes): zip "PK" = `.xlsx` Bancolombia; "%PDF" = PDF
(extrae texto con **PdfBox-Android** y detecta banco por su nombre →
`DaviviendaStatementParser` o `BancolombiaPdfStatementParser`); resto =
texto plano (`.txt` Davivienda). Los PDFs de banco vienen cifrados con
contraseña de USUARIO vacía (solo restricciones de propietario) → abren
con `""` sin pedir clave (`service/PdfTextExtractor.kt`). Parsers PDF
verificados end-to-end con los PDFs reales vía test instrumentado
(desechable, no versionado). Fixtures de test SIEMPRE sintéticos
(datos falsos). Núcleo puro y testeable (como SmsParser):
`service/XlsxReader.kt` (zip+DOM, sin POI),
`service/BancolombiaStatementParser.kt` (filas→`ParsedStatement`),
`service/StatementReconciler.kt` (dedup monto+fecha±1día, cuotas→update
de plan, nuevas→inbox PENDING, ajuste de saldo). `StatementImportViewModel`
+ entrada "Importar extracto" en MoreScreen (SAF OpenDocument → resumen
"X nuevas, Y cuotas, Z ya existían"). Enruta a la cuenta por sus últimos
4 dígitos; aplica MerchantAlias; categoría "Costos financieros" get-or-create.
Cubre los formatos reales de los bancos del usuario (Bancolombia xlsx/PDF,
Davivienda PDF/txt).
**Ajuste de saldo rehecho (2026-10-03, DB v5):** salía cada mes con valores
considerables por artefactos, no por desfases reales: (a) el inbox truncaba
centavos al confirmar y el dedup exacto ya no reconocía la línea al
reimportar (interés contado dos veces); (b) duplicados aún PENDING no se
proyectaban; (c) compras del día de corte que el banco factura el mes
siguiente; (d) la parte en DÓLARES de la TC (compras y "pago dólares") entra a
la deuda de la app pero no al "Pago total" en pesos. Ahora: dedup ±1 peso +
mismo sentido + por `externalRef`; `Transaction.reconciled` marca lo que un
extracto confirmó, y la verificación SOLO cuenta eso (+ source STATEMENT +
líneas nuevas) — lo no confirmado queda fuera y se informa en el resumen;
umbral $100; un ajuste por extracto (huella cuenta+corte, se reemplaza al
reimportar). Cuotas: match por monto total + fecha de compra (ya no por
comercio solo: en agregadores tipo "MERCADO PAGO" facturaba el plan de otra
compra). El plan guarda el texto crudo del extracto; la UI de Diferidos
muestra la descripción de la transacción ancla y abre su edición.
NO se implementa OCR de PDF escaneado (ML Kit): el
usuario no recibe extractos físicos/escaneados, así que no aporta valor.
Objetivo y detalle original, por referencia:

**Objetivo:** importar el PDF/CSV del extracto bancario para:
1. Descubrir transacciones que no generaron SMS (compras online, etc.)
2. Actualizar `billedInstallments` y `installmentAmountMinor` en compras
   diferidas existentes
3. Reconciliar: cruzar líneas del extracto vs. transacciones ya en BD
   (deduplicación por monto + fecha ± 1 día)

**Parsers de extracto:**
- Un parser por banco/formato, igual que con SMS
- Los PDFs de TC colombianos traen formato "CUOTA X/Y" para diferidos
- Algunos PDFs vienen con contraseña = número de cédula: pedir al usuario
  si el parse falla
- Para PDFs escaneados (imagen): usar ML Kit Text Recognition (mismo
  componente que el OCR de recibos del Hito 5)
- Formato CSV es más robusto cuando el banco lo ofrece: preferirlo

**Flujo de reconciliación:**
1. Usuario importa extracto → se parsean todas las líneas
2. Cada línea se intenta deduplicar contra transacciones existentes
   (`externalRef` o monto+fecha±1día)
3. Las que no matchean → inbox como PENDING con source=STATEMENT
4. Las que matchean una compra diferida → actualizar `billedInstallments`
5. Mostrar resumen: "X transacciones nuevas, Y cuotas actualizadas,
   Z ya existían"
6. Los cargos del banco en el extracto (intereses, cuota de manejo,
      seguros, comisiones de avance) se capturan como EXPENSE en la cuenta
      TC con categoría "Costos financieros" pre-asignada, source=STATEMENT,
      status=PENDING.
7. Verificación de saldo: comparar el saldo total que reporta el extracto
   contra la deuda calculada en la app. Si difieren, crear una Transaction
   PENDING de ajuste por la diferencia, sugerida como "Costos financieros",
   para que la deuda nunca se desvíe de la realidad del banco.

**Diccionario de alias aplicado:**
- Las descripciones crípticas del extracto ("PAYU*RAPPI BOG") se
  resuelven con `MerchantAliasDao.findMatch()` igual que en SMS

### 🔲 Hito 5 — Geofencing + OCR de recibos
**Geofencing (modo salida):**
- Permiso: `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION`
- API: `GeofencingClient` de Google Play Services
- Flujo:
  1. "Salir" → usuario declara efectivo en cartera → se registra snapshot
     con `source = RECONCILIATION`, `status = PENDING`
  2. Al detectar regreso a casa (geofence de entrada) → notificación:
     "¿Con cuánto efectivo volviste?"
  3. Diferencia = gasto en efectivo sin desglosar → Transaction PENDING
     que el usuario puede desglosar después por voz u OCR
  4. Si durante la ventana llegaron SMS bancarios (ya capturados por
     Hito 2), la app puede sugerir: "Tu TC registró $X, ¿el resto fue
     efectivo?"

- El geofence "casa" se configura una vez: la app pide ubicación y marca
  la zona actual como home con radio configurable (default 200m)

**OCR de recibos:**
- Dependencia: `com.google.mlkit:text-recognition` (on-device, sin red)
- Flujo: foto → ML Kit extrae texto → mismo parser de monto que SMS
  (normalización de formato numérico colombiano) → Transaction PENDING
- Integrar como opción adicional en `AddTransactionScreen` (ícono de
  cámara junto al campo de monto)
- Para recibos con QR (Dian): considerar ML Kit Barcode Scanning como
  fuente más fiable que OCR de texto para los campos estructurados

### ✅ Hito 6 — Registro por voz
**Completado.** `domain/VoiceParser.kt` (puro, 14 tests JUnit) extrae
monto, descripción, tipo y cuenta inferida de una frase dictada;
`service/VoiceCapture.kt` envuelve `SpeechRecognizer` (on-device vía
`EXTRA_PREFER_OFFLINE`). La hoja `ui/voice/VoiceCaptureSheet.kt` (con el
helper `rememberVoiceInput` que gestiona permiso + ciclo de vida) escucha,
parsea y guarda la transacción con `source = VOICE`, `status = PENDING`:
cae al MISMO inbox del Hito 2 (la tarjeta muestra ícono de micrófono y
"Ver transcripción"). Puntos de entrada hechos: (1) botón de micrófono en
el formulario `AddTransactionSheet` (rellena los campos en sitio, no crea
PENDING) y en el header del inbox (abre la hoja → PENDING); (2) Quick
Settings Tile (`service/VoiceTileService.kt` → `MainActivity` con
`ACTION_VOICE_CAPTURE`). NO implementados: App Shortcut (punto 3, opcional)
ni el fallback LLM (opt-in, fase posterior).
Notas de implementación que difieren del plan original:
- El reconocedor escucha SIEMPRE en **español genérico (`es`)**, desacoplado
  del idioma de la app (constante `VOICE_LANGUAGE` en `VoiceInput.kt`): el
  parser es español y el usuario dicta en español aunque la UI esté en
  inglés. Se usa `"es"` (NO `"es-CO"`): exigir variante de país falla
  offline si el modelo descargado es otra (ej. el usuario tiene `es-US`);
  `"es"` resuelve al español on-device disponible. El controller intenta
  on-device primero y reintenta online si el offline falla por idioma/red.
- Las tx de voz guardan `merchantRaw = description` para **aprender la
  categoría vía MerchantAlias** al confirmar en el inbox (el match por
  nombre de categoría falla si la UI/categorías están en otro idioma que
  el dictado; el alias es idioma-agnóstico y aprende de la corrección).
- El monto se modela en **pesos enteros** (× 100 a centavos); el dictado
  rara vez trae centavos, así que NO se reutiliza el normalizador de
  SmsParser (que asume 2 decimales) sino un parser de números en palabras
  español propio (unidades, decenas, "mil"/"millón", mezcla "20 mil").
- La descripción es heurística: tras el monto se filtran palabras función
  (conectores, artículos, keywords de cuenta/tipo). El usuario corrige en
  el inbox.
- La **categoría** se resuelve en `VoiceCaptureViewModel` (alias o nombre),
  no en el parser, para mantenerlo puro y sin dependencias de BD.
Detalle del plan original, por referencia:
**Objetivo:** capturar transacciones dictadas ("gasté veinte mil en
empanadas") con mínima fricción, principalmente para gastos en efectivo.

**Componentes:**
- `service/VoiceCapture.kt`: wrapper sobre `SpeechRecognizer` nativo de
  Android (`android.speech`). Preferir modelo on-device cuando esté
  disponible (`EXTRA_PREFER_OFFLINE`) por privacidad y latencia.
- `domain/VoiceParser.kt`: lógica PURA (sin Android, testeable con JUnit)
  que recibe el texto transcrito y devuelve `ParsedVoice?` con monto,
  descripción y cuenta inferida.
- Permiso: `RECORD_AUDIO` (runtime permission, pedirla al primer uso).

**Gramática del parser (reglas, sin LLM):**
- Patrón base: `[verbo opcional] [monto] (en|de|por) [descripción]`
  - "gasté 20000 en empanadas" / "veinte mil en empanadas" / "20 mil empanadas"
- Montos: soportar dígitos ("20000", "20.000") y palabras frecuentes
  ("veinte mil", "cincuenta mil", "un millón"). Normalizar a Long centavos
  con el mismo normalizador numérico del SmsParser.
- Cuenta: por defecto la de tipo CASH (la voz es principalmente para
  efectivo). Keywords opcionales la cambian: "con tarjeta" → CREDIT_CARD,
  "de la cuenta" → BANK.
- Tipo: EXPENSE por defecto; "me pagaron" / "recibí" → INCOME.
- Categoría: sugerir vía keywords contra nombres de categorías y
  `MerchantAlias` (ej: "empanadas" → Restaurantes si existe el alias).

**Resultado:** Transaction con `source = VOICE`, `status = PENDING` →
cae al mismo inbox del Hito 2 para confirmación. Si el parser no entiende
la frase, guardar la transcripción cruda en `notes` como PENDING sin
monto para que el usuario complete a mano (nunca perder la captura).

**Puntos de entrada (en orden de implementación):**
1. Botón de micrófono en `AddTransactionScreen` y en el inbox
2. Quick Settings Tile (`TileService`): micrófono a un swipe + tap desde
   cualquier lugar del sistema, sin abrir la app
3. (Opcional, después) App Shortcut al mantener presionado el ícono

**Fallback LLM (opcional, fase posterior):**
- Si las reglas no parsean la frase, opción de enviarla a la API de
  Claude (Haiku) para extracción estructurada. Desactivado por defecto:
  requiere red y envía datos fuera del dispositivo — debe ser opt-in
  explícito del usuario en ajustes.

### ✅ Hito 7 — Enseñar SMS (plantillas dinámicas)
**Completado (2026-10-03).** Motivo: Davivienda cambió su remitente de
89xxxx a 87188 (que además encaja en el patrón de Bancolombia) y sus SMS
dejaron de reconocerse en silencio desde el 2026-09-11. Las plantillas ya
vivían en BD, pero no había forma de editarlas sin código.
- `domain/SmsTemplateGenerator.kt` (PURO, tests en SmsTemplateGeneratorTest):
  de UN SMS de ejemplo + rangos marcados (monto, comercio opcional) genera el
  `bodyPattern`. Texto fijo: palabras/signos literales (distingue "Compra" de
  "Avance"), espacios `\s*`, números `\d+` salvo los dígitos tras `*` (últimos
  4: una plantilla por tarjeta), `$`/`COP` antes del monto intercambiables,
  cola del mensaje recortada a 3 piezas. Remitente: código corto numérico →
  `^\d{4,6}$` (los bancos rotan códigos; el cuerpo distingue al banco); largo o
  alfanumérico → exacto. `amountCandidates` sugiere los montos.
- UI: Más → "Plantillas SMS" (`ui/smstemplates/`): lista con último SMS
  reconocido (diagnóstico), activar/desactivar, borrar solo las enseñadas,
  "Buscar SMS perdidos". "Enseñar un SMS": elegir de la bandeja (READ_SMS),
  marcar monto (chips) y comercio (selección de texto), tipo/cuenta/destino,
  vista previa (qué extrae y cuántos SMS recientes del remitente reconoce).
  Cuenta sugerida por los últimos 4 del SMS.
- Las plantillas enseñadas se prueban ANTES que las de fábrica.
- Recuperación (`service/SmsRecovery.kt`): SMS de los últimos 30 días que
  hoy se reconocen y no están en la app (dedup por externalRef y por
  `rawText`); el usuario ELIGE cuáles van al inbox (no se agregan solos: un
  SMS rechazado a propósito no debe resucitar).
- Aviso temprano: SMS de código corto con pinta de monto que ninguna
  plantilla reconoce → notificación (`service/SmsNotifier.kt`,
  POST_NOTIFICATIONS) que abre "Enseñar" con ese SMS
  (`MainActivity.ACTION_TEACH_SMS`).
- Captura compartida receptor/recuperación en `service/SmsCapture.kt`.

---

## Convenciones de código

### Composables
- Pantallas reciben callbacks de navegación como parámetros (`onBack`,
  `onAccountClick`, etc.); nunca tienen referencia directa al NavController
- Estado del formulario: `rememberSaveable` para sobrevivir rotaciones
- Estado derivado como `val`, nunca almacenado en `mutableStateOf`
- Un `DropdownField<T>` genérico ya existe en `AddTransactionScreen` —
  extraerlo a `ui/components/` cuando se use en más de dos pantallas

### ViewModels
- Catálogos reactivos: `Flow` del DAO → `stateIn(WhileSubscribed(5_000))`
- Operaciones de escritura: `viewModelScope.launch { ... }` con callback
  `onSaved` para navegar al terminar (garantiza que el dato ya está en BD)
- ViewModels parametrizados: usar `viewModelFactory { initializer { } }`
  hasta que se justifique montar Hilt

### Room / base de datos
- **Versión actual: 6** (v5 = `transactions.reconciled`, `MIGRATION_4_5`
  aditiva + backfill; v6 = `sms_templates.exampleBody/lastMatchedMillis` +
  corrección del remitente de Davivienda, `MIGRATION_5_6`). Todo cambio de esquema exige: subir `version`,
  escribir la `Migration` y registrarla en `.addMigrations(...)`. Los
  esquemas se exportan a `app/schemas/` (versionados): comparar el JSON
  nuevo contra el anterior para escribir el SQL. NO hay
  `fallbackToDestructiveMigration()` global (borraría datos reales).
- **Excepción legacy (2026-06-13): `.fallbackToDestructiveMigrationFrom(1, 2, 3)`.**
  Las BD v1–v3 son de builds previos a la exportación de esquemas (no hay
  historial para migrarlas) → se recrean. De **v4 en adelante** rige la
  migración estricta. Por qué: un teléfono con una BD vieja v1 crasheaba al
  abrir ("A migration from 1 to 4 was required but not found") porque al
  quitar el fallback global (06-12) y cifrar con SQLCipher se preservó
  `user_version=1`. El crash es al ABRIR la BD (arranque), no por la feature
  que se estuviera usando.
- **Cifrado ACTIVO (SQLCipher, 2026-06-12)**: passphrase aleatoria envuelta
  con llave AES/GCM del Android Keystore (data/DbCrypto.kt). La BD plana
  preexistente se migra sola vía sqlcipher_export() en el primer arranque
  (preserva user_version; fail-safe: el original queda intacto si falla).
- Queries complejas: validadas por KSP en tiempo de compilación; si el
  build pasa, el SQL es correcto

### Seguridad
- Todo procesamiento de SMS y extractos: **100% local**, nada sale del
  dispositivo
- No usar `Double`/`Float` para dinero bajo ninguna circunstancia
- La app es de uso personal: distribuir por APK directo, no Play Store
