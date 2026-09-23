@echo off
rem 교실 호출 수신기 종료 — PID 파일로 정확히 종료(파이썬/exe 공통), 없으면 이름으로 시도
cd /d "%~dp0"
if exist "교실수신기.pid" (
  set /p _PID=<"교실수신기.pid"
  taskkill /f /pid %_PID% >nul 2>&1
  del "교실수신기.pid" >nul 2>&1
)
taskkill /f /im 교실수신기.exe >nul 2>&1
echo 교실 호출 수신기를 종료했습니다. (없으면 이미 꺼져 있음)
timeout /t 2 >nul
exit
