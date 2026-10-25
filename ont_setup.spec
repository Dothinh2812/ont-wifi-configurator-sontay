# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller specification file for VNPT ONT GW040-NSv2 Desktop Application."""

import sys
import os

block_cipher = None

a = Analysis(
    ['app/main.py'],
    pathex=['.'],
    binaries=[],
    datas=[
        ('file-mau.xlsx', '.'),
    ],
    hiddenimports=[
        'PyQt6',
        'PyQt6.sip',
        'PyQt6.QtCore',
        'PyQt6.QtWidgets',
        'PyQt6.QtGui',
        'openpyxl',
        'requests',
        'urllib3',
        'app.core',
        'app.core.config_models',
        'app.core.network_probe',
        'app.core.ont_driver',
        'app.data',
        'app.data.models',
        'app.data.excel_manager',
        'app.ui',
        'app.ui.audio_alert',
        'app.ui.device_table_model',
        'app.ui.styles',
        'app.ui.worker',
        'app.ui.main_window',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        'PyQt6.QtQml',
        'PyQt6.QtQuick',
        'PyQt6.QtPdf',
        'PyQt6.QtNetworkAuth',
        'PyQt6.QtSql',
        'PyQt6.QtSvgWidgets',
        'PyQt6.QtWebEngineCore',
        'PyQt6.QtWebEngineWidgets',
        'PyQt6.QtBluetooth',
        'PyQt6.QtPositioning',
        'tkinter',
        'matplotlib',
        'numpy',
        'scipy',
        'pandas',
        'IPython',
    ],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='ONT_040NS_Tool',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,  # Avoid Windows Defender false positives
    console=False,  # Windowed GUI application
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name='ONT_040NS_Tool',
)
