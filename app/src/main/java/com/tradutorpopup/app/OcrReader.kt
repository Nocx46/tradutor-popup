package com.tradutorpopup.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.google.mlkit.common.MlKitException
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max

/**
 * Lê texto de IMAGENS dentro do quadro (fotos, vídeos, jogos, mangás...).
 * Usado só quando o app não informa o texto. Precisa de Android 11 ou mais novo.
 */
class OcrReader {

    private val clients = HashMap<String, TextRecognizer>()

    /** true se o leitor de imagens ainda não foi baixado pelo Google Play. */
    @Volatile var modelMissing = false
        private set

    companion object {
        /** O leitor de imagens do ML Kit não reconhece o alfabeto russo (cirílico). */
        fun supports(lang: String) = lang != TranslateLanguage.RUSSIAN
        private const val INSET = 6

        /** Segundas partes que normalmente formam palavra composta com hífen (o hífen fica). */
        private val COMPOUND_PARTS = setOf(
            "based", "like", "free", "type", "style", "made", "related", "friendly", "sized",
            "level", "class", "rank", "time", "old", "year", "proof", "up", "in", "out", "off",
            "on", "down", "over", "all", "self", "ex", "well", "half", "known", "looking",
        )
        private val CJK = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
    }

    private fun client(lang: String): TextRecognizer {
        val key = when (lang) {
            // Automático: o leitor japonês também lê letras latinas (inglês/espanhol)
            Languages.AUTO, TranslateLanguage.JAPANESE -> "ja"
            TranslateLanguage.CHINESE -> "zh"
            else -> "latin"
        }
        return clients.getOrPut(key) {
            when (key) {
                "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

    /**
     * @param hideOverlay/showOverlay usados só quando é preciso fotografar a tela inteira
     * (Android 11–13), para a nossa própria tradução não aparecer na foto.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun read(
        service: AccessibilityService,
        area: Rect,
        lang: String,
        hideOverlay: suspend () -> Unit,
        showOverlay: () -> Unit,
    ): List<Segment> {
        val shot = captureAppWindow(service, area)
            ?: run {
                hideOverlay()
                try { captureScreen(service, area) } finally { showOverlay() }
            }
            ?: return emptyList()
        val (bmp, origin) = shot
        return try {
            modelMissing = false
            val result = client(lang).process(InputImage.fromBitmap(bmp, 0)).await()
            val cjk = lang == TranslateLanguage.JAPANESE || lang == TranslateLanguage.CHINESE ||
                (lang == Languages.AUTO && result.text.any { Character.UnicodeScript.of(it.code) in CJK })
            // Trabalha LINHA por LINHA: o leitor às vezes junta num mesmo "bloco" linhas de dois
            // balões vizinhos (lado a lado). Reagrupamos as linhas pela posição, balão por balão.
            // E mais: balões lado a lado na mesma altura viram UMA linha só para o leitor
            // ("a level-   in rein-"). Quebramos a linha onde há um espaço grande entre palavras.
            val sep = if (cjk) "" else " "
            val lines = result.textBlocks.flatMap { it.lines }.flatMap { line -> splitLine(line, sep) }
                .map { Segment(it.text, Rect(it.rect).apply { offset(origin.x, origin.y) }) }
            val merged = mergeBubbles(lines, sep)
            merged
        } catch (e: MlKitException) {
            // O leitor de imagens ainda está sendo baixado pelo Google Play
            if (e.errorCode == MlKitException.UNAVAILABLE) modelMissing = true
            emptyList()
        } catch (_: Exception) {
            emptyList()
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Divide uma linha lida em pedaços onde o espaço entre duas palavras é grande demais
     * (mais que meia letra de altura) — sinal de que são dois balões diferentes lado a lado.
     */
    private fun splitLine(line: Text.Line, sep: String): List<Segment> {
        val words = line.elements.filter { it.boundingBox != null && it.text.isNotBlank() }
            .sortedBy { it.boundingBox!!.left }
        if (words.isEmpty()) {
            val box = line.boundingBox ?: return emptyList()
            return if (line.text.isBlank()) emptyList() else listOf(Segment(line.text.trim(), Rect(box)))
        }
        val out = ArrayList<Segment>()
        var text = StringBuilder(words[0].text)
        var rect = Rect(words[0].boundingBox!!)
        var prev = Rect(words[0].boundingBox!!)
        for (w in words.drop(1)) {
            val b = w.boundingBox!!
            val gap = b.left - prev.right
            val h = minOf(prev.height(), b.height())
            // Espaço normal entre palavras ≈ 1/4 da altura da letra; balões vizinhos ficam bem mais longe.
            // Outro sinal: a palavra seguinte está numa altura bem diferente (outro balão).
            val otherHeight = abs(b.centerY() - prev.centerY()) > h * 0.4f
            prev = Rect(b)
            if (gap > h * 0.35f || otherHeight) {
                out.add(Segment(text.toString().trim(), rect))
                text = StringBuilder(w.text)
                rect = Rect(b)
            } else {
                text.append(sep).append(w.text)
                rect.union(b)
            }
        }
        out.add(Segment(text.toString().trim(), rect))
        return out.filter { it.text.isNotBlank() }
    }

    /**
     * Junta as linhas de um balão. Em mangá as palavras são quebradas com hífen no fim da linha
     * ("expla-" / "nations", "rein-" / "carnation"): aqui a palavra é remontada ("explanations").
     * Se a segunda parte for uma palavra que costuma formar compostos ("level-" / "based"),
     * o hífen fica ("level-based").
     */
    private fun joinLines(lines: List<String>, sep: String): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (sb.isEmpty()) { sb.append(line); continue }
            val endsWithHyphen = sb.last() == '-' || sb.last() == '‐' || sb.last() == '–'
            val prevChar = if (sb.length >= 2) sb[sb.length - 2] else ' '
            if (endsWithHyphen && prevChar.isLetter() && line.first().isLowerCase()) {
                val nextWord = line.takeWhile { it.isLetter() }.lowercase()
                if (nextWord in COMPOUND_PARTS) {
                    sb.append(line) // "level-" + "based" → "level-based"
                } else {
                    sb.setLength(sb.length - 1) // tira o hífen
                    sb.append(line) // "expla-" + "nations" → "explanations"
                }
            } else {
                sb.append(sep).append(line)
            }
        }
        return sb.toString()
    }

    /**
     * O leitor às vezes divide um mesmo balão de fala em vários pedaços ("Para não mencionar" /
     * "o novo" / "prazer da magia"). Traduzidos separados, perdem o sentido. Aqui juntamos os
     * pedaços que estão um embaixo do outro, alinhados e bem próximos — o mesmo balão — numa frase só.
     */
    private fun mergeBubbles(blocks: List<Segment>, sep: String): List<Segment> {
        if (blocks.size < 2) return blocks
        // Cada grupo guarda também a última linha, para comparar a linha nova com a de cima
        val groups = ArrayList<Pair<StringBuilder, Rect>>()
        val lastLine = ArrayList<Rect>()
        for (b in blocks.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))) {
            val r = b.rect
            val idx = groups.indices.lastOrNull { gi ->
                val g = groups[gi].second // a "coluna" do balão inteiro
                val prev = lastLine[gi]    // a última linha que entrou nele
                val overlapW = minOf(g.right, r.right) - maxOf(g.left, r.left)
                val narrower = minOf(g.width(), r.width())
                val lineH = minOf(prev.height(), r.height())
                val gap = r.top - prev.bottom
                // Mesma coluna do balão e logo abaixo da última linha (ou na mesma linha)
                val centersClose = abs(g.centerX() - r.centerX()) <= maxOf(g.width(), r.width()) / 2
                overlapW > narrower / 2 && centersClose && gap in -lineH..(lineH * 3 / 4 + 4)
            }
            val target = idx?.let { groups[it] }
            if (idx != null) lastLine[idx] = Rect(r)
            if (target != null) {
                val joined = joinLines(listOf(target.first.toString(), b.text), sep)
                target.first.setLength(0)
                target.first.append(joined)
                target.second.union(r)
            } else {
                groups.add(StringBuilder(b.text) to Rect(r))
                lastLine.add(Rect(r))
            }
        }
        return groups.map { (sb, rect) -> Segment(sb.toString().replace(Regex("\\s+"), " ").trim(), rect) }
    }

    /**
     * Fundo para o efeito de vidro fosco: foto do app de baixo (sem o nosso quadro),
     * reduzida 8x e bem desfocada. Só no Android 14+; nos outros, a caixa usa cor lisa.
     */
    suspend fun captureBackdrop(service: AccessibilityService, area: Rect): Backdrop? {
        if (Build.VERSION.SDK_INT < 34) return null
        val (win, wb) = findAppWindow(service, area) ?: return null
        val full = shoot { cb -> service.takeScreenshotOfWindow(win.id, service.mainExecutor, cb) } ?: return null
        if (full.width != wb.width() || full.height != wb.height()) {
            full.recycle()
            return null
        }
        return withContext(Dispatchers.Default) {
            try {
                val scale = 8
                val small = Bitmap.createScaledBitmap(full, max(1, full.width / scale), max(1, full.height / scale), true)
                full.recycle()
                val blurred = boxBlur(small, radius = 3, passes = 3)
                if (blurred !== small) small.recycle()
                Backdrop(blurred, Point(wb.left, wb.top), scale.toFloat())
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Desfoque simples (caixa, várias passadas ≈ gaussiano) numa imagem pequena. */
    private fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        repeat(passes) {
            blurLine(a, b, w, h, radius, horizontal = true)
            blurLine(b, a, w, h, radius, horizontal = false)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(a, 0, w, 0, 0, w, h) }
    }

    private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        for (o in 0 until outer) {
            for (i in 0 until inner) {
                var rs = 0; var gs = 0; var bs = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, inner - 1)
                    val c = if (horizontal) src[o * w + j] else src[j * w + o]
                    rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF; n++
                }
                val idx = if (horizontal) o * w + i else i * w + o
                dst[idx] = (0xFF shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
            }
        }
    }

    private fun findAppWindow(service: AccessibilityService, area: Rect): Pair<AccessibilityWindowInfo, Rect>? {
        val win = try {
            service.windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { it.layer }
                .firstOrNull {
                    val b = Rect().also { r -> it.getBoundsInScreen(r) }
                    b.contains(area.centerX(), area.centerY()) && it.root?.packageName != service.packageName
                }
        } catch (_: Exception) {
            null
        } ?: return null
        return win to Rect().also { win.getBoundsInScreen(it) }
    }

    /** Android 14+: fotografa só a janela do app de baixo (sem o nosso quadro por cima). */
    private suspend fun captureAppWindow(service: AccessibilityService, area: Rect): Pair<Bitmap, Point>? {
        if (Build.VERSION.SDK_INT < 34) return null
        val (win, wb) = findAppWindow(service, area) ?: return null
        val full = shoot { cb -> service.takeScreenshotOfWindow(win.id, service.mainExecutor, cb) } ?: return null
        if (full.width != wb.width() || full.height != wb.height()) {
            full.recycle()
            return null
        }
        val target = Rect(area).apply { inset(INSET, INSET) }
        if (!target.intersect(wb)) { full.recycle(); return null }
        val local = Rect(target).apply { offset(-wb.left, -wb.top) }
        val crop = crop(full, local) ?: return null
        return crop to Point(target.left, target.top)
    }

    /** Android 11–13: fotografa a tela inteira e recorta o quadro. */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreen(service: AccessibilityService, area: Rect): Pair<Bitmap, Point>? {
        val full = shoot { cb -> service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, cb) } ?: return null
        val target = Rect(area).apply { inset(INSET, INSET) }
        if (!target.intersect(0, 0, full.width, full.height)) { full.recycle(); return null }
        val crop = crop(full, target) ?: return null
        return crop to Point(target.left, target.top)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun shoot(start: (AccessibilityService.TakeScreenshotCallback) -> Unit): Bitmap? =
        suspendCancellableCoroutine { cont ->
            try {
                start(object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                        val buffer = shot.hardwareBuffer
                        val out = try {
                            val hw = Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace)
                            val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            soft
                        } catch (_: Exception) {
                            null
                        } finally {
                            buffer.close()
                        }
                        if (cont.isActive) cont.resume(out) else out?.recycle()
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun crop(src: Bitmap, r: Rect): Bitmap? {
        val l = r.left.coerceIn(0, src.width)
        val t = r.top.coerceIn(0, src.height)
        val rr = r.right.coerceIn(0, src.width)
        val b = r.bottom.coerceIn(0, src.height)
        if (rr - l < 16 || b - t < 16) {
            src.recycle()
            return null
        }
        val out = Bitmap.createBitmap(src, l, t, rr - l, b - t)
        if (out !== src) src.recycle()
        return out
    }

    fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }
}
