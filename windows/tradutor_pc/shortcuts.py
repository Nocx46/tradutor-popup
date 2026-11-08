"""Cria atalhos do programa na Área de Trabalho e no menu Iniciar."""
import os
import subprocess
import sys

NAME = "Tradutor Pop-up"
CREATE_NO_WINDOW = 0x08000000


def program_path():
    """Caminho do TradutorPopup.exe (só existe na versão empacotada)."""
    return sys.executable if getattr(sys, "frozen", False) else None


def _ps(script: str) -> str:
    r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                       capture_output=True, text=True, creationflags=CREATE_NO_WINDOW, timeout=30)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip() or "falha ao criar atalho")
    return r.stdout.strip()


def create(desktop=True, start_menu=True):
    """Cria os atalhos e devolve a lista de arquivos .lnk criados."""
    exe = program_path()
    if not exe:
        raise RuntimeError("Os atalhos só podem ser criados pela versão instalada (TradutorPopup.exe).")
    exe = exe.replace("'", "''")  # aspas simples no caminho (PowerShell)
    targets = []
    if desktop:
        targets.append("[Environment]::GetFolderPath('Desktop')")
    if start_menu:
        targets.append("[Environment]::GetFolderPath('Programs')")
    script = "$sh = New-Object -ComObject WScript.Shell; $out = @(); "
    for t in targets:
        script += (
            f"$p = Join-Path ({t}) '{NAME}.lnk'; "
            f"$s = $sh.CreateShortcut($p); $s.TargetPath = '{exe}'; "
            f"$s.WorkingDirectory = (Split-Path '{exe}'); $s.IconLocation = '{exe},0'; "
            f"$s.Description = 'Tradução em tempo real de qualquer janela, site ou mangá'; $s.Save(); "
            f"$out += $p; "
        )
    script += "$out -join '|'"
    return [p for p in _ps(script).split("|") if p]


def exists_on_desktop():
    try:
        desk = _ps("[Environment]::GetFolderPath('Desktop')")
        return os.path.exists(os.path.join(desk, NAME + ".lnk"))
    except Exception:
        return False
