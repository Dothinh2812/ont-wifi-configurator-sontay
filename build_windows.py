"""Build script to package VNPT ONT GW040-NSv2 Configurator into Windows executable."""

import os
import sys
import shutil
import subprocess
import argparse


def check_prerequisites():
    """Verify required packaging packages are installed."""
    print(">>> [1/4] Kiểm tra thư viện phụ thuộc...")
    required = ["PyQt6", "openpyxl", "requests", "urllib3"]
    for pkg in required:
        try:
            __import__(pkg)
            print(f"  ✓ {pkg}: OK")
        except ImportError:
            print(f"  ✗ THIẾU THƯ VIỆN: {pkg}")
            print(f"    Vui lòng chạy: pip install {pkg}")
            sys.exit(1)

    try:
        import PyInstaller
        print(f"  ✓ PyInstaller {PyInstaller.__version__}: OK")
    except ImportError:
        print("  ✗ THIẾU PYINSTALLER: vui lòng chạy 'pip install pyinstaller'")
        sys.exit(1)


def clean_build_artifacts():
    """Clean prior build and dist artifacts."""
    print(">>> [2/4] Dọn dẹp thư mục build cũ...")
    for folder in ["build", "dist", "__pycache__"]:
        if os.path.exists(folder):
            try:
                shutil.rmtree(folder)
                print(f"  ✓ Đã xóa {folder}/")
            except Exception as e:
                print(f"  ! Không thể xóa {folder}: {e}")


def build_executable(onefile: bool = False):
    """Run PyInstaller with ont_setup.spec or custom flags."""
    print(f">>> [3/4] Bắt đầu đóng gói ứng dụng (chế độ: {'--onefile' if onefile else '--onedir'})...")

    spec_file = "ont_setup.spec"
    if onefile:
        cmd = [
            sys.executable,
            "-m",
            "PyInstaller",
            "--noconfirm",
            "--clean",
            "--windowed",
            "--onefile",
            "--name",
            "ONT_040NS_Tool",
            "--add-data",
            f"file-mau.xlsx{os.pathsep}.",
            "--hidden-import",
            "PyQt6.sip",
            "--hidden-import",
            "openpyxl",
            "--hidden-import",
            "requests",
            "--hidden-import",
            "urllib3",
            "--noupx",
            "app/main.py",
        ]
    else:
        cmd = [sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", spec_file]

    print("  Lệnh thực thi:", " ".join(cmd))
    res = subprocess.run(cmd)
    if res.returncode != 0:
        print("  ✗ Đóng gói thất bại với mã lỗi:", res.returncode)
        sys.exit(res.returncode)

    print(">>> [4/4] Đóng gói thành công!")
    if onefile:
        exe_path = os.path.join("dist", "ONT_040NS_Tool.exe" if sys.platform == "win32" else "ONT_040NS_Tool")
        print(f"  ✓ File chạy độc lập: {exe_path}")
    else:
        dist_folder = os.path.join("dist", "ONT_040NS_Tool")
        print(f"  ✓ Thư mục ứng dụng hoàn chỉnh: {dist_folder}")

        # Copy create_desktop_shortcut.bat into dist folder
        shortcut_bat = "create_desktop_shortcut.bat"
        if os.path.exists(shortcut_bat):
            shutil.copy2(shortcut_bat, os.path.join(dist_folder, shortcut_bat))
            print(f"  ✓ Đã kèm script tạo shortcut: {shortcut_bat}")

        # Auto-create .zip package using shutil.make_archive
        zip_base = os.path.join("dist", "ONT_040NS_Tool")
        print(f"  >>> [Tự động nén Zip] Đang tạo {zip_base}.zip...")
        shutil.make_archive(zip_base, "zip", root_dir="dist", base_dir="ONT_040NS_Tool")
        zip_file = f"{zip_base}.zip"
        if os.path.exists(zip_file):
            size_mb = os.path.getsize(zip_file) / (1024 * 1024)
            print(f"  ✓ Gói zip hoàn tất: {zip_file} ({size_mb:.1f} MB)")
            print("  ✓ Hướng dẫn: Kỹ thuật viên giải nén (Extract All) và chạy ONT_040NS_Tool.exe.")

def main():
    parser = argparse.ArgumentParser(description="Đóng gói ứng dụng ONT Configurator cho Windows")
    parser.add_argument("--onefile", action="store_true", help="Đóng gói thành 1 file .exe duy nhất (mặc định dạng thư mục --onedir)")
    args = parser.parse_args()

    check_prerequisites()
    clean_build_artifacts()
    build_executable(onefile=args.onefile)


if __name__ == "__main__":
    main()
