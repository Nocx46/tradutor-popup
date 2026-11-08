"""Configurações salvas em %APPDATA%\\TradutorPopup\\settings.json."""
import json
import os

APP_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "TradutorPopup")
DATA_DIR = os.path.join(os.environ.get("LOCALAPPDATA", APP_DIR), "TradutorPopup")
MODELS_DIR = os.path.join(DATA_DIR, "models")
SETTINGS_FILE = os.path.join(APP_DIR, "settings.json")

DEFAULTS = {
    "source": "auto",
    "target": "pt",
    "corrections": "",
    "box": None,      # [x, y, w, h] em pixels lógicos
    "bubble": None,   # [x, y]
    "mirror": False,  # teste: salva em mirror.png o que o quadro mostra (texto + tradução)
    "shortcuts_done": False,  # já criou os atalhos na primeira vez?
}

MIRROR_FILE = os.path.join(DATA_DIR, "mirror.png")


class Settings:
    def __init__(self):
        self.data = dict(DEFAULTS)
        try:
            with open(SETTINGS_FILE, "r", encoding="utf-8") as f:
                self.data.update(json.load(f))
        except Exception:
            pass

    def __getitem__(self, key):
        return self.data.get(key, DEFAULTS.get(key))

    def __setitem__(self, key, value):
        self.data[key] = value
        self.save()

    def save(self):
        try:
            os.makedirs(APP_DIR, exist_ok=True)
            with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
                json.dump(self.data, f, ensure_ascii=False, indent=2)
        except Exception:
            pass
