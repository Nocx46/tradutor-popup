"""
Janelas que ficam por cima de tudo:
  - Bubble: o botão 文A (arrastar move; clique abre/fecha o quadro; botão direito = menu)
  - FrameWindow: o quadro transparente (o mouse passa através dele) com a tradução em vidro fosco
  - BarWindow: a barra de cima (arrastar move; clique no idioma troca; ⚙ configurações; ✕ fecha)
  - HandleWindow: a bolinha do canto (arrastar muda o tamanho)
Todas ficam fora das capturas de tela (o leitor enxerga só o que está embaixo).
"""
import ctypes
from ctypes import wintypes

from PySide6.QtCore import QPoint, QPointF, QRect, QRectF, Qt, Signal
from PySide6.QtGui import (QColor, QFont, QFontMetrics, QLinearGradient, QPainter, QPainterPath,
                           QPen)
from PySide6.QtWidgets import QWidget

user32 = ctypes.windll.user32
WDA_EXCLUDEFROMCAPTURE = 0x11

BAR_H = 36
HANDLE = 30

TEXT_DARK = QColor(28, 28, 30)
TEXT_LIGHT = QColor(255, 255, 255)
UI_FONT = "Segoe UI"


def exclude_from_capture(widget: QWidget):
    """O Windows deixa esta janela fora de prints/capturas (Windows 10 2004+)."""
    try:
        user32.SetWindowDisplayAffinity(int(widget.winId()), WDA_EXCLUDEFROMCAPTURE)
    except Exception:
        pass


def physical_rect(widget: QWidget):
    """Retângulo da janela em pixels físicos da tela."""
    r = wintypes.RECT()
    user32.GetWindowRect(int(widget.winId()), ctypes.byref(r))
    return r.left, r.top, r.right - r.left, r.bottom - r.top


def _overlay_flags(click_through=False):
    f = Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.Tool | Qt.WindowDoesNotAcceptFocus
    if click_through:
        f |= Qt.WindowTransparentForInput
    return f


class _OverlayWidget(QWidget):
    def __init__(self, click_through=False):
        super().__init__(None, _overlay_flags(click_through))
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating)

    def showEvent(self, e):
        super().showEvent(e)
        exclude_from_capture(self)


# ======================================================================
# Botão 文A
# ======================================================================

class Bubble(_OverlayWidget):
    clicked = Signal()
    menu_requested = Signal(QPoint)
    moved = Signal(QPoint)

    SIZE = 56

    def __init__(self):
        super().__init__()
        self.setFixedSize(self.SIZE, self.SIZE)
        self.setCursor(Qt.PointingHandCursor)
        self.active = False
        self._press = None
        self._start = None
        self._dragging = False

    def set_active(self, on):
        self.active = on
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(3, 3, self.SIZE - 6, self.SIZE - 6)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(0, 0, 0, 45))
        p.drawEllipse(r.translated(0, 2))
        p.setBrush(QColor("#15803D") if self.active else QColor("#2563EB"))
        p.setPen(QPen(QColor(255, 255, 255), 2))
        p.drawEllipse(r)
        f = QFont(UI_FONT)
        f.setBold(True)
        f.setPixelSize(17)
        p.setFont(f)
        p.setPen(Qt.white)
        p.drawText(r, Qt.AlignCenter, "文A")

    def mousePressEvent(self, e):
        if e.button() == Qt.RightButton:
            self.menu_requested.emit(e.globalPosition().toPoint())
            return
        self._press = e.globalPosition().toPoint()
        self._start = self.pos()
        self._dragging = False

    def mouseMoveEvent(self, e):
        if self._press is None:
            return
        d = e.globalPosition().toPoint() - self._press
        if not self._dragging and d.manhattanLength() > 6:
            self._dragging = True
        if self._dragging:
            self.move(self._start + d)

    def mouseReleaseEvent(self, e):
        if self._press is None:
            return
        if self._dragging:
            self.moved.emit(self.pos())
        elif e.button() == Qt.LeftButton:
            self.clicked.emit()
        self._press = None


# ======================================================================
# Quadro com a tradução (o mouse passa através dele)
# ======================================================================

