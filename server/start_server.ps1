# Launched by the "SmartRingServer" Task Scheduler task (At log on).
# Runs uvicorn hidden (no console window) so it survives without a terminal
# open; stdout/stderr go to server.log / server.err.log instead of a window.
$ErrorActionPreference = "Stop"
$serverDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $serverDir

Start-Process `
    -FilePath (Join-Path $serverDir ".venv\Scripts\python.exe") `
    -ArgumentList @("-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000") `
    -WorkingDirectory $serverDir `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $serverDir "server.log") `
    -RedirectStandardError (Join-Path $serverDir "server.err.log")
