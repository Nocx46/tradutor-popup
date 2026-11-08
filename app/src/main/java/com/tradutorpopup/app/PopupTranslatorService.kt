package com.tradutorpopup.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * Serviço que fica ligado em segundo plano enquanto o pop-up está ativo.
 * Desenha o botão flutuante e o quadro de tradução por cima de qualquer app.
 *
 * O pop-up some sozinho:
 *  - com a tela bloqueada;
 *  - enquanto um app de banco/pagamento (ex.: Nubank) estiver aberto.
 */
class PopupTranslatorService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val scope = MainScope()
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    /** true enquanto um app de banco/pagamento está na frente. */
    private var pausedForSensitiveApp = false

    /** Esconde o pop-up com a tela bloqueada e mostra de novo ao desbloquear. */
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> overlay?.hideAll()
                Intent.ACTION_USER_PRESENT -> showIfAllowed()
            }
        }
    }

    private fun isLocked() = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    private fun showIfAllowed() {
        if (prefs.popupEnabled && !isLocked() && !pausedForSensitiveApp) overlay?.showBubble()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        // Ligou o serviço (pelo app, pelas Configurações ou pelo atalho de volume) = pop-up ligado
        prefs.popupEnabled = true
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        overlay = OverlayController(this, scope, prefs, onTurnOff = { turnOff() })
        registerReceiver(lockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        pausedForSensitiveApp = SensitiveApps.isSensitive(rootInActiveWindow?.packageName)
        showIfAllowed()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName == packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) onAppChanged(event.packageName)
        if (!pausedForSensitiveApp) overlay?.onScreenChanged(event.eventType)
    }

    /** Um app novo veio para a frente: se for de banco/pagamento, o pop-up some até sair dele. */
    private fun onAppChanged(pkg: CharSequence?) {
        val p = pkg?.toString() ?: return
        // Barra do sistema e teclados não contam como "trocar de app"
        if (p == "com.android.systemui" || p.contains("inputmethod") ||
            p.contains("honeyboard") || p.contains("keyboard")
        ) return
        val sensitive = SensitiveApps.isSensitive(p)
        if (sensitive && !pausedForSensitiveApp) {
            pausedForSensitiveApp = true
            overlay?.hideAll()
        } else if (!sensitive && pausedForSensitiveApp) {
            pausedForSensitiveApp = false
            showIfAllowed()
        }
    }

    override fun onInterrupt() {}

    /**
     * Desliga o tradutor DE VERDADE (o serviço de acessibilidade fica desativado).
     * Apps de banco como o Nubank bloqueiam enquanto ele está ligado.
     * Para ligar de novo: pelo app, pelas Configurações ou pelo atalho de volume.
     */
    private fun turnOff() {
        overlay?.hideAll()
        Toast.makeText(
            this,
            "Tradutor desligado. Para ligar de novo: abra o Tradutor Pop-up (ou use o atalho de volume).",
            Toast.LENGTH_LONG,
        ).show()
        if (prefs.popupEnabled) prefs.popupEnabled = false
        disableSelf()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        val o = overlay ?: return
        when (key) {
            Prefs.KEY_POPUP -> if (prefs.popupEnabled) showIfAllowed() else { o.hideAll(); disableSelf() }
            Prefs.KEY_SOURCE, Prefs.KEY_TARGET, Prefs.KEY_CORRECTIONS -> o.onLanguageChanged()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onScreenRotated()
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) {
            prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
            try { unregisterReceiver(lockReceiver) } catch (_: Exception) {}
        }
        overlay?.destroy()
        overlay = null
        scope.cancel()
        super.onDestroy()
    }
}
