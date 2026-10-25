@echo off
chcp 65001 >nul
echo ========================================================
echo   VNPT ONT GW040-NSv2 Configurator - Tao Desktop Shortcut
echo ========================================================

set "APP_DIR=%~dp0"
if "%APP_DIR:~-1%"=="\" set "APP_DIR=%APP_DIR:~0,-1%"

:: 1. Check in current directory
if exist "%APP_DIR%\ONT_040NS_Tool.exe" (
    set "TARGET_EXE=%APP_DIR%\ONT_040NS_Tool.exe"
    goto :CREATE_SHORTCUT
)

:: 2. Check in nested subfolder ONT_040NS_Tool\
if exist "%APP_DIR%\ONT_040NS_Tool\ONT_040NS_Tool.exe" (
    set "APP_DIR=%APP_DIR%\ONT_040NS_Tool"
    set "TARGET_EXE=%APP_DIR%\ONT_040NS_Tool.exe"
    goto :CREATE_SHORTCUT
)

:: 3. Check in dist\ONT_040NS_Tool\
if exist "%APP_DIR%\dist\ONT_040NS_Tool\ONT_040NS_Tool.exe" (
    set "APP_DIR=%APP_DIR%\dist\ONT_040NS_Tool"
    set "TARGET_EXE=%APP_DIR%\dist\ONT_040NS_Tool\ONT_040NS_Tool.exe"
    goto :CREATE_SHORTCUT
)

:: 4. Check if Linux binary was unpacked by mistake
if exist "%APP_DIR%\ONT_040NS_Tool" (
    echo [LOI] Phat hien file 'ONT_040NS_Tool' khong co duoi .exe.
    echo Day la ban build tu moi truong Linux (khong the chay truc tiep tren Windows).
    echo.
    echo -> DE DONG GOI FILE .EXE CHUAN CHO WINDOWS:
    echo    1. Mo Command Prompt (cmd) tai thu muc du an tren may Windows.
    echo    2. Cai dat thu vien: pip install PyQt6 openpyxl requests urllib3 pyinstaller
    echo    3. Chay: build.bat (hoac: python build_windows.py)
    echo    Qua trinh build tren Windows se tao file ONT_040NS_Tool.exe va file zip moi.
    pause
    exit /b 1
)

echo [LOI] Khong tim thay file thuc thi: "%APP_DIR%\ONT_040NS_Tool.exe"
echo Vui long dam bao ban da giai nen toan bo file zip hoac da chay build.bat truoc khi tao shortcut!
pause
exit /b 1

:CREATE_SHORTCUT
set "SHORTCUT_NAME=ONT GW040-NSv2 Configurator.lnk"
echo Dang tao shortcut ra man hinh Desktop...
echo - Target: "%TARGET_EXE%"
echo - Working Dir: "%APP_DIR%"

powershell -NoProfile -ExecutionPolicy Bypass -Command "$ws = New-Object -ComObject WScript.Shell; $desktop = $ws.SpecialFolders('Desktop'); $s = $ws.CreateShortcut((Join-Path $desktop '%SHORTCUT_NAME%')); $s.TargetPath = '%TARGET_EXE%'; $s.WorkingDirectory = '%APP_DIR%'; $s.Description = 'VNPT ONT GW040-NSv2 Configurator'; $s.Save()"

if %errorlevel% equ 0 (
    echo.
    echo [THANH CONG] Da tao Shortcut '%SHORTCUT_NAME%' tren man hinh Desktop!
    echo Ban co the mo ung dung truc tiep tu Desktop.
) else (
    echo [LOI] Khong the tao shortcut qua PowerShell.
)

pause
