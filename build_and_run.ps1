# ============================================================
#  YouLearn - Build, Install & Monitor (CLEAN LOGS)
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

# ── 5. Live Monitor (CLEAN — only crash-relevant logs) ──
Write-Host "  [5/5] Starting crash monitor..." -ForegroundColor Cyan
Start-Sleep -Seconds 2
$appPid = (& $ADB shell pidof $PKG 2>$null).Trim()
$pidDisplay = if ($appPid) { $appPid } else { "unknown" }

Write-Host ""
Write-Host "  ========================================" -ForegroundColor DarkCyan
Write-Host "    CRASH MONITOR (clean logs only)" -ForegroundColor Cyan
Write-Host "    PID: $pidDisplay" -ForegroundColor Gray
Write-Host "    Press Ctrl+C to stop" -ForegroundColor DarkGray
Write-Host "  ========================================" -ForegroundColor DarkCyan
Write-Host ""

& $ADB logcat -c 2>$null
Add-Content -Path $ERR -Value "`n── RUNTIME LOGS ──"
Add-Content -Path $ERR -Value "App PID: $pidDisplay`n"

$crashDetected = $false
$crashDumpMode = $false   # When true, capture ALL lines (crash backtrace from debuggerd)
$crashDumpLines = 0       # Count lines captured in crash dump mode

# ── NOISE FILTER: skip these tags/patterns entirely ──
# These produce 80%+ of the log spam and are NEVER relevant to crashes
$noisePattern = "^.*(Choreographer|ViewRootImpl|OpenGLRenderer|SurfaceFlinger|InputMethodManager|ImeTracker|InsetsController|HandWritingStubImpl|WindowOnBackDispatcher|CompatibilityChangeReporter|MiuiMultiWindowUtils|IS_CTS_MODE|MULTI_WINDOW_SWITCH_ENABLED|AppScoutStateMachine|ProfileInstaller|ForceDarkHelperStubImpl|MirrorManager|FileUtils|BinderMonitor|TransportRuntime|DynamiteModule|DecoupledTextDelegate|PipelineManager|tflite |native  |Manager |ServerFlag|nativeloader|ApkAssets|TextToSpeech|RemoteInputConnectionImpl|ActivityThread).*$"

# ── OUR APP TAGS: these are the ones that matter ──
$appTagPattern = "ModelService|LLMBooster|LLMBoost|BridgeGen|DocumentReader|AccentTTS|AndroidSTT|SmartDocSearch"

# ── SDK TAGS: RunAnywhere internals ──
$sdkTagPattern = "RACCommonsJNI|CppBridgeLLM|CppBridgeState|CppBridgeTelemetry"

# ── CRASH SIGNATURES ──
$crashPattern = "FATAL|Fatal signal|SIGABRT|SIGSEGV|SIGILL|SIGFPE|SIGBUS|native crash|tombstone|Abort message|backtrace:|#\d+\s+pc|Build fingerprint|AndroidRuntime|GGML_ABORT|ggml_abort|ggml_assert|OutOfMemory|NativeAlloc|CRASH-DIAG"

