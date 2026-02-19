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

$crashDetected = $false

if ($appPid) {
    # PID-based filtering: shows ALL logs from our app + FULL CRASH DUMPS
    & $ADB logcat -v time --pid=$appPid 2>&1 | ForEach-Object {
        $line = $_.ToString()
        if (-not $line) { return }

        # CRITICAL: Capture native crashes with full stack trace
        if ($line -match "FATAL|AndroidRuntime|CRASH|signal|SIGABRT|SIGSEGV|SIGILL|native crash|tombstone|DEBUG\s+:|backtrace:|#\d+\s+pc|Build fingerprint|Abort message|Cause:") {
            Write-Host "  [FATAL] $line" -ForegroundColor Red
            Add-Content -Path $ERR -Value "[FATAL] $line"
            $crashDetected = $true
        }
        # Capture all errors including native library errors
        elseif ($line -match " E[/ ]|Error|Exception|abort|librac_|librunanywhere|NativeAlloc|OutOfMemory") {
            Write-Host "  [ERROR] $line" -ForegroundColor Red
            Add-Content -Path $ERR -Value "[ERROR] $line"
        }
        elseif ($line -match " W[/ ]|Warning|warn|WaitForGcToComplete") {
            Write-Host "  [WARN]  $line" -ForegroundColor Yellow
            Add-Content -Path $ERR -Value "[WARN] $line"
        }
        elseif ($line -match "ModelService|RunAnywhere|STT|TTS|LLM|download|load|transcri|synthes|generate|KV cache|Native heap") {
            Write-Host "  [AI]    $line" -ForegroundColor Magenta
            # Log AI operations to file too for debugging
            Add-Content -Path $ERR -Value "[AI] $line"
        }
        elseif ($line -match " I[/ ]") {
            Write-Host "  [INFO]  $line" -ForegroundColor Gray
        }
        else {
            Write-Host "  [DBG]   $line" -ForegroundColor DarkGray
        }
    }
} else {
    # Fallback: tag-based filtering with enhanced crash detection
    Write-Host "  [!] Could not get PID, using enhanced tag filter" -ForegroundColor Yellow
    Write-Host ""

    $noisePattern = "Looper|ViewRootImpl|Choreographer|OpenGLRenderer|gralloc|SurfaceFlinger|hwcomposer|InputDispatcher|InputMethodManager|StatusBar|SystemUI|Zygote"

    # Enhanced filter to catch native crashes
    & $ADB logcat -v time "ModelService:V" "YouLearn:V" "RunAnywhere:V" "runanywhere:V" "AndroidRuntime:E" "System.err:W" "ActivityManager:I" "DEBUG:I" "libc:E" "*:F" "*:S" 2>&1 | ForEach-Object {
        $line = $_.ToString()
        if (-not $line) { return }
        if ($line -match $noisePattern) { return }

        if ($line -match "FATAL|AndroidRuntime|CRASH|signal|SIGABRT|SIGSEGV|native crash|tombstone|DEBUG\s+:") {
            Write-Host "  [FATAL] $line" -ForegroundColor Red
            Add-Content -Path $ERR -Value "[FATAL] $line"
        }
        elseif ($line -match " E[/ ]|Error|Exception|abort|librac_|NativeAlloc") {
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

# ── Capture tombstone if crash detected ──
if ($crashDetected) {
    Write-Host ""
    Write-Host "  ========================================" -ForegroundColor Red
    Write-Host "    CRASH DETECTED! Fetching details..." -ForegroundColor Red
    Write-Host "  ========================================" -ForegroundColor Red
    Start-Sleep -Seconds 2
    
    Add-Content -Path $ERR -Value "`n`n── CRASH ANALYSIS ──"
    
    # Get latest tombstone
    $tombstones = & $ADB shell "ls -t /data/tombstones/tombstone_* 2>/dev/null | head -1" 2>$null
    if ($tombstones) {
        Write-Host "  Extracting tombstone: $tombstones" -ForegroundColor Yellow
        $tombstoneContent = & $ADB shell "cat $tombstones" 2>$null
        Add-Content -Path $ERR -Value "`nTOMBSTONE FILE: $tombstones`n"
        Add-Content -Path $ERR -Value $tombstoneContent
        
        # Extract key crash info
        $abortMsg = $tombstoneContent | Select-String -Pattern "Abort message:" -Context 0,3
        $signal = $tombstoneContent | Select-String -Pattern "signal \d+" -Context 0,1
        $backtrace = $tombstoneContent | Select-String -Pattern "backtrace:" -Context 0,20
        
        Write-Host ""
        Write-Host "  ── CRASH ROOT CAUSE ──" -ForegroundColor Red
        if ($abortMsg) {
            Write-Host "  $abortMsg" -ForegroundColor Yellow
        }
        if ($signal) {
            Write-Host "  $signal" -ForegroundColor Yellow
        }
        if ($backtrace) {
            Write-Host "  Stack trace saved to errors.txt" -ForegroundColor Yellow
        }
        Write-Host ""
    } else {
        Write-Host "  No tombstone found (may need root access)" -ForegroundColor DarkGray
    }
    
    # Get native heap info
    Write-Host "  Checking native memory state..." -ForegroundColor Cyan
    $meminfo = & $ADB shell "dumpsys meminfo $PKG | grep -A 20 'Native Heap'" 2>$null
    if ($meminfo) {
        Add-Content -Path $ERR -Value "`n── NATIVE MEMORY AT CRASH ──`n"
        Add-Content -Path $ERR -Value $meminfo
        Write-Host "  Native memory info saved to errors.txt" -ForegroundColor Green
    }
    Write-Host ""
}

Write-Host ""
Write-Host "  Monitoring stopped. Errors in: errors.txt" -ForegroundColor Green
