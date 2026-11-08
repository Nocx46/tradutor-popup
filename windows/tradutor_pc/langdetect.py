"""Detecção simples do idioma pelo tipo de letra (e, no alfabeto latino, por palavras comuns)."""
import re

_ES = {"el", "la", "los", "las", "de", "que", "y", "en", "un", "una", "es", "por", "con", "para", "no",
       "se", "lo", "pero", "qué", "está", "muy", "yo", "tú", "como", "más", "del", "al", "su", "esto"}
_EN = {"the", "a", "an", "and", "of", "to", "in", "is", "it", "you", "that", "i", "was", "for", "on",
       "with", "he", "she", "this", "be", "are", "but", "not", "what", "my", "have", "do", "we"}


def detect(text: str) -> str:
    cyr = kana = han = latin = 0
    for ch in text:
        o = ord(ch)
        if 0x0400 <= o <= 0x04FF:
            cyr += 1
        elif 0x3040 <= o <= 0x30FF:
            kana += 1
        elif 0x4E00 <= o <= 0x9FFF:
            han += 1
        elif ch.isalpha() and o < 0x0250:
            latin += 1
    if kana > 0 and kana + han >= latin * 0.3:
        return "ja"
    if han > latin:
        return "zh"
    if cyr > latin:
        return "ru"
    words = re.findall(r"[a-záéíóúñü]+", text.lower())
    es = sum(w in _ES for w in words) + 2 * len(re.findall(r"[ñ¿¡]", text.lower()))
    en = sum(w in _EN for w in words)
    return "es" if es > en else "en"
