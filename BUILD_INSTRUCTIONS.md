# YouLearn - Build & Run Instructions

## 🚀 Quick Start

### Windows (PowerShell)
```powershell
.\build_and_run.ps1
```

### Linux/Ubuntu (Bash)
```bash
./build_and_run.sh
```

## 📋 Prerequisites

1. **Android SDK** installed
   - Windows: `%LOCALAPPDATA%\Android\Sdk`
   - Linux: `~/Android/Sdk`

2. **ADB** in PATH or in SDK platform-tools

3. **Android device** connected with:
   - USB Debugging enabled
   - Device authorized

4. **Gradle** wrapper (included in project)

## 🔧 What the Script Does

1. ✅ **Checks device connection**
2. 🧹 **Cleans build cache**
3. 🔨 **Builds debug APK** (1-2 minutes)
4. 📱 **Installs on device**
5. 🚀 **Launches app**
6. 📊 **Live monitoring** with color-coded logs:
   - 🔴 **FATAL/ERROR** - Crashes and errors
   - 🟡 **WARN** - Warnings
   - 🟣 **AI** - Model operations (STT/TTS/LLM)
   - ⚪ **INFO/DEBUG** - General logs

## 📝 Error Logs

All errors are automatically saved to `errors.txt` with timestamps.

## ⚠️ Troubleshooting

### "No device found"
- Check USB cable connection
- Enable USB Debugging in Developer Options
- Run `adb devices` to verify

### "Cannot find ADB"
- Install Android SDK
- Add `platform-tools` to PATH:
  - Windows: `%LOCALAPPDATA%\Android\Sdk\platform-tools`
  - Linux: `~/Android/Sdk/platform-tools`

### Build fails
- Check `errors.txt` for details
- Ensure internet connection (for dependencies)
- Try: `./gradlew clean` (Linux) or `.\gradlew.bat clean` (Windows)

## 🛑 Stop Monitoring

Press `Ctrl+C` to stop the live log monitor.

## 📱 Manual Commands

### Build only
```bash
# Linux
./gradlew assembleDebug

# Windows
.\gradlew.bat assembleDebug
```

### Install only
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Launch only
```bash
adb shell am start -n com.runanywhere.kotlin_starter_example/.MainActivity
```

### View logs only
```bash
# All app logs
adb logcat --pid=$(adb shell pidof com.runanywhere.kotlin_starter_example)

# Errors only
adb logcat *:E
```

## 🎯 Features

- ✅ Automatic device detection
- ✅ Clean build process
- ✅ Auto-install & launch
- ✅ Real-time log monitoring
- ✅ Color-coded output
- ✅ Error logging to file
- ✅ PID-based filtering (shows only app logs)
- ✅ Crash detection & highlighting

---

**Made with ❤️ for YouLearn**
