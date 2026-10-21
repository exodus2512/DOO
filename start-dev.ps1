# ==============================================================================
# DOO — Development Environment Startup Script (PowerShell)
# ==============================================================================
$ErrorActionPreference = "Stop"

Write-Host @"
============================================================
      ____    ___     ___  
     |  _ \  / _ \   / _ \ 
     | | | || | | | | | | |
     | |_| || |_| | | |_| |
     |____/  \___/   \___/  Phase 1 · Personal AI Agent
============================================================
 "You ask. It does."
============================================================
"@ -ForegroundColor Cyan

$RootDir = $PSScriptRoot
$JavaHome = "C:\tools\jdk"
$Maven = "C:\Program Files\apache-maven-3.9.16\bin\mvn.cmd"

if (Test-Path $JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome\bin;$env:PATH"
}

# --- Load local .env if present ---
if (Test-Path "$RootDir\.env") {
    Get-Content "$RootDir\.env" | ForEach-Object {
        $line = $_.Trim()
        if ($line -and !$line.StartsWith("#") -and $line.Contains("=")) {
            $parts = $line.Split("=", 2)
            [System.Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), [System.EnvironmentVariableTarget]::Process)
        }
    }
}

# --- Automatic Port Cleanup ---
function Stop-PortProcess([int]$Port) {
    $conns = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue
    if ($conns) {
        $pids = $conns | Select-Object -ExpandProperty OwningProcess -Unique
        foreach ($p in $pids) {
            if ($p -and $p -ne $PID -and $p -ne 0) {
                Stop-Process -Id $p -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

Write-Host "Ensuring clean DOO ports (3000, 3001, 8081, 8082)..." -ForegroundColor Gray
Stop-PortProcess 3000
Stop-PortProcess 3001
Stop-PortProcess 8081
Stop-PortProcess 8082

# 1. Start Browser Worker (Node.js Playwright on port 3001)
Write-Host "[1/4] Starting Browser Worker service on http://localhost:3001..." -ForegroundColor Yellow
$WorkerProcess = Start-Process -FilePath "node" -ArgumentList "src/server.js" -WorkingDirectory "$RootDir\browser-worker" -PassThru

Start-Sleep -Seconds 2

# 2. Start Test Fixture server (Port 8081)
Write-Host "[2/4] Starting Test Fixture HTTP server on http://localhost:8081..." -ForegroundColor Yellow
$FixtureProcess = Start-Process -FilePath "node" -ArgumentList "server.js" -WorkingDirectory "$RootDir\test-fixture" -PassThru

Start-Sleep -Seconds 1

# 3. Start Frontend (Next.js on port 3000)
Write-Host "[3/4] Starting Next.js UI on http://localhost:3000..." -ForegroundColor Yellow
$FrontendProcess = Start-Process -FilePath "cmd.exe" -ArgumentList "/c npm run dev" -WorkingDirectory "$RootDir\frontend" -PassThru

Start-Sleep -Seconds 2

# 4. Start Backend (Spring Boot on port 8082)
$ActiveProfile = if ($env:USE_H2 -eq "true") { "h2" } else { "default" }
if ($ActiveProfile -eq "default") {
    Write-Host "[4/4] Starting Spring Boot Backend with Neon PostgreSQL & Gemini AI..." -ForegroundColor Green
} else {
    Write-Host "[4/4] Launching Spring Boot with embedded database profile (H2)..." -ForegroundColor Green
}

Write-Host "`nAll DOO services initialized!" -ForegroundColor Cyan
Write-Host "------------------------------------------------------------"
Write-Host "Frontend UI:     http://localhost:3000" -ForegroundColor White
Write-Host "Backend API:     http://localhost:8082" -ForegroundColor White
Write-Host "Browser Worker:  http://localhost:3001" -ForegroundColor White
Write-Host "Test Fixture:    http://localhost:8081" -ForegroundColor White
Write-Host "------------------------------------------------------------"
Write-Host "Press Ctrl+C to terminate all services.`n" -ForegroundColor DarkGray

try {
    Set-Location "$RootDir\backend"
    $JarPath = "$RootDir\backend\target\doo-backend-1.0.0-SNAPSHOT.jar"
    if (Test-Path $JarPath) {
        Write-Host "Running packaged backend JAR (profile: $ActiveProfile)..." -ForegroundColor Green
        & "$JavaHome\bin\java.exe" "-Djava.net.preferIPv4Stack=true" "-Dspring.profiles.active=$ActiveProfile" "-jar" $JarPath
    } else {
        $MavenArgs = @("spring-boot:run", "-Dspring-boot.run.profiles=$ActiveProfile", "-Dspring-boot.run.jvmArguments=-Djava.net.preferIPv4Stack=true")
        & $Maven $MavenArgs
    }
} finally {
    Write-Host "`nShutting down DOO background services..." -ForegroundColor Red
    if ($WorkerProcess -and !$WorkerProcess.HasExited) { Stop-Process -Id $WorkerProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($FixtureProcess -and !$FixtureProcess.HasExited) { Stop-Process -Id $FixtureProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($FrontendProcess -and !$FrontendProcess.HasExited) { Stop-Process -Id $FrontendProcess.Id -Force -ErrorAction SilentlyContinue }
    Write-Host "Services stopped cleanly." -ForegroundColor Gray
}
