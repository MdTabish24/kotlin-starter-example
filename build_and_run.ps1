# ============================================================
#  YouLearn - Build, Install & Monitor
# ============================================================
$ErrorActionPreference = "SilentlyContinue"
$Host.UI.RawUI.WindowTitle = "YouLearn Build"

$DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
$ERR = Join-Path $DIR "errors.txt"
$PKG = "com.runanywhere.kotlin_starter_example"

# ── Find ADB ──
$adbPaths = @(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)
$ADB = $null
foreach ($p in $adbPaths) {
    if (Test-Path $p) { $ADB = $p; break }
}
if (-not $ADB) {
    $ADB = (Get-Command adb -ErrorAction SilentlyContinue).Source
}
if (-not $ADB) {
    Write-Host "`n  [X] Cannot find ADB!" -ForegroundColor Red
    exit 1
}

# ── Fresh errors.txt ──
$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
@"
====================================================
  YouLearn Error Log
  $timestamp
====================================================

"@ | Set-Content -Path $ERR -Encoding UTF8

Clear-Host
Write-Host ""
Write-Host "  ========================================" -ForegroundColor Green
Write-Host "    YouLearn - Build and Run" -ForegroundColor Green
Write-Host "  ========================================" -ForegroundColor Green
Write-Host ""

# ── 1. Device check ──
Write-Host "  [1/5] Checking device..." -ForegroundColor Cyan
& $ADB start-server 2>$null
$deviceLines = (& $ADB devices 2>$null) | Where-Object { $_ -match "^\S+\s+device" }
if (-not $deviceLines) {
    Write-Host "  [X] No device found!" -ForegroundColor Red
    Write-Host "      - Check USB cable"
    Write-Host "      - Turn ON USB debugging"
    Add-Content -Path $ERR -Value "[FATAL] No device connected"
    exit 1
}
& $ADB logcat -c 2>$null
Write-Host "        OK - Device found" -ForegroundColor Green
Write-Host ""

# ── 2. Gradle clean ──
Write-Host "  [2/5] Cleaning build cache..." -ForegroundColor Cyan
Set-Location $DIR
& cmd /c "gradlew.bat clean --quiet" 2>$null | Out-Null
Write-Host "        OK" -ForegroundColor Green
Write-Host ""

# ── 3. Build + Install ──
Write-Host "  [3/5] Building APK..." -ForegroundColor Cyan
Write-Host "        (takes 1-2 min)" -ForegroundColor DarkGray
Write-Host ""

$buildOutput = & cmd /c "gradlew.bat assembleDebug 2>&1"
$buildText = $buildOutput -join "`n"

if ($buildText -match "BUILD SUCCESSFUL") {
    Write-Host "        BUILD SUCCESSFUL!" -ForegroundColor Green

    $apkPath = Get-ChildItem -Path "$DIR\app\build\outputs\apk\debug\*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($apkPath) {
        Write-Host "        Installing APK..." -ForegroundColor Cyan
        & $ADB install -r $apkPath.FullName 2>$null | Out-Null
        Write-Host "        Installed!" -ForegroundColor Green
    }
    Write-Host ""
} else {
    Write-Host ""
    Write-Host "  ========================================" -ForegroundColor Red
    Write-Host "    BUILD FAILED!" -ForegroundColor Red
    Write-Host "  ========================================" -ForegroundColor Red
    Write-Host ""

    Add-Content -Path $ERR -Value "── GRADLE BUILD ERRORS ──`n"

    $errors = $buildText -split "`n" | Where-Object {
        $_ -match "^e:|error:|FAILURE|Unresolved|Could not|What went wrong|Cannot find|Compilation error"
    }
    if ($errors.Count -eq 0) {
        $errors = ($buildText -split "`n") | Select-Object -Last 30
    }
    foreach ($line in $errors) {
        $clean = $line.Trim()
        if ($clean) {
            Write-Host "  $clean" -ForegroundColor Red
            Add-Content -Path $ERR -Value $clean
        }
    }
    Write-Host ""
    Write-Host "  Errors saved to: errors.txt" -ForegroundColor Yellow
    exit 1
}

# ── 4. Launch app ──
Write-Host "  [4/5] Launching YouLearn..." -ForegroundColor Cyan
& $ADB shell am force-stop $PKG 2>$null
Start-Sleep -Seconds 1
& $ADB shell am start -n "$PKG/.MainActivity" 2>$null | Out-Null
Write-Host "        App launched!" -ForegroundColor Green
Write-Host ""

