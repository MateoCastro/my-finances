package co.purrito.myfinances.ui.theme

/* =====================================================================
 * Duraciones de animación de la app — cortas a propósito: en pantallas
 * de alta tasa de refresco una animación larga se percibe "laggy".
 * Compose ya anima a la frecuencia del display; esto solo fija cuánto
 * duran.
 *
 *  - Fast:   micro-interacciones (pills, tintes, indicadores)
 *  - Normal: transiciones de contenido (pantallas, meses, expansiones)
 * ===================================================================== */

object Motion {
    const val Fast = 250
    const val Normal = 400

    /** Transiciones entre pantallas (bottom nav, push al detalle):
     *  más cortas que Normal — a pantalla completa se perciben lentas. */
    const val Nav = 300
}
