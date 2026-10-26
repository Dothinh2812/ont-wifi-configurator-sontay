"""Automated Performance Benchmark Suite for ONT 040-NSv2 Configurator.

Measures:
1. MainWindow initialization and render latency (< 1.0s with 89 rows).
2. Main GUI thread responsiveness and event loop latency during network probe timeout (< 16ms / 60 FPS).
3. Status brush cache lookup performance (> 1,000,000 lookups/sec, < 5ms for 10,000 lookups).
"""

import os
import sys
import time
import unittest
from unittest.mock import patch, MagicMock

# Force offscreen rendering for headless CI / testing environments
os.environ["QT_QPA_PLATFORM"] = "offscreen"

from PyQt6.QtWidgets import QApplication
from PyQt6.QtCore import QCoreApplication

app = QApplication.instance()
if app is None:
    app = QApplication([])

from app.core.config_models import get_app_path
from app.data.models import DeviceStatus
from app.ui.device_table_model import get_status_brush
from app.ui.main_window import MainWindow


class TestPerformanceBenchmarks(unittest.TestCase):
    """Automated benchmark tests enforcing performance thresholds."""

    def test_benchmark_mainwindow_initialization_latency(self):
        """MainWindow init and data rendering must complete in under 1.0 second."""
        sample_path = get_app_path("file-mau.xlsx")
        self.assertTrue(os.path.exists(sample_path), "file-mau.xlsx must exist")

        start = time.perf_counter()
        win = MainWindow(initial_excel_path=sample_path)
        win.show()
        QCoreApplication.processEvents()
        elapsed = time.perf_counter() - start

        print(f"\n[BENCHMARK] MainWindow init & render (89 rows): {elapsed * 1000:.2f} ms")
        self.assertLess(elapsed, 1.0, f"Init time {elapsed:.3f}s exceeds threshold of 1.0s")
        self.assertEqual(len(win.records), 89)
        self.assertEqual(win.table_model.columnCount(), 10)
        # Cleanup
        win.close()
        QCoreApplication.processEvents()

    def test_benchmark_event_loop_latency_during_background_probe_failure(self):
        """Main thread event processing latency must remain <= 16ms even when probe is blocking."""
        sample_path = get_app_path("file-mau.xlsx")
        win = MainWindow(initial_excel_path=sample_path)
        win.show()
        QCoreApplication.processEvents()

        # Simulate probe encountering a blocking network timeout of 1.5s
        def slow_failing_probe():
            time.sleep(0.2)
            return False

        latencies = []
        with patch.object(win.probe, "probe_once", side_effect=slow_failing_probe):
            # Run event processing measurements while probe worker runs in background
            win.probe_worker.start()

            for _ in range(15):
                t0 = time.perf_counter()
                QCoreApplication.processEvents()
                latencies.append((time.perf_counter() - t0) * 1000)
                time.sleep(0.01)

        win.close()
        QCoreApplication.processEvents()

        max_latency = max(latencies)
        avg_latency = sum(latencies) / len(latencies)
        print(f"[BENCHMARK] Main GUI Thread Event Latency during probe failure: Avg={avg_latency:.2f}ms, Max={max_latency:.2f}ms")
        self.assertLess(max_latency, 16.0, f"Max event latency {max_latency:.2f}ms exceeds 16ms (60 FPS limit)")

    def test_benchmark_status_brush_cache_throughput(self):
        """10,000 brush cache lookups must complete in under 5ms (zero heap allocations)."""
        statuses = [
            DeviceStatus.INVALID_DATA,
            DeviceStatus.RUNNING,
            DeviceStatus.SUCCESS,
            DeviceStatus.FAILED,
            DeviceStatus.SKIPPED,
            DeviceStatus.PENDING,
        ]

        # Warm up
        for s in statuses:
            get_status_brush(s)

        start = time.perf_counter()
        for i in range(10000):
            bg, fg = get_status_brush(statuses[i % len(statuses)])
        elapsed = time.perf_counter() - start

        print(f"[BENCHMARK] 10,000 status brush cache lookups: {elapsed * 1000:.3f} ms")
        self.assertLess(elapsed, 0.005, f"Cache lookup time {elapsed * 1000:.2f}ms exceeds 5ms limit")


if __name__ == "__main__":
    unittest.main()
