import SwiftUI
import UniformTypeIdentifiers

public struct ConfigReviewView: View {
    @ObservedObject var viewModel: MainViewModel
    let onShareExcelReport: () -> Void

    @State private var showPppoePass = false
    @State private var showFirmwareImporter = false

    public init(
        viewModel: MainViewModel,
        onShareExcelReport: @escaping () -> Void
    ) {
        self.viewModel = viewModel
        self.onShareExcelReport = onShareExcelReport
    }

    public var body: some View {
        VStack(spacing: 0) {
            if let record = viewModel.selectedRecord {
                let preRunValidation = OntInputValidator.validateBeforeRun(
                    record,
                    settings: viewModel.globalSettings
                )

                Form {
                    subscriberInfoSection(record)

                    serviceParametersSection(record)

                    wifiConfigurationSection(record, validation: preRunValidation)

                    adminAndGatewaySection

                    firmwareUpdateSection

                    validationSummarySection(preRunValidation)

                    connectedBandAndActionSection(preRunValidation: preRunValidation)
                }
            } else {
                Text("Chưa chọn thuê bao.")
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            FooterSignatureBar()
        }
        .navigationTitle("Kiểm tra & Đổ cấu hình")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    viewModel.openDiagnosticLogSheet()
                } label: {
                    Image(systemName: "ladybug")
                }
                .accessibilityLabel("Nhật ký Debug")
            }
        }
        .fileImporter(
            isPresented: $showFirmwareImporter,
            allowedContentTypes: [.data, .item],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let selectedURL = urls.first {
                    viewModel.importFirmwareFile(from: selectedURL)
                }
            case .failure(let error):
                viewModel.statusMessage = "Lỗi chọn file Firmware: \(error.localizedDescription)"
            }
        }
    }

    private func subscriberInfoSection(_ record: SubscriberRecord) -> some View {
        Section("Thông tin hành chính thuê bao") {
            LabeledContent("Tên thuê bao", value: record.tenTb)
            LabeledContent("Địa chỉ", value: record.diachiTb)
            LabeledContent("Số điện thoại", value: record.soDt)
            LabeledContent("Tổ ĐB / NVKT", value: "\(record.toDiaBan) - \(record.nvkt)")
        }
    }

    private func serviceParametersSection(_ record: SubscriberRecord) -> some View {
        Section("Thông số dịch vụ WAN PPPoE & GPON SLID") {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("SLID / Mã MEN (GPON Password)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Spacer()
                    Text("\(record.slid.trimmingCharacters(in: .whitespacesAndNewlines).count)/10 ký tự")
                        .font(.caption2.monospacedDigit())
                        .foregroundColor(record.slid.count > 10 ? .red : .secondary)
                }
                TextField(
                    "SLID tối đa 10 ký tự ASCII",
                    text: Binding(
                        get: { record.slid },
                        set: { newVal in
                            var updated = record
                            let oldSlid = record.slid.trimmingCharacters(in: .whitespacesAndNewlines)
                            let oldSoMay = record.soMay.trimmingCharacters(in: .whitespacesAndNewlines)
                            let currentSsid24 = record.ssid24g.trimmingCharacters(in: .whitespacesAndNewlines)
                            let wasDefaultSsid = currentSsid24.isEmpty ||
                                currentSsid24 == "VNPT_\(oldSlid)" ||
                                currentSsid24 == "VNPT_\(oldSoMay)"

                            updated.slid = newVal
                            updated.soMay = newVal
                            if wasDefaultSsid {
                                let cleanNew = newVal.trimmingCharacters(in: .whitespacesAndNewlines)
                                updated.ssid24g = cleanNew.isEmpty ? "" : "VNPT_\(cleanNew)"
                                if viewModel.autoSyncWifi5g {
                                    updated.ssid5g = ExcelManager.deriveWifi5Ssid(
                                        ssid24g: updated.ssid24g,
                                        rawSsid5g: "",
                                        defaultSsid5g: viewModel.globalSettings.defaultWifi5Ssid
                                    )
                                }
                            }
                            viewModel.updateSelectedRecord(updated)
                        }
                    )
                )
                .autocorrectionDisabled(true)
                .textInputAutocapitalization(.never)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Account PPPoE (VLAN \(viewModel.globalSettings.vlanId))")
                    .font(.caption)
                    .foregroundColor(.secondary)
                TextField(
                    "Account PPPoE",
                    text: Binding(
                        get: { record.accountPppoe },
                        set: { newVal in
                            var updated = record
                            updated.accountPppoe = newVal
                            viewModel.updateSelectedRecord(updated)
                        }
                    )
                )
                .autocorrectionDisabled(true)
                .textInputAutocapitalization(.never)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Mật khẩu PPPoE (Bắt buộc)")
                    .font(.caption)
                    .foregroundColor(.secondary)
                HStack {
                    if showPppoePass {
                        TextField(
                            "Nhập mật khẩu PPPoE",
                            text: Binding(
                                get: { record.passPppoe },
                                set: { newVal in
                                    var updated = record
                                    updated.passPppoe = newVal
                                    viewModel.updateSelectedRecord(updated)
                                }
                            )
                        )
                        .autocorrectionDisabled(true)
                        .textInputAutocapitalization(.never)
                    } else {
                        SecureField(
                            "Nhập mật khẩu PPPoE",
                            text: Binding(
                                get: { record.passPppoe },
                                set: { newVal in
                                    var updated = record
                                    updated.passPppoe = newVal
                                    viewModel.updateSelectedRecord(updated)
                                }
                            )
                        )
                        .autocorrectionDisabled(true)
                        .textInputAutocapitalization(.never)
                    }
                    Button(showPppoePass ? "Ẩn" : "Hiện") {
                        showPppoePass.toggle()
                    }
                    .font(.caption.weight(.semibold))
                }
            }
        }
    }

    private func wifiConfigurationSection(
        _ record: SubscriberRecord,
        validation: ValidationResult
    ) -> some View {
        let selectedSecMode = WifiSecurityMode(rawValue: viewModel.globalSettings.wifiSecurityMode) ?? .wpa2Wpa3Psk
        return Section("Cấu hình Wi-Fi 2.4GHz & 5GHz") {
            Picker(
                "Chuẩn bảo mật Wi-Fi",
                selection: Binding(
                    get: { viewModel.globalSettings.wifiSecurityMode },
                    set: { newMode in
                        var s = viewModel.globalSettings
                        s.wifiSecurityMode = newMode
                        viewModel.updateGlobalSettings(s)
                    }
                )
            ) {
                ForEach(WifiSecurityMode.allCases, id: \.rawValue) { mode in
                    Text(mode.displayLabel).tag(mode.rawValue)
                }
            }
            .disabled(viewModel.isRunningSetup)

            Toggle(
                "Tự động đồng bộ Wi-Fi 5G theo 2.4G",
                isOn: Binding(
                    get: { viewModel.autoSyncWifi5g },
                    set: { viewModel.setAutoSyncWifi5g($0) }
                )
            )
            VStack(alignment: .leading, spacing: 4) {
                Text("Tên Wi-Fi 2.4GHz (SSID 1..32 ký tự ASCII)")
                    .font(.caption)
                    .foregroundColor(.secondary)
                TextField(
                    "SSID Wi-Fi 2.4GHz",
                    text: Binding(
                        get: { record.ssid24g },
                        set: { newVal in
                            var updated = record
                            updated.ssid24g = newVal
                            if viewModel.autoSyncWifi5g {
                                let cand = "\(newVal.trimmingCharacters(in: .whitespacesAndNewlines)) 5G"
                                updated.ssid5g = cand.count > 32
                                    ? String(cand.prefix(32)).trimmingCharacters(in: .whitespacesAndNewlines)
                                    : cand
                            }
                            viewModel.updateSelectedRecord(updated)
                        }
                    )
                )
                .autocorrectionDisabled(true)
                .textInputAutocapitalization(.never)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(selectedSecMode.requiresPassword ? "Mật khẩu Wi-Fi 2.4GHz (8..63 ký tự ASCII)" : "Mật khẩu Wi-Fi 2.4GHz (Open - Không mật khẩu)")
                    .font(.caption)
                    .foregroundColor(.secondary)
                HStack {
                    TextField(
                        "Mật khẩu Wi-Fi 2.4G",
                        text: Binding(
                            get: { record.pass24g },
                            set: { newVal in
                                var updated = record
                                updated.pass24g = newVal
                                if viewModel.autoSyncWifi5g {
                                    updated.pass5g = newVal
                                }
                                viewModel.updateSelectedRecord(updated)
                            }
                        )
                    )
                    .autocorrectionDisabled(true)
                    .textInputAutocapitalization(.never)
                }

                if validation.canPrependZero24g || validation.canPrependZero5g {
                    Button {
                        viewModel.prependLeadingZeroOnSelectedRecord()
                    } label: {
                        Label(
                            "Thêm số 0 ở đầu (0\(record.pass24g.trimmingCharacters(in: .whitespacesAndNewlines)))",
                            systemImage: "wand.and.stars"
                        )
                        .font(.caption.weight(.bold))
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.orange)
                    .padding(.top, 4)
                }
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Tên Wi-Fi 5GHz (SSID 1..32 ký tự ASCII)")
                    .font(.caption)
                    .foregroundColor(.secondary)
                TextField(
                    "SSID Wi-Fi 5GHz",
                    text: Binding(
                        get: { record.ssid5g },
                        set: { newVal in
                            var updated = record
                            updated.ssid5g = newVal
                            viewModel.updateSelectedRecord(updated)
                        }
                    )
                )
                .autocorrectionDisabled(true)
                .textInputAutocapitalization(.never)
                .disabled(viewModel.autoSyncWifi5g)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(selectedSecMode.requiresPassword ? "Mật khẩu Wi-Fi 5GHz (8..63 ký tự ASCII)" : "Mật khẩu Wi-Fi 5GHz (Open - Không mật khẩu)")
                    .font(.caption)
                    .foregroundColor(.secondary)
                TextField(
                    "Mật khẩu Wi-Fi 5G",
                    text: Binding(
                        get: { record.pass5g },
                        set: { newVal in
                            var updated = record
                            updated.pass5g = newVal
                            viewModel.updateSelectedRecord(updated)
                        }
                    )
                )
                .autocorrectionDisabled(true)
                .textInputAutocapitalization(.never)
                .disabled(viewModel.autoSyncWifi5g)
            }
        }
    }

    private var adminAndGatewaySection: some View {
        Section("Bảo mật Admin ONT") {
            VStack(alignment: .leading, spacing: 4) {
                Text("Mật khẩu Admin mới (8..32 ký tự: Hoa, thường, số, ký tự đặc biệt)")
                    .font(.caption)
                    .foregroundColor(.secondary)
                TextField(
                    "Mật khẩu Admin mới",
                    text: Binding(
                        get: { viewModel.globalSettings.adminNewPass },
                        set: { newVal in
                            var s = viewModel.globalSettings
                            s.adminNewPass = newVal
                            viewModel.updateGlobalSettings(s)
                        }
                    )
                )
                .autocorrectionDisabled(true)
                .textInputAutocapitalization(.never)
            }
        }
    }

    private var firmwareUpdateSection: some View {
        let settings = viewModel.globalSettings
        let hasValidFirmware = !settings.firmwareFilePath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            OntInputValidator.isValidFirmwareExtension(settings.firmwareFileName) &&
            (OntInputValidator.minFirmwareSizeBytes...OntInputValidator.maxFirmwareSizeBytes).contains(settings.firmwareFileSize)

        return Section("4. Cập nhật Firmware ONT (Bước cuối sau khi đổi xong cả 2 Wi-Fi)") {
            Toggle(
                "Tự động đổ Firmware mới ở bước cuối (Bước 9/9)",
                isOn: Binding(
                    get: { viewModel.globalSettings.enableFirmwareUpdate },
                    set: { enabled in
                        var s = viewModel.globalSettings
                        s.enableFirmwareUpdate = enabled
                        viewModel.updateGlobalSettings(s)
                    }
                )
            )
            .disabled(viewModel.isRunningSetup || viewModel.isImportingFirmware)

            if hasValidFirmware {
                let sizeMb = String(format: "%.2f", Double(settings.firmwareFileSize) / (1024.0 * 1024.0))
                HStack(spacing: 8) {
                    Image(systemName: "shippingbox.fill")
                        .foregroundColor(.green)
                    Text("File Firmware: \(settings.firmwareFileName) (\(sizeMb) MB)")
                        .font(.caption.weight(.semibold))
                        .foregroundColor(.green)
                }
            } else if settings.enableFirmwareUpdate {
                HStack(alignment: .top, spacing: 6) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(.orange)
                    Text("Chưa chọn file Firmware (.signed / .bin / .img) hợp lệ (64 KB – 64 MB).")
                        .font(.caption)
                        .foregroundColor(.orange)
                }
            }

            Button {
                showFirmwareImporter = true
            } label: {
                Label(
                    hasValidFirmware
                        ? "Đổi file Firmware (.signed / .bin / .img)"
                        : "Chọn file Firmware (.signed / .bin / .img)",
                    systemImage: "folder.badge.plus"
                )
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity)
            }
            .disabled(viewModel.isRunningSetup || viewModel.isImportingFirmware)

            Text(
                settings.enableFirmwareUpdate
                    ? "Quy trình 2 lần kết nối (9 bước): Lần 1 đổi Wi-Fi phụ -> Chuyển sang Wi-Fi vừa đổi -> Lần 2 đổi nốt Wi-Fi còn lại rồi nạp Firmware ở Bước 9/9."
                    : "Quy trình 2 lần kết nối (8 bước): Lần 1 đổi Wi-Fi phụ -> Chuyển sang Wi-Fi vừa đổi -> Lần 2 đổi nốt Wi-Fi còn lại."
            )
            .font(.caption2)
            .foregroundColor(.secondary)
        }
    }

    @ViewBuilder
    private func validationSummarySection(_ validation: ValidationResult) -> some View {
        Section("Trạng thái kiểm tra hợp lệ trước khi RUN") {
            if validation.isValid {
                HStack {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.green)
                    Text("Tất cả thông số hợp lệ 100% — Sẵn sàng bấm RUN!")
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.green)
                }
            } else {
                ForEach(validation.errors, id: \.self) { err in
                    HStack(alignment: .top, spacing: 6) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.red)
                        Text(err)
                            .font(.caption)
                            .foregroundColor(.red)
                    }
                }
            }
        }
    }

    private func connectedBandAndActionSection(preRunValidation: ValidationResult) -> some View {
        Section("Chọn băng tần đang bắt & Thực thi tự động") {
            VStack(alignment: .leading, spacing: 6) {
                Text("Băng tần Wi-Fi ONT iPhone đang kết nối:")
                    .font(.caption.weight(.semibold))
                Picker("Băng tần kết nối", selection: $viewModel.connectedBand) {
                    Text("Đang bắt 2.4GHz").tag(WifiBand.band24GHz)
                    Text("Đang bắt 5GHz").tag(WifiBand.band5GHz)
                }
                .pickerStyle(.segmented)
                Text(
                    viewModel.connectedBand == .band24GHz
                        ? "Lần 1 cấu hình Wi-Fi phụ 5GHz trước -> Bước 7 chuyển kết nối sang 5GHz mới -> Lần 2 cấu hình nốt 2.4GHz và nạp Firmware (nếu bật)."
                        : "Lần 1 cấu hình Wi-Fi phụ 2.4GHz trước -> Bước 7 chuyển kết nối sang 2.4GHz mới -> Lần 2 cấu hình nốt 5GHz và nạp Firmware (nếu bật)."
                )
                .font(.caption2)
                .foregroundColor(.secondary)
            }

            Button {
                viewModel.saveSelectedRecordChanges()
            } label: {
                Label("LƯU THAY ĐỔI", systemImage: "square.and.arrow.down")
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity)
            }

            let runStepsLabel = viewModel.globalSettings.enableFirmwareUpdate
                ? "RUN - ĐỔ CẤU HÌNH & FIRMWARE (9 BƯỚC)"
                : "RUN - ĐỔ CẤU HÌNH 2 WI-FI (8 BƯỚC)"
            Button {
                viewModel.runOntConfiguration()
            } label: {
                Label(runStepsLabel, systemImage: "bolt.horizontal.circle.fill")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .tint(.green)
            .disabled(!preRunValidation.isValid || viewModel.isRunningSetup || viewModel.isImportingFirmware)
        }
    }
}
