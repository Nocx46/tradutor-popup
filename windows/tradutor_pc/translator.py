"""
Tradução offline e gratuita com os modelos do Argos Translate (CTranslate2 + SentencePiece).
Cada par de idiomas é baixado uma vez (~60–90 MB) e depois funciona sem internet.
Quando não há modelo direto, traduz passando pelo inglês (ex.: japonês → inglês → português).
"""
import os
import re
import shutil
import threading
import urllib.request
import zipfile

import ctranslate2
import sentencepiece as spm

from . import languages as L

_SENTENCE_END = re.compile(r"(?<=[.!?…。！？])\s+")


class Translator:
    def __init__(self, models_dir: str):
        self.models_dir = models_dir
        self._loaded = {}   # (de, para) -> (sentencepiece, translator)
        self._cache = {}    # (de, para, frase) -> tradução
        self._lock = threading.Lock()

    # ---------- caminho de tradução ----------

    @staticmethod
    def _argos(code: str) -> str:
        return L.ARGOS_CODE.get(code, code)

    def path(self, src: str, tgt: str):
        s, t = self._argos(src), self._argos(tgt)
        if s == t:
            return []
        if (s, t) in L.MODELS:
            return [(s, t)]
        return [(s, "en"), ("en", t)]

    def missing(self, src: str, tgt: str):
        """Pares que ainda precisam ser baixados."""
        return [p for p in self.path(src, tgt) if not os.path.isdir(self._dir(p))]

    def _dir(self, pair):
        return os.path.join(self.models_dir, f"{pair[0]}_{pair[1]}")

    # ---------- download ----------

    def download(self, pair, progress=None):
        """Baixa e descompacta um modelo. progress(fração 0..1)."""
        os.makedirs(self.models_dir, exist_ok=True)
        url = L.ARGOS_BASE + L.MODELS[pair]
        tmp = self._dir(pair) + ".download"
        req = urllib.request.Request(url, headers={"User-Agent": "TradutorPopup/1.0 (Windows)"})
        with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as f:
            total = int(r.headers.get("Content-Length") or 0)
            done = 0
            while True:
                chunk = r.read(256 * 1024)
                if not chunk:
                    break
                f.write(chunk)
                done += len(chunk)
                if progress and total:
                    progress(done / total)
        extract = self._dir(pair) + ".extract"
        shutil.rmtree(extract, ignore_errors=True)
        with zipfile.ZipFile(tmp) as z:
            z.extractall(extract)
        # O zip tem uma pasta "translate-xx_yy-v" com model/ e sentencepiece.model dentro
        inner = next((os.path.join(extract, d) for d in os.listdir(extract)
                      if os.path.isdir(os.path.join(extract, d, "model"))), extract)
        shutil.rmtree(self._dir(pair), ignore_errors=True)
        shutil.move(inner, self._dir(pair))
        shutil.rmtree(extract, ignore_errors=True)
        os.remove(tmp)

    # ---------- tradução ----------

    def _load(self, pair):
        if pair not in self._loaded:
            d = self._dir(pair)
            sp = spm.SentencePieceProcessor(model_file=os.path.join(d, "sentencepiece.model"))
            tr = ctranslate2.Translator(os.path.join(d, "model"), device="cpu",
                                        compute_type="int8", inter_threads=1, intra_threads=2)
            self._loaded[pair] = (sp, tr)
        return self._loaded[pair]

    def _translate_pair(self, texts, pair):
        sp, tr = self._load(pair)
        # Divide em frases (os modelos funcionam melhor frase por frase)
        pieces = []  # (índice do texto, frase)
        for i, t in enumerate(texts):
            for s in _SENTENCE_END.split(t.strip()):
                if s.strip():
                    pieces.append((i, s.strip()))
        todo = [s for _, s in pieces if (pair[0], pair[1], s) not in self._cache]
        todo = list(dict.fromkeys(todo))
        if todo:
            toks = [sp.encode(s, out_type=str) for s in todo]
            # beam 4 + leve preferência por frases mais longas: evita cortar o fim da frase
            res = tr.translate_batch(toks, beam_size=4, length_penalty=1.5, max_decoding_length=256)
            for s, r in zip(todo, res):
                out = "".join(r.hypotheses[0]).replace("▁", " ").strip()
                self._cache[(pair[0], pair[1], s)] = out
        joined = [[] for _ in texts]
        for i, s in pieces:
            joined[i].append(self._cache[(pair[0], pair[1], s)])
        return [" ".join(parts) for parts in joined]

    def translate(self, texts, src: str, tgt: str):
        with self._lock:
            out = list(texts)
            for pair in self.path(src, tgt):
                out = self._translate_pair(out, pair)
            return out
