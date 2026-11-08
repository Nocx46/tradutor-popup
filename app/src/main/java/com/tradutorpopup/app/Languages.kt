package com.tradutorpopup.app

import com.google.mlkit.nl.translate.TranslateLanguage

data class Lang(val code: String, val name: String, val flag: String, val short: String)

object Languages {
    /** Código especial: detectar o idioma do texto sozinho. */
    const val AUTO = "auto"

    private val real = listOf(
        Lang(TranslateLanguage.ENGLISH, "Inglês", "🇺🇸", "EN"),
        Lang(TranslateLanguage.JAPANESE, "Japonês", "🇯🇵", "JA"),
        Lang(TranslateLanguage.SPANISH, "Espanhol", "🇪🇸", "ES"),
        Lang(TranslateLanguage.CHINESE, "Chinês", "🇨🇳", "ZH"),
        Lang(TranslateLanguage.RUSSIAN, "Russo", "🇷🇺", "RU"),
    )

    /** Idiomas do texto que será lido na tela (Automático primeiro). */
    val sources = listOf(Lang(AUTO, "Automático (detecta sozinho)", "🌐", "AUTO")) + real

    /** Idiomas para os quais dá para traduzir (Português primeiro). */
    val targets = listOf(Lang(TranslateLanguage.PORTUGUESE, "Português", "🇧🇷", "PT")) + real

    fun find(code: String): Lang = (sources + targets).firstOrNull { it.code == code } ?: targets.first()
}