if ($appPid) {
    # Use DUAL monitoring: PID-filtered for clean app logs + crash_dump process for backtraces
    # The --pid filter HIDES crash dumps because debuggerd runs as a separate process.
    # So we use a broader filter that includes both our PID and crash-related tags from any PID.
    & $ADB logcat -v time 2>&1 | ForEach-Object {
        $line = $_.ToString()
        if (-not $line -or $line.Length -lt 5) { return }

        # Check if this log line is from OUR process or from crash_dump/debuggerd
        $isOurPid = $line -match "\(\s*$appPid\)" -or $line -match "pid $appPid"
        $isCrashDump = $line -match "DEBUG\s*\(|crash_dump|debuggerd|pid:\s*$appPid|>>> $PKG|signal \d+.*pid $appPid"

        # In crash dump mode: scan up to 500 total lines for the backtrace from debuggerd
        # debuggerd runs as a separate process, so its output comes AFTER the SIGABRT line
        if ($crashDumpMode) {
            $crashDumpLines++
            if ($crashDumpLines -gt 500) {
                $crashDumpMode = $false
                Write-Host "  [CRASH] ── end crash dump capture ──" -ForegroundColor DarkRed
                return
            }
            # Show lines from DEBUG tag (crash dump) or containing crash keywords
            if ($line -match "F/DEBUG|crash_dump|>>> |backtrace:|#\d+\s+pc|Abort message|signal \d+|GGML|ggml|abort|librac_|llamacpp|Cause:|memory.map|pid:\s*$appPid|Build fingerprint|ABI:|Cmdline:") {
                Write-Host "  [CRASH] $line" -ForegroundColor Red -BackgroundColor Black
                Add-Content -Path $ERR -Value "[CRASH] $line"
            }
            return
        }

        # Only process lines from our PID OR crash-related lines mentioning our PID/package
        if (-not $isOurPid -and -not $isCrashDump) { return }

        # ── 1. ALWAYS skip noise (biggest filter — removes 80% of spam) ──
        if ($line -match $noisePattern) { return }

        # ── 2. CRASH: highest priority — always show with full detail ──
        if ($line -match $crashPattern) {
            Write-Host "  [CRASH] $line" -ForegroundColor Red -BackgroundColor Black
            Add-Content -Path $ERR -Value "[CRASH] $line"
            $crashDetected = $true
            # Enter crash dump mode to capture backtrace from debuggerd (different PID)
            if ($line -match "Fatal signal|SIGABRT|SIGSEGV") {
                $crashDumpMode = $true
                $crashDumpLines = 0
            }
            return
        }

        # ── 3. OUR APP LOGS: ModelService, BridgeGen, LLMBoost, etc. ──
        if ($line -match $appTagPattern) {
            # Error level from our code
            if ($line -match " E[/ ]|Error|Exception|failed|FAIL") {
                Write-Host "  [APP-ERR] $line" -ForegroundColor Red
                Add-Content -Path $ERR -Value "[APP-ERR] $line"
            } else {
                Write-Host "  [APP]     $line" -ForegroundColor Green
                Add-Content -Path $ERR -Value "[APP] $line"
            }
            return
        }

        # ── 4. SDK LOGS: RunAnywhere/llama.cpp internals ──
        if ($line -match $sdkTagPattern) {
            # Only show SDK errors, load/generate events, state changes (skip telemetry spam)
            if ($line -match "Telemetry|telemetry|apikey|supabase") { return }
            if ($line -match "error|Error|fail|FAIL| E[/ ]") {
                Write-Host "  [SDK-ERR] $line" -ForegroundColor Yellow
                Add-Content -Path $ERR -Value "[SDK-ERR] $line"
            } elseif ($line -match "State changed|loaded|Loading|generate|READY|GENERATING") {
                Write-Host "  [SDK]     $line" -ForegroundColor Cyan
                Add-Content -Path $ERR -Value "[SDK] $line"
            }
            return
        }

        # ── 5. NATIVE MEMORY: critical for diagnosing SIGABRT ──
        if ($line -match "Native heap|native heap|NativeHeap|getNativeHeap|mmap|munmap|malloc|alloc.*fail") {
            Write-Host "  [MEM]     $line" -ForegroundColor Magenta
            Add-Content -Path $ERR -Value "[MEM] $line"
            return
        }

        # ── 6. GC EVENTS: show only on 6GB to track memory pressure ──
        if ($line -match "GC freed|concurrent copying GC|WaitForGcToComplete") {
            Write-Host "  [GC]      $line" -ForegroundColor DarkGray
            return
        }

        # ── 7. GENERIC ERRORS from any source ──
        if ($line -match " E[/ ]|Exception|OutOfMemory|abort|ABORT") {
            Write-Host "  [ERR]     $line" -ForegroundColor Red
            Add-Content -Path $ERR -Value "[ERR] $line"
            return
        }

        # ── Everything else: DROPPED (not shown, not saved) ──
    }
} else {
    # Fallback: tag-based filtering — only our tags + crashes
    Write-Host "  [!] Could not get PID, using tag filter" -ForegroundColor Yellow
    Write-Host ""

    & $ADB logcat -v time "ModelService:V" "LLMBooster:V" "LLMBoost:V" "BridgeGen:V" "DocumentReader:V" "AccentTTS:V" "AndroidSTT:V" "SmartDocSearch:V" "RACCommonsJNI:V" "CppBridgeLLM:V" "CppBridgeState:V" "AndroidRuntime:E" "DEBUG:I" "libc:F" "*:S" 2>&1 | ForEach-Object {
        $line = $_.ToString()
        if (-not $line) { return }

        if ($line -match $crashPattern) {
            Write-Host "  [CRASH] $line" -ForegroundColor Red -BackgroundColor Black
            Add-Content -Path $ERR -Value "[CRASH] $line"
            $crashDetected = $true
        }
        elseif ($line -match " E[/ ]|Error|Exception|fail") {
            Write-Host "  [ERR]   $line" -ForegroundColor Red
            Add-Content -Path $ERR -Value "[ERR] $line"
        }
        else {
            Write-Host "  [LOG]   $line" -ForegroundColor Gray
            Add-Content -Path $ERR -Value "[LOG] $line"
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
