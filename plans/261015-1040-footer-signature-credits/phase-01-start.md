---
phase: 1
title: "Xây dựng thành phần Footer UI & QSS Styling"
status: completed
priority: P1
effort: "30m"
dependencies: []
---

# Phase 1: Xây dựng thành phần Footer UI & QSS Styling

<!-- Updated: Validation Session 1 - Added clipboard copy on click and 2-sided layout -->

## Overview
Bổ sung thanh Footer cố định ở đáy giao diện `MainWindow` trong `app/ui/main_window.py` và khai báo định dạng QSS chuyên nghiệp trong `app/ui/styles.py` để hiển thị thông tin tác giả và đơn vị phát hành: **"Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186"**. Hỗ trợ tương tác nhấp chuột tự động sao chép số điện thoại vào Clipboard.

## Requirements
- Functional:
  - Hiển thị nhãn cố định ở đáy cửa sổ ứng dụng: `Đơn vị phát triển: Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186`.
  - Có thuộc tính đối tượng rõ ràng (`self.lbl_footer_signature`) để thuận tiện cho việc truy xuất, kiểm thử và bảo trì sau này.
  - Hiển thị thông tin phiên bản ở góc trái đáy cửa sổ (`self.lbl_footer_left`): `VNPT iGate GW040-NSv2 Configurator v1.1`.
  - Tương tác nhấp chuột (mousePressEvent / Click): Khi nhấp vào `lbl_footer_signature`, tự động sao chép số điện thoại `"0946827186"` vào Clipboard hệ thống (`QApplication.clipboard().setText(...)`) và hiển thị thông báo `QToolTip.showText` ("Đã sao chép SĐT hỗ trợ: 0946827186").
  - Hiển thị con trỏ dạng bàn tay (`Qt.CursorShape.PointingHandCursor`) khi di chuột vào nhãn chữ ký.
- Non-functional:
  - Chiếm không gian dọc tối thiểu ($\le 24\text{px}$) để không làm co ngắn bảng dữ liệu 89 hàng và log console.
  - Phù hợp với bảng màu Fluent UI sẵn có (`#4B5563`, nền badge `#E5E7EB`, viền mỏng `#D1D5DB`).
  - Hỗ trợ tốt trên màn hình High DPI và font Segoe UI / Arial.

## Architecture
Thanh Footer được lồng trong `main_layout` dạng `QHBoxLayout` đặt ngay sau `op_group`:
```python
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
```

Handler `_on_signature_clicked`:
```python
def _on_signature_clicked(self, event) -> None:
    from PyQt6.QtWidgets import QApplication, QToolTip
    from PyQt6.QtGui import QCursor
    clipboard = QApplication.clipboard()
    if clipboard:
        clipboard.setText("0946827186")
        QToolTip.showText(QCursor.pos(), "Đã sao chép SĐT hỗ trợ: 0946827186", self.lbl_footer_signature)
```

## Related Code Files
- Modify: `app/ui/main_window.py` (khởi tạo `lbl_footer_left`, `lbl_footer_signature`, và hàm `_on_signature_clicked` trong `_build_ui`)
- Modify: `app/ui/styles.py` (thêm selector styling `#lblFooterLeft` và `#lblFooterSignature`)

## Implementation Steps
1. Mở `app/ui/styles.py`, thêm rules định dạng QSS cho `QLabel#lblFooterLeft` và `QLabel#lblFooterSignature` kèm hiệu ứng hover (`#lblFooterSignature:hover { background-color: #D1D5DB; }`).
2. Mở `app/ui/main_window.py`, tại cuối phương thức `_build_ui()`, thêm `footer_layout` với 2 nhãn và khoảng dãn `addStretch()`, gán sự kiện click sao chép clipboard và tooltip.
3. Khởi tạo thử nghiệm ứng dụng trong môi trường headless để kiểm tra không bị lỗi cú pháp hay gán parent sai.

## Success Criteria
- [x] `self.lbl_footer_signature` được khởi tạo và gắn vào `main_layout`.
- [x] Text hiển thị đúng chuỗi: `Đơn vị phát triển: Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186`.
- [x] Con trỏ chuột chuyển thành hình bàn tay khi hover qua nhãn chữ ký.
- [x] Nhấp vào chữ ký sao chép thành công chuỗi `"0946827186"` vào clipboard.
- [x] QSS stylesheet biên dịch hợp lệ mà không có cảnh báo Qt CSS warning.

## Risk Assessment
- *Nguy cơ:* Gán đè `mousePressEvent` trực tiếp trên instance QLabel có thể làm mất hành vi mặc định nếu QLabel có link.
- *Biện pháp giảm thiểu:* Không dùng rich text URL, chỉ dùng plain text và xử lý sự kiện click sao chép clipboard sạch sẽ.
