#!/bin/bash
# ============================================================
#  YouLearn - Build, Install & Monitor (Ubuntu/Linux)
# ============================================================

set +e  # Don't exit on errors, we handle them

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ERR="$DIR/errors.txt"
PKG="com.runanywhere.kotlin_starter_example"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
GRAY='\033[0;37m'
DARKGRAY='\033[1;30m'
NC='\033[0m' # No Color

# ── Find ADB ──
ADB=""
if [ -f "$HOME/Android/Sdk/platform-tools/adb" ]; then
    ADB="$HOME/Android/Sdk/platform-tools/adb"
elif command -v adb &> /dev/null; then
    ADB="$(command -v adb)"
fi

if [ -z "$ADB" ]; then
    echo -e "\n  ${RED}[X] Cannot find ADB!${NC}"
    echo "      Install Android SDK or add adb to PATH"
    exit 1
fi

# ── Fresh errors.txt ──
timestamp=$(date '+%Y-%m-%d %H:%M:%S')
cat > "$ERR" << EOF
====================================================
  YouLearn Error Log
  $timestamp
====================================================

EOF

clear
echo ""
echo -e "  ${GREEN}========================================${NC}"
echo -e "    ${GREEN}YouLearn - Build and Run${NC}"
echo -e "  ${GREEN}========================================${NC}"
echo ""

# ── 1. Device check ──
echo -e "  ${CYAN}[1/5] Checking device...${NC}"
$ADB start-server &> /dev/null
device_count=$($ADB devices 2>/dev/null | grep -c "device$")

if [ "$device_count" -lt 1 ]; then
    echo -e "  ${RED}[X] No device found!${NC}"
    echo "      - Check USB cable"
    echo "      - Turn ON USB debugging"
    echo "[FATAL] No device connected" >> "$ERR"
    exit 1
fi

$ADB logcat -c &> /dev/null
echo -e "        ${GREEN}OK - Device found${NC}"
echo ""

# ── 2. Gradle clean ──
echo -e "  ${CYAN}[2/5] Cleaning build cache...${NC}"
cd "$DIR"
./gradlew clean --quiet &> /dev/null
echo -e "        ${GREEN}OK${NC}"
echo ""

# ── 3. Build + Install ──
echo -e "  ${CYAN}[3/5] Building APK...${NC}"
echo -e "        ${DARKGRAY}(takes 1-2 min)${NC}"
echo ""

build_output=$(./gradlew assembleDebug 2>&1)

if echo "$build_output" | grep -q "BUILD SUCCESSFUL"; then
    echo -e "        ${GREEN}BUILD SUCCESSFUL!${NC}"
    
    apk_path=$(find "$DIR/app/build/outputs/apk/debug" -name "*.apk" -type f 2>/dev/null | head -n 1)
    if [ -n "$apk_path" ]; then
        echo -e "        ${CYAN}Installing APK...${NC}"
        $ADB install -r "$apk_path" &> /dev/null
        echo -e "        ${GREEN}Installed!${NC}"
    fi
    echo ""
else
    echo ""
    echo -e "  ${RED}========================================${NC}"
    echo -e "    ${RED}BUILD FAILED!${NC}"
    echo -e "  ${RED}========================================${NC}"
    echo ""
    
    echo "── GRADLE BUILD ERRORS ──" >> "$ERR"
    echo "" >> "$ERR"
    
    # Extract error lines
    errors=$(echo "$build_output" | grep -E "^e:|error:|FAILURE|Unresolved|Could not|What went wrong|Cannot find|Compilation error" || echo "$build_output" | tail -n 30)
    
    echo "$errors" | while IFS= read -r line; do
        if [ -n "$line" ]; then
            echo -e "  ${RED}$line${NC}"
            echo "$line" >> "$ERR"
        fi
    done
    
    echo ""
    echo -e "  ${YELLOW}Errors saved to: errors.txt${NC}"
    exit 1
fi

# ── 4. Launch app ──
echo -e "  ${CYAN}[4/5] Launching YouLearn...${NC}"
$ADB shell am force-stop "$PKG" &> /dev/null
sleep 1
$ADB shell am start -n "$PKG/.MainActivity" &> /dev/null
echo -e "        ${GREEN}App launched!${NC}"
echo ""

