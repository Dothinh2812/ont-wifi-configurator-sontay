import SwiftUI

public struct WifiGuideSheet: View {
    @ObservedObject var viewModel: MainViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var ontSsidInput: String = ""
    @State private var selectedQuickBand: WifiBand = .band24GHz
    @State private var isConnectingQuickOnt: Bool = false
    @State private var quickConnectError: String? = nil


    public init(viewModel: MainViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    statusCard

                    if viewModel.hasActiveVpn {
                        vpnWarningCard
                    }
                    quickConnectCard


                    bandSelectorCard

                    guideStepsCard

                    VStack(spacing: 12) {
                        Button {
                            viewModel.wifiMonitor.openSystemWifiSettings()
                        } label: {
                            Label("Mở Cài Đặt Wi-Fi iOS", systemImage: "wifi")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                        }
                        .buttonStyle(.borderedProminent)

                        Button {
                            Task {
                                _ = await viewModel.wifiMonitor.awaitWifiIpv4Ready(maxWaitMs: 1200)
                                let ok = await viewModel.performGatewayProbeOnce()
                                if ok {
                                    dismiss()
                                }
                            }
                        } label: {
                            Label("Kiểm tra lại kết nối Gateway (192.168.1.1)", systemImage: "arrow.clockwise.circle.fill")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .padding()
            }
            .navigationTitle("Kết nối Wi-Fi ONT")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Đóng") {
                        dismiss()
                    }
                }
            }
        }
    }

    private var quickConnectCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "bolt.badge.automatic.fill")
                    .foregroundColor(.blue)
                    .font(.title3)
                Text("Bắt nhanh sóng xuất xưởng ONT")
                    .font(.subheadline.weight(.bold))
            }

            Text("Sóng xuất xưởng của ONT iGate GW040-NSv2 luôn là mạng Open (không mật khẩu) có định dạng 'GW040-NSv2_2.4G_XXXXXX' (hoặc 5G_...). Nhập 6 ký tự MAC cuối trên tem dán thiết bị (VD: A1B2C3 hoặc A1:B2:C3):")
                .font(.caption)
                .foregroundColor(.secondary)

            TextField("Nhập 6 ký tự MAC từ tem dán (VD: A1B2C3)", text: $ontSsidInput)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled(true)
                .textInputAutocapitalization(.characters)

            Picker("Băng tần xuất xưởng", selection: $selectedQuickBand) {
                Text("2.4GHz").tag(WifiBand.band24GHz)
                Text("5GHz").tag(WifiBand.band5GHz)
            }
            .pickerStyle(.segmented)

            if let err = quickConnectError {
                HStack(alignment: .top, spacing: 6) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(.red)
                    Text(err)
                        .font(.caption)
                        .foregroundColor(.red)
                }
                .padding(6)
                .background(RoundedRectangle(cornerRadius: 6).fill(Color.red.opacity(0.12)))
            }

            Button {
                Task {
                    isConnectingQuickOnt = true
                    quickConnectError = nil
                    defer { isConnectingQuickOnt = false }

                    let normResult = IosWifiMonitor.normalizeQuickOntSsid(ontSsidInput, band: selectedQuickBand)
                    switch normResult {
                    case .failure(let err):
                        quickConnectError = err.message
                    case .success(let targetSsid):
                        let res = await viewModel.quickConnectOntWifi(ssid: targetSsid)
                        switch res {
                        case .joined:
                            dismiss()
                        case .entitlementUnavailable(let reason):
                            quickConnectError = reason
                        case .transientFailure(let reason):
                            quickConnectError = reason
                        }
                    }
                }
            } label: {
                HStack(spacing: 8) {
                    if isConnectingQuickOnt {
                        ProgressView()
                            .controlSize(.small)
                        Text("Đang kết nối sóng xuất xưởng ONT...")
                    } else {
                        Image(systemName: "link.badge.plus")
                        Text("Kết nối sóng xuất xưởng (Mạng Open - Không pass)")
                    }
                }
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
            }
            .buttonStyle(.borderedProminent)
            .disabled(isConnectingQuickOnt || ontSsidInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.blue.opacity(0.08)))
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Circle()
                    .fill(viewModel.isOntGatewayOnline ? Color.green : Color.orange)
                    .frame(width: 12, height: 12)
                Text(
                    viewModel.isOntGatewayOnline
                        ? "ONT Gateway (192.168.1.1): ONLINE — Sẵn sàng"
                        : "ONT Gateway (192.168.1.1): Chưa kết nối"
                )
                .font(.headline)
            }
            Text("IP Wi-Fi iPhone (en0): \(viewModel.en0IpAddress.isEmpty ? "Chưa có IP" : viewModel.en0IpAddress)")
                .font(.subheadline)
                .foregroundColor(.secondary)
            Text("Lưu lượng 192.168.1.1 được khóa cứng qua Wi-Fi en0 (allowsCellularAccess = false), bạn không cần tắt 4G/5G.")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(viewModel.isOntGatewayOnline ? Color.green.opacity(0.12) : Color.orange.opacity(0.12))
        )
    }

    private var vpnWarningCard: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(.red)
            VStack(alignment: .leading, spacing: 4) {
                Text("Phát hiện VPN đang bật (utun)")
                    .font(.subheadline.weight(.bold))
                    .foregroundColor(.red)
                Text("Vui lòng tắt ứng dụng VPN (1.1.1.1 / WireGuard) để không chặn kết nối nội bộ 192.168.1.1.")
                    .font(.caption)
            }
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.red.opacity(0.12)))
    }

    private var bandSelectorCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Băng tần Wi-Fi ONT iPhone đang bắt:")
                .font(.subheadline.weight(.semibold))
            Picker("Băng tần kết nối", selection: $viewModel.connectedBand) {
                Text("Đang bắt 2.4GHz").tag(WifiBand.band24GHz)
                Text("Đang bắt 5GHz").tag(WifiBand.band5GHz)
            }
            .pickerStyle(.segmented)
            Text("Quy trình 2 lần kết nối tự động: Lần 1 cấu hình Wi-Fi phụ trước -> Bước 7 chuyển kết nối sang Wi-Fi phụ vừa đổi -> Lần 2 đổi nốt Wi-Fi còn lại và nạp Firmware mới ở bước cuối.")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding()
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(UIColor.secondarySystemBackground)))
    }

    private var guideStepsCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Hướng dẫn bắt sóng ONT trên iOS:")
                .font(.subheadline.weight(.semibold))
            Text("1. Quét mã QR Wi-Fi dưới đáy modem bằng Camera iOS hoặc bấm 'Mở Cài Đặt Wi-Fi iOS'.")
                .font(.caption)
            Text("2. Chọn sóng mặc định: GW040-NSv2_2.4G_XXXXXX (hoặc 5G) và nhập mật khẩu in ở mặt sau ONT.")
                .font(.caption)
            Text("3. Quay lại ứng dụng bấm RUN — ở Bước 7 khi có thông báo, chọn sang tên Wi-Fi phụ mới để ứng dụng tự hoàn tất nốt Wi-Fi còn lại & Firmware.")
                .font(.caption)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(UIColor.secondarySystemBackground)))
    }
}
