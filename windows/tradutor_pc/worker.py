"""
Trabalho em segundo plano: a cada ~0,5 s captura a área do quadro. Se a imagem mudou (rolagem,
quadro movido), espera parar; quando estabiliza, lê o texto, traduz e manda o resultado para a tela.
"""
import threading
import time

import numpy as np
from PIL import Image, ImageFilter
from PySide6.QtCore import QObject, Signal
from PySide6.QtGui import QImage

from . import corrections, langdetect, ocr
from . import languages as L

BACKDROP_SCALE = 6  # o fundo desfocado é guardado 6x menor


def _thumb(img: Image.Image):
    return np.asarray(img.convert("L").resize((48, 48)), dtype=np.int16)


def _diff(a, b) -> float:
    return float(np.abs(a - b).mean())


class Worker(QObject):
    result = Signal(object)   # dict: blocks, backdrop, detected
    message = Signal(str)     # aviso no meio do quadro
    moving = Signal()         # a tela está mexendo: esconder as placas

    def __init__(self, settings, translator):
        super().__init__()
        self.settings = settings
        self.translator = translator
        self._rect = None          # (left, top, width, height) em pixels físicos
        self._active = False
        self._wake = threading.Event()
        self._prev = None
        self._done = None
        self._showing = False
        self._last_msg = None
        threading.Thread(target=self._loop, daemon=True).start()

    # ---------- controle (chamado pela tela) ----------

    def set_rect(self, rect):
        self._rect = rect
        self._done = None
        self._wake.set()

    def start(self):
        self._active = True
        self._prev = self._done = None
        self._showing = False
        self._last_msg = None
        self._wake.set()

    def stop(self):
        self._active = False

    def refresh(self):
        """Força ler/traduzir de novo (ex.: trocou o idioma)."""
        self._done = None
        self._last_msg = None
        self._wake.set()

    # ---------- laço ----------

    def _loop(self):
        while True:
            self._wake.wait(0.5)
            self._wake.clear()
            if not self._active or not self._rect:
                continue
            try:
                self._tick()
            except Exception as e:  # nunca derruba o app
                self._say(f"⚠ Algo deu errado: {e}")
                time.sleep(1)

    def _say(self, text):
        if text != self._last_msg:
            self._last_msg = text
            self._showing = False
            self.message.emit(text)

    def _tick(self):
        l, t, w, h = self._rect
        if w < 20 or h < 20:
            return
        img = ocr.capture(l, t, w, h)
        sig = _thumb(img)
        if self._prev is None or _diff(sig, self._prev) > 2.5:
            # Mudou: espera a próxima captura para ver se parou de mexer
            self._prev = sig
            if self._showing:
                self._showing = False
                self.moving.emit()
            return
        self._prev = sig
        if self._done is not None and _diff(sig, self._done) <= 2.5:
            return  # nada novo
        self._done = sig
        self._process(img)

    def _process(self, img):
        src, tgt = self.settings["source"], self.settings["target"]
        sep = "" if src in ("ja", "zh") else " "

        # Escolhe o leitor de imagens do Windows para o idioma
        if src == L.AUTO:
            tags = [ocr.ocr_tag_for("en")] + [ocr.ocr_tag_for(x) for x in ("ja", "zh", "ru")]
            tags = [x for x in dict.fromkeys(tags) if x]
        else:
            tag = ocr.ocr_tag_for(src)
            if not tag:
                self._say(f"Falta o leitor de {L.name(src)} do Windows.\n"
                          f"Abra ⚙ Configurações e toque em \"Instalar\" ao lado de {L.name(src)}.")
                return
            tags = [tag]

        groups = []
        for tag in tags:
            s = "" if tag.lower().startswith(("ja", "zh")) else sep
            groups = ocr.read(img, tag, s)
            if groups:
                break
        if not groups:
            self._say("Nenhum texto encontrado nesta área.\nArraste o quadro para cima do texto ou aumente o tamanho.")
            return

        texts = [g.text for g in groups]
        real = langdetect.detect(" ".join(texts)) if src == L.AUTO else src
        if real == tgt:
            translated = texts
        else:
            for pair in self.translator.missing(real, tgt):
                label = f"{L.name(real)} → {L.name(tgt)}"

                def prog(f, label=label):
                    self._say(f"⏳ Baixando o idioma {label}… {int(f * 100)}%\n(só na primeira vez)")
                prog(0)
                self.translator.download(pair, prog)
            translated = self.translator.translate(texts, real, tgt)
        fixes = self.settings["corrections"] or ""
        translated = [corrections.apply(t, fixes) for t in translated]

        # Fundo desfocado para o efeito de vidro fosco + se cada placa fica clara ou escura
        small = img.resize((max(1, img.width // BACKDROP_SCALE), max(1, img.height // BACKDROP_SCALE)))
        small = small.filter(ImageFilter.GaussianBlur(3)).convert("RGBA")
        lum = np.asarray(small.convert("L"), dtype=np.float32)
        qimg = QImage(small.tobytes(), small.width, small.height, QImage.Format_RGBA8888).copy()

        blocks = []
        for g, tr in zip(groups, translated):
            x0, y0 = int(g.x / BACKDROP_SCALE), int(g.y / BACKDROP_SCALE)
            x1 = max(x0 + 1, int(g.r / BACKDROP_SCALE))
            y1 = max(y0 + 1, int(g.b / BACKDROP_SCALE))
            region = lum[y0:y1, x0:x1]
            dark = bool(region.size and region.mean() < 110)
            blocks.append((g.x, g.y, g.w, g.h, tr, dark))

        self._last_msg = None
        self._showing = True
        res = {"blocks": blocks, "backdrop": qimg, "detected": real}
        if self.settings["mirror"]:
            res["image"] = img  # para o modo espelho (teste): salvar o que o quadro mostra
        self.result.emit(res)
