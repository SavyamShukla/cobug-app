$ErrorActionPreference = "Stop"
$venvActivate = "C:\cobug-intent-venv\Scripts\Activate.ps1"
if (-not (Test-Path $venvActivate)) {
    throw "Virtual environment not found at $venvActivate. Create it and install requirements first."
}
. $venvActivate
$detectorDir = Split-Path -Parent $MyInvocation.MyCommand.Path
python -m uvicorn app:app --app-dir $detectorDir --host 127.0.0.1 --port 8091
