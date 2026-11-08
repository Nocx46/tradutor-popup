package com.tradutorpopup.app

import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Tradução no próprio celular (Google ML Kit). Depois que o idioma é baixado
 * uma vez, funciona sem internet. Guarda as traduções recentes para ser rápido
 * ao rolar a página. No modo Automático, descobre sozinho o idioma do texto.
 */
class TranslationEngine {

    private var key = ""
    private var translator: Translator? = null
    private var ready = false
    private val cache = LruCache<String, String>(800)
    private val langId = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.35f).build()
    )

    /** Idioma detectado na última tradução (no modo Automático), ex.: "en". */
    var lastSource: String? = null
        private set

    /**
     * Traduz [segments] de [src] para [tgt]. Se [src] for Automático, detecta o idioma antes.
     * [onPreparing] é chamado se for preciso baixar o pacote de idioma (só na 1ª vez).
     */
    suspend fun translate(
        src: String,
        tgt: String,
        segments: List<String>,
        onPreparing: () -> Unit = {},
    ): List<String> {
        val real = if (src == Languages.AUTO) detect(segments) else src
        lastSource = real
        if (real == tgt) return segments // já está no idioma de destino
        val k = "$real>$tgt"
        if (k != key || translator == null) {
            translator?.close()
            translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(real).setTargetLanguage(tgt).build()
            )
            key = k
            ready = false
        }
        val t = translator!!
        if (!ready) {
            onPreparing()
            t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            ready = true
        }
        return segments.map { s ->
            val ck = k + "\u0000" + s
            cache.get(ck) ?: t.translate(s).await().also { cache.put(ck, it) }
        }
    }

    /** Descobre o idioma do texto; se não souber, considera inglês. */
    private suspend fun detect(segments: List<String>): String {
        val sample = segments.joinToString(" ").take(500)
        val cached = cache.get("lid\u0000$sample")
        if (cached != null) return cached
        val tag = try { langId.identifyLanguage(sample).await() } catch (_: Exception) { "und" }
        val code = when {
            tag == "und" || tag.contains("-Latn") -> null
            else -> TranslateLanguage.fromLanguageTag(tag.substringBefore('-'))
        } ?: TranslateLanguage.ENGLISH
        cache.put("lid\u0000$sample", code)
        return code
    }

    fun close() {
        translator?.close()
        translator = null
        langId.close()
        key = ""
        ready = false
    }
}
