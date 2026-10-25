"""Entry point for VNPT ONT GW040-NSv2 Desktop Configuration Application."""

import sys
import os
from PyQt6.QtWidgets import QApplication
from PyQt6.QtCore import Qt

from app.ui.main_window import MainWindow
from app.core.config_models import get_app_path


def _handle_exception(exc_type, exc_value, exc_traceback):
    """Global exception hook to prevent silent crashes in --noconsole Windows builds."""
    import traceback
    import datetime
    err_msg = "".join(traceback.format_exception(exc_type, exc_value, exc_traceback))
    now_str = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    crash_log = f"[{now_str}] UNHANDLED EXCEPTION:\n{err_msg}\n"
    try:
        crash_log_file = get_app_path("crash_dump.log")
        with open(crash_log_file, "a", encoding="utf-8") as f:
            f.write(crash_log)
    except Exception:
        pass

    try:
        from PyQt6.QtWidgets import QMessageBox
        QMessageBox.critical(
            None,
            "Lỗi Hệ Thống",
            f"Ứng dụng gặp sự cố bất ngờ:\n\n{exc_value}\n\nChi tiết đã ghi vào file crash_dump.log.",
        )
    except Exception:
        sys.__excepthook__(exc_type, exc_value, exc_traceback)


def main():
    """Start the Desktop GUI application."""
    sys.excepthook = _handle_exception

    # Enable High DPI scaling
    if hasattr(Qt.ApplicationAttribute, "AA_EnableHighDpiScaling"):
        QApplication.setAttribute(Qt.ApplicationAttribute.AA_EnableHighDpiScaling, True)
    if hasattr(Qt.ApplicationAttribute, "AA_UseHighDpiPixmaps"):
        QApplication.setAttribute(Qt.ApplicationAttribute.AA_UseHighDpiPixmaps, True)

    app = QApplication(sys.argv)
    app.setApplicationName("VNPT ONT GW040-NSv2 Configurator")
    app.setOrganizationName("VNPT")

    initial_file = sys.argv[1] if len(sys.argv) > 1 else None
    window = MainWindow(initial_excel_path=initial_file)
    window.show()

    sys.exit(app.exec())


if __name__ == "__main__":
    main()
