"""
Leitura da tela: captura a área do quadro (as janelas do tradutor ficam FORA da captura)
e lê o texto com o leitor de imagens do próprio Windows (Windows.Media.Ocr).
Depois agrupa as linhas por parágrafo/balão — igual à versão do celular.
"""
from dataclasses import dataclass

import mss
import winocr
from PIL import Image

from . import languages as L

COMPOUND_PARTS = {
    "based", "like", "free", "type", "style", "made", "related", "friendly", "sized", "level", "class",
    "rank", "time", "old", "year", "proof", "up", "in", "out", "off", "on", "down", "over", "all", "self",
    "ex", "well", "half", "known", "looking",
}


@dataclass
class Seg:
    text: str
    x: float
    y: float
    w: float
    h: float

    @property
    def r(self):
        return self.x + self.w

    @property
    def b(self):
        return self.y + self.h

    @property
    def cx(self):
        return self.x + self.w / 2

    @property
    def cy(self):
        return self.y + self.h / 2

    def union(self, o):
        x, y = min(self.x, o.x), min(self.y, o.y)
        r, b = max(self.r, o.r), max(self.b, o.b)
        self.x, self.y, self.w, self.h = x, y, r - x, b - y


# ---------------- captura ----------------

_sct = None


def capture(left, top, width, height) -> Image.Image:
    """Captura um retângulo da tela (pixels físicos)."""
    global _sct
    if _sct is None:
        _sct = mss.mss()
    shot = _sct.grab({"left": int(left), "top": int(top), "width": int(width), "height": int(height)})
    return Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")


# ---------------- idiomas do leitor ----------------

def available_ocr_langs():
    """Etiquetas instaladas no Windows, ex.: ['en-US', 'pt-BR']."""
    try:
        return [l.language_tag for l in winocr.OcrEngine.available_recognizer_languages]
    except Exception:
        return ["en-US"]


def ocr_tag_for(lang: str):
    """Melhor etiqueta instalada para o idioma, ou None se faltar o pacote do Windows."""
    avail = available_ocr_langs()
    want = L.OCR_TAG.get(lang, lang)
    for tag in avail:
        if tag.lower().split("-")[0] == want:
            return tag
    # Espanhol/português: o leitor de inglês lê o alfabeto latino razoavelmente
    if lang in ("es", "pt", "auto"):
        for tag in avail:
            if tag.lower().startswith("en"):
                return tag
        return avail[0] if avail else None
    return None


# ---------------- leitura ----------------

def read(img: Image.Image, lang_tag: str, sep: str = " "):
    """Lê o texto da imagem e devolve os balões/parágrafos (coordenadas da imagem)."""
    scale = 1.0
    if max(img.size) < 1400:  # texto pequeno: ampliar ajuda o leitor
        scale = 2.0
        img = img.resize((int(img.width * scale), int(img.height * scale)), Image.LANCZOS)
    res = winocr.recognize_pil_sync(img, lang_tag)
    pieces = []
    for line in res.get("lines", []):
        pieces.extend(_split_line(line, sep, scale))
    return _merge_bubbles(pieces, sep)


def _split_line(line, sep, scale):
    """Divide a linha onde há um vão grande entre palavras (balões lado a lado)."""
    words = []
    for w in line.get("words", []):
        br = w.get("bounding_rect") or {}
        t = (w.get("text") or "").strip()
        if t and br:
            words.append(Seg(t, br["x"] / scale, br["y"] / scale, br["width"] / scale, br["height"] / scale))
    if not words:
        return []
    words.sort(key=lambda s: s.x)
    gaps = [b.x - a.r for a, b in zip(words, words[1:])]
    med = sorted(gaps)[len(gaps) // 2] if gaps else 0
    out = [Seg(words[0].text, words[0].x, words[0].y, words[0].w, words[0].h)]
    prev = words[0]
    for w, gap in zip(words[1:], gaps):
        h = min(prev.h, w.h)
        other_height = abs(w.cy - prev.cy) > h * 0.35
        far = gap > h * 1.2 or (len(gaps) >= 3 and gap > max(2.5 * med, h * 0.6))
        if far or (gap > h * 0.5 and other_height):
            out.append(Seg(w.text, w.x, w.y, w.w, w.h))
        else:
            cur = out[-1]
            cur.text += sep + w.text
            cur.union(w)
        prev = w
    return out


def _join(a: str, b: str, sep: str) -> str:
    """Junta linhas remontando palavras quebradas com hífen ("expla-" + "nations")."""
    a, b = a.rstrip(), b.lstrip()
    if not a:
        return b
    if not b:
        return a
    if a[-1] in "-‐–" and len(a) >= 2 and a[-2].isalpha() and b[0].islower():
        nxt = "".join(ch for ch in b.split(" ")[0] if ch.isalpha()).lower()
        return a + b if nxt in COMPOUND_PARTS else a[:-1] + b
    return a + sep + b


def _merge_bubbles(pieces, sep):
    """Junta pedaços que estão um embaixo do outro, na mesma coluna — o mesmo balão/parágrafo."""
    groups = []   # [Seg do grupo, Seg da última linha]
    for p in sorted(pieces, key=lambda s: (s.y, s.x)):
        target = None
        for g in reversed(groups):
            box, last = g
            overlap = min(box.r, p.r) - max(box.x, p.x)
            narrower = min(box.w, p.w)
            line_h = min(last.h, p.h)
            gap = p.y - last.b
            centers = abs(box.cx - p.cx) <= max(box.w, p.w) / 2
            if overlap > narrower / 2 and centers and -line_h <= gap <= line_h * 0.9 + 4:
                target = g
                break
        if target:
            box, _ = target
            box.text = _join(box.text, p.text, sep)
            box.union(p)
            target[1] = p
        else:
            groups.append([Seg(p.text, p.x, p.y, p.w, p.h), p])
    out = []
    for box, _ in groups:
        box.text = " ".join(box.text.split())
        if any(ch.isalnum() for ch in box.text):
            out.append(box)
    return out
