package com.tradutorpopup.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Imagem desfocada do app que está embaixo do quadro (para o efeito de vidro fosco).
 * [small] é a tela reduzida [scale] vezes e já desfocada; [origin] é onde ela começa na tela.
 */
class Backdrop(val small: Bitmap, val origin: Point, val scale: Float)

/**
 * Desenha a tradução DENTRO do quadro, por cima do texto original, com efeito de
 * vidro fosco no estilo dos widgets do iPhone: o fundo fica desfocado (não dá para
 * ler o texto original), com cantos arredondados, borda clarinha e sombra suave.
 * Se o fundo for escuro, o vidro fica escuro e o texto branco.
 *
 * As posições ficam guardadas em coordenadas da tela; assim, se o quadro for movido,
 * as caixas continuam alinhadas com o texto da página.
 */
class TranslationOverlayView(context: Context) : View(context) {

    private class Block(val screen: Rect, val text: String) {
        val local = RectF()
        var layout: StaticLayout? = null
        var dark = false
    }

    private var blocks: List<Block> = emptyList()
    private var backdrop: Backdrop? = null
    private var message: String? = null
    private var messageLayout: StaticLayout? = null
    private val loc = IntArray(2)
    private var cacheKey = ""

    private val padH = dp(8f)
    private val padV = dp(4f)
    private val maxRadius = dp(14f)
    private val minText = sp(7f)
    private val maxText = sp(22f)

    // --- Pincéis do vidro ---
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        setShadowLayer(dp(10f), 0f, dp(3f), 0x40000000)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val path = Path()
    private val src = Rect()

    // Cores (iOS: vidro claro com texto quase preto; vidro escuro com texto branco)
    private val lightTintWithBlur = 0x9EF7F7FA.toInt()
    private val lightTintNoBlur = 0xF0F2F2F7.toInt()
    private val darkTintWithBlur = 0x8C1C1C1E.toInt()
    private val darkTintNoBlur = 0xF02C2C2E.toInt()
    private val lightText = 0xFF1C1C1E.toInt()
    private val darkText = 0xFFFFFFFF.toInt()

