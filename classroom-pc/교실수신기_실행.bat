@echo off
rem 교실 호출 수신기 실행 (검은 창 없이 백그라운드로) — 파이썬이 설치된 PC용
cd /d "%~dp0"
where pythonw >nul 2>&1 && ( start "" pythonw "교실수신기.py" ) || ( start "" python "교실수신기.py" )
exit
