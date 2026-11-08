package com.tradutorpopup.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import com.google.mlkit.nl.translate.TranslateLanguage

/** Configurações salvas, compartilhadas entre a tela principal e o serviço do pop-up. */
class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var source: String
        get() = sp.getString(KEY_SOURCE, Languages.AUTO) ?: Languages.AUTO
        set(v) = sp.edit().putString(KEY_SOURCE, v).apply()

    var target: String
        get() = sp.getString(KEY_TARGET, TranslateLanguage.PORTUGUESE) ?: TranslateLanguage.PORTUGUESE
        set(v) = sp.edit().putString(KEY_TARGET, v).apply()

    var popupEnabled: Boolean
        get() = sp.getBoolean(KEY_POPUP, false)
        set(v) = sp.edit().putBoolean(KEY_POPUP, v).apply()

    /** Correções do usuário, uma por linha: "errado = certo". */
    var corrections: String
        get() = sp.getString(KEY_CORRECTIONS, "") ?: ""
        set(v) = sp.edit().putString(KEY_CORRECTIONS, v).apply()

    /** Posição/tamanho do quadro da última vez (x, y, largura, altura), ou null. */
    var box: Rect?
        get() {
            val w = sp.getInt(KEY_BOX_W, 0)
            if (w <= 0) return null
            val x = sp.getInt(KEY_BOX_X, 0)
            val y = sp.getInt(KEY_BOX_Y, 0)
            return Rect(x, y, x + w, y + sp.getInt(KEY_BOX_H, 0))
        }
        set(r) {
            if (r == null) return
            sp.edit()
                .putInt(KEY_BOX_X, r.left).putInt(KEY_BOX_Y, r.top)
                .putInt(KEY_BOX_W, r.width()).putInt(KEY_BOX_H, r.height())
                .apply()
        }

    companion object {
        const val KEY_SOURCE = "source"
        const val KEY_TARGET = "target"
        const val KEY_POPUP = "popup"
        const val KEY_CORRECTIONS = "corrections"
        private const val KEY_BOX_X = "box_x"
        private const val KEY_BOX_Y = "box_y"
        private const val KEY_BOX_W = "box_w"
        private const val KEY_BOX_H = "box_h"
    }
}
