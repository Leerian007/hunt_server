@echo off
setlocal
cd /d "%~dp0"
set "PYTHONUTF8=1"
python -c "import sys; assert sys.platform == 'win32' and sys.version_info >= (3, 10), 'Windows Python 3.10+ required'"
if errorlevel 1 goto fail
if not exist ".venv-build\Scripts\python.exe" (
    python -m venv .venv-build
    if errorlevel 1 goto fail
)
set "BUILD_PYTHON=%~dp0.venv-build\Scripts\python.exe"
"%BUILD_PYTHON%" -m pip install -r requirements-build.txt
if errorlevel 1 goto fail
"%BUILD_PYTHON%" -m pip check
if errorlevel 1 goto fail
"%BUILD_PYTHON%" -m unittest discover -s tests -v
if errorlevel 1 goto fail
"%BUILD_PYTHON%" generate_icon.py
if errorlevel 1 goto fail
"%BUILD_PYTHON%" -m PyInstaller --noconfirm --clean --distpath dist --workpath build build.spec
if errorlevel 1 goto fail
if not exist "dist\hunt_tracker.exe" goto fail
"%BUILD_PYTHON%" verify_build.py
if errorlevel 1 goto fail
echo.
echo Build complete: %~dp0dist\hunt_tracker.exe
exit /b 0
:fail
echo.
echo Build failed. See the error output above.
exit /b 1
