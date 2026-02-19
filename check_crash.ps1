# ============================================================
#  Check Last Crash - Detailed Analysis
# ============================================================
$ErrorActionPreference = "SilentlyContinue"

$PKG = "com.runanywhere.kotlin_starter_example"

# Find ADB
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

Clear-Host
Write-Host ""
Write-Host "  ========================================" -ForegroundColor Cyan
Write-Host "    Crash Analysis Tool" -ForegroundColor Cyan
Write-Host "  ========================================" -ForegroundColor Cyan
Write-Host ""

# 1. Check for recent crashes in logcat
Write-Host "  [1/4] Checking recent crash logs..." -ForegroundColor Yellow
$crashLogs = & $ADB logcat -d -v time "*:E" "*:F" 2>&1 | Select-String -Pattern "FATAL|SIGABRT|SIGSEGV|native crash|$PKG" | Select-Object -Last 50

if ($crashLogs) {
    Write-Host "        Found crash logs!" -ForegroundColor Red
    Write-Host ""
    foreach ($log in $crashLogs) {
        Write-Host "  $log" -ForegroundColor Red
    }
} else {
    Write-Host "        No recent crashes in logcat" -ForegroundColor Green
}

Write-Host ""

# 2. Check tombstones (native crashes)
Write-Host "  [2/4] Checking tombstone files..." -ForegroundColor Yellow
$tombstones = & $ADB shell "ls -lt /data/tombstones/ 2>/dev/null | head -n 5" 2>$null

if ($tombstones) {
    Write-Host "        Recent tombstones found:" -ForegroundColor Yellow
    Write-Host ""
    $tombstones | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }
    
    Write-Host ""
    Write-Host "  Pulling latest tombstone..." -ForegroundColor Yellow
    $latestTombstone = & $ADB shell "ls -t /data/tombstones/tombstone_* 2>/dev/null | head -n 1" 2>$null
    
    if ($latestTombstone) {
        $latestTombstone = $latestTombstone.Trim()
        & $ADB pull $latestTombstone "./crash_tombstone.txt" 2>$null
        
        if (Test-Path "./crash_tombstone.txt") {
            Write-Host "        Saved to: crash_tombstone.txt" -ForegroundColor Green
            Write-Host ""
            Write-Host "  === CRASH DETAILS ===" -ForegroundColor Red
            Get-Content "./crash_tombstone.txt" | Select-Object -First 100 | ForEach-Object {
                if ($_ -match "signal|SIGABRT|SIGSEGV|Abort|backtrace|librac_|librunanywhere") {
                    Write-Host "  $_" -ForegroundColor Red
                } else {
                    Write-Host "  $_" -ForegroundColor Gray
                }
            }
        }
    }
} else {
    Write-Host "        No tombstones found" -ForegroundColor Green
}

Write-Host ""

# 3. Check app-specific crashes
Write-Host "  [3/4] Checking app-specific errors..." -ForegroundColor Yellow
$appErrors = & $ADB logcat -d -v time 2>&1 | Select-String -Pattern "$PKG.*Exception|$PKG.*Error|$PKG.*FATAL" | Select-Object -Last 20

if ($appErrors) {
    Write-Host "        Found app errors!" -ForegroundColor Red
    Write-Host ""
    foreach ($err in $appErrors) {
        Write-Host "  $err" -ForegroundColor Red
    }
} else {
    Write-Host "        No app-specific errors" -ForegroundColor Green
}

Write-Host ""

# 4. Check native memory issues
Write-Host "  [4/4] Checking memory issues..." -ForegroundColor Yellow
$memIssues = & $ADB logcat -d -v time 2>&1 | Select-String -Pattern "NativeAlloc|OutOfMemory|GC_|Native heap" | Select-Object -Last 20

if ($memIssues) {
    Write-Host "        Found memory warnings!" -ForegroundColor Yellow
    Write-Host ""
    foreach ($mem in $memIssues) {
        Write-Host "  $mem" -ForegroundColor Yellow
    }
} else {
    Write-Host "        No memory issues detected" -ForegroundColor Green
}

Write-Host ""
Write-Host "  ========================================" -ForegroundColor Cyan
Write-Host "    Analysis Complete" -ForegroundColor Cyan
Write-Host "  ========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Files created:" -ForegroundColor Gray
Write-Host "    - crash_tombstone.txt (if crash found)" -ForegroundColor Gray
Write-Host ""
