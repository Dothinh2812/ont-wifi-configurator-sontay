import Foundation

public enum SubscriberStatus: String, Codable, CaseIterable, Sendable {
    case pending = "PENDING"
    case running = "RUNNING"
    case success = "SUCCESS"
    case failed = "FAILED"
    case invalidData = "INVALID_DATA"

    public var label: String {
        switch self {
        case .pending: return "Chờ cấu hình"
        case .running: return "Đang chạy"
        case .success: return "Thành công"
        case .failed: return "Lỗi cấu hình"
        case .invalidData: return "Thiếu TT / Lỗi dữ liệu"
        }
    }
}

public struct SubscriberRecord: Identifiable, Codable, Equatable, Sendable {
    public var id: Int { rowIndex }

    public var rowIndex: Int
    public var stt: Int?
    public var toDiaBan: String
    public var nvkt: String
    public var tenTb: String
    public var diachiTb: String
    public var soDt: String
    public var soMay: String
    public var accountPppoe: String
    public var passPppoe: String
    public var maMen: String
    public var slid: String
    public var ssid24g: String
    public var pass24g: String
    public var ssid5g: String
    public var pass5g: String
    public var isValid: Bool
    public var validationErrors: [String]
    public var status: SubscriberStatus
    public var startTime: String
    public var endTime: String
    public var errorMessage: String
    public var lastKnownAdminPass: String
    public var rawRowData: [String: String]

    public init(
        rowIndex: Int,
        stt: Int? = nil,
        toDiaBan: String = "",
        nvkt: String = "",
        tenTb: String = "",
        diachiTb: String = "",
        soDt: String = "",
        soMay: String = "",
        accountPppoe: String = "",
        passPppoe: String = "",
        maMen: String = "",
        slid: String = "",
        ssid24g: String = "",
        pass24g: String = "",
        ssid5g: String = "",
        pass5g: String = "",
        isValid: Bool = true,
        validationErrors: [String] = [],
        status: SubscriberStatus = .pending,
        startTime: String = "",
        endTime: String = "",
        errorMessage: String = "",
        lastKnownAdminPass: String = "",
        rawRowData: [String: String] = [:]
    ) {
        self.rowIndex = rowIndex
        self.stt = stt
        self.toDiaBan = toDiaBan
        self.nvkt = nvkt
        self.tenTb = tenTb
        self.diachiTb = diachiTb
        self.soDt = soDt
        let unifiedSlid = slid.isEmpty ? soMay : slid
        self.soMay = unifiedSlid
        self.accountPppoe = accountPppoe
        self.passPppoe = passPppoe
        self.maMen = maMen
        self.slid = unifiedSlid
        self.ssid24g = ssid24g
        self.pass24g = pass24g
        self.ssid5g = ssid5g
        self.pass5g = pass5g
        self.isValid = isValid
        self.validationErrors = validationErrors
        self.status = status
        self.startTime = startTime
        self.endTime = endTime
        self.errorMessage = errorMessage
        self.lastKnownAdminPass = lastKnownAdminPass
        self.rawRowData = rawRowData
    }

    public var effectiveWifi24Ssid: String {
        let trimmed = ssid24g.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanSlid = slid.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "VNPT_\(cleanSlid)" : trimmed
    }

    public var effectiveWifi24Pass: String {
        let trimmed = pass24g.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "1234567890" : trimmed
    }

    public var effectiveWifi5Ssid: String {
        let trimmed = ssid5g.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            return trimmed
        }
        let candidate = "\(effectiveWifi24Ssid) 5G"
        if candidate.count > 32 {
            return String(candidate.prefix(32)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return candidate
    }

    public var effectiveWifi5Pass: String {
        let trimmed = pass5g.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? effectiveWifi24Pass : trimmed
    }
}
