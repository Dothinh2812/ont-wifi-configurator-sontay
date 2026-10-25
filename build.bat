@echo off
chcp 65001 > nul
echo =======================================================
echo    DONG GOI UNG DUNG ONT 040-NSv2 CONFIGURATOR
echo =======================================================
python build_windows.py %*
pause
