import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

public enum IosDiagnosticLogFilter: String, CaseIterable, Identifiable {
    case all = "Tất cả"
    case warnAndError = "Cảnh báo & Lỗi"
    case wifiAndHandover = "Wi-Fi & Handover"

    public var id: String { rawValue }
}

private let iosHandoverTags: Set<String> = ["WIFI", "HANDOVER", "STEP6", "STEP7", "VERIFY", "AUTH"]

public struct DiagnosticLogSheet: View {
    @ObservedObject var viewModel: MainViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var selectedFilter: IosDiagnosticLogFilter = .all
    @State private var copiedBannerVisible = false

    public init(viewModel: MainViewModel) {
        self.viewModel = viewModel
    }

    private var filteredEntries: [DiagnosticLogEntry] {
        switch selectedFilter {
        case .all:
            return viewModel.diagnosticLogs
        case .warnAndError:
            return viewModel.diagnosticLogs.filter { $0.level != .info }
        case .wifiAndHandover:
            return viewModel.diagnosticLogs.filter { entry in
                iosHandoverTags.contains(entry.tag) ||
                    entry.message.localizedCaseInsensitiveContains("Wi-Fi") ||
                    entry.message.localizedCaseInsensitiveContains("DHCP")
            }
        }
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: 10) {
                Picker("Bộ lọc nhật ký", selection: $selectedFilter) {
                    ForEach(IosDiagnosticLogFilter.allCases) { mode in
                        Text(mode.rawValue).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 12)
                .padding(.top, 8)

                HStack {
                    Text("Hiển thị \(filteredEntries.count)/\(viewModel.diagnosticLogs.count) dòng (đã ẩn mật khẩu)")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    Spacer()
                    if copiedBannerVisible {
                        Text("Đã chép (tự xóa sau 180s)")
                            .font(.caption2.weight(.semibold))
                            .foregroundColor(.green)
                    }
                }
                .padding(.horizontal, 14)

                ScrollViewReader { proxy in
                    ZStack {
                        RoundedRectangle(cornerRadius: 10)
                            .fill(Color(red: 0.06, green: 0.09, blue: 0.16))

                        if filteredEntries.isEmpty {
                            Text("Chưa có dòng nhật ký nào khớp bộ lọc hiện tại.")
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundColor(.gray)
                                .padding()
                        } else {
                            ScrollView {
                                LazyVStack(alignment: .leading, spacing: 4) {
                                    ForEach(filteredEntries) { entry in
                                        Text(entry.formatLine())
                                            .font(.system(size: 11, design: .monospaced))
                                            .foregroundColor(colorForLevel(entry.level))
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                            .id(entry.id)
                                    }
                                }
                                .padding(8)
                            }
                        }
                    }
                    .padding(.horizontal, 12)

                    VStack(spacing: 8) {
                        HStack(spacing: 8) {
                            Button {
                                if let lastId = filteredEntries.last?.id {
                                    withAnimation {
                                        proxy.scrollTo(lastId, anchor: .bottom)
                                    }
                                }
                            } label: {
                                Label("Cuộn cuối", systemImage: "arrow.down.to.line")
                                    .font(.caption.weight(.semibold))
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)

                            Button {
                                viewModel.copyDiagnosticLogsToPasteboard()
                                copiedBannerVisible = true
                            } label: {
                                Label("Chép Log", systemImage: "doc.on.doc")
                                    .font(.caption.weight(.semibold))
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)

                            Button(role: .destructive) {
                                viewModel.clearDiagnosticLogs()
                            } label: {
                                Label("Xóa Log", systemImage: "trash")
                                    .font(.caption.weight(.semibold))
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)
                        }

                        HStack(spacing: 10) {
                            Button {
                                shareDiagnosticLogTempFile()
                            } label: {
                                Label("Chia sẻ (.txt) qua Zalo", systemImage: "square.and.arrow.up")
                                    .font(.subheadline.weight(.semibold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 4)
                            }
                            .buttonStyle(.borderedProminent)

                            Button {
                                dismiss()
                            } label: {
                                Text("Đóng")
                                    .font(.subheadline.weight(.semibold))
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 4)
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.bottom, 10)
                }
            }
            .navigationTitle("Nhật ký Tiến trình & Debug")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func colorForLevel(_ level: LogLevel) -> Color {
        switch level {
        case .error:
            return Color(red: 1.0, green: 0.54, blue: 0.50)
        case .warn:
            return Color(red: 1.0, green: 0.84, blue: 0.31)
        case .info:
            return Color(red: 0.89, green: 0.91, blue: 0.94)
        }
    }

    private func shareDiagnosticLogTempFile() {
        #if canImport(UIKit)
        do {
            let tempURL = try viewModel.exportDiagnosticLogToTempFile()
            DiagnosticLogSharePresenter.presentShareSheet(for: tempURL)
        } catch {
            viewModel.statusMessage = "Lỗi xuất Nhật ký Debug (.txt): \(error.localizedDescription)"
        }
        #endif
    }
}

#if canImport(UIKit)
enum DiagnosticLogSharePresenter {
    @MainActor
    static func presentShareSheet(for tempURL: URL) {
        let activityVC = UIActivityViewController(activityItems: [tempURL], applicationActivities: nil)
        activityVC.completionWithItemsHandler = { _, _, _, _ in
            try? FileManager.default.removeItem(at: tempURL)
        }
        if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let rootVC = scene.windows.first?.rootViewController {
            var topVC = rootVC
            while let presented = topVC.presentedViewController {
                topVC = presented
            }
            if let popover = activityVC.popoverPresentationController {
                popover.sourceView = topVC.view
                popover.sourceRect = CGRect(
                    x: topVC.view.bounds.midX,
                    y: topVC.view.bounds.midY,
                    width: 1,
                    height: 1
                )
                popover.permittedArrowDirections = []
            }
            topVC.present(activityVC, animated: true)
        }
    }
}
#endif
