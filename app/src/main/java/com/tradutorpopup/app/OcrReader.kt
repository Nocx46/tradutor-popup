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
            result.textBlocks.mapNotNull { block ->
                val box = block.boundingBox ?: return@mapNotNull null
                val text = block.lines.joinToString(if (cjk) "" else " ") { it.text.trim() }
                if (text.isBlank()) null
                else Segment(text, Rect(box).apply { offset(origin.x, origin.y) })
            }
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
