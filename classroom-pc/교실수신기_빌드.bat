@echo off
rem 교실수신기.py → 단일 exe로 빌드 (검은 창 없음). 파이썬+인터넷 되는 PC에서 1회 실행.
rem 만들어진 dist\교실수신기.exe 를 USB로 교실 컴퓨터에 옮겨 쓰면 됩니다(파이썬 설치 불필요).
cd /d "%~dp0"
python -m pip install --upgrade pyinstaller >nul 2>&1
python -m PyInstaller --onefile --noconsole --name 교실수신기 "교실수신기.py"
echo.
echo ===============================================
echo  빌드 끝!  dist\교실수신기.exe 를 USB로 옮기세요.
echo  (선택) 같은 폴더에 '토픽.txt' 를 두면 그 채널로 동작합니다.
echo ===============================================
pause
