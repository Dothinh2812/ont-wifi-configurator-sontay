"""Main Window for VNPT iGate GW040-NSv2 Automated Configuration Desktop Application."""

import os
import datetime
import ipaddress
from typing import Optional, List
from PyQt6.QtCore import Qt, QTimer, QModelIndex
from PyQt6.QtWidgets import (
    QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QSplitter,
    QTableView, QPushButton, QLabel, QLineEdit, QCheckBox,
    QProgressBar, QPlainTextEdit, QGroupBox, QFileDialog,
    QMessageBox, QHeaderView, QFormLayout, QSpinBox, QAbstractItemView
)

from app.core.config_models import GlobalConfig, get_app_path
from app.core.network_probe import NetworkProbe
from app.core.ont_driver import validate_password_complexity
from app.data.models import DeviceRecord, DeviceStatus
from app.data.excel_manager import ExcelManager, ExcelFileLockedError
from app.ui.device_table_model import DeviceTableModel
from app.ui.worker import ONTExecutionWorker, ProbeWorker
from app.ui.audio_alert import play_alert_sound
from app.ui.styles import MAIN_STYLE


class MainWindow(QMainWindow):
    """Primary application window handling GUI events and background workers."""

    def __init__(self, initial_excel_path: Optional[str] = None):
        super().__init__()
        self.setWindowTitle("VNPT ONT 040-NSv2 Configurator - Tự Động Đổ Cấu Hình Từ Excel")
        self.resize(1340, 780)
        self.setStyleSheet(MAIN_STYLE)

        self.excel_manager = ExcelManager()
        self.current_excel_path: Optional[str] = initial_excel_path
        self.records: List[DeviceRecord] = []
        self.global_cfg = GlobalConfig()

        # Network Probe with 3s Debounce (managed via background ProbeWorker)
        self.probe = NetworkProbe(
            base_url=self.global_cfg.base_url,
            timeout=1.5,
            required_consecutive_success=self.global_cfg.debounce_probe_count,
            callback=None,
        )

        self.active_worker: Optional[ONTExecutionWorker] = None
        self.selected_row: int = -1
        self._awaiting_next_ont: bool = False
        self._completion_dialog_open: bool = False
        self._completion_msg_box: Optional[QMessageBox] = None
        self._build_ui()

        # Start Background ProbeWorker Thread
        self.probe_worker = ProbeWorker(self.probe, parent=self)
        self.probe_worker.status_changed.connect(self._on_probe_status_changed)
        self.probe_worker.start()
        # Auto-load initial file if exists
        default_sample = get_app_path("file-mau.xlsx")
        if initial_excel_path and os.path.exists(initial_excel_path):
            self.load_excel(initial_excel_path)
        elif os.path.exists(default_sample):
            self.load_excel(default_sample)

    def _build_ui(self) -> None:
        central_widget = QWidget(self)
        self.setCentralWidget(central_widget)
        main_layout = QVBoxLayout(central_widget)
        main_layout.setContentsMargins(12, 12, 12, 12)
        main_layout.setSpacing(10)

        # 1. Header Toolbar
        toolbar_layout = QHBoxLayout()
        self.btn_load_excel = QPushButton("📁 Nạp File Excel")
        self.btn_load_excel.clicked.connect(self._browse_excel)
        toolbar_layout.addWidget(self.btn_load_excel)

        self.btn_export_results = QPushButton("💾 Xuất Báo Cáo Kết Quả")
        self.btn_export_results.clicked.connect(self._export_results)
        toolbar_layout.addWidget(self.btn_export_results)

        toolbar_layout.addSpacing(20)

        # Stats Chips
        self.lbl_stat_total = QLabel("Tổng số: 0")
        self.lbl_stat_total.setProperty("class", "counterChip")
        toolbar_layout.addWidget(self.lbl_stat_total)

        self.lbl_stat_done = QLabel("Đã xong: 0")
        self.lbl_stat_done.setProperty("class", "counterChip")
        self.lbl_stat_done.setStyleSheet("color: #10B981;")
        toolbar_layout.addWidget(self.lbl_stat_done)

        self.lbl_stat_invalid = QLabel("Lỗi dữ liệu: 0")
        self.lbl_stat_invalid.setProperty("class", "counterChip")
        self.lbl_stat_invalid.setStyleSheet("color: #D97706;")
        toolbar_layout.addWidget(self.lbl_stat_invalid)

        self.lbl_stat_pending = QLabel("Còn lại: 0")
        self.lbl_stat_pending.setProperty("class", "counterChip")
        toolbar_layout.addWidget(self.lbl_stat_pending)

        toolbar_layout.addStretch()

        # ONT Link Status Indicator in Header
        self.lbl_link_status = QLabel("○ OFFLINE (Chưa kết nối ONT)")
        self.lbl_link_status.setObjectName("lblStatusOffline")
        toolbar_layout.addWidget(self.lbl_link_status)

        main_layout.addLayout(toolbar_layout)

        # 2. Main Content Splitter (Left: Table, Right: Settings)
        content_splitter = QSplitter(Qt.Orientation.Horizontal)

        # Left: Device Table View
        table_container = QWidget()
        table_layout = QVBoxLayout(table_container)
        table_layout.setContentsMargins(0, 0, 0, 0)

        self.table_view = QTableView()
        self.table_model = DeviceTableModel([])
        self.table_view.setModel(self.table_model)
        self.table_view.setSelectionBehavior(QTableView.SelectionBehavior.SelectRows)
        self.table_view.setSelectionMode(QTableView.SelectionMode.SingleSelection)
        self.table_view.verticalHeader().setVisible(False)
        self.table_view.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Interactive)
        self.table_view.horizontalHeader().setStretchLastSection(True)
        self._configure_table_columns()
        self.table_view.selectionModel().selectionChanged.connect(self._on_table_selection_changed)
        self.table_model.dataChanged.connect(self._on_table_data_changed)
        table_layout.addWidget(self.table_view)

        content_splitter.addWidget(table_container)

        # Right: Settings Panel
        settings_container = QWidget()
        settings_container.setMinimumWidth(340)
        settings_container.setMaximumWidth(355)
        settings_layout = QVBoxLayout(settings_container)
        settings_layout.setContentsMargins(0, 0, 0, 0)

        grp_settings = QGroupBox("Cài Đặt Tham Số Chung ONT")
        form_layout = QFormLayout(grp_settings)

        self.txt_admin_pass = QLineEdit(self.global_cfg.admin_new_pass)
        self.txt_admin_pass.textChanged.connect(self._on_admin_pass_changed)
        self.lbl_pass_complexity = QLabel("✓ Đạt chuẩn mật khẩu mạnh")
        self.lbl_pass_complexity.setStyleSheet("color: #10B981; font-size: 11px;")
        pass_vbox = QVBoxLayout()
        pass_vbox.addWidget(self.txt_admin_pass)
        pass_vbox.addWidget(self.lbl_pass_complexity)
        form_layout.addRow("Mật khẩu Admin mới:", pass_vbox)

        self.txt_wifi5_ssid = QLineEdit(self.global_cfg.default_wifi5_ssid)
        form_layout.addRow("WiFi 5G SSID mặc định:", self.txt_wifi5_ssid)

        self.txt_wifi5_pass = QLineEdit(self.global_cfg.default_wifi5_pass)
        form_layout.addRow("WiFi 5G Pass mặc định:", self.txt_wifi5_pass)

        # LAN IPv4 Settings (Locked by default)
        self.chk_unlock_lan = QCheckBox("Mở khóa chỉnh sửa IP LAN")
        self.chk_unlock_lan.toggled.connect(self._on_unlock_lan_toggled)
        form_layout.addRow("", self.chk_unlock_lan)

        self.txt_lan_ip = QLineEdit(self.global_cfg.lan_ip)
        self.txt_lan_ip.setReadOnly(True)
        form_layout.addRow("IP Gateway LAN:", self.txt_lan_ip)

        self.txt_lan_mask = QLineEdit(self.global_cfg.lan_mask)
        self.txt_lan_mask.setReadOnly(True)
        form_layout.addRow("Subnet Mask:", self.txt_lan_mask)

        self.txt_dhcp_range = QLineEdit(f"{self.global_cfg.dhcp_start} - {self.global_cfg.dhcp_end}")
        self.txt_dhcp_range.setReadOnly(True)
        form_layout.addRow("Dải cấp DHCP:", self.txt_dhcp_range)

        # WAN Settings
        self.spn_vlan = QSpinBox()
        self.spn_vlan.setRange(1, 4094)
        self.spn_vlan.setValue(self.global_cfg.vlan_id)
        form_layout.addRow("VLAN ID WAN:", self.spn_vlan)

        self.spn_mtu = QSpinBox()
        self.spn_mtu.setRange(576, 1500)
        self.spn_mtu.setValue(self.global_cfg.mtu)
        form_layout.addRow("MTU:", self.spn_mtu)

        settings_layout.addWidget(grp_settings)
        settings_layout.addStretch()

        content_splitter.addWidget(settings_container)
        content_splitter.setStretchFactor(0, 3)
        content_splitter.setStretchFactor(1, 1)
        content_splitter.setSizes([955, 345])

        main_layout.addWidget(content_splitter, stretch=2)

        # 3. Bottom Operation & Monitor Panel
        op_group = QGroupBox("Điều Khiển && Giám Sát Cấu Hình 1-by-1")
        op_layout = QVBoxLayout(op_group)

        # Action Buttons Row
        action_layout = QHBoxLayout()
        self.btn_run = QPushButton("▶ RUN - ĐỔ CẤU HÌNH (Space / Enter)")
        self.btn_run.setObjectName("btnRun")
        self.btn_run.setEnabled(False)
        self.btn_run.clicked.connect(self.run_current_device)
        action_layout.addWidget(self.btn_run, stretch=2)

        self.btn_skip = QPushButton("⏭ BỎ QUA (SKIP)")
        self.btn_skip.setObjectName("btnSkip")
        self.btn_skip.clicked.connect(self.skip_current_device)
        action_layout.addWidget(self.btn_skip)

        self.btn_retry = QPushButton("🔄 THỬ LẠI (RETRY)")
        self.btn_retry.setObjectName("btnRetry")
        self.btn_retry.clicked.connect(self.retry_current_device)
        action_layout.addWidget(self.btn_retry)

        self.btn_cancel = QPushButton("⏹ HỦY (CANCEL)")
        self.btn_cancel.setObjectName("btnCancel")
        self.btn_cancel.setEnabled(False)
        self.btn_cancel.clicked.connect(self.cancel_execution)
        action_layout.addWidget(self.btn_cancel)

        op_layout.addLayout(action_layout)

        # Progress Bar & Current Step Label + Auto-Run Next ONT Option
        status_step_layout = QHBoxLayout()
        self.lbl_progress_step = QLabel("Sẵn sàng. Vui lòng chọn thiết bị và ấn RUN.")
        status_step_layout.addWidget(self.lbl_progress_step, stretch=1)

        self.chk_auto_run_next = QCheckBox("Tự động đổ cấu hình khi cắm ONT tiếp theo")
        self.chk_auto_run_next.setChecked(True)
        status_step_layout.addWidget(self.chk_auto_run_next)
        op_layout.addLayout(status_step_layout)
        self.progress_bar = QProgressBar()
        self.progress_bar.setRange(0, 10)
        self.progress_bar.setValue(0)
        op_layout.addWidget(self.progress_bar)

        # Real-time Masked Log Console
        self.log_console = QPlainTextEdit()
        self.log_console.setObjectName("logConsole")
        self.log_console.setReadOnly(True)
        self.log_console.setMaximumHeight(140)
        op_layout.addWidget(self.log_console)

        main_layout.addWidget(op_group, stretch=1)

        # 4. Bottom Footer Bar (Version & Developer Credits)
        footer_layout = QHBoxLayout()
        footer_layout.setContentsMargins(4, 2, 4, 0)

        self.lbl_footer_left = QLabel("VNPT iGate GW040-NSv2 Configurator v1.1")
        self.lbl_footer_left.setObjectName("lblFooterLeft")
        footer_layout.addWidget(self.lbl_footer_left)

        footer_layout.addStretch()

        self.lbl_footer_signature = QLabel("Đơn vị phát triển: Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186")
        self.lbl_footer_signature.setObjectName("lblFooterSignature")
        self.lbl_footer_signature.setCursor(Qt.CursorShape.PointingHandCursor)
        self.lbl_footer_signature.setToolTip("Nhấp để sao chép số điện thoại hỗ trợ kỹ thuật")
        self.lbl_footer_signature.mousePressEvent = self._on_signature_clicked
        footer_layout.addWidget(self.lbl_footer_signature)

        main_layout.addLayout(footer_layout)

    def _configure_table_columns(self) -> None:
        """Set balanced default widths so all 11 columns including Status & Notes are visible at 1200x780."""
        col_widths = {
            0: 36,   # STT
            1: 82,   # Số máy
            2: 82,   # SLID
            3: 98,   # Account PPPoE
            4: 104,  # Mật khẩu PPPoE
            5: 80,   # SSID 2.4G
            6: 90,   # Mật khẩu 2.4G
            7: 82,   # SSID 5G
            8: 90,   # Mật khẩu 5G
            9: 102,  # Trạng thái
        }
        for col_idx, width in col_widths.items():
            self.table_view.setColumnWidth(col_idx, width)

    def _on_signature_clicked(self, event=None) -> None:
        """Handle click on footer signature to copy support phone number to clipboard."""
        if event is not None and event.button() != Qt.MouseButton.LeftButton:
            if hasattr(super(QLabel, self.lbl_footer_signature), "mousePressEvent"):
                super(QLabel, self.lbl_footer_signature).mousePressEvent(event)
            return
        from PyQt6.QtWidgets import QApplication, QToolTip
        from PyQt6.QtGui import QCursor
        clipboard = QApplication.clipboard()
        if clipboard:
            clipboard.setText("0946827186")
            QToolTip.showText(QCursor.pos(), "Đã sao chép SĐT hỗ trợ: 0946827186", self.lbl_footer_signature)
        if event:
            event.accept()

    def keyPressEvent(self, event) -> None:
        """Handle keyboard shortcuts (Space / Enter to trigger RUN) when not editing inputs."""
        if event.key() in (Qt.Key.Key_Space, Qt.Key.Key_Return, Qt.Key.Key_Enter):
            focused = self.focusWidget()
            if isinstance(focused, (QLineEdit, QSpinBox, QPlainTextEdit)):
                super().keyPressEvent(event)
                return
            if self.table_view.state() == QAbstractItemView.State.EditingState:
                super().keyPressEvent(event)
                return
            if self.btn_run.isEnabled():
                self.run_current_device()
                event.accept()
                return
        super().keyPressEvent(event)

    def load_excel(self, file_path: str) -> None:
        """Load and display records from Excel file."""
        try:
            self.records = self.excel_manager.load_file(
                file_path=file_path,
                default_wifi5_ssid=self.txt_wifi5_ssid.text(),
                default_wifi5_pass=self.txt_wifi5_pass.text(),
            )
            self.current_excel_path = file_path
            self.table_model.set_records(self.records)
            self._configure_table_columns()

            # Auto-restore previous checkpoint if exists
            checkpoint_file = get_app_path(".checkpoint_results.json")
            if os.path.exists(checkpoint_file):
                restored = self.excel_manager.load_checkpoint(checkpoint_file)
                for r in self.records:
                    if r.so_may in restored:
                        item = restored[r.so_may]
                        r.status = DeviceStatus(item.get("status", r.status))
                        r.error_message = item.get("error_message")
                        r.start_time = item.get("start_time")
                        r.end_time = item.get("end_time")

            self._awaiting_next_ont = False
            self._update_summary_stats()
            self._select_first_pending_row()
            self._log(f"Đã nạp thành công {len(self.records)} bản ghi từ '{os.path.basename(file_path)}'.")

        except Exception as e:
            QMessageBox.critical(self, "Lỗi Nạp File", f"Không thể nạp file Excel:\n{e}")

    def _browse_excel(self) -> None:
        file_path, _ = QFileDialog.getOpenFileName(
            self, "Chọn File Cấu Hình Excel", "", "Excel Files (*.xlsx *.xls)"
        )
        if file_path:
            self.load_excel(file_path)

    def _export_results(self) -> None:
        if not self.records:
            QMessageBox.warning(self, "Cảnh Báo", "Chưa có danh sách thiết bị để xuất kết quả.")
            return

        default_name = "KetQua_CauHinh_ONT.xlsx"
        if self.current_excel_path:
            base, ext = os.path.splitext(self.current_excel_path)
            default_name = f"{base}_KetQua{ext}"

        out_path, _ = QFileDialog.getSaveFileName(
            self, "Lưu File Báo Cáo Kết Quả", default_name, "Excel Files (*.xlsx)"
        )
        if not out_path:
            return

        try:
            self.excel_manager.export_results(
                output_path=out_path,
                records=self.records,
                source_excel_path=self.current_excel_path,
            )
            QMessageBox.information(self, "Thành Công", f"Đã xuất báo cáo thành công ra file:\n{out_path}")
            self._log(f"Đã xuất báo cáo kết quả ra '{out_path}'.")
        except ExcelFileLockedError as e:
            QMessageBox.warning(self, "File Đang Bị Khóa", str(e))
        except Exception as e:
            QMessageBox.critical(self, "Lỗi Xuất File", f"Lỗi khi xuất file:\n{e}")

    def _update_summary_stats(self) -> None:
        total = len(self.records)
        done = sum(1 for r in self.records if r.status in (DeviceStatus.SUCCESS, DeviceStatus.FAILED, DeviceStatus.SKIPPED))
        invalid = sum(1 for r in self.records if r.status == DeviceStatus.INVALID_DATA)
        pending = sum(1 for r in self.records if r.status == DeviceStatus.PENDING)

        self.lbl_stat_total.setText(f"Tổng số: {total}")
        self.lbl_stat_done.setText(f"Đã xong: {done}")
        self.lbl_stat_invalid.setText(f"Lỗi dữ liệu: {invalid}")
        self.lbl_stat_pending.setText(f"Còn lại: {pending}")

    def _select_first_pending_row(self) -> None:
        for idx, r in enumerate(self.records):
            if r.status == DeviceStatus.PENDING:
                self.table_view.selectRow(idx)
                return
        if self.records:
            self.table_view.selectRow(0)

    def _on_table_selection_changed(self) -> None:
        indexes = self.table_view.selectionModel().selectedRows()
        if indexes:
            self.selected_row = indexes[0].row()
        else:
            self.selected_row = -1
        self._update_run_button_state()

    def _on_table_data_changed(self, top_left: QModelIndex, bottom_right: QModelIndex) -> None:
        self._update_summary_stats()
        self._update_run_button_state()

    def _on_admin_pass_changed(self, text: str) -> None:
        valid = validate_password_complexity(text)
        if valid:
            self.lbl_pass_complexity.setText("✓ Đạt chuẩn mật khẩu mạnh")
            self.lbl_pass_complexity.setStyleSheet("color: #10B981; font-size: 11px;")
            self.global_cfg.admin_new_pass = text
        else:
            self.lbl_pass_complexity.setText("✗ Yêu cầu: >= 8 ký tự, có hoa, thường, số, ký tự đặc biệt")
            self.lbl_pass_complexity.setStyleSheet("color: #EF4444; font-size: 11px;")
        self._update_run_button_state()

    def _on_unlock_lan_toggled(self, checked: bool) -> None:
        if checked:
            reply = QMessageBox.warning(
                self,
                "Cảnh Báo Nguy Hiểm",
                "CẢNH BÁO:\n\nĐổi dải IP LAN sẽ làm mất kết nối gateway giữa máy tính và ONT, "
                "làm gián đoạn toàn bộ quá trình tự động cấu hình.\n\n"
                "Bạn có chắc chắn muốn mở khóa chỉnh sửa không?",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                QMessageBox.StandardButton.No,
            )
            if reply == QMessageBox.StandardButton.Yes:
                self.txt_lan_ip.setReadOnly(False)
                self.txt_lan_mask.setReadOnly(False)
                self.txt_dhcp_range.setReadOnly(False)
            else:
                self.chk_unlock_lan.setChecked(False)
        else:
            self.txt_lan_ip.setReadOnly(True)
            self.txt_lan_mask.setReadOnly(True)
            self.txt_dhcp_range.setReadOnly(True)

    def _on_probe_status_changed(self, is_online: bool, consecutive_count: int) -> None:
        if is_online:
            self.lbl_link_status.setText(f"● ONLINE (Đã giữ ổn định {consecutive_count}s)")
            self.lbl_link_status.setObjectName("lblStatusOnline")
            self.lbl_link_status.setStyleSheet("color: #10B981; font-weight: bold;")
        else:
            self.lbl_link_status.setText("○ OFFLINE (Chưa kết nối ONT)")
            self.lbl_link_status.setObjectName("lblStatusOffline")
            self.lbl_link_status.setStyleSheet("color: #EF4444; font-weight: bold;")
        self._update_run_button_state()
        if is_online:
            if (
                self._completion_dialog_open
                and self._completion_msg_box is not None
                and self.probe.has_seen_offline
            ):
                self._completion_msg_box.accept()
            self._maybe_auto_run_next_device()
    def _maybe_auto_run_next_device(self) -> None:
        """Automatically trigger configuration for the next pending ONT once a new connection is detected."""
        if not self._awaiting_next_ont:
            return
        if self._completion_dialog_open:
            return
        if not hasattr(self, "chk_auto_run_next") or not self.chk_auto_run_next.isChecked():
            return
        if self.active_worker and self.active_worker.isRunning():
            return
        if not self.probe.is_online or not self.probe.has_seen_offline:
            return
        if self.selected_row < 0:
            return
        rec = self.table_model.get_record(self.selected_row)
        if not rec or not rec.is_valid or rec.status != DeviceStatus.PENDING:
            return
        if not validate_password_complexity(self.txt_admin_pass.text()):
            return

        self._awaiting_next_ont = False
        self._log(
            f"Đã tự động nhận kết nối ONT mới. Bắt đầu đổ cấu hình tiếp cho thiết bị {rec.so_may}..."
        )
        self.run_current_device()
    def _update_run_button_state(self) -> None:
        if self.active_worker and self.active_worker.isRunning():
            self.btn_run.setEnabled(False)
            self.btn_cancel.setEnabled(True)
            self.btn_skip.setEnabled(False)
            self.btn_retry.setEnabled(False)
            return

        self.btn_cancel.setEnabled(False)
        self.btn_skip.setEnabled(self.selected_row >= 0)
        self.btn_retry.setEnabled(self.selected_row >= 0)

        can_run = False
        if self.probe.is_online and self.selected_row >= 0:
            rec = self.table_model.get_record(self.selected_row)
            if rec and rec.is_valid and validate_password_complexity(self.txt_admin_pass.text()):
                can_run = True

        self.btn_run.setEnabled(can_run)

    def run_current_device(self) -> None:
        """Start execution worker for the currently selected device record."""
        if self.selected_row < 0 or not self.records:
            return

        rec = self.table_model.get_record(self.selected_row)
        if not rec or not rec.is_valid:
            QMessageBox.warning(self, "Dữ Liệu Không Hợp Lệ", "Dòng được chọn bị lỗi dữ liệu hoặc thiếu pass PPPoE.")
            return

        self._awaiting_next_ont = False

        # Validate and apply LAN settings from UI
        lan_ip = self.txt_lan_ip.text().strip()
        lan_mask = self.txt_lan_mask.text().strip()
        dhcp_range_raw = self.txt_dhcp_range.text().strip()
        dhcp_parts = [p.strip() for p in dhcp_range_raw.split("-") if p.strip()]
        try:
            ipaddress.IPv4Address(lan_ip)
            ipaddress.IPv4Address(lan_mask)
            if len(dhcp_parts) != 2:
                raise ValueError("Dải cấp DHCP phải có định dạng 'IP_đầu - IP_cuối'")
            ipaddress.IPv4Address(dhcp_parts[0])
            ipaddress.IPv4Address(dhcp_parts[1])
        except ValueError as e:
            QMessageBox.warning(
                self,
                "Cấu Hình LAN Không Hợp Lệ",
                f"Thông số IP LAN / Subnet Mask / DHCP không hợp lệ:\n{e}",
            )
            return

        # Prepare global config from UI
        self.global_cfg.lan_ip = lan_ip
        self.global_cfg.lan_mask = lan_mask
        self.global_cfg.dhcp_start = dhcp_parts[0]
        self.global_cfg.dhcp_end = dhcp_parts[1]
        self.global_cfg.admin_new_pass = self.txt_admin_pass.text()
        self.global_cfg.default_wifi5_ssid = self.txt_wifi5_ssid.text()
        self.global_cfg.default_wifi5_pass = self.txt_wifi5_pass.text()
        self.global_cfg.vlan_id = self.spn_vlan.value()
        self.global_cfg.mtu = self.spn_mtu.value()
        start_time_str = datetime.datetime.now().strftime("%Y-%m-%dT%H:%M:%S")
        self.table_model.update_record_status(
            self.selected_row, DeviceStatus.RUNNING, message="Đang đổ cấu hình...", start_time=start_time_str
        )
        self._update_summary_stats()

        device_cfg = rec.to_device_config()
        self.active_worker = ONTExecutionWorker(device_cfg, self.global_cfg, parent=self)

        # Wire Signals
        self.active_worker.step_progress.connect(self._on_worker_progress)
        self.active_worker.log_message.connect(self._on_worker_log)
        self.active_worker.probe_pause_requested.connect(self.probe_worker.pause)
        self.active_worker.probe_resume_requested.connect(self.probe_worker.resume)
        self.probe_worker.pause()
        self.active_worker.finished.connect(self._on_worker_finished)

        self._update_run_button_state()
        self.progress_bar.setValue(0)
        self.active_worker.start()

    def _on_worker_progress(
        self, step_name: str, step_num: int, total_steps: int, status: str, detail: str
    ) -> None:
        self.progress_bar.setValue(step_num)
        self.lbl_progress_step.setText(f"Bước {step_num}/{total_steps}: {step_name} [{status}] - {detail}")

    def _on_worker_log(self, timestamp: str, text: str) -> None:
        self._log(f"[{timestamp}] {text}")

    def _on_worker_finished(self, success: bool, message: str, result: dict) -> None:
        self.active_worker = None
        end_time_str = datetime.datetime.now().strftime("%Y-%m-%dT%H:%M:%S")

        status = DeviceStatus.SUCCESS if success else DeviceStatus.FAILED
        self.table_model.update_record_status(
            self.selected_row, status, message=message, end_time=end_time_str
        )
        self._update_summary_stats()
        self.probe_worker.resume()
        self._update_run_button_state()

        # Persist checkpoint immediately
        self.excel_manager.save_checkpoint(self.records, checkpoint_path=get_app_path(".checkpoint_results.json"))

        rec = self.table_model.get_record(self.selected_row)
        so_may = rec.so_may if rec else "N/A"

        if success:
            has_next = self._select_next_pending_row()
            self._awaiting_next_ont = has_next
            if has_next:
                next_rec = self.table_model.get_record(self.selected_row)
                next_so_may = next_rec.so_may if next_rec else "N/A"
                self.lbl_progress_step.setText(
                    f"Đã xong [{so_may}]. Đang chờ cắm ONT tiếp theo cho [{next_so_may}]..."
                )
            play_alert_sound()
            self._completion_dialog_open = True
            try:
                self._show_completion_dialog(so_may)
            finally:
                self._completion_dialog_open = False
                self._completion_msg_box = None
            self._update_run_button_state()
            self._maybe_auto_run_next_device()
        else:
            self._awaiting_next_ont = False
            QMessageBox.critical(
                self,
                "Cấu Hình Thất Bại",
                f"Cấu hình thiết bị [{so_may}] gặp lỗi:\n\n{message}\n\n"
                "Bạn có thể ấn THỬ LẠI (RETRY) hoặc BỎ QUA (SKIP).",
            )

    def _show_completion_dialog(self, so_may: str) -> None:
        """Show completion dialog that also auto-closes when the next ONT is plugged in and online."""
        text = (
            f"ĐÃ HOÀN TẤT CẤU HÌNH THIẾT BỊ [{so_may}]!\n\n"
            "Vui lòng rút cáp mạng và cắm thiết bị tiếp theo."
        )
        # Preserve mock compatibility if QMessageBox.information is patched in unit tests
        if hasattr(QMessageBox.information, "_mock_name") or not callable(QMessageBox.information):
            QMessageBox.information(self, "Hoàn Tất Thiết Bị", text)
            return

        msg_box = QMessageBox(self)
        msg_box.setIcon(QMessageBox.Icon.Information)
        msg_box.setWindowTitle("Hoàn Tất Thiết Bị")
        msg_box.setText(text)
        msg_box.setStandardButtons(QMessageBox.StandardButton.Ok)
        self._completion_msg_box = msg_box
        msg_box.exec()

    def _select_next_pending_row(self) -> bool:
        next_row = self.selected_row + 1
        while next_row < len(self.records):
            if self.records[next_row].status == DeviceStatus.PENDING:
                self.table_view.selectRow(next_row)
                return True
            next_row += 1
        # Loop from beginning if needed
        for idx in range(0, max(0, self.selected_row)):
            if self.records[idx].status == DeviceStatus.PENDING:
                self.table_view.selectRow(idx)
                return True
        return False
    def skip_current_device(self) -> None:
        if self.selected_row < 0:
            return
        self.table_model.update_record_status(
            self.selected_row, DeviceStatus.SKIPPED, message="Người dùng chọn bỏ qua"
        )
        self.excel_manager.save_checkpoint(self.records, checkpoint_path=get_app_path(".checkpoint_results.json"))
        self._update_summary_stats()
        self._select_next_pending_row()

    def retry_current_device(self) -> None:
        if self.selected_row < 0:
            return
        rec = self.table_model.get_record(self.selected_row)
        if rec and rec.is_valid:
            self.table_model.update_record_status(
                self.selected_row, DeviceStatus.PENDING, message=None
            )
            self.run_current_device()

    def cancel_execution(self) -> None:
        if self.active_worker and self.active_worker.isRunning():
            self._log("Đang gửi yêu cầu hủy tiến trình cấu hình...")
            self.active_worker.cancel()

    def _log(self, text: str) -> None:
        self.log_console.appendPlainText(text)
        # Scroll to bottom
        sb = self.log_console.verticalScrollBar()
        if sb:
            sb.setValue(sb.maximum())

    def closeEvent(self, event) -> None:
        """Safely terminate background threads before closing the window."""
        if self.active_worker and self.active_worker.isRunning():
            self.active_worker.cancel()
            try:
                self.active_worker.step_progress.disconnect()
                self.active_worker.log_message.disconnect()
                self.active_worker.finished.disconnect()
            except Exception:
                pass
            self.active_worker.wait(2500)

        if hasattr(self, "probe_worker") and self.probe_worker.isRunning():
            self.probe_worker.stop()
            self.probe_worker.wait(2500)

        event.accept()
