"""Correções aplicadas depois da tradução (uma por linha: "errado = certo")."""
import re

BUILT_IN = """
arcos e setas = arco e flecha
arco e setas = arco e flechas
arco e seta = arco e flecha
"""

_cache_text = None
_cache_rules = []


def parse(text: str):
    rules = []
    for line in text.splitlines():
        if "=" not in line:
            continue
        wrong, right = line.split("=", 1)
        wrong, right = wrong.strip(), right.strip()
        if not wrong:
            continue
        rx = re.compile(r"(?<!\w)" + re.escape(wrong) + r"(?!\w)", re.IGNORECASE)
        rules.append((rx, right))
    return rules


def apply(translated: str, user_text: str) -> str:
    global _cache_text, _cache_rules
    if user_text != _cache_text:
        _cache_text = user_text
        _cache_rules = parse(user_text) + parse(BUILT_IN)  # as do usuário têm prioridade
    out = translated
    for rx, right in _cache_rules:
        def rep(m, right=right):
            return right[:1].upper() + right[1:] if m.group(0)[:1].isupper() else right
        out = rx.sub(rep, out)
    return out