# ── 5. Live Monitor ──
Write-Host "  [5/5] Starting live monitor..." -ForegroundColor Cyan
Start-Sleep -Seconds 2
$appPid = (& $ADB shell pidof $PKG 2>$null).Trim()

$pidDisplay = if ($appPid) { $appPid } else { "unknown" }

Write-Host ""
Write-Host "  ----------------------------------------" -ForegroundColor DarkCyan
Write-Host "    LIVE APP MONITOR" -ForegroundColor Cyan
Write-Host "    PID: $pidDisplay" -ForegroundColor Gray
Write-Host "    Shows: ALL app logs + crashes" -ForegroundColor DarkGray
Write-Host "    Saves errors to: errors.txt" -ForegroundColor DarkGray
Write-Host "    Press Ctrl+C to stop" -ForegroundColor DarkGray
Write-Host "  ----------------------------------------" -ForegroundColor DarkCyan
Write-Host ""

& $ADB logcat -c 2>$null
Add-Content -Path $ERR -Value "`n── RUNTIME LOGS ──"
Add-Content -Path $ERR -Value "Started: $timestamp"
Add-Content -Path $ERR -Value "App PID: $pidDisplay`n"

if ($appPid) {
    # PID-based filtering: shows ALL logs from our app
    & $ADB logcat -v time --pid=$appPid 2>&1 | ForEach-Object {
        $line = $_.ToString()
        if (-not $line) { return }

        if ($line -match "FATAL|AndroidRuntime|CRASH") {
            Write-Host "  [FATAL] $line" -ForegroundColor Red
            Add-Content -Path $ERR -Value "[FATAL] $line"
        }
        elseif ($line -match " E[/ ]|Error|Exception") {
            Write-Host "  [ERROR] $line" -ForegroundColor Red
            Add-Content -Path $ERR -Value "[ERROR] $line"
        }
        elseif ($line -match " W[/ ]|Warning|warn") {
            Write-Host "  [WARN]  $line" -ForegroundColor Yellow
            Add-Content -Path $ERR -Value "[WARN] $line"
        }
        elseif ($line -match "ModelService|RunAnywhere|STT|TTS|LLM|download|load|transcri|synthes") {
            Write-Host "  [AI]    $line" -ForegroundColor Magenta
        }
        elseif ($line -match " I[/ ]") {
            Write-Host "  [INFO]  $line" -ForegroundColor Gray
        }
        else {
            Write-Host "  [DBG]   $line" -ForegroundColor DarkGray
        }
    }
} else {
    # Fallback: tag-based filtering
    Write-Host "  [!] Could not get PID, using tag filter" -ForegroundColor Yellow
    Write-Host ""

    $noisePattern = "Looper|ViewRootImpl|Choreographer|OpenGLRenderer|gralloc|SurfaceFlinger|hwcomposer|InputDispatcher|InputMethodManager|StatusBar|SystemUI|Zygote|xiaomi|miui"

    & $ADB logcat -v time "ModelService:V" "YouLearn:V" "RunAnywhere:V" "runanywhere:V" "AndroidRuntime:E" "System.err:W" "ActivityManager:I" "*:F" "*:S" 2>&1 | ForEach-Object {
        $line = $_.ToString()
        if (-not $line) { return }
        if ($line -match $noisePattern) { return }

        if ($line -match "FATAL|AndroidRuntime|CRASH") {
            Write-Host "  [FATAL] $line" -ForegroundColor Red
            Add-Content -Path $ERR -Value "[FATAL] $line"
        }
        elseif ($line -match " E[/ ]|Error|Exception") {
            Write-Host "  [ERROR] $line" -ForegroundColor Red
            Add-Content -Path $ERR -Value "[ERROR] $line"
        }
        elseif ($line -match " W[/ ]|Warning|warn") {
            Write-Host "  [WARN]  $line" -ForegroundColor Yellow
            Add-Content -Path $ERR -Value "[WARN] $line"
        }
        else {
            Write-Host "  [LOG]   $line" -ForegroundColor Gray
        }
    }
}

Write-Host ""
Write-Host "  Monitoring stopped. Errors in: errors.txt" -ForegroundColor Green
