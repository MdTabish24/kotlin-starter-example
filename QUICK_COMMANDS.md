# Quick Commands Cheat Sheet

## 🚀 One-Command Build & Run

| Platform | Command |
|----------|---------|
| **Windows** | `.\build_and_run.ps1` |
| **Linux/Ubuntu** | `./build_and_run.sh` |

---

## 🔨 Manual Build Commands

### Clean Build
```bash
# Linux/Mac
./gradlew clean

# Windows
.\gradlew.bat clean
```

### Build APK
```bash
# Linux/Mac
./gradlew assembleDebug

# Windows
.\gradlew.bat assembleDebug
```

### Build + Install
```bash
# Linux/Mac
./gradlew installDebug

# Windows
.\gradlew.bat installDebug
```

---

## 📱 ADB Commands

### Device Management
```bash
# List devices
adb devices

# Start ADB server
adb start-server

# Kill ADB server
adb kill-server

# Restart device
adb reboot
```

### App Management
```bash
# Install APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Uninstall app
adb uninstall com.runanywhere.kotlin_starter_example

# Force stop app
adb shell am force-stop com.runanywhere.kotlin_starter_example

# Launch app
adb shell am start -n com.runanywhere.kotlin_starter_example/.MainActivity

# Clear app data
adb shell pm clear com.runanywhere.kotlin_starter_example
```

### Logs & Debugging
```bash
# Clear logs
adb logcat -c

# View all logs
adb logcat

# View app logs only (with PID)
adb logcat --pid=$(adb shell pidof com.runanywhere.kotlin_starter_example)

# View errors only
adb logcat *:E

# View specific tag
adb logcat ModelService:V *:S

# Save logs to file
adb logcat > logs.txt

# View crash logs
adb logcat | grep -E "FATAL|AndroidRuntime"
```

### File Management
```bash
# Push file to device
adb push local_file.txt /sdcard/

# Pull file from device
adb pull /sdcard/file.txt ./

# List files
adb shell ls /sdcard/

# View file content
adb shell cat /sdcard/file.txt
```

### System Info
```bash
# Get device info
adb shell getprop

# Get Android version
adb shell getprop ro.build.version.release

# Get device model
adb shell getprop ro.product.model

# Get RAM info
adb shell cat /proc/meminfo

# Get CPU info
adb shell cat /proc/cpuinfo

# Get battery info
adb shell dumpsys battery

# Get running processes
adb shell ps | grep kotlin_starter
```

---

## 🐛 Debugging Commands

### Memory Analysis
```bash
# Get app memory usage
adb shell dumpsys meminfo com.runanywhere.kotlin_starter_example

# Get native heap info
adb shell dumpsys meminfo com.runanywhere.kotlin_starter_example | grep -A 10 "Native Heap"

# Monitor memory in real-time
watch -n 1 'adb shell dumpsys meminfo com.runanywhere.kotlin_starter_example | grep -E "TOTAL|Native Heap"'
```

### Performance Monitoring
```bash
# Get CPU usage
adb shell top -n 1 | grep kotlin_starter

# Get app PID
adb shell pidof com.runanywhere.kotlin_starter_example

# Kill app process
adb shell kill $(adb shell pidof com.runanywhere.kotlin_starter_example)
```

### Crash Analysis
```bash
# View tombstones (native crashes)
adb shell ls /data/tombstones/

# Pull latest tombstone
adb pull /data/tombstones/tombstone_00 ./

# View ANR traces
adb pull /data/anr/traces.txt ./
```

---

## 🔧 Gradle Tasks

```bash
# List all tasks
./gradlew tasks

# Build variants
./gradlew assembleDebug
./gradlew assembleRelease

# Run tests
./gradlew test
./gradlew connectedAndroidTest

# Lint check
./gradlew lint

# Dependency tree
./gradlew dependencies

# Clean + Build
./gradlew clean assembleDebug
```

---

## 💡 Pro Tips

### Monitor Specific Issues

**Memory Leaks:**
```bash
adb logcat | grep -E "GC_|NativeAlloc|OutOfMemory"
```

**Crashes:**
```bash
adb logcat | grep -E "FATAL|SIGABRT|SIGSEGV|AndroidRuntime"
```

**LLM Operations:**
```bash
adb logcat | grep -E "ModelService|RunAnywhere|LLM|generate|transcribe|synthesize"
```

**Performance:**
```bash
adb logcat | grep -E "tok/s|tokens|inference|generation"
```

### Quick Reinstall
```bash
# One-liner: uninstall, build, install, launch
adb uninstall com.runanywhere.kotlin_starter_example && ./gradlew installDebug && adb shell am start -n com.runanywhere.kotlin_starter_example/.MainActivity
```

### Background Monitoring
```bash
# Run in background and save to file
nohup adb logcat > app_logs.txt 2>&1 &

# Stop background monitoring
pkill -f "adb logcat"
```

---

**Happy Coding! 🚀**
