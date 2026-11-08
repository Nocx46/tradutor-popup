# Gera o programa TradutorPopup.exe (pasta dist\TradutorPopup) com o PyInstaller.
# Uso:  powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$venv = "$env:LOCALAPPDATA\TPdev\venv"   # caminho curto (o Windows tem limite de 260 caracteres)
if (-not (Test-Path "$venv\Scripts\python.exe")) {
    python -m venv $venv
}
& "$venv\Scripts\python.exe" -m pip install --quiet -r "$here\requirements.txt" pyinstaller
$work = "$env:LOCALAPPDATA\TPdev\build"
$dist = "$env:LOCALAPPDATA\TPdev\dist"
# O sentencepiece traz DLLs próprias (msvcp140-xxxx.dll) que o PyInstaller pula por engano
$site = & "$venv\Scripts\python.exe" -c "import sysconfig; print(sysconfig.get_paths()['purelib'])"
$extra = @()
Get-ChildItem "$site\sentencepiece.libs\*.dll" | ForEach-Object { $extra += @("--add-binary", "$($_.FullName);sentencepiece.libs") }
& "$venv\Scripts\pyinstaller.exe" --noconfirm --clean --windowed --name TradutorPopup @extra `
    --icon "$here\icon.ico" `
    --collect-all winocr --collect-all winrt `
    --collect-all sentencepiece --collect-all ctranslate2 `
    --exclude-module tkinter `
    --workpath "$work\work" --specpath "$work" --distpath $dist `
    "$here\run.py"
# Compacta a pasta do programa num .zip (para baixar e descompactar em qualquer lugar)
New-Item -ItemType Directory -Force "$here\dist" | Out-Null
$zip = "$here\dist\TradutorPopup-Windows.zip"
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path "$dist\TradutorPopup" -DestinationPath $zip
Write-Host "Pronto: $dist\TradutorPopup\TradutorPopup.exe"
Write-Host "Zip:    $zip"
