import SwiftUI
#if canImport(UIKit)
import UIKit
import UniformTypeIdentifiers
#endif

public struct ExecutionProgressSheet: View {
    @ObservedObject var viewModel: MainViewModel
    let onShareExcelReport: () -> Void

    @State private var copiedWifiPassToast = false

    public init(
        viewModel: MainViewModel,
        onShareExcelReport: @escaping () -> Void
    ) {
        self.viewModel = viewModel
        self.onShareExcelReport = onShareExcelReport
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    headerBanner

                    VStack(spacing: 10) {
                        ForEach(viewModel.stepProgressList) { step in
                            stepRow(step)
                        }
                    }

                    if let result = viewModel.setupResult, result.success {
                        successSummaryCard(result)
                    }

                    if let errorMsg = viewModel.executionError {
                        errorCard(errorMsg)
                    }

                    if !viewModel.isRunningSetup {
                        actionButtons
                    }
                }
                .padding()
            }
            .navigationTitle("Tiến trình cấu hình ONT")
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(viewModel.isRunningSetup)
        }
    }

    private var headerBanner: some View {
        HStack(spacing: 12) {
            if viewModel.isRunningSetup {
                ProgressView()
                Text("Đang đổ cấu hình 7 bước qua Wi-Fi ONT... Vui lòng giữ màn hình.")
                    .font(.subheadline.weight(.semibold))
            } else if viewModel.setupResult?.success == true {
                Image(systemName: "checkmark.seal.fill")
                    .foregroundColor(.green)
                    .font(.title2)
                Text("Hoàn tất 7/7 bước cấu hình ONT thành công!")
                    .font(.headline)
                    .foregroundColor(.green)
            } else {
                Image(systemName: "xmark.octagon.fill")
                    .foregroundColor(.red)
                    .font(.title2)
                Text("Cấu hình bị gián đoạn — Đã tự động giải phóng phiên (logout)")
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(.red)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(UIColor.secondarySystemBackground)))
    }

    private func stepRow(_ step: StepProgress) -> some View {
        HStack(alignment: .top, spacing: 10) {
            stepIcon(step.status)
                .frame(width: 22, height: 22)
            VStack(alignment: .leading, spacing: 3) {
                Text(step.title)
                    .font(.subheadline.weight(.semibold))
                if !step.detail.isEmpty {
                    Text(step.detail)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            Spacer()
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color(UIColor.tertiarySystemBackground)))
    }

    @ViewBuilder
    private func stepIcon(_ status: StepStatus) -> some View {
        switch status {
        case .pending:
            Image(systemName: "circle")
                .foregroundColor(.secondary)
        case .running:
            ProgressView()
        case .success:
            Image(systemName: "checkmark.circle.fill")
                .foregroundColor(.green)
        case .failed:
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundColor(.red)
        }
    }

    private func successSummaryCard(_ result: MobileSetupResult) -> some View {
        let secMode = WifiSecurityMode(rawValue: result.wifiSecurityMode) ?? .wpa2Wpa3Psk
        let isOpenWifi = secMode == .open
        return VStack(alignment: .leading, spacing: 8) {
            Text("Thông tin Wi-Fi mới của khách hàng:")
                .font(.subheadline.weight(.bold))
            Text("• Bảo mật Wi-Fi: \(secMode.displayLabel)")
                .font(.subheadline)
            Text("• Wi-Fi 2.4GHz: \(result.newSsid24g)")
                .font(.subheadline)
            Text("• Wi-Fi 5GHz: \(result.newSsid5g)")
                .font(.subheadline)
            Text("• Mật khẩu Wi-Fi: \(isOpenWifi ? "Không mật khẩu (Open)" : result.newPass24g)")
                .font(.subheadline.monospaced())

            if !isOpenWifi && !result.newPass24g.isEmpty {
                Button {
                    copyPasswordLocalExpiring(result.newPass24g)
                    copiedWifiPassToast = true
                } label: {
                    Label(
                        copiedWifiPassToast
                            ? "Đã sao chép mật khẩu Wi-Fi (tự xóa sau 120s)"
                            : "Sao chép mật khẩu Wi-Fi mới",
                        systemImage: "doc.on.doc.fill"
                    )
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.green.opacity(0.12)))
    }

    private func errorCard(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Chi tiết lỗi:")
                .font(.subheadline.weight(.bold))
                .foregroundColor(.red)
            Text(message)
                .font(.caption)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.red.opacity(0.12)))
    }

    private var actionButtons: some View {
        VStack(spacing: 10) {
            if viewModel.executionError != nil {
                Button {
                    viewModel.runOntConfiguration()
                } label: {
                    Label("Thử chạy lại (Smart Retry)", systemImage: "arrow.clockwise")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                }
                .buttonStyle(.borderedProminent)
            }

            Button {
                onShareExcelReport()
            } label: {
                Label("Xuất & Chia Sẻ Báo Cáo Excel (.xlsx)", systemImage: "square.and.arrow.up")
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
            }
            .buttonStyle(.bordered)

            Button {
                viewModel.dismissExecutionSheet()
            } label: {
                Text("Đóng")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
            }
            .buttonStyle(.bordered)
        }
    }

    /// Copy Wi-Fi password to UIPasteboard with `.localOnly: true` and 120s expiration
    /// so customer credentials do not leak over Universal Clipboard.
    private func copyPasswordLocalExpiring(_ password: String) {
        #if canImport(UIKit)
        UIPasteboard.general.setItems(
            [[UIPasteboard.typeAutomatic: password]],
            options: [
                .localOnly: true,
                .expirationDate: Date().addingTimeInterval(120)
            ]
        )
        #endif
    }
}
