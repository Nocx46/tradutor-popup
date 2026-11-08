package com.tradutorpopup.app

/**
 * Apps de banco, pagamento e senhas. Quando um deles está na tela, o Tradutor Pop-up
 * some sozinho e não lê nada (privacidade, e para não atrapalhar a segurança desses apps).
 */
object SensitiveApps {

    private val packages = setOf(
        "com.nu.production",            // Nubank
        "com.itau", "com.itau.iti",     // Itaú / iti
        "br.com.bradesco", "com.bradesco", "br.com.bradesco.next",
        "br.com.bb.android",            // Banco do Brasil
        "br.gov.caixa.tem", "br.com.gabba.Caixa", "br.gov.caixa.fgts.trabalhador",
        "com.santander.app", "br.com.santander.way",
        "br.com.intermedium",           // Inter
        "com.c6bank.app",
        "com.picpay",
        "com.mercadopago.wallet",
        "br.com.original.bank",
        "br.com.neon",
        "com.btgpactual.pangea",
        "br.com.xp.carteira", "br.com.rico.mobile",
        "com.sicredi.mobile", "br.com.sicoobnet",
        "com.paypal.android.p2pmobile",
        "com.binance.dev",
        "com.google.android.apps.walletnfcrel", // Google Carteira
        "com.samsung.android.spay",             // Samsung Wallet
        "com.x8bit.bitwarden", "com.lastpass.lpandroid", "com.agilebits.onepassword",
    )

    private val keywords = listOf("bank", "banco", "banking", "wallet", "pay.", "password")

    fun isSensitive(pkg: CharSequence?): Boolean {
        val p = pkg?.toString()?.lowercase() ?: return false
        return p in packages || keywords.any { p.contains(it) }
    }
}
