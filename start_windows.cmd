@echo off
setlocal
cd /d "%~dp0"
title Rain Classroom MCP Setup
if exist ".venv\Scripts\python.exe" goto install
where py >nul 2>nul
if errorlevel 1 goto python_fallback
py -3 -c "import sys; sys.exit(0 if sys.version_info >= (3,11) else 1)"
if errorlevel 1 goto python_missing
py -3 -m venv .venv
if errorlevel 1 goto failed
goto install
:python_fallback
python -c "import sys; sys.exit(0 if sys.version_info >= (3,11) else 1)"
if errorlevel 1 goto python_missing
python -m venv .venv
if errorlevel 1 goto failed
:install
echo Installing project requirements...
".venv\Scripts\python.exe" -m pip install -r requirements.txt --disable-pip-version-check
if errorlevel 1 goto failed
echo Starting local WeChat login setup...
".venv\Scripts\python.exe" server.py setup
if errorlevel 1 goto failed
exit /b 0
:python_missing
echo Install Python 3.11 or newer from https://www.python.org/downloads/
echo Then run this file again. Python 3.11 and 3.12 are recommended for this prototype.
pause
exit /b 1
:failed
echo Setup stopped. Read the error above. No account credentials are required in chat.
pause
exit /b 1