# ── 5. Live Monitor ──
echo -e "  ${CYAN}[5/5] Starting live monitor...${NC}"
sleep 2
app_pid=$($ADB shell pidof "$PKG" 2>/dev/null | tr -d '\r\n')

pid_display="${app_pid:-unknown}"

echo ""
echo -e "  ${CYAN}----------------------------------------${NC}"
echo -e "    ${CYAN}LIVE APP MONITOR${NC}"
echo -e "    ${GRAY}PID: $pid_display${NC}"
echo -e "    ${DARKGRAY}Shows: ALL app logs + crashes${NC}"
echo -e "    ${DARKGRAY}Saves errors to: errors.txt${NC}"
echo -e "    ${DARKGRAY}Press Ctrl+C to stop${NC}"
echo -e "  ${CYAN}----------------------------------------${NC}"
echo ""

$ADB logcat -c &> /dev/null
echo "" >> "$ERR"
echo "── RUNTIME LOGS ──" >> "$ERR"
echo "Started: $timestamp" >> "$ERR"
echo "App PID: $pid_display" >> "$ERR"
echo "" >> "$ERR"

if [ -n "$app_pid" ]; then
    # PID-based filtering: shows ALL logs from our app
    $ADB logcat -v time --pid="$app_pid" 2>&1 | while IFS= read -r line; do
        if [ -z "$line" ]; then continue; fi
        
        if echo "$line" | grep -qE "FATAL|AndroidRuntime|CRASH"; then
            echo -e "  ${RED}[FATAL] $line${NC}"
            echo "[FATAL] $line" >> "$ERR"
        elif echo "$line" | grep -qE " E[/ ]|Error|Exception"; then
            echo -e "  ${RED}[ERROR] $line${NC}"
            echo "[ERROR] $line" >> "$ERR"
        elif echo "$line" | grep -qE " W[/ ]|Warning|warn"; then
            echo -e "  ${YELLOW}[WARN]  $line${NC}"
            echo "[WARN] $line" >> "$ERR"
        elif echo "$line" | grep -qE "ModelService|RunAnywhere|STT|TTS|LLM|download|load|transcri|synthes"; then
            echo -e "  ${MAGENTA}[AI]    $line${NC}"
        elif echo "$line" | grep -qE " I[/ ]"; then
            echo -e "  ${GRAY}[INFO]  $line${NC}"
        else
            echo -e "  ${DARKGRAY}[DBG]   $line${NC}"
        fi
    done
else
    # Fallback: tag-based filtering
    echo -e "  ${YELLOW}[!] Could not get PID, using tag filter${NC}"
    echo ""
    
    noise_pattern="Looper|ViewRootImpl|Choreographer|OpenGLRenderer|gralloc|SurfaceFlinger|hwcomposer|InputDispatcher|InputMethodManager|StatusBar|SystemUI|Zygote|xiaomi|miui"
    
    $ADB logcat -v time "ModelService:V" "YouLearn:V" "RunAnywhere:V" "runanywhere:V" "AndroidRuntime:E" "System.err:W" "ActivityManager:I" "*:F" "*:S" 2>&1 | while IFS= read -r line; do
        if [ -z "$line" ]; then continue; fi
        if echo "$line" | grep -qE "$noise_pattern"; then continue; fi
        
        if echo "$line" | grep -qE "FATAL|AndroidRuntime|CRASH"; then
            echo -e "  ${RED}[FATAL] $line${NC}"
            echo "[FATAL] $line" >> "$ERR"
        elif echo "$line" | grep -qE " E[/ ]|Error|Exception"; then
            echo -e "  ${RED}[ERROR] $line${NC}"
            echo "[ERROR] $line" >> "$ERR"
        elif echo "$line" | grep -qE " W[/ ]|Warning|warn"; then
            echo -e "  ${YELLOW}[WARN]  $line${NC}"
            echo "[WARN] $line" >> "$ERR"
        else
            echo -e "  ${GRAY}[LOG]   $line${NC}"
        fi
    done
fi

echo ""
echo -e "  ${GREEN}Monitoring stopped. Errors in: errors.txt${NC}"
