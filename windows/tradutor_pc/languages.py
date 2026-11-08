"""Idiomas do app, códigos dos modelos de tradução (Argos) e do leitor de imagens (OCR do Windows)."""

AUTO = "auto"

# (código, nome, sigla)
SOURCES = [
    (AUTO, "Automático (detecta sozinho)", "AUTO"),
    ("en", "Inglês", "EN"),
    ("ja", "Japonês", "JA"),
    ("es", "Espanhol", "ES"),
    ("zh", "Chinês", "ZH"),
    ("ru", "Russo", "RU"),
]

TARGETS = [
    ("pt", "Português", "PT"),
    ("en", "Inglês", "EN"),
    ("ja", "Japonês", "JA"),
    ("es", "Espanhol", "ES"),
    ("zh", "Chinês", "ZH"),
    ("ru", "Russo", "RU"),
]


def short(code: str) -> str:
    for c, _, s in SOURCES + TARGETS:
        if c == code:
            return s
    return code.upper()


def name(code: str) -> str:
    for c, n, _ in SOURCES + TARGETS:
        if c == code:
            return n
    return code


# Nos modelos Argos, o português do Brasil é "pb"
ARGOS_CODE = {"pt": "pb"}

# Modelos de tradução gratuitos (Argos Translate, código aberto). Tudo passa pelo inglês quando
# não há modelo direto (ex.: japonês → inglês → português).
ARGOS_BASE = "https://argos-net.com/v1/"
MODELS = {
    ("en", "pb"): "translate-en_pb-1_9.argosmodel",
    ("pb", "en"): "translate-pb_en-1_9.argosmodel",
    ("es", "en"): "translate-es_en-1_9.argosmodel",
    ("en", "es"): "translate-en_es-1_0.argosmodel",
    ("ja", "en"): "translate-ja_en-1_1.argosmodel",
    ("en", "ja"): "translate-en_ja-1_1.argosmodel",
    ("zh", "en"): "translate-zh_en-1_9.argosmodel",
    ("en", "zh"): "translate-en_zh-1_9.argosmodel",
    ("ru", "en"): "translate-ru_en-1_9.argosmodel",
    ("en", "ru"): "translate-en_ru-1_9.argosmodel",
}

# Leitor de imagens do Windows: idioma → etiqueta e "pacote" do Windows (para instalar se faltar)
OCR_TAG = {"en": "en", "es": "es", "pt": "pt", "ja": "ja", "zh": "zh", "ru": "ru"}
OCR_CAPABILITY = {
    "en": "Language.OCR~~~en-US~0.0.1.0",
    "es": "Language.OCR~~~es-ES~0.0.1.0",
    "ja": "Language.OCR~~~ja-JP~0.0.1.0",
    "zh": "Language.OCR~~~zh-CN~0.0.1.0",
    "ru": "Language.OCR~~~ru-RU~0.0.1.0",
}
