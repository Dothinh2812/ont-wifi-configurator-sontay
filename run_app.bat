@echo off
chcp 65001 >nul
echo ========================================================
echo   VNPT ONT GW040-NSv2 Configurator - Khoi Chay Ung Dung
echo ========================================================
echo.
python -m app.main
if %errorlevel% neq 0 (
    echo.
    echo [LOI] Khong the khoi dong ung dung.
    echo Vui long dam bao ban da cai dat thu vien bang cach chay:
    echo   pip install -r requirements.txt
    pause
)
