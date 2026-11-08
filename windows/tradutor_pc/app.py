"""Tradutor Pop-up para Windows — ponto de entrada."""
import ctypes
import os
import sys
from ctypes import wintypes

from PySide6.QtCore import QAbstractNativeEventFilter, QPoint, QRect, Qt, QTimer
from PySide6.QtNetwork import QLocalServer, QLocalSocket
from PySide6.QtGui import QAction, QColor, QFont, QGuiApplication, QIcon, QImage, QPainter, QPixmap
from PySide6.QtWidgets import QApplication, QMenu, QSystemTrayIcon

from . import languages as L
from . import shortcuts
from .overlay import BAR_H, HANDLE, BarWindow, Bubble, FrameWindow, HandleWindow, physical_rect
from .settings import MIRROR_FILE, MODELS_DIR, Settings
from .settings_window import SettingsWindow
from .translator import Translator
from .worker import BACKDROP_SCALE, Worker

user32 = ctypes.windll.user32
WM_HOTKEY = 0x0312
MOD_CONTROL, MOD_SHIFT, MOD_NOREPEAT = 0x0002, 0x0004, 0x4000
VK_T = 0x54
HOTKEY_ID = 0xB0A1


def app_icon():
    pm = QPixmap(64, 64)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing)
    p.setBrush(QColor("#2563EB"))
    p.setPen(Qt.NoPen)
    p.drawEllipse(2, 2, 60, 60)
    f = QFont("Segoe UI")
    f.setBold(True)
    f.setPixelSize(22)
    p.setFont(f)
    p.setPen(Qt.white)
    p.drawText(pm.rect(), Qt.AlignCenter, "文A")
    p.end()
    return QIcon(pm)


class HotkeyFilter(QAbstractNativeEventFilter):
    """Recebe o atalho global Ctrl+Shift+T."""

    def __init__(self, callback):
        super().__init__()
        self.callback = callback

    def nativeEventFilter(self, event_type, message):
        if event_type == b"windows_generic_MSG":
            msg = wintypes.MSG.from_address(int(message))
            if msg.message == WM_HOTKEY and msg.wParam == HOTKEY_ID:
                self.callback()
                return True, 0
        return False, 0


