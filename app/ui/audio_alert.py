"""Cross-platform safe audio alert module.

Guards against Windows-only winsound crashes on Linux/macOS.
"""

import sys


def play_alert_sound() -> None:
    """Play a system notification beep safely across Windows and Linux/macOS."""
    if sys.platform == "win32":
        try:
            import winsound
            winsound.MessageBeep(winsound.MB_ICONASTERISK)
            return
        except Exception:
            pass

    try:
        from PyQt6.QtWidgets import QApplication
        app = QApplication.instance()
        if app:
            QApplication.beep()
    except Exception:
        # Never crash if audio system is unavailable
        pass
