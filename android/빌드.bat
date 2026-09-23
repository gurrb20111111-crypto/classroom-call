@echo off
chcp 65001 >nul
title 교실 호출 앱 빌드
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
set "ANDROID_SDK=C:\android-sdk"
python "%~dp0build.py"
echo.
pause