class Controller:
    def __init__(self, app: QApplication):
        self.app = app
        self.settings = Settings()
        self.translator = Translator(MODELS_DIR)
        self.worker = Worker(self.settings, self.translator)
        self.worker.result.connect(self._on_result)
        self.worker.message.connect(self._on_message)
        self.worker.moving.connect(lambda: self.frame.hide_plates())

        self.bubble = Bubble()
        self.frame = FrameWindow()
        self.bar = BarWindow()
        self.handle = HandleWindow()
        self.settings_win = None
        self.open = False

        scr = QGuiApplication.primaryScreen().availableGeometry()
        bpos = self.settings["bubble"]
        self.bubble.move(QPoint(*bpos) if bpos else QPoint(scr.right() - 70, scr.top() + scr.height() // 3))
        self.bubble.clicked.connect(self.toggle)
        self.bubble.menu_requested.connect(self._menu)
        self.bubble.moved.connect(lambda p: self.settings.__setitem__("bubble", [p.x(), p.y()]))

        box = self.settings["box"]
        self.box = QRect(*box) if box else QRect(scr.center().x() - 300, scr.top() + 200, 600, 240)
        self._drag_origin = None
        self.bar.drag.connect(self._bar_drag)
        self.bar.drag_finished.connect(self._drag_done)
        self.bar.label_clicked.connect(self._cycle_source)
        self.bar.settings_clicked.connect(self.show_settings)
        self.bar.close_clicked.connect(self.close)
        self.handle.drag.connect(self._handle_drag)
        self.handle.drag_finished.connect(self._drag_done)

        # Ícone na bandeja (perto do relógio)
        self.tray = QSystemTrayIcon(app_icon())
        self.tray.setToolTip("Tradutor Pop-up — Ctrl+Shift+T")
        self.tray.setContextMenu(self._build_menu())
        self.tray.activated.connect(lambda r: self.toggle() if r == QSystemTrayIcon.Trigger else None)
        self.tray.show()

        self.bubble.show()
        self._register_hotkey()
        self._update_label()
        hello = "Rodando! Clique no botão 文A ou use Ctrl+Shift+T."
        # Primeira vez: cria o atalho na Área de Trabalho e no menu Iniciar
        if not self.settings["shortcuts_done"] and shortcuts.program_path():
            try:
                shortcuts.create()
                hello += "\nCriei o atalho \"Tradutor Pop-up\" na Área de Trabalho."
            except Exception:
                pass
            self.settings["shortcuts_done"] = True
        self.tray.showMessage("Tradutor Pop-up", hello, app_icon(), 5000)

    def on_second_launch(self):
        """Abriram o atalho com o tradutor já aberto: mostra o quadro (sem abrir outra cópia)."""
        self.bubble.show()
        self.bubble.raise_()
        if not self.open:
            self.show_box()

    # ---------- menu ----------

    def _build_menu(self):
        m = QMenu()
        a = QAction("Abrir/fechar o quadro  (Ctrl+Shift+T)", m)
        a.triggered.connect(self.toggle)
        m.addAction(a)
        s = QAction("Configurações…", m)
        s.triggered.connect(self.show_settings)
        m.addAction(s)
        m.addSeparator()
        q = QAction("⏻  Desligar o tradutor", m)
        q.triggered.connect(self.quit)
        m.addAction(q)
        return m

    def _menu(self, pos):
        self._ctx = self._build_menu()
        self._ctx.popup(pos)

    # ---------- atalho ----------

    def _register_hotkey(self):
        self._filter = HotkeyFilter(self.toggle)
        self.app.installNativeEventFilter(self._filter)
        user32.RegisterHotKey(int(self.bubble.winId()), HOTKEY_ID, MOD_CONTROL | MOD_SHIFT | MOD_NOREPEAT, VK_T)

    # ---------- quadro ----------

    def toggle(self):
        self.close() if self.open else self.show_box()

    def show_box(self):
        self.open = True
        self._clamp()
        self._layout()
        for w in (self.frame, self.bar, self.handle):
            w.show()
            w.raise_()
        self.bubble.raise_()
        self.bubble.set_active(True)
        self.frame.show_message("Lendo o texto dentro do quadro…")
        self._update_label()
        QTimer.singleShot(50, self._send_rect)
        self.worker.start()

    def close(self):
        self.open = False
        self.worker.stop()
        for w in (self.frame, self.bar, self.handle):
            w.hide()
        self.bubble.set_active(False)

    def _clamp(self):
        scr = QGuiApplication.screenAt(self.box.center()) or QGuiApplication.primaryScreen()
        g = scr.availableGeometry()
        self.box.setWidth(max(160, min(self.box.width(), g.width())))
        self.box.setHeight(max(70, min(self.box.height(), g.height() - BAR_H)))
        self.box.moveLeft(max(g.left(), min(self.box.left(), g.right() - self.box.width() + 1)))
        self.box.moveTop(max(g.top() + BAR_H, min(self.box.top(), g.bottom() - self.box.height() + 1)))

    def _layout(self):
        b = self.box
        self.frame.setGeometry(b)
        self.bar.setGeometry(b.left(), b.top() - BAR_H, b.width(), BAR_H)
        self.handle.move(b.right() - HANDLE // 2, b.bottom() - HANDLE // 2)

    def _send_rect(self):
        if self.open:
            self.worker.set_rect(physical_rect(self.frame))

    def _bar_drag(self, d):
        if self._drag_origin is None:
            self._drag_origin = QRect(self.box)
        self.box.moveTopLeft(self._drag_origin.topLeft() + d)
        self._clamp()
        self._layout()
        self.frame.hide_plates()

    def _handle_drag(self, d):
        if self._drag_origin is None:
            self._drag_origin = QRect(self.box)
        self.box.setWidth(self._drag_origin.width() + d.x())
        self.box.setHeight(self._drag_origin.height() + d.y())
        self._clamp()
        self._layout()
        self.frame.hide_plates()

    def _drag_done(self):
        self._drag_origin = None
        self.settings["box"] = [self.box.x(), self.box.y(), self.box.width(), self.box.height()]
        self._send_rect()

    # ---------- idioma ----------

    def _cycle_source(self):
        codes = [c for c, _, _ in L.SOURCES]
        i = codes.index(self.settings["source"]) if self.settings["source"] in codes else 0
        self.settings["source"] = codes[(i + 1) % len(codes)]
        self._on_setting("source")

    def _update_label(self, detected=None):
        src, tgt = self.settings["source"], self.settings["target"]
        s = f"AUTO·{L.short(detected)}" if src == L.AUTO and detected else L.short(src)
        self.bar.set_label(f"{s} → {L.short(tgt)}")

    def _on_setting(self, key):
        self._update_label()
        if self.settings_win and key == "source":
            i = self.settings_win.src.findData(self.settings["source"])
            self.settings_win.src.blockSignals(True)
            self.settings_win.src.setCurrentIndex(i)
            self.settings_win.src.blockSignals(False)
        self.worker.refresh()

    def show_settings(self):
        if self.settings_win is None:
            self.settings_win = SettingsWindow(self.settings)
            self.settings_win.setWindowIcon(app_icon())
            self.settings_win.changed.connect(self._on_setting)
            self.settings_win.quit_requested.connect(self.quit)
        self.settings_win.show()
        self.settings_win.raise_()
        self.settings_win.activateWindow()

    # ---------- resultados do trabalho em segundo plano ----------

    def _on_result(self, res):
        if not self.open:
            return
        self.frame.set_result(res["blocks"], res["backdrop"], BACKDROP_SCALE)
        self._update_label(res.get("detected"))
        if res.get("image") is not None:
            QTimer.singleShot(150, lambda img=res["image"]: self._save_mirror(img))

    def _save_mirror(self, bg):
        """Modo espelho: junta a imagem de baixo com as placas e salva (para acompanhar testes)."""
        try:
            from PIL import Image
            fg = self.frame.grab().toImage().convertToFormat(QImage.Format_RGBA8888)
            fgp = Image.frombuffer("RGBA", (fg.width(), fg.height()), bytes(fg.constBits()), "raw", "RGBA", 0, 1)
            out = Image.alpha_composite(bg.convert("RGBA"), fgp.resize(bg.size))
            os.makedirs(os.path.dirname(MIRROR_FILE), exist_ok=True)
            out.convert("RGB").save(MIRROR_FILE)
        except Exception:
            pass

    def _on_message(self, text):
        if self.open:
            self.frame.show_message(text)

    def quit(self):
        """Desliga o tradutor por completo (some o 文A e o ícone da bandeja)."""
        self.close()
        user32.UnregisterHotKey(int(self.bubble.winId()), HOTKEY_ID)
        for w in (self.bubble, self.settings_win):
            if w:
                w.hide()
        self.tray.hide()
        self.app.quit()


INSTANCE_NAME = "TradutorPopup-instancia"


def main():
    app = QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(False)
    app.setApplicationName("Tradutor Pop-up")
    app.setWindowIcon(app_icon())

    # Só uma cópia aberta por vez: se já estiver rodando, avisa a outra cópia para abrir o quadro
    sock = QLocalSocket()
    sock.connectToServer(INSTANCE_NAME)
    if sock.waitForConnected(300):
        sock.write(b"show")
        sock.flush()
        sock.waitForBytesWritten(300)
        sock.disconnectFromServer()
        return
    QLocalServer.removeServer(INSTANCE_NAME)
    server = QLocalServer()
    server.listen(INSTANCE_NAME)

    ctrl = Controller(app)

    def on_conn():
        c = server.nextPendingConnection()
        if c:
            c.readyRead.connect(ctrl.on_second_launch)
            c.disconnected.connect(c.deleteLater)
    server.newConnection.connect(on_conn)
    sys.exit(app.exec())