class FrameWindow(_OverlayWidget):
    PAD_H, PAD_V = 8, 4
    MIN_PX, MAX_PX = 9, 26

    def __init__(self):
        super().__init__(click_through=True)
        self.blocks = []       # dicts com rect (lógico), text, dark, font, flags
        self.backdrop = None   # QImage pequena e desfocada
        self.backdrop_scale = 6
        self.message = None
        self.plates_visible = True

    # ---------- dados ----------

    def set_result(self, blocks, backdrop, scale):
        """blocks: (x, y, w, h, texto, escuro) em pixels FÍSICOS a partir do canto do quadro."""
        dpr = self.devicePixelRatioF() or 1.0
        self.backdrop, self.backdrop_scale = backdrop, scale
        self.message = None
        self.plates_visible = True
        originals = [QRectF(x / dpr, y / dpr, w / dpr, h / dpr) for x, y, w, h, _, _ in blocks]
        bounds = QRectF(self.rect()).adjusted(3, 3, -3, -3)
        self.blocks = []
        for i, (b, o) in enumerate(zip(blocks, originals)):
            self.blocks.append(self._layout(i, b[4], b[5], o, originals, bounds))
        self.update()

    def show_message(self, text):
        self.message = text
        self.blocks = []
        self.update()

    def hide_plates(self):
        self.plates_visible = False
        self.update()

    # ---------- encaixe do texto (igual ao celular) ----------

    def _font(self, px):
        f = QFont(UI_FONT)
        f.setPixelSize(int(px))
        f.setWeight(QFont.DemiBold)
        return f

    def _fits(self, text, r: QRectF, px):
        w, h = r.width() - 2 * self.PAD_H, r.height() - 2 * self.PAD_V
        if w < 8 or h < 4:
            return False
        br = QFontMetrics(self._font(px)).boundingRect(QRect(0, 0, int(w), 100000), Qt.TextWordWrap, text)
        return br.height() <= h and br.width() <= w + 1

    def _best(self, text, r: QRectF, min_px=None):
        min_px = min_px or self.MIN_PX
        if not self._fits(text, r, min_px):
            return None
        lo, hi = min_px, max(min_px, min(self.MAX_PX, int(r.height() * 0.9)))
        if self._fits(text, r, hi):
            return hi
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if self._fits(text, r, mid):
                lo = mid
            else:
                hi = mid
        return lo

    READABLE_PX = 12   # abaixo disso fica difícil de ler: primeiro tenta crescer a placa
    MAX_GROW = 1.5     # a placa cresce no máximo 50% em volta do texto original (fica no balão)

    def _layout(self, idx, text, dark, o: QRectF, originals, bounds: QRectF):
        base = QRectF(o).adjusted(-2, -1, 2, 1).intersected(bounds)

        # Limites: não cobrir o texto de outro balão nem sair do quadro
        lim_l, lim_t, lim_r, lim_b = bounds.left(), bounds.top(), bounds.right(), bounds.bottom()
        for j, n in enumerate(originals):
            if j == idx:
                continue
            same_row = n.top() < o.bottom() and n.bottom() > o.top()
            same_col = n.left() < o.right() and n.right() > o.left()
            if same_row and n.left() >= o.right() - 2:
                lim_r = min(lim_r, n.left() - 3)
            if same_row and n.right() <= o.left() + 2:
                lim_l = max(lim_l, n.right() + 3)
            if same_col and n.top() >= o.bottom() - 2:
                lim_b = min(lim_b, n.top() - 3)
            if same_col and n.bottom() <= o.top() + 2:
                lim_t = max(lim_t, n.bottom() + 3)

        def grown(k):
            """A placa original aumentada k vezes em volta do próprio centro, dentro dos limites."""
            c = base.center()
            w, h = base.width() * k, base.height() * k
            r = QRectF(c.x() - w / 2, c.y() - h / 2, w, h)
            r.setLeft(max(r.left(), min(lim_l, base.left())))
            r.setRight(min(r.right(), max(lim_r, base.right())))
            r.setTop(max(r.top(), min(lim_t, base.top())))
            r.setBottom(min(r.bottom(), max(lim_b, base.bottom())))
            return r

        steps = (1.0, 1.15, 1.3, 1.5)
        r, px = base, None
        # 1) Letra legível, crescendo pouco a pouco em volta do balão
        for k in steps:
            cand = grown(k)
            px = self._best(text, cand, self.READABLE_PX)
            if px is not None:
                r = cand
                break
        # 2) Não deu: aceita letra menor, primeiro no tamanho original
        if px is None:
            for k in steps:
                cand = grown(k)
                px = self._best(text, cand)
                if px is not None:
                    r = cand
                    break
        # 3) Nem assim: letra mínima na maior placa possível (o resto fica cortado)
        if px is None:
            r, px = grown(self.MAX_GROW), self.MIN_PX
        one_line = QFontMetrics(self._font(px)).boundingRect(text).width() <= r.width() - 2 * self.PAD_H
        flags = Qt.TextWordWrap | Qt.AlignLeft | (Qt.AlignVCenter if one_line else Qt.AlignTop)
        return {"rect": r, "text": text, "dark": dark, "px": px, "flags": flags}

    # ---------- desenho ----------

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        r = QRectF(self.rect()).adjusted(0.5, 0, -0.5, -0.5)
        # Contorno fino e neutro (escuro por fora, claro por dentro)
        path = QPainterPath()
        path.addRoundedRect(r, 14, 14)
        p.setPen(QPen(QColor(0, 0, 0, 60), 1))
        p.drawPath(path)
        inner = QPainterPath()
        inner.addRoundedRect(r.adjusted(1, 1, -1, -1), 13, 13)
        p.setPen(QPen(QColor(242, 242, 247, 230), 1.5))
        p.drawPath(inner)

        if self.message:
            self._draw_message(p)
            return
        if not self.plates_visible:
            return
        for b in self.blocks:
            self._draw_glass(p, b["rect"], b["dark"])
            p.setFont(self._font(b["px"]))
            p.setPen(TEXT_LIGHT if b["dark"] else TEXT_DARK)
            p.drawText(b["rect"].adjusted(self.PAD_H, self.PAD_V, -self.PAD_H, -self.PAD_V), b["flags"], b["text"])

    def _draw_glass(self, p: QPainter, r: QRectF, dark: bool):
        radius = min(14.0, r.height() / 2)
        p.setPen(Qt.NoPen)
        for grow, alpha in ((4, 14), (2, 22)):  # sombra suave
            p.setBrush(QColor(0, 0, 0, alpha + (10 if dark else 0)))
            p.drawRoundedRect(r.adjusted(-grow / 2, -grow / 2 + 2, grow / 2, grow / 2 + 2),
                              radius + grow / 2, radius + grow / 2)
        path = QPainterPath()
        path.addRoundedRect(r, radius, radius)
        p.save()
        p.setClipPath(path)
        if self.backdrop is not None:
            dpr = self.devicePixelRatioF() or 1.0
            k = dpr / self.backdrop_scale
            p.drawImage(r, self.backdrop, QRectF(r.x() * k, r.y() * k, r.width() * k, r.height() * k))
            tint = QColor(28, 28, 30, 140) if dark else QColor(247, 247, 250, 158)
        else:
            tint = QColor(44, 44, 46, 240) if dark else QColor(242, 242, 247, 240)
        p.fillRect(r, tint)
        g = QLinearGradient(QPointF(0, r.top()), QPointF(0, r.top() + r.height() * 0.6))
        g.setColorAt(0, QColor(255, 255, 255, 31 if dark else 89))
        g.setColorAt(1, QColor(255, 255, 255, 0))
        p.fillRect(r, g)
        p.restore()
        p.setPen(QPen(QColor(255, 255, 255, 46 if dark else 140), 1))
        p.setBrush(Qt.NoBrush)
        p.drawRoundedRect(r.adjusted(0.5, 0.5, -0.5, -0.5), radius, radius)

    def _draw_message(self, p: QPainter):
        f = self._font(14)
        p.setFont(f)
        maxw = max(60, self.width() - 48)
        br = QFontMetrics(f).boundingRect(QRect(0, 0, maxw, 100000), Qt.TextWordWrap | Qt.AlignCenter, self.message)
        w, h = br.width() + 28, br.height() + 20
        r = QRectF((self.width() - w) / 2, (self.height() - h) / 2, w, h)
        saved = self.backdrop
        self.backdrop = None
        self._draw_glass(p, r, False)
        self.backdrop = saved
        p.setPen(TEXT_DARK)
        p.drawText(r.adjusted(14, 10, -14, -10), Qt.TextWordWrap | Qt.AlignCenter, self.message)


