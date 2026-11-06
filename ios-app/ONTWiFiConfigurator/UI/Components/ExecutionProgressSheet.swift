import SwiftUI
#if canImport(UIKit)
import UIKit
import UniformTypeIdentifiers
#endif

public struct ExecutionProgressSheet: View {
    @ObservedObject var viewModel: MainViewModel
    let onShareExcelReport: () -> Void

    @State private var copiedWifiPassToast = false
    @State private var rebootCountdownSec: Int = 150
    @State private var isLogPanelExpanded: Bool = false
    @State private var showFullLogSheet: Bool = false

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

                    if let prompt = viewModel.handoverPrompt, viewModel.isRunningSetup {
                        step7HandoverPromptCard(prompt)
                    }

                    VStack(spacing: 10) {
                        ForEach(viewModel.stepProgressList) { step in
                            stepRow(step)
                        }
                    }

                    if let result = viewModel.setupResult, result.success {
                        if result.firmwareUpdated {
                            firmwareRebootCountdownCard(result)
                        }
                        successSummaryCard(result)
                    }

                    if let errorMsg = viewModel.executionError {
                        errorCard(errorMsg)
                    }

                    diagnosticLogPanelCard

                    if !viewModel.isRunningSetup {
                        actionButtons
                    }
                }
                .padding()
            }
            .navigationTitle("Tiến trình cấu hình ONT")
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(viewModel.isRunningSetup)
            .onChange(of: viewModel.executionError) { newErr in
                if let err = newErr, !err.isEmpty {
                    isLogPanelExpanded = true
                }
            }
            .onAppear {
                if let err = viewModel.executionError, !err.isEmpty {
                    isLogPanelExpanded = true
                }
            }
            .sheet(isPresented: $showFullLogSheet) {
                DiagnosticLogSheet(viewModel: viewModel)
            }
            .task(id: viewModel.setupResult?.firmwareUpdated == true && viewModel.setupResult?.success == true) {
                guard let res = viewModel.setupResult, res.success, res.firmwareUpdated else { return }
                rebootCountdownSec = 150
                while rebootCountdownSec > 0 && !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                    if Task.isCancelled { break }
                    rebootCountdownSec -= 1
                }
            }
        }
    }

    private var headerBanner: some View {
        let totalSteps = viewModel.stepProgressList.first?.totalSteps ?? viewModel.stepProgressList.count
        return HStack(spacing: 12) {
            if viewModel.isRunningSetup {
                ProgressView()
                Text("Đang đổ cấu hình qua quy trình 2 lần kết nối... Vui lòng giữ ứng dụng.")
                    .font(.subheadline.weight(.semibold))
            } else if viewModel.setupResult?.success == true {
                Image(systemName: "checkmark.seal.fill")
                    .foregroundColor(.green)
                    .font(.title2)
                Text("Hoàn tất \(totalSteps)/\(totalSteps) bước cấu hình ONT thành công!")
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

    private func step7HandoverPromptCard(_ prompt: HandoverPromptState) -> some View {
        let secMode = WifiSecurityMode(rawValue: prompt.securityMode) ?? .wpa2Wpa3Psk
        let requiresPass = secMode.requiresPassword && !prompt.targetPass.isEmpty
        return TimelineView(.periodic(from: .now, by: 1.0)) { context in
            let remainingSec = max(0, Int(prompt.deadline.timeIntervalSince(context.date)))
            let mins = remainingSec / 60
            let secs = remainingSec % 60
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: "wifi.circle.fill")
                        .foregroundColor(.blue)
                        .font(.title3)
                    Text("Bước 7: Chuyển sang Wi-Fi phụ mới (\(prompt.band.label))")
                        .font(.subheadline.weight(.bold))
                        .foregroundColor(.blue)
                }
                Text("ONT đang phát sóng mới & ổn định dịch vụ WPA/DHCP (tổng ngân sách 240s, tự động retry giãn cách 5s → 10s → 15s → 20s). Hãy chọn mạng dưới đây trong Cài đặt Wi-Fi iOS rồi quay lại ứng dụng — Bước 8 & 9 sẽ tự chạy tiếp:")
                    .font(.caption)
                Text("• Tên Wi-Fi cần bắt: \(prompt.targetSsid)")
                    .font(.subheadline.weight(.semibold))
                Text("• Mật khẩu: \(requiresPass ? prompt.targetPass : "Không mật khẩu (Open)")")
                    .font(.subheadline.monospaced())
                Text(String(format: "⏳ Thời gian chờ tự động còn lại: %02d:%02d (%ds)", mins, secs, remainingSec))
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.orange)

                HStack(spacing: 8) {
                    if requiresPass {
                        Button {
                            copyPasswordLocalExpiring(prompt.targetPass)
                            copiedWifiPassToast = true
                        } label: {
                            Label("Chép mật khẩu", systemImage: "doc.on.doc")
                                .font(.caption.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 6)
                        }
                        .buttonStyle(.bordered)
                    }

                    Button {
                        viewModel.wifiMonitor.openSystemWifiSettings()
                    } label: {
                        Label("Mở Cài Đặt Wi-Fi iOS", systemImage: "gear")
                            .font(.caption.weight(.bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding()
            .background(RoundedRectangle(cornerRadius: 12).fill(Color.blue.opacity(0.12)))
        }
    }

    private func firmwareRebootCountdownCard(_ result: MobileSetupResult) -> some View {
        let mins = rebootCountdownSec / 60
        let secs = rebootCountdownSec % 60
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "arrow.triangle.2.circlepath.circle.fill")
                    .foregroundColor(.orange)
                    .font(.title3)
                Text("ONT Đang Tự Ghi Flash & Khởi Động Lại (~2 phút 20 giây)")
                    .font(.subheadline.weight(.bold))
                    .foregroundColor(.orange)
            }
            if rebootCountdownSec > 0 {
                Text(String(format: "⏳ Đếm ngược tham khảo: còn %02d:%02d (%ds) — Tuyệt đối không tắt nguồn ONT!", mins, secs, rebootCountdownSec))
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.orange)
            } else {
                Text("✅ Đã qua 2 phút 30 giây — ONT đã có thể hoàn tất khởi động lại với Firmware mới (\(result.firmwareFileName))!")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.green)
            }
            Text("• Cả 2 băng tần Wi-Fi (\(result.newSsid24g) / \(result.newSsid5g)) và Firmware (\(result.firmwareFileName)) đã hoàn tất 100%. Bạn có thể xuất báo cáo Excel ngay bên dưới.")
                .font(.caption)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.orange.opacity(0.14)))
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

    private var diagnosticLogPanelCard: some View {
        let recentLogs = Array(viewModel.diagnosticLogs.suffix(200))
        return VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation {
                    isLogPanelExpanded.toggle()
                }
            } label: {
                HStack {
                    Text("📋 Nhật ký tiến trình & Debug (\(viewModel.diagnosticLogs.count))")
                        .font(.subheadline.weight(.bold))
                        .foregroundColor(.primary)
                    Spacer()
                    Image(systemName: isLogPanelExpanded ? "chevron.up" : "chevron.down")
                        .foregroundColor(.secondary)
                }
            }
            .buttonStyle(.plain)

            if isLogPanelExpanded {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(Color(red: 0.06, green: 0.09, blue: 0.16))

                    if recentLogs.isEmpty {
                        Text("Chưa có dòng nhật ký nào trong phiên hiện tại.")
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundColor(.gray)
                            .padding(8)
                    } else {
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 3) {
                                ForEach(recentLogs) { entry in
                                    Text(entry.formatLine())
                                        .font(.system(size: 10, design: .monospaced))
                                        .foregroundColor(logColor(entry.level))
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                }
                            }
                            .padding(6)
                        }
                    }
                }
                .frame(height: 150)

                HStack(spacing: 8) {
                    Button {
                        showFullLogSheet = true
                    } label: {
                        Label("Mở rộng", systemImage: "arrow.up.left.and.arrow.down.right")
                            .font(.caption.weight(.semibold))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)

                    Button {
                        viewModel.copyDiagnosticLogsToPasteboard()
                    } label: {
                        Label("Chép Log", systemImage: "doc.on.doc")
                            .font(.caption.weight(.semibold))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)

                    Button {
                        #if canImport(UIKit)
                        if let url = try? viewModel.exportDiagnosticLogToTempFile() {
                            DiagnosticLogSharePresenter.presentShareSheet(for: url)
                        }
                        #endif
                    } label: {
                        Label("Chia sẻ (.txt)", systemImage: "square.and.arrow.up")
                            .font(.caption.weight(.semibold))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(UIColor.secondarySystemBackground)))
    }

    private func logColor(_ level: LogLevel) -> Color {
        switch level {
        case .error:
            return Color(red: 1.0, green: 0.54, blue: 0.50)
        case .warn:
            return Color(red: 1.0, green: 0.84, blue: 0.31)
        case .info:
            return Color(red: 0.89, green: 0.91, blue: 0.94)
        }
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
    @MainActor
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
