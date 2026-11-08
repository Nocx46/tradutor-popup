package com.tradutorpopup.app

/**
 * Correções aplicadas DEPOIS da tradução, para consertar traduções literais demais
 * do tradutor offline (ex.: "bow and arrow" → "arcos e setas" em vez de "arco e flecha").
 *
 * O usuário pode adicionar as suas no app, uma por linha:  errado = certo
 */
object Corrections {

    /** Correções que já vêm prontas (português do Brasil). */
    const val BUILT_IN = """
arcos e setas = arco e flecha
arco e setas = arco e flechas
arco e seta = arco e flecha
um arco e uma seta = um arco e flecha
"""

    private var lastText: String? = null
    private var rules: List<Pair<Regex, String>> = emptyList()

    /** Lê o texto "errado = certo" (uma por linha). Linhas sem "=" são ignoradas. */
    fun parse(text: String): List<Pair<Regex, String>> =
        text.lines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) return@mapNotNull null
            val wrong = line.substring(0, i).trim()
            val right = line.substring(i + 1).trim()
            if (wrong.isEmpty()) return@mapNotNull null
            // Só troca palavras inteiras, sem diferenciar maiúsculas/minúsculas
            Regex("(?<!\\p{L})" + Regex.escape(wrong) + "(?!\\p{L})", RegexOption.IGNORE_CASE) to right
        }

    /** Aplica as correções prontas + as do usuário. */
    fun apply(translated: String, userText: String): String {
        if (userText != lastText) {
            lastText = userText
            // As do usuário vêm primeiro (têm prioridade sobre as prontas)
            rules = parse(userText) + parse(BUILT_IN)
        }
        var out = translated
        for ((rx, right) in rules) {
            out = rx.replace(out) { m ->
                // Mantém a primeira letra maiúscula se o original começava com maiúscula
                if (m.value.firstOrNull()?.isUpperCase() == true) right.replaceFirstChar { it.uppercaseChar() } else right
            }
        }
        return out
    }
}