    init {
        // A sombra (setShadowLayer) precisa de camada de software em alguns aparelhos
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    val isEmpty get() = blocks.isEmpty()

    fun setBlocks(list: List<Pair<Rect, String>>, newBackdrop: Backdrop?) {
        blocks = list.map { Block(Rect(it.first), it.second) }
        backdrop?.takeIf { it !== newBackdrop }?.small?.recycle()
        backdrop = newBackdrop
        message = null
        cacheKey = ""
        invalidate()
    }

    /** Aviso centralizado (ex.: "Lendo o texto…") no lugar da tradução. */
    fun showMessage(text: String) {
        blocks = emptyList()
        message = text
        cacheKey = ""
        invalidate()
    }

    fun clear() {
        blocks = emptyList()
        message = null
        cacheKey = ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        relayout()
        val msg = messageLayout
        if (message != null && msg != null) {
            drawMessage(canvas, msg)
            return
        }
        for (b in blocks) {
            val lay = b.layout ?: continue
            drawGlass(canvas, b.local, b.dark)
            textPaint.color = if (b.dark) darkText else lightText
            canvas.save()
            canvas.clipRect(b.local)
            val innerH = b.local.height() - 2 * padV
            val dy = if (lay.lineCount == 1) max(0f, (innerH - lay.height) / 2f) else 0f
            canvas.translate(b.local.left + padH, b.local.top + padV + dy)
            lay.draw(canvas)
            canvas.restore()
        }
    }

    /** Uma "placa" de vidro fosco: sombra, fundo desfocado, véu de cor, brilho e borda. */
    private fun drawGlass(canvas: Canvas, r: RectF, dark: Boolean) {
        val radius = min(maxRadius, r.height() / 2f)
        val bd = backdrop

        // Sombra suave por baixo
        shadowPaint.color = if (dark) 0x33000000 else 0x22000000
        canvas.drawRoundRect(r, radius, radius, shadowPaint)

        path.reset()
        path.addRoundRect(r, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)

        // Fundo: o próprio app de baixo, bem desfocado
        if (bd != null) {
            src.set(
                ((r.left + loc[0] - bd.origin.x) / bd.scale).toInt(),
                ((r.top + loc[1] - bd.origin.y) / bd.scale).toInt(),
                ((r.right + loc[0] - bd.origin.x) / bd.scale).toInt() + 1,
                ((r.bottom + loc[1] - bd.origin.y) / bd.scale).toInt() + 1,
            )
            canvas.drawBitmap(bd.small, src, r, bitmapPaint)
        }
        // Véu de cor (mais forte se não houver desfoque, para esconder o texto original)
        tintPaint.color = when {
            dark && bd != null -> darkTintWithBlur
            dark -> darkTintNoBlur
            bd != null -> lightTintWithBlur
            else -> lightTintNoBlur
        }
        canvas.drawRect(r, tintPaint)

        // Brilho no topo, como a luz refletida no vidro
        sheenPaint.shader = LinearGradient(
            0f, r.top, 0f, r.top + r.height() * 0.6f,
            if (dark) 0x1FFFFFFF else 0x59FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(r, sheenPaint)
        canvas.restore()

        // Borda fina e clara
        borderPaint.color = if (dark) 0x2EFFFFFF else 0x8CFFFFFF.toInt()
        val half = borderPaint.strokeWidth / 2f
        canvas.drawRoundRect(r.left + half, r.top + half, r.right - half, r.bottom - half, radius, radius, borderPaint)
    }

    private fun drawMessage(canvas: Canvas, lay: StaticLayout) {
        val w = lay.width + 2 * dp(14f)
        val h = lay.height + 2 * dp(10f)
        val r = RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        drawGlass(canvas, r, dark = false)
        textPaint.color = lightText
        canvas.save()
        canvas.translate(r.left + dp(14f), r.top + dp(10f))
        lay.draw(canvas)
        canvas.restore()
    }

    /** Recalcula as caixas quando o quadro muda de lugar/tamanho ou o texto muda. */
    private fun relayout() {
        getLocationOnScreen(loc)
        val key = "${loc[0]},${loc[1]},$width,$height,${blocks.size},${message?.length}"
        if (key == cacheKey) return
        cacheKey = key

        message?.let { m ->
            val maxW = max(1, (width - 2 * dp(24f)).toInt())
            val p = TextPaint(textPaint).apply { textSize = sp(14f); color = lightText }
            val natural = StaticLayout.getDesiredWidth(m, p).toInt() + 1
            messageLayout = StaticLayout.Builder.obtain(m, 0, m.length, p, min(maxW, natural))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
        }

        val inset = dp(3f)
        val bounds = RectF(inset, inset, width - inset, height - inset)
        // Posição original de cada texto (dentro do quadro), para uma caixa não crescer por cima da outra
        val originals = blocks.map {
            RectF(
                (it.screen.left - loc[0]).toFloat(), (it.screen.top - loc[1]).toFloat(),
                (it.screen.right - loc[0]).toFloat(), (it.screen.bottom - loc[1]).toFloat(),
            )
        }
        val gap = dp(2f)
        for ((idx, b) in blocks.withIndex()) {
            b.layout = null
            val o = originals[idx]
            val r = RectF(o.left - dp(2f), o.top - dp(1f), o.right + dp(2f), o.bottom + dp(1f))
            if (!r.intersect(bounds)) continue
            if (r.width() < dp(10f) || r.height() < dp(6f)) continue

            // Até onde a caixa pode crescer sem cobrir o vizinho da direita / de baixo
            var rightLimit = bounds.right
            var bottomLimit = bounds.bottom
            for ((j, n) in originals.withIndex()) {
                if (j == idx) continue
                val sameRow = n.top < o.bottom && n.bottom > o.top
                val sameCol = n.left < o.right && n.right > o.left
                if (sameRow && n.left >= o.right - gap) rightLimit = min(rightLimit, n.left - gap)
                if (sameCol && n.top >= o.bottom - gap) bottomLimit = min(bottomLimit, n.top - gap)
            }
            rightLimit = max(rightLimit, r.right)
            bottomLimit = max(bottomLimit, r.bottom)

            // 1) Tenta caber no espaço do texto original
            var lay = fit(b.text, r)
            if (lay == null) {
                // 2) Não coube: a caixa cresce para a direita, até o vizinho ou a borda do quadro
                r.right = rightLimit
                lay = fit(b.text, r)
            }
            if (lay == null) {
                // 3) Ainda não: cresce para baixo o necessário (letra legível), até o vizinho de baixo
                val readable = make(b.text, (r.width() - 2 * padH).toInt(), max(minText, sp(11f)))
                r.bottom = min(bottomLimit, r.top + readable.height + 2 * padV)
                lay = fit(b.text, r)
            }
            if (lay == null) {
                // 4) Não cabe nem assim: letra mínima (o resto fica cortado)
                lay = make(b.text, max(1, (r.width() - 2 * padH).toInt()), minText)
            }
            b.local.set(r)
            b.layout = lay
            b.dark = isDarkBehind(r)
        }
    }

    /** O fundo atrás desta caixa é escuro? (média de brilho da imagem desfocada) */
    private fun isDarkBehind(r: RectF): Boolean {
        val bd = backdrop ?: return false
        val bmp = bd.small
        val l = ((r.left + loc[0] - bd.origin.x) / bd.scale).toInt().coerceIn(0, bmp.width - 1)
        val t = ((r.top + loc[1] - bd.origin.y) / bd.scale).toInt().coerceIn(0, bmp.height - 1)
        val rr = ((r.right + loc[0] - bd.origin.x) / bd.scale).toInt().coerceIn(l + 1, bmp.width)
        val bb = ((r.bottom + loc[1] - bd.origin.y) / bd.scale).toInt().coerceIn(t + 1, bmp.height)
        var sum = 0.0
        var n = 0
        var y = t
        while (y < bb) {
            var x = l
            while (x < rr) {
                val c = bmp.getPixel(x, y)
                sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
                n++
                x += 2
            }
            y += 2
        }
        return n > 0 && sum / n < 110
    }

    /** Maior tamanho de letra (entre o mínimo e o máximo) em que o texto cabe em [r]; null se não couber. */
    private fun fit(text: String, r: RectF): StaticLayout? {
        val w = (r.width() - 2 * padH).toInt()
        val h = r.height() - 2 * padV
        if (w < dp(8f) || h < dp(4f)) return null
        val smallest = make(text, w, minText)
        if (smallest.height > h) return null
        var lo = minText
        var hi = max(minText, min(maxText, h * 0.9f))
        val top = make(text, w, hi)
        if (top.height <= h) return top
        var best = smallest
        repeat(7) {
            val mid = (lo + hi) / 2f
            val lay = make(text, w, mid)
            if (lay.height <= h) { best = lay; lo = mid } else hi = mid
        }
        return best
    }

    private fun make(text: String, width: Int, size: Float): StaticLayout {
        val p = TextPaint(textPaint).apply { textSize = size }
        return StaticLayout.Builder.obtain(text, 0, text.length, p, max(1, width))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