# ======================================================================
# Barra de cima
# ======================================================================

class BarWindow(_OverlayWidget):
    drag = Signal(QPoint)        # deslocamento desde o início do arrasto
    drag_finished = Signal()
    label_clicked = Signal()
    settings_clicked = Signal()
    close_clicked = Signal()

    def __init__(self):
        super().__init__()
        self.label = "EN → PT"
        self._press = None
        self._dragging = False
        self.setCursor(Qt.SizeAllCursor)

    def set_label(self, text):
        self.label = text
        self.update()

    def _zones(self):
        w = self.width()
        return QRect(w - 36, 0, 36, BAR_H), QRect(w - 72, 0, 36, BAR_H)  # ✕, ⚙

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, 0)
        path = QPainterPath()
        path.addRoundedRect(r.adjusted(0, 0, 0, 20), 14, 14)  # só os cantos de cima arredondados
        p.setClipRect(self.rect())
        g = QLinearGradient(QPointF(0, 0), QPointF(0, BAR_H))
        g.setColorAt(0, QColor(250, 250, 252, 247))
        g.setColorAt(1, QColor(236, 236, 241, 245))
        p.fillPath(path, g)
        p.setPen(QPen(QColor(0, 0, 0, 31), 1))
        p.drawPath(path)
        close_r, gear_r = self._zones()
        f = QFont(UI_FONT)
        f.setBold(True)
        size = 14
        while size > 9:
            f.setPixelSize(size)
            if QFontMetrics(f).horizontalAdvance("✥  " + self.label) <= gear_r.left() - 14:
                break
            size -= 1
        p.setFont(f)
        p.setPen(TEXT_DARK)
        p.drawText(QRect(12, 0, gear_r.left() - 12, BAR_H), Qt.AlignVCenter | Qt.AlignLeft, "✥  " + self.label)
        f2 = QFont(UI_FONT)
        f2.setPixelSize(17)
        p.setFont(f2)
        p.drawText(gear_r, Qt.AlignCenter, "⚙")
        p.drawText(close_r, Qt.AlignCenter, "✕")

    def mousePressEvent(self, e):
        if e.button() != Qt.LeftButton:
            return
        self._press = e.globalPosition().toPoint()
        self._dragging = False

    def mouseMoveEvent(self, e):
        if self._press is None:
            return
        d = e.globalPosition().toPoint() - self._press
        if not self._dragging and d.manhattanLength() > 6:
            self._dragging = True
        if self._dragging:
            self.drag.emit(d)

    def mouseReleaseEvent(self, e):
        if self._press is None:
            return
        if self._dragging:
            self.drag_finished.emit()
        else:
            close_r, gear_r = self._zones()
            pos = e.position().toPoint()
            if close_r.contains(pos):
                self.close_clicked.emit()
            elif gear_r.contains(pos):
                self.settings_clicked.emit()
            else:
                self.label_clicked.emit()
        self._press = None


# ======================================================================
# Bolinha de redimensionar
# ======================================================================

class HandleWindow(_OverlayWidget):
    drag = Signal(QPoint)
    drag_finished = Signal()

    def __init__(self):
        super().__init__()
        self.setFixedSize(HANDLE, HANDLE)
        self.setCursor(Qt.SizeFDiagCursor)
        self._press = None

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        r = QRectF(1, 1, HANDLE - 2, HANDLE - 2)
        p.setBrush(QColor(242, 242, 247, 245))
        p.setPen(QPen(QColor(0, 0, 0, 46), 1))
        p.drawEllipse(r)
        f = QFont(UI_FONT)
        f.setPixelSize(15)
        p.setFont(f)
        p.setPen(TEXT_DARK)
        p.drawText(r, Qt.AlignCenter, "⤡")

    def mousePressEvent(self, e):
        if e.button() == Qt.LeftButton:
            self._press = e.globalPosition().toPoint()

    def mouseMoveEvent(self, e):
        if self._press is not None:
            self.drag.emit(e.globalPosition().toPoint() - self._press)

    def mouseReleaseEvent(self, e):
        if self._press is not None:
            self._press = None
            self.drag_finished.emit()
