package com.tradutorpopup.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Desenha e controla:
 *  - o botão flutuante (文A), que pode ser arrastado;
 *  - o quadro transparente (toques passam através dele para o app de baixo);
 *  - a barra azul em cima do quadro (arrastar = mover, ✕ = fechar);
 *  - a bolinha no canto (arrastar = aumentar/diminuir);
 *  - a tradução, desenhada dentro do quadro sobre o texto original (vidro fosco).
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class OverlayController(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    /** Tocar e segurar o 文A: desliga o tradutor (ex.: antes de abrir o app do banco). */
    private val onTurnOff: () -> Unit = {},
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val engine = TranslationEngine()
    private val ocr = OcrReader()

    // Botão flutuante
    private var bubble: TextView? = null
    private val bubbleP = overlayParams(touchable = true)
    private val bubbleSize get() = dp(56)

    // Quadro
    private var boxOpen = false
    private var frame: View? = null
    private var bar: LinearLayout? = null
    private var barLabel: TextView? = null
    private var handle: TextView? = null
    private var overlayView: TranslationOverlayView? = null
    private var hiddenForScroll = false
    private val frameP = overlayParams(touchable = false)
    private val barP = overlayParams(touchable = true)
    private val handleP = overlayParams(touchable = true)

    private var boxX = 0
    private var boxY = 0
    private var boxW = 0
    private var boxH = 0
    private val barH get() = dp(36)
    private val handleSize get() = dp(40)

    // Atualização em tempo real
    private var loopJob: Job? = null
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private var lastSig: String? = null
    private var lastOcrAt = 0L
    private var lastOcrSegments: List<Segment> = emptyList()
    private var interacting = false
    private var lastEventAt = 0L

    // ================= Botão flutuante =================

    fun showBubble() {
        if (bubble != null) return
        val scr = screenSize()
        val v = TextView(service).apply {
            text = "文A"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bubble_bg)
            contentDescription = "Abrir tradutor"
        }
        bubbleP.width = bubbleSize
        bubbleP.height = bubbleSize
        bubbleP.x = scr.x - bubbleSize - dp(6)
        bubbleP.y = scr.y / 3

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false; var longPressed = false
        // Tocar e segurar (sem arrastar) = desligar o tradutor
        val longPress = Runnable {
            longPressed = true
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onTurnOff()
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = bubbleP.x; oy = bubbleP.y
                    moved = false; longPressed = false
                    v.postDelayed(longPress, 800)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!moved && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                        moved = true
                        v.removeCallbacks(longPress)
                    }
                    if (moved && !longPressed) {
                        bubbleP.x = ox + dx.toInt()
                        bubbleP.y = oy + dy.toInt()
                        clampBubble()
                        update(v, bubbleP)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    if (e.actionMasked == MotionEvent.ACTION_UP && !longPressed) {
                        if (moved) snapBubble() else toggleBox()
                    }
                }
            }
            true
        }
        safeAdd(v, bubbleP)
        bubble = v
    }

    private fun clampBubble() {
        val scr = screenSize()
        bubbleP.x = bubbleP.x.coerceIn(0, max(0, scr.x - bubbleSize))
        bubbleP.y = bubbleP.y.coerceIn(0, max(0, scr.y - bubbleSize))
    }

    /** Encosta o botão na borda mais próxima da tela. */
    private fun snapBubble() {
        val scr = screenSize()
        bubbleP.x = if (bubbleP.x + bubbleSize / 2 < scr.x / 2) dp(6) else scr.x - bubbleSize - dp(6)
        clampBubble()
        bubble?.let { update(it, bubbleP) }
    }

    private fun toggleBox() = if (boxOpen) closeBox() else openBox()

    // ================= Quadro de tradução =================

    private fun openBox() {
        if (boxOpen) return
        val scr = screenSize()
        if (boxW == 0) {
            // Abre onde estava da última vez; na primeira vez, no meio da tela
            val saved = prefs.box
            if (saved != null) {
                boxX = saved.left; boxY = saved.top; boxW = saved.width(); boxH = saved.height()
            } else {
                boxW = (scr.x * 0.86f).toInt()
                boxH = dp(170)
                boxX = (scr.x - boxW) / 2
                boxY = (scr.y * 0.28f).toInt()
            }
        }
        clampBox()
        createBoxViews()
        boxOpen = true
        layoutBox() // define posição/tamanho ANTES de adicionar as janelas
        safeAdd(frame!!, frameP)
        safeAdd(bar!!, barP)
        safeAdd(handle!!, handleP)
        setBoxHidden(false)
        updateBarLabel()
        setStatus("Lendo o texto dentro do quadro…")
        bubble?.setBackgroundResource(R.drawable.bubble_bg_active)
        lastSig = null
        lastOcrAt = 0
        startLoop()
    }

    private fun closeBox() {
        if (!boxOpen) return
        boxOpen = false
        loopJob?.cancel()
        loopJob = null
        listOf(frame, bar, handle).forEach { it?.let(::safeRemove) }
        bubble?.setBackgroundResource(R.drawable.bubble_bg)
    }

    private fun createBoxViews() {
        if (frame != null) return

        // Quadro: borda + a tradução desenhada por dentro (não recebe toques)
        val ov = TranslationOverlayView(service)
        overlayView = ov
        frame = FrameLayout(service).apply {
            setBackgroundResource(R.drawable.frame_border)
            addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        // Barra superior: arrastar para mover
        val label = TextView(service).apply {
            setTextColor(0xFF1C1C1E.toInt())
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            // Quadro estreito: a letra diminui para "EN → PT" caber inteiro
            setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f)
        }
        val close = TextView(service).apply {
            text = "✕"
            setTextColor(0xFF1C1C1E.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            contentDescription = "Fechar quadro"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnClickListener { closeBox() }
        }
        bar = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bar_bg)
            setPadding(dp(12), 0, 0, 0)
            addView(label)
            addView(close)
            setOnTouchListener(dragTouch(resize = false))
        }
        barLabel = label

        // Bolinha do canto: arrastar para redimensionar
        handle = TextView(service).apply {
            text = "⤡"
            setTextColor(0xFF1C1C1E.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.handle_bg)
            contentDescription = "Redimensionar quadro"
            setOnTouchListener(dragTouch(resize = true))
        }
    }

    /**
     * Toque para mover (barra) ou redimensionar (bolinha do canto).
     * Um toque rápido na barra (sem arrastar) troca o idioma do texto.
     */
    private fun dragTouch(resize: Boolean): View.OnTouchListener {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var ow = 0; var oh = 0
        var moved = false
        return View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY
                    ox = boxX; oy = boxY; ow = boxW; oh = boxH
                    moved = false
                    interacting = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (!moved && abs(dx) < dp(8) && abs(dy) < dp(8)) return@OnTouchListener true
                    moved = true
                    if (resize) {
                        val scr = screenSize()
                        boxW = (ow + dx).coerceIn(dp(120), max(dp(120), scr.x - boxX))
                        boxH = (oh + dy).coerceIn(dp(60), max(dp(60), scr.y - boxY))
                    } else {
                        boxX = ox + dx
                        boxY = oy + dy
                    }
                    clampBox()
                    layoutBox()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    interacting = false
                    if (moved) {
                        prefs.box = Rect(boxX, boxY, boxX + boxW, boxY + boxH) // lembra para a próxima vez
                        lastOcrAt = 0
                        trigger.trySend(Unit)
                    } else if (!resize && e.actionMasked == MotionEvent.ACTION_UP) {
                        cycleSourceLanguage()
                    }
                }
            }
            true
        }
    }

    /** Toque na barra: Automático → Inglês → Japonês → Espanhol → Chinês → Russo → Automático… */
    private fun cycleSourceLanguage() {
        val list = Languages.sources
        val i = list.indexOfFirst { it.code == prefs.source }
        prefs.source = list[(i + 1) % list.size].code // o serviço avisa onLanguageChanged()
    }

    private fun clampBox() {
        val scr = screenSize()
        boxW = boxW.coerceIn(dp(120), max(dp(120), scr.x))
        boxH = boxH.coerceIn(dp(60), max(dp(60), scr.y - barH - dp(40)))
        boxX = boxX.coerceIn(0, max(0, scr.x - boxW))
        boxY = boxY.coerceIn(barH, max(barH, scr.y - boxH))
    }

    private fun layoutBox() {
        frameP.x = boxX; frameP.y = boxY; frameP.width = boxW; frameP.height = boxH
        barP.x = boxX; barP.y = boxY - barH; barP.width = boxW; barP.height = barH
        handleP.width = handleSize; handleP.height = handleSize
        handleP.x = boxX + boxW - handleSize / 2
        handleP.y = boxY + boxH - handleSize / 2
        frame?.let { update(it, frameP) }
        bar?.let { update(it, barP) }
        handle?.let { update(it, handleP) }
        overlayView?.invalidate() // mantém as caixas alinhadas com o texto da página
    }

    /** Ex.: "✥  EN → PT", ou no Automático "✥  AUTO·EN → PT" (idioma que foi detectado). */
    private fun updateBarLabel(detected: String? = null) {
        val s = Languages.find(prefs.source)
        val t = Languages.find(prefs.target)
        val src = if (s.code == Languages.AUTO && detected != null) "AUTO·${Languages.find(detected).short}" else s.short
        barLabel?.text = "✥  $src → ${t.short}"
    }

    /** Aviso (lendo, sem texto, erro...): aparece no meio do próprio quadro. */
    private fun setStatus(text: String) {
        hiddenForScroll = false
        overlayView?.apply {
            showMessage(text)
            visibility = View.VISIBLE
        }
    }

    /** Mostra a tradução dentro do quadro, em placas de vidro fosco sobre o texto original. */
    private fun showTranslation(segments: List<Segment>, translated: List<String>, backdrop: Backdrop?) {
        hiddenForScroll = false
        overlayView?.apply {
            setBlocks(segments.zip(translated).map { (s, t) -> s.rect to t }, backdrop)
            visibility = View.VISIBLE
        }
    }

    // ================= Leitura + tradução em tempo real =================

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                try {
                    if (!interacting) refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastSig = null
                    setStatus("⚠ Não foi possível traduzir agora.\nVerifique a internet (necessária só no primeiro uso de cada idioma).")
                }
                // Atualiza logo quando a tela muda. Sem eventos, confere de vez em quando:
                // rápido logo depois de um movimento (rolagem), devagar com a tela parada
                // (economiza bateria).
                val quiet = SystemClock.elapsedRealtime() - lastEventAt
                val wait = when {
                    overlayView?.isEmpty == false && quiet < 4000 -> 300L
                    quiet < 4000 -> 800L
                    else -> 2000L
                }
                withTimeoutOrNull(wait) { trigger.receive() }
                delay(150)
            }
        }
    }

    private suspend fun refresh() {
        val area = frameRect() ?: return
        val src = prefs.source
        val tgt = prefs.target

        // 1) Texto "de verdade" da tela (sites, apps): funciona em todos os idiomas
        val read = withContext(Dispatchers.Default) { TextReader.read(service, area) }
        // Barra de notificações aberta por cima: esconde o quadro inteiro até ela fechar
        setBoxHidden(read.coveredBySystem)
        if (read.coveredBySystem) return
        if (read.sensitive) {
            // App de banco/pagamento: não lê nem traduz nada
            if (lastSig != "sensitive") {
                lastSig = "sensitive"
                setStatus("🔒 App de banco ou pagamento na tela.\nPor segurança, o tradutor não lê esta tela.")
            }
            return
        }
        var segments = read.segments

        // Mangá/quadrinhos: o quadro é quase todo imagem e tem pouco texto "de verdade"
        // (só número da página, menu...). Aí o texto que importa está DENTRO da imagem.
        val imageMode = segments.isEmpty() ||
            (read.imageFraction >= 0.35f && textFraction(segments, area) < 0.3f)

        if (!imageMode && sigOf(src, tgt, segments) != lastSig) {
            // Mudou (ex.: a página rolou): as caixas atuais estariam no lugar errado,
            // então somem já; depois espera a tela parar e confere de novo.
            if (overlayView?.isEmpty == false && overlayView?.visibility == View.VISIBLE) {
                hiddenForScroll = true
                overlayView?.visibility = View.INVISIBLE
            }
            delay(350)
            val again = withContext(Dispatchers.Default) { TextReader.read(service, area) }
            if (again.segments != segments) return // ainda mexendo; tenta na próxima volta
        }

        // Em cima do próprio Tradutor Pop-up não há o que traduzir
        if (segments.isEmpty() && service.rootInActiveWindow?.packageName == service.packageName) {
            if (lastSig != "own") {
                lastSig = "own"
                setStatus("Abra outro app ou site: o quadro traduz o que estiver embaixo dele.")
            }
            return
        }

        // 2) Imagem (mangá, foto, vídeo, jogo...): lê o texto de dentro da imagem (OCR)
        if (imageMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && OcrReader.supports(src)) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastOcrAt >= 900) {
                lastOcrAt = now
                lastOcrSegments = ocr.read(
                    service, area, src,
                    hideOverlay = { overlayView?.visibility = View.INVISIBLE; delay(80) },
                    showOverlay = { if (!hiddenForScroll) overlayView?.visibility = View.VISIBLE },
                )
            }
            if (lastOcrSegments.isNotEmpty()) segments = lastOcrSegments
            else if (ocr.modelMissing && segments.isEmpty()) {
                if (lastSig != "ocr-download") {
                    lastSig = "ocr-download"
                    setStatus("⏳ Baixando o leitor de imagens do Google…\n(só na primeira vez; precisa de internet)")
                }
                return
            }
        }

        val sig = sigOf(src, tgt, segments)
        if (sig == lastSig) {
            // Nada mudou: se as caixas foram escondidas por uma rolagem, mostra de novo
            if (hiddenForScroll) {
                hiddenForScroll = false
                overlayView?.visibility = View.VISIBLE
            }
            return
        }
        lastSig = sig

        if (segments.isEmpty()) {
            var msg = "Nenhum texto encontrado nesta área.\nArraste o quadro para cima do texto ou aumente o tamanho."
            if (!OcrReader.supports(src)) msg += "\n\n(Texto em russo dentro de imagens não pode ser lido; textos normais de sites e apps funcionam.)"
            setStatus(msg)
            return
        }

        val userFixes = prefs.corrections
        val translated = engine.translate(src, tgt, segments.map { it.text }) {
            setStatus("⏳ Preparando o idioma…\n(na primeira vez é feito um download rápido)")
        }.map { Corrections.apply(it, userFixes) } // ex.: "arcos e setas" → "arco e flecha"
        updateBarLabel(engine.lastSource)
        // Foto desfocada do app de baixo para o vidro fosco (não inclui o nosso quadro)
        val backdrop = try { ocr.captureBackdrop(service, area) } catch (_: Exception) { null }
        if (prefs.source == src && prefs.target == tgt) showTranslation(segments, translated, backdrop)
        else backdrop?.small?.recycle()
    }

    private var boxHidden = false

    /** Some/volta com o quadro inteiro (barra, borda, tradução e bolinha) sem fechá-lo. */
    private fun setBoxHidden(hidden: Boolean) {
        if (hidden == boxHidden) return
        boxHidden = hidden
        val vis = if (hidden) View.INVISIBLE else View.VISIBLE
        listOf(frame, bar, handle).forEach { it?.visibility = vis }
        if (!hidden) lastSig = null // ao voltar, lê de novo
    }

    /** Quanto do quadro (0 a 1) está ocupado por texto "de verdade". */
    private fun textFraction(segments: List<Segment>, area: Rect): Float {
        val boxArea = area.width().toLong() * area.height()
        if (boxArea <= 0) return 0f
        var sum = 0L
        for (s in segments) {
            val r = Rect(s.rect)
            if (r.intersect(area)) sum += r.width().toLong() * r.height()
        }
        return min(1f, sum.toFloat() / boxArea)
    }

    /** Assinatura do que está no quadro: texto + posição (para as caixas acompanharem a página). */
    private fun sigOf(src: String, tgt: String, segments: List<Segment>) =
        "$src>$tgt|" + segments.joinToString("\u0001") { "${it.text}@${it.rect.toShortString()}" }

    private fun frameRect(): Rect? {
        val f = frame ?: return null
        if (!f.isAttachedToWindow || f.width == 0 || f.height == 0) return null
        val loc = IntArray(2)
        f.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + f.width, loc[1] + f.height)
    }

    // ================= Eventos do serviço =================

    fun onScreenChanged(eventType: Int) {
        if (!boxOpen) return
        lastEventAt = SystemClock.elapsedRealtime()
        // Durante a rolagem as caixas ficariam no lugar errado: esconde até a tela parar
        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && overlayView?.isEmpty == false) {
            hiddenForScroll = true
            overlayView?.visibility = View.INVISIBLE
        }
        trigger.trySend(Unit)
    }

    fun onLanguageChanged() {
        updateBarLabel()
        lastSig = null
        lastOcrAt = 0
        lastOcrSegments = emptyList()
        trigger.trySend(Unit)
    }

    fun onScreenRotated() {
        bubble?.let { clampBubble(); update(it, bubbleP) }
        if (boxOpen) {
            clampBox()
            layoutBox()
        }
    }

    fun hideAll() {
        closeBox()
        bubble?.let(::safeRemove)
        bubble = null
    }

    fun destroy() {
        hideAll()
        engine.close()
        ocr.close()
    }

    // ================= Utilidades de janela =================

    private fun overlayParams(touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun screenSize(): Point =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            Point(m.widthPixels, m.heightPixels)
        }

    private fun safeAdd(v: View, p: WindowManager.LayoutParams) {
        if (v.isAttachedToWindow || v.parent != null) return
        try { wm.addView(v, p) } catch (_: Exception) {}
    }

    private fun safeRemove(v: View) {
        if (!v.isAttachedToWindow && v.parent == null) return
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    private fun update(v: View, p: WindowManager.LayoutParams) {
        // parent != null logo após addView (isAttachedToWindow só fica true depois)
        if (v.parent == null) return
        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }
}

