---
title: "Windows ONT Firmware Update Step (11-Step LuCI CGI & Hot-Swap Guard)"
date: 2026-10-26
summary: Implemented optional Step 10/11 LuCI CGI firmware update and hot-swap reboot protection on Windows PyQt6 app with 74/74 tests passing.
---

# Windows ONT Firmware Update Step (11-Step LuCI CGI & Hot-Swap Guard)

## What happened
Implemented the optional 11-step Firmware Update (`Update Software`) workflow at the end of the Windows PyQt6 ONT configuration sequence (`plans/261026-1420-windows-firmware-update-step/plan.md`) using TDD and `kongming` advisory checkpoints across all 3 phases:
- **Phase 1 (`app/core/config_models.py`, `app/core/ont_driver.py`):** Added `enable_firmware_update`, `firmware_path`, and `firmware_upload_timeout` to `GlobalConfig`. Implemented `ONTDriver.update_firmware()` executing the 3-step LuCI CGI protocol (`updatefwsessioncheck.json` -> `imagesize.json` -> `uploadsoftware.conf` multipart upload with `"Content-Type": None`). Applied Red Team protections: sanitized multipart filename (`RT-05`), checked `ramFree > 0` (`RT-04`), rotated `sessionKey` on error so `logout.cmd` cleans up failed uploads, and immediately cleared `self.session_key = ""` upon `status == "valid"` (`RT-01`) so `logout.cmd` is never sent into a flashing/rebooting ONT.
- **Phase 2 (`app/ui/main_window.py`, `app/ui/worker.py`, `app/core/network_probe.py`):** Added the `Cập Nhật Firmware Tự Động (Tùy Chọn)` group box on `MainWindow` (default unchecked, auto-detects newest `*.signed*` file by `mtime` in `get_app_base_dir()`), locked controls during worker execution, passed `dataclasses.replace(self.global_cfg)` snapshot to `ONTExecutionWorker` (`RT-06`), masked JSON `"sessionKey"` tokens (`RT-05`), and implemented `_awaiting_fw_reboot_disconnect` + `NetworkProbe.is_same_device_as_last_reset` (`RT-02`) to block premature re-runs during the 14s `uHTTPd` shutdown window and post-reboot re-appearance of the same ONT while allowing instant cable hot-swap to the next ONT.
- **Phase 3 (`tests/mock_luci_server.py`, `tests/test_*.py`, `README.md`, `HUONG_DAN_SU_DUNG_TOOL_ONT.md`):** Updated `MockLuCIHandler.do_POST` to parse raw binary `multipart/form-data` payloads via `email.parser.BytesParser` (`RT-03`) and added unit, UI, and E2E tests (74/74 passing).

## Decision
- Kept `chk_enable_fw_update` unchecked (`False`) on startup and persisted across the session while pre-filling `txt_fw_path` with the newest `*.signed*` file in `get_app_base_dir()`.
- Set `_awaiting_fw_reboot_disconnect` in `MainWindow._on_worker_finished` prior to `probe_worker.resume()` (per `KongmingPhase1` counsel) and placed `_auto_detect_default_firmware()` after action button initialization in `_build_ui()`.

## Next steps
- Package Windows executable via `build_windows.py` when distributing to field technicians.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
