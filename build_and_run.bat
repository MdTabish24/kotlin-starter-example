@echo off
:: Launcher for PowerShell build script
:: Just double-click this to build, install and monitor
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build_and_run.ps1"
pause
