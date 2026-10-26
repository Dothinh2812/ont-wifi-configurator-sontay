"""Background worker thread for executing ONT configuration sequence."""

import re
import datetime
import threading
from typing import Optional, Dict, Any
from PyQt6.QtCore import QThread, pyqtSignal

from app.core.network_probe import NetworkProbe

from app.core.config_models import DeviceConfig, GlobalConfig
from app.core.ont_driver import ONTDriver


def mask_sensitive_credentials(text: str) -> str:
    """Mask sensitive passwords and tokens in logs to prevent accidental exposure."""
    if not isinstance(text, str):
        text = str(text or "")

    patterns = [
        (r"(pppPassword=)([^&\s]+)", r"\1***"),
        (r"(pass=)([^&\s]+)", r"\1***"),
        (r"(newPass=)([^&\s]+)", r"\1***"),
        (r"(oldPass=)([^&\s]+)", r"\1***"),
        (r"(wl_wpa_psk=)([^&\s]+)", r"\1***"),
        (r"(password=)([^&\s]+)", r"\1***"),
        (r"(sessionKey=)([^&\s]+)", r"\1***"),
        (r"(data=)([^&\s\x7f]+)", r"\1***"),
    ]

    for pattern, replacement in patterns:
        text = re.sub(pattern, replacement, text, flags=re.IGNORECASE)
    return text


class ONTExecutionWorker(QThread):
    """Executes the 10-step ONT configuration sequence in a non-blocking thread."""

    step_progress = pyqtSignal(str, int, int, str, str)  # (name, current, total, status, detail)
    log_message = pyqtSignal(str, str)                    # (timestamp, masked_text)
    finished = pyqtSignal(bool, str, dict)                # (success, message, result_dict)
    probe_pause_requested = pyqtSignal()
    probe_resume_requested = pyqtSignal()

    def __init__(
        self,
        device_cfg: DeviceConfig,
        global_cfg: GlobalConfig,
        parent: Optional[Any] = None,
    ):
        super().__init__(parent)
        self.device_cfg = device_cfg
        self.global_cfg = global_cfg
        self._is_cancelled = False
        self.driver: Optional[ONTDriver] = None

    def cancel(self) -> None:
        """Flag worker to abort and trigger cleanup in-thread."""
        self._is_cancelled = True
        # Red Team Finding 5: Do NOT call self.driver.logout() here on caller thread!
        # Logout is safely executed inside worker thread's run() finally block.

    def emit_log(self, raw_text: str) -> None:
        """Emit a masked log message with timestamp."""
        now_str = datetime.datetime.now().strftime("%H:%M:%S")
        masked = mask_sensitive_credentials(raw_text)
        self.log_message.emit(now_str, masked)

    def run(self) -> None:
        """Run the configuration workflow with strict pause/resume coordination."""
        self.emit_log(f"Bắt đầu nạp cấu hình cho thiết bị {self.device_cfg.slid}...")
        self.probe_pause_requested.emit()

        success = False
        message = ""
        result: Dict[str, Any] = {}

        try:
            self.driver = ONTDriver(
                base_url=self.global_cfg.base_url,
                timeout=self.global_cfg.request_timeout,
            )

            def progress_callback(
                step_name: str, step_num: int, total_steps: int, status: str, detail: str
            ):
                if self._is_cancelled:
                    raise RuntimeError("Tiến trình đã bị người dùng hủy bỏ (Cancelled).")
                self.step_progress.emit(step_name, step_num, total_steps, status, detail)
                self.emit_log(f"[Bước {step_num}/{total_steps}] {step_name}: {status} - {detail}")

            result = self.driver.execute_full_setup(
                device_cfg=self.device_cfg,
                global_cfg=self.global_cfg,
                progress_cb=progress_callback,
            )
            success = True
            message = "Cấu hình thành công 10/10 bước!"
            self.emit_log(f"Hoàn tất cấu hình thành công cho thiết bị {self.device_cfg.slid}.")

        except Exception as e:
            success = False
            message = str(e)
            self.emit_log(f"LỖI trong quá trình cấu hình: {e}")
            result = {
                "error": mask_sensitive_credentials(str(e)),
                "slid": self.device_cfg.slid,
                "so_may": self.device_cfg.slid,
            }

        finally:
            # Guarantees:
            # 1. Driver logout already handled in driver.execute_full_setup, but call logout again if not
            if self.driver:
                try:
                    self.driver.logout()
                except Exception:
                    pass
            # 2. Network probe resumed immediately
            self.probe_resume_requested.emit()
            # 3. Finished signal emitted to UI
            self.finished.emit(success, message, result)

class ProbeWorker(QThread):
    """Background worker thread executing continuous network probing without blocking GUI."""

    status_changed = pyqtSignal(bool, int)

    def __init__(
        self,
        probe: NetworkProbe,
        parent: Optional[Any] = None,
        interval_ms: int = 1500,
    ):
        super().__init__(parent)
        self.probe = probe
        self.interval_ms = interval_ms
        self._is_stopped = False
        self._is_paused = False
        self._wake_requested = False
        self._probe_lock = threading.Lock()

    @property
    def is_paused(self) -> bool:
        return self._is_paused

    def pause(self, timeout: float = 3.0) -> None:
        """Pause probing with barrier synchronization.

        Waits for any in-flight probe_once HTTP request to complete before returning,
        preventing concurrent socket contention on ONT uHTTPd server.
        """
        self._is_paused = True
        self.probe.pause()
        acquired = self._probe_lock.acquire(timeout=timeout)
        if acquired:
            self._probe_lock.release()

    def resume(self) -> None:
        """Resume network probing with fresh probe state and session."""
        if not self._is_paused and not self.probe.is_paused:
            return
        with self._probe_lock:
            self.probe.reset()
            self.probe.resume()
            self._is_paused = False
            self._wake_requested = True
        self.status_changed.emit(self.probe.is_online, self.probe._consecutive_success)

    def stop(self) -> None:
        """Signal background loop to terminate cleanly."""
        self._is_stopped = True
        self._is_paused = True
        self._wake_requested = True

    def run_iteration(self) -> None:
        """Execute a single probe iteration safely inside lock."""
        if self._is_stopped or self._is_paused:
            return
        with self._probe_lock:
            if not self._is_stopped and not self._is_paused:
                online = self.probe.probe_once()
                self.status_changed.emit(online, self.probe._consecutive_success)

    def run(self) -> None:
        """Continuous polling loop in background thread."""
        while not self._is_stopped:
            self._wake_requested = False
            self.run_iteration()

            elapsed = 0
            while (
                elapsed < self.interval_ms
                and not self._is_stopped
                and not self._wake_requested
            ):
                sleep_chunk = min(100, self.interval_ms - elapsed)
                self.msleep(sleep_chunk)
                elapsed += sleep_chunk
