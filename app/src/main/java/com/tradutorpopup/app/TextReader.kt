package com.tradutorpopup.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.min

/** Um parágrafo de texto e onde ele está na tela. */
data class Segment(val text: String, val rect: Rect)

/**
 * Resultado da leitura: os textos e quanto do quadro (0 a 1) está coberto por imagens
 * (ex.: página de mangá). Se for muito, o texto está DENTRO da imagem e é preciso lê-la (OCR).
 */
data class ReadResult(
    val segments: List<Segment>,
    val imageFraction: Float,
    val sensitive: Boolean = false,
    /** A barra de notificações (ou outra tela do sistema) está aberta por cima do quadro. */
    val coveredBySystem: Boolean = false,
)

/**
 * Lê o texto que está visível dentro de uma área da tela, usando as informações de
 * acessibilidade dos apps e sites (Chrome, redes sociais, etc.).
 * Devolve os parágrafos na ordem de leitura, com a posição de cada um.
 */
object TextReader {

    private class Item(val rect: Rect, val text: String)

    private const val MAX_NODES = 6000

    fun read(service: AccessibilityService, area: Rect): ReadResult {
        val items = ArrayList<Item>()
        val seen = HashSet<String>()
        val budget = intArrayOf(MAX_NODES)
        val imageArea = longArrayOf(0)

        val windows = try {
            service.windows.sortedByDescending { it.layer }
        } catch (_: Exception) {
            emptyList()
        }

        var usedAny = false
        for (w in windows) {
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue // nosso pop-up
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue // teclado
            val bounds = Rect().also { w.getBoundsInScreen(it) }
            if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                // (a janela da barra fechada é só a faixa de cima; aberta, ocupa a tela toda)
                // Barra de notificações aberta por cima de tudo: o quadro deve sumir
                if (bounds.contains(area)) return ReadResult(emptyList(), 0f, coveredBySystem = true)
                continue // barra de status/navegação
            }
            if (!Rect.intersects(bounds, area)) continue
            val root = w.root ?: continue
            if (root.packageName == service.packageName) continue
            // Apps de banco/pagamento: não lê nada (privacidade)
            if (SensitiveApps.isSensitive(root.packageName)) return ReadResult(emptyList(), 0f, sensitive = true)
            collect(root, area, items, seen, budget, imageArea, 0)
            usedAny = true
            // Uma janela de app que cobre todo o quadro esconde as janelas de baixo
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && bounds.contains(area)) break
        }
        if (!usedAny) {
            service.rootInActiveWindow?.let { root ->
                if (SensitiveApps.isSensitive(root.packageName)) return ReadResult(emptyList(), 0f, sensitive = true)
                if (root.packageName != service.packageName) collect(root, area, items, seen, budget, imageArea, 0)
            }
        }
        val boxArea = area.width().toLong() * area.height()
        val fraction = if (boxArea > 0) min(1f, imageArea[0].toFloat() / boxArea) else 0f
        return ReadResult(toParagraphs(items), fraction)
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        area: Rect,
        out: MutableList<Item>,
        seen: HashSet<String>,
        budget: IntArray,
        imageArea: LongArray,
        depth: Int,
    ) {
        if (budget[0]-- <= 0 || depth > 100) return
        val r = Rect()
        node.getBoundsInScreen(r)
        // Obs.: não pulamos "containers" pela posição/visibilidade deles — depois de rolar,
        // o Chrome informa a posição de alguns containers errada, e o texto de dentro sumiria.
        // A posição é conferida só nos próprios textos.

        // Imagens (ou áreas grandes sem texto, como a página de um leitor de mangá):
        // soma a área delas dentro do quadro e NÃO usa a "descrição" (alt) como texto.
        val cls = node.className?.toString() ?: ""
        val isImage = cls.contains("Image", ignoreCase = true)
        if (node.isVisibleToUser && !r.isEmpty && (isImage || node.childCount == 0)) {
            val inter = Rect(r)
            if (inter.intersect(area)) {
                val a = inter.width().toLong() * inter.height()
                val bigBlankLeaf = node.childCount == 0 && node.text.isNullOrEmpty() &&
                    a * 4 >= area.width().toLong() * area.height()
                if (isImage || bigBlankLeaf) imageArea[0] += a
            }
        }
        if (isImage) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collect(child, area, out, seen, budget, imageArea, depth + 1)
            }
            return
        }

        val raw = node.text ?: if (node.childCount == 0) node.contentDescription else null
        val text = raw?.toString()?.trim()?.replace(Regex("\\s+"), " ")
        if (!text.isNullOrEmpty() && !r.isEmpty && node.isVisibleToUser && isInside(r, area) &&
            seen.add(text + r.toShortString())
        ) {
            out.add(Item(Rect(r), text))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, area, out, seen, budget, imageArea, depth + 1)
        }
    }

    /** O texto conta se estiver, na maior parte, dentro do quadro. */
    private fun isInside(r: Rect, area: Rect): Boolean {
        if (area.contains(r.centerX(), r.centerY())) return true
        val inter = Rect(r)
        if (!inter.intersect(area)) return false
        val interArea = inter.width().toLong() * inter.height()
        val nodeArea = r.width().toLong() * r.height()
        if (nodeArea > 0 && interArea * 10 >= nodeArea * 4) return true
        // Parágrafos grandes que passam do quadro
        return inter.height() * 2 >= area.height() && inter.width() * 2 >= min(r.width(), area.width())
    }

    /**
     * Junta os pedaços em parágrafos. Os pedaços já vêm na ordem de leitura (ordem da página);
     * um novo parágrafo começa quando há um espaço vertical maior que meia linha.
     * Assim "texto com [link] no meio" é traduzido como uma frase só.
     */
    private fun toParagraphs(items: List<Item>): List<Segment> {
        if (items.isEmpty()) return emptyList()
        val paragraphs = ArrayList<Pair<StringBuilder, Rect>>()
        var cur: StringBuilder? = null
        var curRect: Rect? = null
        var lineH = 0 // menor altura vista no parágrafo = altura de uma linha
        for (item in items) {
            val r = item.rect
            val rc = curRect
            val h = if (lineH > 0) min(lineH, r.height()) else r.height()
            val sameParagraph = rc != null &&
                r.top <= rc.bottom + h / 2 && // não há espaço grande abaixo
                r.bottom >= rc.top // não voltou para cima (outra coluna/bloco)
            if (sameParagraph && cur != null) {
                if (needsSpace(cur, item.text)) cur.append(' ')
                cur.append(item.text)
                rc!!.union(r)
                lineH = h
            } else {
                cur = StringBuilder(item.text)
                curRect = Rect(r)
                paragraphs.add(cur to curRect)
                lineH = r.height()
            }
        }
        return paragraphs
            .map { (sb, rect) -> Segment(sb.toString().replace(Regex("\\s+"), " ").trim(), rect) }
            .filter { it.text.isNotEmpty() }
    }

    /** Não coloca espaço antes de pontuação nem entre ideogramas (japonês/chinês). */
    private fun needsSpace(sb: StringBuilder, next: String): Boolean {
        if (sb.isEmpty() || next.isEmpty()) return false
        val a = sb[sb.length - 1]
        val b = next[0]
        if (a.isWhitespace() || b.isWhitespace()) return false
        if (b in ".,;:!?)]}%»”’、。，．！？」』）") return false
        if (a in "([{«“‘「『（") return false
        if (isCjk(a) && isCjk(b)) return false
        return true
    }

    private fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }
}
