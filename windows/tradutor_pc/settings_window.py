"""Janela de configurações: idiomas, correções e leitores de imagem do Windows."""
import ctypes

from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtWidgets import (QComboBox, QDialog, QFrame, QHBoxLayout, QLabel, QPlainTextEdit,
                               QPushButton, QVBoxLayout)

from . import corrections, ocr
from . import languages as L

STYLE = """
QDialog { background: #F3F5F9; }
QLabel { color: #111827; font-size: 13px; }
QLabel#title { font-size: 22px; font-weight: 700; }
QLabel#h { font-size: 15px; font-weight: 700; }
QLabel#muted { color: #6B7280; }
QFrame#card { background: white; border-radius: 14px; }
QComboBox, QPlainTextEdit { font-size: 13px; padding: 4px; border: 1px solid #D1D5DB; border-radius: 8px; background: white; }
QPushButton { font-size: 13px; padding: 6px 12px; border-radius: 8px; background: #2563EB; color: white; border: none; }
QPushButton:hover { background: #1D4ED8; }
QPushButton#flat { background: #E5E7EB; color: #111827; }
"""


def _card():
    f = QFrame()
    f.setObjectName("card")
    lay = QVBoxLayout(f)
    lay.setContentsMargins(16, 14, 16, 14)
    lay.setSpacing(8)
    return f, lay


def _label(text, obj=None, wrap=True):
    l = QLabel(text)
    if obj:
        l.setObjectName(obj)
    l.setWordWrap(wrap)
    return l


class SettingsWindow(QDialog):
    changed = Signal(str)  # chave que mudou

    def __init__(self, settings):
        super().__init__(None, Qt.WindowCloseButtonHint | Qt.WindowTitleHint)
        self.settings = settings
        self.setWindowTitle("Tradutor Pop-up — Configurações")
        self.setStyleSheet(STYLE)
        self.setMinimumWidth(460)
        root = QVBoxLayout(self)
        root.setContentsMargins(18, 16, 18, 16)
        root.setSpacing(12)
        root.addWidget(_label("Tradutor Pop-up", "title"))
        root.addWidget(_label("Tradução em tempo real de qualquer janela, site ou mangá. "
                              "Atalho: Ctrl+Shift+T abre/fecha o quadro.", "muted"))

        # --- idiomas ---
        card, lay = _card()
        lay.addWidget(_label("Idioma do texto", "h"))
        self.src = QComboBox()
        for code, name, _ in L.SOURCES:
            self.src.addItem(name, code)
        self.src.setCurrentIndex(max(0, self.src.findData(settings["source"])))
        self.src.currentIndexChanged.connect(lambda: self._set("source", self.src.currentData()))
        lay.addWidget(self.src)
        lay.addWidget(_label("Traduzir para", "h"))
        self.tgt = QComboBox()
        for code, name, _ in L.TARGETS:
            self.tgt.addItem(name, code)
        self.tgt.setCurrentIndex(max(0, self.tgt.findData(settings["target"])))
        self.tgt.currentIndexChanged.connect(lambda: self._set("target", self.tgt.currentData()))
        lay.addWidget(self.tgt)
        lay.addWidget(_label("Cada idioma é baixado uma vez (~60–90 MB) e depois funciona sem internet. "
                             "Dica: um clique no nome do idioma, na barra do quadro, também troca.", "muted"))
        root.addWidget(card)

        # --- leitores de imagem do Windows ---
        card, lay = _card()
        lay.addWidget(_label("Leitor de texto do Windows", "h"))
        lay.addWidget(_label("O tradutor lê a tela com o leitor de imagens do próprio Windows. "
                             "Japonês, chinês e russo precisam do pacote do idioma (o Windows pede permissão "
                             "de administrador para instalar).", "muted"))
        self.ocr_rows = {}
        for code in ("ja", "zh", "ru", "es"):
            row = QHBoxLayout()
            lbl = QLabel()
            btn = QPushButton("Instalar")
            btn.setObjectName("flat")
            btn.clicked.connect(lambda _=False, c=code: self._install_ocr(c))
            row.addWidget(lbl, 1)
            row.addWidget(btn)
            lay.addLayout(row)
            self.ocr_rows[code] = (lbl, btn)
        self._refresh_ocr()
        root.addWidget(card)

        # --- correções ---
        card, lay = _card()
        lay.addWidget(_label("✏️  Correções de tradução", "h"))
        lay.addWidget(_label("Se uma tradução sair estranha, escreva a correção, uma por linha:  errado = certo",
                             "muted"))
        self.fix = QPlainTextEdit(settings["corrections"] or "")
        self.fix.setPlaceholderText("arcos e setas = arco e flecha")
        self.fix.setFixedHeight(90)
        lay.addWidget(self.fix)
        row = QHBoxLayout()
        self.fix_status = _label("", "muted")
        save = QPushButton("Salvar correções")
        save.clicked.connect(self._save_fixes)
        row.addWidget(self.fix_status, 1)
        row.addWidget(save)
        lay.addLayout(row)
        root.addWidget(card)

        root.addWidget(_label("A tradução não é 100% correta: o app usa só ferramentas gratuitas e offline. "
                              "Use para entender o sentido do texto.", "muted"))

    def _set(self, key, value):
        self.settings[key] = value
        self.changed.emit(key)

    def _save_fixes(self):
        text = self.fix.toPlainText().strip()
        self.settings["corrections"] = text
        n = len(corrections.parse(text))
        self.fix_status.setText(f"✓ {n} correç{'ão salva' if n == 1 else 'ões salvas'}." if n
                                else "Nenhuma correção sua (as prontas continuam valendo).")
        self.changed.emit("corrections")

    def _refresh_ocr(self):
        avail = [t.lower().split("-")[0] for t in ocr.available_ocr_langs()]
        for code, (lbl, btn) in self.ocr_rows.items():
            ok = code in avail
            extra = " (opcional: o leitor de inglês já lê espanhol)" if code == "es" and not ok else ""
            lbl.setText(f"{L.name(code)}: {'✓ instalado' if ok else 'não instalado'}{extra}")
            btn.setVisible(not ok)

    def _install_ocr(self, code):
        cap = L.OCR_CAPABILITY[code]
        cmd = (f"-NoProfile -Command \"Add-WindowsCapability -Online -Name '{cap}'; "
               f"Write-Host 'Pronto! Pode fechar esta janela.'; Start-Sleep 3\"")
        # Abre o PowerShell como administrador (o Windows mostra o pedido de permissão)
        ctypes.windll.shell32.ShellExecuteW(None, "runas", "powershell.exe", cmd, None, 1)
        lbl, _ = self.ocr_rows[code]
        lbl.setText(f"{L.name(code)}: instalando… (pode levar alguns minutos)")
        QTimer.singleShot(15000, self._refresh_ocr)
        QTimer.singleShot(60000, self._refresh_ocr)
