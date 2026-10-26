import SwiftUI
import UniformTypeIdentifiers
#if canImport(UIKit)
import UIKit
#endif

public struct SubscriberListView: View {
    @ObservedObject var viewModel: MainViewModel
    @State private var isShowingFileImporter = false

    public init(viewModel: MainViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ontWifiStatusBanner
                    .padding(.horizontal, 12)
                    .padding(.top, 8)

                excelActionRow
                    .padding(.horizontal, 12)
                    .padding(.top, 8)

                searchAndFilterSection
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)

                if let msg = viewModel.statusMessage, !msg.isEmpty {
                    Text(msg)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.bottom, 4)
                }

                subscriberListContent

                FooterSignatureBar()
            }
            .navigationTitle("Cấu Hình ONT VNPT")
            .navigationBarTitleDisplayMode(.inline)
            .fileImporter(
                isPresented: $isShowingFileImporter,
                allowedContentTypes: [
                    UTType(filenameExtension: "xlsx") ?? .data
                ],
                allowsMultipleSelection: false
            ) { result in
                handleImportedFile(result)
            }
            .sheet(isPresented: $viewModel.showWifiGuideSheet) {
                WifiGuideSheet(viewModel: viewModel)
            }
            .sheet(isPresented: $viewModel.showExecutionSheet) {
                ExecutionProgressSheet(
                    viewModel: viewModel,
                    onShareExcelReport: { shareExcelReport() }
                )
            }
            .navigationDestination(
                isPresented: Binding(
                    get: { viewModel.selectedRecord != nil },
                    set: { if !$0 { viewModel.selectSubscriber(nil) } }
                )
            ) {
                ConfigReviewView(
                    viewModel: viewModel,
                    onShareExcelReport: { shareExcelReport() }
                )
            }
        }
    }

    private var ontWifiStatusBanner: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Circle()
                    .fill(viewModel.isOntGatewayOnline ? Color.green : Color.orange)
                    .frame(width: 10, height: 10)
                Text(
                    viewModel.isOntGatewayOnline
                        ? "ONLINE - Sẵn sàng đổ cấu hình (192.168.1.1)"
                        : "Chưa kết nối ONT (192.168.1.1)"
                )
                .font(.subheadline.weight(.bold))
                Spacer()
                Button("Bắt Wi-Fi ONT") {
                    viewModel.showWifiGuideSheet = true
                }
                .font(.caption.weight(.semibold))
                .buttonStyle(.borderedProminent)
            }

            HStack {
                Text("Wi-Fi en0: \(viewModel.en0IpAddress.isEmpty ? "Chưa có IP" : viewModel.en0IpAddress)")
                    .font(.caption)
                    .foregroundColor(.secondary)
                if viewModel.hasActiveVpn {
                    Text("• Cảnh báo VPN đang bật!")
                        .font(.caption.weight(.bold))
                        .foregroundColor(.red)
                }
                Spacer()
                Picker("Băng tần", selection: $viewModel.connectedBand) {
                    Text("2.4GHz").tag(WifiBand.band24GHz)
                    Text("5GHz").tag(WifiBand.band5GHz)
                }
                .pickerStyle(.segmented)
                .frame(width: 140)
            }
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(viewModel.isOntGatewayOnline ? Color.green.opacity(0.12) : Color(UIColor.secondarySystemBackground))
        )
    }

    private var excelActionRow: some View {
        HStack(spacing: 8) {
            Button {
                isShowingFileImporter = true
            } label: {
                Label("Nạp Excel (.xlsx)", systemImage: "tray.and.arrow.down.fill")
                    .font(.caption.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
            }
            .buttonStyle(.borderedProminent)

            Button {
                viewModel.loadDemoExcelFromBundle()
            } label: {
                Label("Nạp Mẫu Demo", systemImage: "doc.badge.gearshape")
                    .font(.caption.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
            }
            .buttonStyle(.bordered)

            Button {
                shareExcelReport()
            } label: {
                Label("Xuất Báo Cáo", systemImage: "square.and.arrow.up")
                    .font(.caption.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
            }
            .buttonStyle(.bordered)
            .disabled(viewModel.allRecords.isEmpty)
        }
    }

    private var searchAndFilterSection: some View {
        VStack(spacing: 8) {
            HStack {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.secondary)
                TextField(
                    "Tìm Account, SLID (0015...), Tên TB, SĐT...",
                    text: Binding(
                        get: { viewModel.searchQuery },
                        set: { viewModel.onSearchQueryChange($0) }
                    )
                )
                .autocorrectionDisabled(true)
                .textInputAutocapitalization(.never)
                if !viewModel.searchQuery.isEmpty {
                    Button {
                        viewModel.onSearchQueryChange("")
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.secondary)
                    }
                }
            }
            .padding(8)
            .background(RoundedRectangle(cornerRadius: 10).fill(Color(UIColor.secondarySystemBackground)))

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    filterChip(title: "Tất cả (\(viewModel.allRecords.count))", filter: nil)
                    filterChip(
                        title: "Chờ (\(viewModel.allRecords.filter { $0.status == .pending }.count))",
                        filter: .pending
                    )
                    filterChip(
                        title: "Xong (\(viewModel.allRecords.filter { $0.status == .success }.count))",
                        filter: .success
                    )
                    filterChip(
                        title: "Lỗi / Thiếu TT (\(viewModel.allRecords.filter { $0.status == .invalidData || $0.status == .failed }.count))",
                        filter: .invalidData
                    )
                }
            }
        }
    }

    private func filterChip(title: String, filter: SubscriberStatus?) -> some View {
        let isSelected = viewModel.selectedStatusFilter == filter
        return Button {
            viewModel.onStatusFilterChange(filter)
        } label: {
            Text(title)
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    Capsule()
                        .fill(isSelected ? Color.blue : Color(UIColor.secondarySystemBackground))
                )
                .foregroundColor(isSelected ? .white : .primary)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var subscriberListContent: some View {
        if viewModel.filteredRecords.isEmpty {
            VStack(spacing: 12) {
                Spacer()
                Image(systemName: "doc.text.magnifyingglass")
                    .font(.largeTitle)
                    .foregroundColor(.secondary)
                Text(
                    viewModel.allRecords.isEmpty
                        ? "Chưa có dữ liệu. Hãy bấm 'Nạp Excel (.xlsx)' hoặc 'Nạp Mẫu Demo'."
                        : "Không tìm thấy thuê bao khớp từ khóa."
                )
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                Spacer()
            }
            .padding()
        } else {
            List(viewModel.filteredRecords) { record in
                Button {
                    viewModel.selectSubscriber(record)
                } label: {
                    subscriberRow(record)
                }
                .buttonStyle(.plain)
            }
            .listStyle(.plain)
        }
    }

    private func subscriberRow(_ record: SubscriberRecord) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("\(record.accountPppoe.isEmpty ? "(Thiếu Account)" : record.accountPppoe) — SLID: \(record.slid)")
                    .font(.subheadline.weight(.bold))
                Spacer()
                Text(record.status.label)
                    .font(.caption2.weight(.bold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(statusColor(record.status).opacity(0.16))
                    .foregroundColor(statusColor(record.status))
                    .clipShape(Capsule())
            }
            Text("\(record.tenTb) • SĐT: \(record.soDt)")
                .font(.caption)
                .foregroundColor(.secondary)
            Text("Đ/C: \(record.diachiTb) • Wi-Fi: \(record.effectiveWifi24Ssid)")
                .font(.caption2)
                .foregroundColor(.secondary)
            if !record.validationErrors.isEmpty {
                Text("⚠ \(record.validationErrors.joined(separator: "; "))")
                    .font(.caption2)
                    .foregroundColor(.red)
            }
        }
        .padding(.vertical, 4)
    }

    private func statusColor(_ status: SubscriberStatus) -> Color {
        switch status {
        case .pending: return .blue
        case .running: return .orange
        case .success: return .green
        case .failed, .invalidData: return .red
        }
    }

    private func handleImportedFile(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            let accessed = url.startAccessingSecurityScopedResource()
            defer {
                if accessed {
                    url.stopAccessingSecurityScopedResource()
                }
            }
            if let data = try? Data(contentsOf: url) {
                viewModel.importExcelData(data)
            } else {
                viewModel.statusMessage = "Không thể đọc dữ liệu từ tệp Excel đã chọn."
            }
        case .failure(let error):
            viewModel.statusMessage = "Lỗi chọn tệp Excel: \(error.localizedDescription)"
        }
    }

    private func shareExcelReport() {
        #if canImport(UIKit)
        do {
            let tempURL = try viewModel.exportExcelReportToTempFile()
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
                activityVC.popoverPresentationController?.sourceView = topVC.view
                topVC.present(activityVC, animated: true)
            }
        } catch {
            viewModel.statusMessage = "Lỗi xuất báo cáo Excel: \(error.localizedDescription)"
        }
        #endif
    }
}
