package com.tradutorpopup.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var switchPopup: Switch
    private lateinit var txtPopupStatus: TextView
    private lateinit var btnPermission: Button
    private lateinit var txtModelStatus: TextView
    private var ignoreSwitch = false
    private var downloadToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        switchPopup = findViewById(R.id.switchPopup)
        txtPopupStatus = findViewById(R.id.txtPopupStatus)
        btnPermission = findViewById(R.id.btnPermission)
        txtModelStatus = findViewById(R.id.txtModelStatus)

        setupSourceLanguages()
        setupTargetLanguages()

        switchPopup.setOnCheckedChangeListener { _, checked ->
            if (ignoreSwitch) return@setOnCheckedChangeListener
            if (checked) {
                // Ligar: se o serviço já está ativo, só mostra o botão; senão, abre as Configurações
                if (isServiceEnabled()) prefs.popupEnabled = true else askForPermission()
            } else {
                // Desligar de verdade: o serviço se desativa sozinho (assim o banco não bloqueia)
                prefs.popupEnabled = false
            }
            switchPopup.postDelayed({ refreshStatus() }, 700)
        }
        btnPermission.setOnClickListener { askForPermission() }
        findViewById<Button>(R.id.btnShortcut).setOnClickListener { openServiceSettings() }
        setupCorrections()

        downloadLanguage()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------- Idiomas ----------

    private fun setupSourceLanguages() {
        val group = findViewById<RadioGroup>(R.id.groupSource)
        val textColor = getColor(R.color.text)
        Languages.sources.forEachIndexed { i, lang ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${lang.flag}  ${lang.name}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(textColor)
                minHeight = (44 * resources.displayMetrics.density).toInt()
                tag = lang.code
            }
            group.addView(rb)
            if (lang.code == prefs.source) rb.isChecked = true
            if (i == 0 && Languages.sources.none { it.code == prefs.source }) rb.isChecked = true
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val code = g.findViewById<RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            prefs.source = code
            downloadLanguage()
        }
    }

    private fun setupTargetLanguages() {
        val spinner = findViewById<Spinner>(R.id.spinnerTarget)
        val items = Languages.targets.map { "${it.flag}  ${it.name}" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(Languages.targets.indexOfFirst { it.code == prefs.target }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val code = Languages.targets[pos].code
                if (code != prefs.target) {
                    prefs.target = code
                    downloadLanguage()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** Baixa o pacote de idioma já agora, para o pop-up funcionar mesmo sem internet depois. */
    private fun downloadLanguage() {
        val src = prefs.source
        val tgt = prefs.target
        val token = ++downloadToken
        if (src == Languages.AUTO) {
            txtModelStatus.text = "🌐 O idioma do texto é detectado sozinho. Cada pacote de idioma é baixado " +
                "na primeira vez que ele aparecer (precisa de internet só nessa hora)."
            txtModelStatus.setTextColor(getColor(R.color.text_muted))
            return
        }
        if (src == tgt) {
            txtModelStatus.text = "Origem e destino são o mesmo idioma."
            txtModelStatus.setTextColor(getColor(R.color.warn))
            return
        }
        txtModelStatus.text = "⏳ Preparando pacote de idioma (${Languages.find(src).name} → ${Languages.find(tgt).name})…"
        txtModelStatus.setTextColor(getColor(R.color.text_muted))
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (token != downloadToken) return@addOnSuccessListener
                txtModelStatus.text = "✓ Pacote de idioma pronto — funciona até sem internet."
                txtModelStatus.setTextColor(getColor(R.color.ok))
            }
            .addOnFailureListener {
                if (token != downloadToken) return@addOnFailureListener
                txtModelStatus.text = "⚠ Não foi possível baixar o idioma. Conecte-se à internet e abra o app de novo."
                txtModelStatus.setTextColor(getColor(R.color.warn))
            }
            .addOnCompleteListener { translator.close() }
    }

    // ---------- Correções de tradução ----------

    private fun setupCorrections() {
        val edit = findViewById<EditText>(R.id.editCorrections)
        val status = findViewById<TextView>(R.id.txtCorrectionsStatus)
        edit.setText(prefs.corrections)
        findViewById<Button>(R.id.btnSaveCorrections).setOnClickListener {
            val text = edit.text.toString().trim()
            prefs.corrections = text
            val n = Corrections.parse(text).size
            status.text = if (n == 0) "Nenhuma correção sua salva (as prontas continuam valendo)."
            else "✓ $n correç${if (n == 1) "ão salva" else "ões salvas"}. Já vale para as próximas traduções."
            status.setTextColor(getColor(R.color.ok))
            edit.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(edit.windowToken, 0)
        }
    }

    // ---------- Pop-up / permissão ----------

    /** O pop-up está ligado quando o serviço de acessibilidade está ativo. */
    private fun refreshStatus() {
        val on = isServiceEnabled()
        ignoreSwitch = true
        switchPopup.isChecked = on
        ignoreSwitch = false
        btnPermission.visibility = View.GONE
        if (on) {
            txtPopupStatus.text = "● Ligado. Toque no botão 文A em qualquer site ou app.\n" +
                "(Toque e segure o 文A para desligar.)"
            txtPopupStatus.setTextColor(getColor(R.color.ok))
        } else {
            txtPopupStatus.text = "Desligado. Ligue para mostrar o botão flutuante 文A.\n" +
                "(Apps de banco funcionam normalmente com ele desligado.)"
            txtPopupStatus.setTextColor(getColor(R.color.text_muted))
        }
    }

    private fun askForPermission() {
        AlertDialog.Builder(this)
            .setTitle("Ligar o tradutor")
            .setMessage(
                "Na próxima tela, toque em \"Aplicativos instalados\" › \"Tradutor Pop-up\" e ative.\n\n" +
                    "Dica: nessa mesma tela, ligue também \"Atalho do Tradutor Pop-up\" para poder " +
                    "ligar/desligar segurando os dois botões de volume.\n\n" +
                    "A tradução é feita no próprio celular; o texto da tela não é enviado para lugar nenhum.\n\n" +
                    "Se a opção aparecer bloqueada (\"configuração restrita\"), vá em Configurações › Apps › " +
                    "Tradutor Pop-up › menu ⋮ › \"Permitir configurações restritas\" e tente de novo."
            )
            .setPositiveButton("Abrir") { _, _ -> openServiceSettings() }
            .setNegativeButton("Agora não") { _, _ -> refreshStatus() }
            .setOnCancelListener { refreshStatus() }
            .show()
    }

    /** Abre direto a página do Tradutor Pop-up nas Configurações de acessibilidade. */
    private fun openServiceSettings() {
        val cn = ComponentName(this, PopupTranslatorService::class.java).flattenToString()
        try {
            startActivity(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, cn)
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun isServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val si = it.resolveInfo.serviceInfo
            si.packageName == packageName && si.name == PopupTranslatorService::class.java.name
        }
    }
}
