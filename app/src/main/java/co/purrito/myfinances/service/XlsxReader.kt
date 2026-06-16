package co.purrito.myfinances.service

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/* =====================================================================
 * Lector mínimo de .xlsx (Hito 4) — SIN librería pesada (no Apache POI).
 *
 * Un .xlsx es un ZIP de XML. Solo necesitamos texto plano por celda, así
 * que basta con descomprimir (java.util.zip) y parsear con DOM
 * (javax.xml, disponible en Android y en la JVM → testeable). No hay
 * dependencias de Android aquí: la lectura del archivo (content Uri →
 * InputStream) la hace la capa de UI.
 *
 * Devuelve una hoja por cada xl/worksheets/sheetN.xml, como filas de
 * celdas (texto). Las celdas se ubican por su referencia ("C5" → col 2)
 * para respetar columnas vacías intermedias.
 * ===================================================================== */

object XlsxReader {

    private val docFactory: DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            // Defensa básica contra XXE en XML de terceros
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isNamespaceAware = false
        }

    /** Lee todas las hojas. Cada hoja es una lista de filas; cada fila, celdas. */
    fun readSheets(input: InputStream): List<List<List<String>>> {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }

        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()

        return entries.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sorted()
            .map { parseSheet(entries.getValue(it), sharedStrings) }
    }

    private fun parseDoc(bytes: ByteArray): Element =
        docFactory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val root = parseDoc(bytes)
        val siList = root.getElementsByTagName("si")
        return (0 until siList.length).map { i ->
            // Concatena todos los <t> del <si> (texto simple o con runs <r>)
            val si = siList.item(i) as Element
            val tList = si.getElementsByTagName("t")
            buildString {
                for (j in 0 until tList.length) append(tList.item(j).textContent)
            }
        }
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val root = parseDoc(bytes)
        val rowList = root.getElementsByTagName("row")
        val rows = ArrayList<List<String>>(rowList.length)

        for (i in 0 until rowList.length) {
            val rowEl = rowList.item(i) as Element
            val cells = sortedMapOf<Int, String>()
            val cNodes = rowEl.childNodes
            for (j in 0 until cNodes.length) {
                val node = cNodes.item(j)
                if (node !is Element || node.tagName != "c") continue
                val ref = node.getAttribute("r")           // "C5"
                val col = columnIndex(ref)
                cells[col] = cellValue(node, shared)
            }
            val maxCol = cells.keys.maxOrNull() ?: -1
            rows.add((0..maxCol).map { cells[it] ?: "" })
        }
        return rows
    }

    private fun cellValue(c: Element, shared: List<String>): String {
        val type = c.getAttribute("t")
        return when (type) {
            "s" -> {
                val idx = childText(c, "v")?.toIntOrNull() ?: return ""
                shared.getOrElse(idx) { "" }
            }
            "inlineStr" -> {
                val isEl = firstChild(c, "is") ?: return ""
                val tList = isEl.getElementsByTagName("t")
                buildString { for (k in 0 until tList.length) append(tList.item(k).textContent) }
            }
            else -> childText(c, "v") ?: ""   // número/fecha: valor literal
        }
    }

    private fun childText(parent: Element, tag: String): String? =
        firstChild(parent, tag)?.textContent

    private fun firstChild(parent: Element, tag: String): Element? {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.tagName == tag) return n
        }
        return null
    }

    /** "C5" → 2, "AB12" → 27. Solo la parte alfabética. */
    private fun columnIndex(ref: String): Int {
        var col = 0
        for (ch in ref) {
            if (!ch.isLetter()) break
            col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return (col - 1).coerceAtLeast(0)
    }
}
