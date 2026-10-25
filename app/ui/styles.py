"""Modern Flat / Fluent UI Stylesheet for VNPT ONT Configuration Desktop App."""

MAIN_STYLE = """
QMainWindow {
    background-color: #F4F6F9;
    font-family: 'Segoe UI', Arial, sans-serif;
    font-size: 13px;
}

QGroupBox {
    font-weight: bold;
    border: 1px solid #D1D5DB;
    border-radius: 8px;
    margin-top: 12px;
    padding-top: 14px;
    background-color: #FFFFFF;
}

QGroupBox::title {
    subcontrol-origin: margin;
    subcontrol-position: top left;
    left: 14px;
    padding: 0 6px;
    color: #1F2937;
}

QPushButton {
    background-color: #0066CC;
    color: #FFFFFF;
    font-weight: 600;
    border: none;
    border-radius: 6px;
    padding: 7px 16px;
    min-height: 24px;
}

QPushButton:hover {
    background-color: #0052A3;
}

QPushButton:pressed {
    background-color: #003D7A;
}

QPushButton:disabled {
    background-color: #9CA3AF;
    color: #E5E7EB;
}

/* Big Action Buttons */
QPushButton#btnRun {
    background-color: #10B981;
    font-size: 15px;
    font-weight: bold;
    padding: 10px 24px;
    border-radius: 8px;
}

QPushButton#btnRun:hover {
    background-color: #059669;
}

QPushButton#btnRun:disabled {
    background-color: #9CA3AF;
}

QPushButton#btnSkip {
    background-color: #F59E0B;
    color: #FFFFFF;
}

QPushButton#btnSkip:hover {
    background-color: #D97706;
}

QPushButton#btnRetry {
    background-color: #3B82F6;
    color: #FFFFFF;
}

QPushButton#btnRetry:hover {
    background-color: #2563EB;
}

QPushButton#btnCancel {
    background-color: #EF4444;
    color: #FFFFFF;
}

QPushButton#btnCancel:hover {
    background-color: #DC2626;
}

/* Table View */
QTableView {
    background-color: #FFFFFF;
    gridline-color: #E5E7EB;
    border: 1px solid #D1D5DB;
    border-radius: 6px;
    selection-background-color: #BFDBFE;
    selection-color: #1E3A8A;
}

QHeaderView::section {
    background-color: #F3F4F6;
    color: #374151;
    font-weight: bold;
    border: 1px solid #E5E7EB;
    padding: 6px;
}

/* Inputs */
QLineEdit, QSpinBox {
    background-color: #FFFFFF;
    border: 1px solid #D1D5DB;
    border-radius: 5px;
    padding: 5px 8px;
    color: #111827;
}

QLineEdit:focus, QSpinBox:focus {
    border: 2px solid #0066CC;
}

QLineEdit:read-only {
    background-color: #F3F4F6;
    color: #6B7280;
}

/* Progress Bar */
QProgressBar {
    border: 1px solid #D1D5DB;
    border-radius: 6px;
    text-align: center;
    background-color: #E5E7EB;
    font-weight: bold;
    height: 22px;
}

QProgressBar::chunk {
    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #10B981, stop:1 #059669);
    border-radius: 5px;
}

/* Log View */
QPlainTextEdit#logConsole {
    background-color: #1E1E1E;
    color: #D4D4D4;
    font-family: 'Consolas', 'Courier New', monospace;
    font-size: 12px;
    border: 1px solid #333333;
    border-radius: 6px;
    padding: 8px;
}

/* Link Status Indicators */
QLabel#lblStatusOnline {
    color: #10B981;
    font-weight: bold;
    font-size: 14px;
}

QLabel#lblStatusOffline {
    color: #EF4444;
    font-weight: bold;
    font-size: 14px;
}

/* Summary Counter Chips */
QLabel.counterChip {
    font-size: 13px;
    padding: 4px 10px;
    border-radius: 5px;
    font-weight: bold;
}

/* Footer Bar */
QLabel#lblFooterLeft {
    color: #6B7280;
    font-size: 11px;
    font-weight: 500;
    padding: 2px 4px;
}

QLabel#lblFooterSignature {
    color: #374151;
    font-size: 11px;
    font-weight: 600;
    background-color: #F3F4F6;
    border: 1px solid #D1D5DB;
    border-radius: 4px;
    padding: 2px 8px;
}

QLabel#lblFooterSignature:hover {
    background-color: #E5E7EB;
    border-color: #9CA3AF;
    color: #1F2937;
}
"""
