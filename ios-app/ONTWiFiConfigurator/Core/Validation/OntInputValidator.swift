import Foundation

public struct ValidationResult: Equatable, Sendable {
    public let isValid: Bool
    public let errors: [String]
    public let canPrependZero24g: Bool
    public let canPrependZero5g: Bool

    public init(
        isValid: Bool,
        errors: [String],
        canPrependZero24g: Bool = false,
        canPrependZero5g: Bool = false
    ) {
        self.isValid = isValid
        self.errors = errors
        self.canPrependZero24g = canPrependZero24g
        self.canPrependZero5g = canPrependZero5g
    }
}

public enum OntInputValidator {

    /// Complete set of 11 shell metacharacters forbidden by GW040-NSv2 LuCI CGI:
    /// & | ; $ > < ` \ ! ' "
    public static let unsafeShellMetachars: Set<Character> = [
        "&", "|", ";", "$", ">", "<", "`", "\\", "!", "'", "\""
    ]

    private static let slidAllowedChars = CharacterSet(
        charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-."
    )

    private static let unreservedFormBytes: Set<UInt8> = {
        var set = Set<UInt8>()
        for b in UInt8(ascii: "A")...UInt8(ascii: "Z") { set.insert(b) }
        for b in UInt8(ascii: "a")...UInt8(ascii: "z") { set.insert(b) }
        for b in UInt8(ascii: "0")...UInt8(ascii: "9") { set.insert(b) }
        set.insert(UInt8(ascii: "-"))
        set.insert(UInt8(ascii: "."))
        set.insert(UInt8(ascii: "_"))
        set.insert(UInt8(ascii: "~"))
        return set
    }()

    /// Firmware encode_query reproduction: replaces '&' with '\u{007f}' (0x7F).
    public static func ontEncodeQuery(_ queryStr: String) -> String {
        return queryStr.replacingOccurrences(of: "&", with: "\u{007f}")
    }

    /// Strictly percent-encode form parameter values (RFC 3986 unreserved only; spaces -> %20).
    public static func urlEncodeFormValue(_ value: String) -> String {
        var out = String()
        out.reserveCapacity(value.utf8.count * 2)
        for byte in value.utf8 {
            if unreservedFormBytes.contains(byte) {
                out.append(Character(UnicodeScalar(byte)))
            } else {
                out.append(String(format: "%%%02X", byte))
            }
        }
        return out
    }

    public static func containsControlCharacters(_ text: String) -> Bool {
        return text.unicodeScalars.contains { $0.value < 0x20 || $0.value == 0x7F }
    }

    public static func isPrintableAscii(_ text: String) -> Bool {
        return text.unicodeScalars.allSatisfy { $0.value >= 0x20 && $0.value <= 0x7E }
    }

    @discardableResult
    public static func sanitizeInputMetacharacters(_ text: String) throws -> String {
        if containsControlCharacters(text) {
            throw NSError(
                domain: "OntInputValidator",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Chuỗi chứa ký tự điều khiển ASCII không hợp lệ."]
            )
        }
        for ch in text {
            if unsafeShellMetachars.contains(ch) {
                throw NSError(
                    domain: "OntInputValidator",
                    code: 2,
                    userInfo: [NSLocalizedDescriptionKey: "Ký tự '\(ch)' không an toàn cho hệ thống LuCI CGI (chứa shell metacharacter)."]
                )
            }
        }
        return text
    }

    /// Check ONT admin password complexity per accesscontrol.js:
    /// - 8..32 printable ASCII characters (0x21..0x7E, no spaces)
    /// - At least 1 uppercase, 1 lowercase, 1 digit, 1 special character
    /// - No unsafe shell metacharacters
    public static func validatePasswordComplexity(_ password: String) -> Bool {
        guard (8...32).contains(password.count) else { return false }
        guard password.unicodeScalars.allSatisfy({ $0.value >= 0x21 && $0.value <= 0x7E }) else {
            return false
        }
        if password.contains(where: { unsafeShellMetachars.contains($0) }) {
            return false
        }

        let hasUpper = password.contains { $0.isUppercase }
        let hasLower = password.contains { $0.isLowercase }
        let hasDigit = password.contains { $0.isNumber && $0.isASCII }
        let hasSpecial = password.contains { !$0.isLetter && !$0.isNumber && !$0.isWhitespace }
        return hasUpper && hasLower && hasDigit && hasSpecial
    }

    public static func validateAdminPassword(_ password: String) -> [String] {
        var errors: [String] = []
        if !(8...32).contains(password.count) {
            errors.append("Mật khẩu Admin mới phải dài từ 8..32 ký tự (hiện tại: \(password.count))")
        }
        if !password.unicodeScalars.allSatisfy({ $0.value >= 0x21 && $0.value <= 0x7E }) {
            errors.append("Mật khẩu Admin mới chỉ chấp nhận ký tự ASCII không khoảng trắng")
        }
        for ch in password {
            if unsafeShellMetachars.contains(ch) {
                errors.append("Mật khẩu Admin mới chứa ký tự shell không an toàn: '\(ch)'")
                break
            }
        }
        if !validatePasswordComplexity(password) && errors.isEmpty {
            errors.append("Mật khẩu Admin mới phải có đủ chữ hoa, chữ thường, chữ số và ký tự đặc biệt")
        }
        return errors
    }

    private static func isValidSlidFormat(_ slid: String) -> Bool {
        guard !slid.isEmpty else { return false }
        return slid.unicodeScalars.allSatisfy { slidAllowedChars.contains($0) }
    }

    public static func validateSlid(_ slid: String) throws -> String {
        let trimmed = slid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw NSError(
                domain: "OntInputValidator",
                code: 3,
                userInfo: [NSLocalizedDescriptionKey: "SLID / GPON Password không được để trống."]
            )
        }
        guard trimmed.count <= 10 else {
            throw NSError(
                domain: "OntInputValidator",
                code: 4,
                userInfo: [NSLocalizedDescriptionKey: "SLID '\(trimmed)' vượt quá độ dài tối đa 10 ký tự (\(trimmed.count) ký tự)."]
            )
        }
        guard isValidSlidFormat(trimmed) else {
            throw NSError(
                domain: "OntInputValidator",
                code: 5,
                userInfo: [NSLocalizedDescriptionKey: "SLID '\(trimmed)' chứa ký tự không hợp lệ cho GPON Password."]
            )
        }
        try sanitizeInputMetacharacters(trimmed)
        return trimmed
    }

    /// Check if a Wi-Fi password is a 7-digit numeric ASCII string (missing leading '0' from date in Excel).
    public static func canPrependLeadingZeroToWifiPass(_ password: String) -> Bool {
        let trimmed = password.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count == 7 else { return false }
        return trimmed.unicodeScalars.allSatisfy { $0.value >= 0x30 && $0.value <= 0x39 }
    }

    /// 1-tap action to prepend '0' to a 7-digit Wi-Fi password and optionally sync 5G.
    public static func prependLeadingZeroToWifiPass(
        _ record: SubscriberRecord,
        sync5g: Bool = true
    ) -> SubscriberRecord {
        let p24 = record.pass24g.trimmingCharacters(in: .whitespacesAndNewlines)
        let p5 = record.pass5g.trimmingCharacters(in: .whitespacesAndNewlines)
        let newPass24 = canPrependLeadingZeroToWifiPass(p24) ? "0\(p24)" : record.pass24g
        let newPass5: String
        if sync5g && canPrependLeadingZeroToWifiPass(p24) {
            newPass5 = newPass24
        } else if canPrependLeadingZeroToWifiPass(p5) {
            newPass5 = "0\(p5)"
        } else {
            newPass5 = record.pass5g
        }

        var updated = record
        updated.pass24g = newPass24
        updated.pass5g = newPass5
        let importCheck = validateOnImport(updated)
        updated.isValid = importCheck.isValid
        updated.validationErrors = importCheck.errors
        if updated.status != .success {
            updated.status = importCheck.isValid ? .pending : .invalidData
        }
        return updated
    }

    /// Stage 1 Validation — validateOnImport:
    /// Flags missing PPPoE password, missing SO_MAY/Account, invalid SLID (>10 chars or bad format),
    /// ASCII control characters (<0x20 or 0x7F), and any of the 11 unsafe shell metacharacters.
    public static func validateOnImport(_ record: SubscriberRecord) -> ValidationResult {
        var errors: [String] = []

        if record.passPppoe.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            errors.append("Thiếu mật khẩu PPPoE - Yêu cầu nhập tay (không dùng mật khẩu giả lập)")
        }
        if record.accountPppoe.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            errors.append("Thiếu Account PPPoE")
        }

        let slid = record.slid.trimmingCharacters(in: .whitespacesAndNewlines)
        if !slid.isEmpty {
            if slid.count > 10 {
                errors.append("SLID '\(slid)' vượt quá 10 ký tự (\(slid.count) ký tự)")
            } else if !isValidSlidFormat(slid) {
                errors.append("SLID '\(slid)' chứa ký tự không hợp lệ cho GPON")
            }
        } else {
            errors.append("Thiếu thông tin SLID / Mã MEN")
        }

        let fieldsToCheck: [(String, String)] = [
            ("SLID", record.slid),
            ("Account PPPoE", record.accountPppoe),
            ("Pass PPPoE", record.passPppoe),
            ("SSID 2.4G", record.ssid24g),
            ("Pass 2.4G", record.pass24g),
            ("SSID 5G", record.ssid5g),
            ("Pass 5G", record.pass5g)
        ]

        for (fieldName, fieldVal) in fieldsToCheck {
            if containsControlCharacters(fieldVal) {
                errors.append("\(fieldName) chứa ký tự điều khiển ASCII không hợp lệ")
            }
            for ch in fieldVal {
                if unsafeShellMetachars.contains(ch) {
                    errors.append("\(fieldName) chứa ký tự shell không an toàn: '\(ch)'")
                    break
                }
            }
        }

        return ValidationResult(
            isValid: errors.isEmpty,
            errors: errors,
            canPrependZero24g: canPrependLeadingZeroToWifiPass(record.pass24g),
            canPrependZero5g: canPrependLeadingZeroToWifiPass(record.pass5g)
        )
    }

    /// Stage 2 Validation — validateBeforeRun:
    /// Enforces all Stage 1 rules PLUS printable ASCII 0x20..0x7E and WPA2 length bounds for SSID/Pass
    /// and Admin password complexity.
    public static func validateBeforeRun(
        _ record: SubscriberRecord,
        settings: GlobalSettings = GlobalSettings()
    ) -> ValidationResult {
        let importResult = validateOnImport(record)
        var errors = importResult.errors

        let ssid24 = record.effectiveWifi24Ssid
        let pass24 = record.effectiveWifi24Pass
        let ssid5 = record.effectiveWifi5Ssid
        let pass5 = record.effectiveWifi5Pass

        if !(1...32).contains(ssid24.count) {
            errors.append("SSID Wi-Fi 2.4G phải dài từ 1..32 ký tự (hiện tại: \(ssid24.count))")
        } else if !isPrintableAscii(ssid24) {
            errors.append("SSID Wi-Fi 2.4G chỉ chấp nhận ký tự ASCII in được (0x20..0x7E)")
        }

        if !(1...32).contains(ssid5.count) {
            errors.append("SSID Wi-Fi 5G phải dài từ 1..32 ký tự (hiện tại: \(ssid5.count))")
        } else if !isPrintableAscii(ssid5) {
            errors.append("SSID Wi-Fi 5G chỉ chấp nhận ký tự ASCII in được (0x20..0x7E)")
        }

        let canFix24 = canPrependLeadingZeroToWifiPass(pass24)
        let canFix5 = canPrependLeadingZeroToWifiPass(pass5)

        if !(8...63).contains(pass24.count) {
            let hint = canFix24 ? " — Gợi ý: bấm 'Thêm số 0 ở đầu (0\(pass24))'" : ""
            errors.append("Mật khẩu Wi-Fi 2.4G (\(pass24.count)/8 ký tự): chuẩn WPA2 yêu cầu từ 8..63 ký tự\(hint)")
        } else if !isPrintableAscii(pass24) {
            errors.append("Mật khẩu Wi-Fi 2.4G chỉ chấp nhận ký tự ASCII in được (0x20..0x7E)")
        }

        if !(8...63).contains(pass5.count) {
            errors.append("Mật khẩu Wi-Fi 5G (\(pass5.count)/8 ký tự): chuẩn WPA2 yêu cầu từ 8..63 ký tự")
        } else if !isPrintableAscii(pass5) {
            errors.append("Mật khẩu Wi-Fi 5G chỉ chấp nhận ký tự ASCII in được (0x20..0x7E)")
        }

        errors.append(contentsOf: validateAdminPassword(settings.adminNewPass))

        return ValidationResult(
            isValid: errors.isEmpty,
            errors: errors,
            canPrependZero24g: canFix24,
            canPrependZero5g: canFix5
        )
    }

    /// Mask sensitive passwords, SLID data, and session tokens before logging or exporting.
    public static func maskSensitiveCredentials(_ text: String) -> String {
        var masked = text
        let queryKeys = [
            "pppPassword", "pass", "newPass", "oldPass",
            "wl_wpa_psk", "password", "sessionKey", "data"
        ]
        for key in queryKeys {
            if let regex = try? NSRegularExpression(
                pattern: "(\(key)=)([^&\\s\\u007f]+)",
                options: [.caseInsensitive]
            ) {
                let range = NSRange(masked.startIndex..<masked.endIndex, in: masked)
                masked = regex.stringByReplacingMatches(
                    in: masked,
                    options: [],
                    range: range,
                    withTemplate: "$1***"
                )
            }
            if let jsonRegex = try? NSRegularExpression(
                pattern: "(\"\(key)\"\\s*:\\s*\")([^\"]*)(\")",
                options: [.caseInsensitive]
            ) {
                let range = NSRange(masked.startIndex..<masked.endIndex, in: masked)
                masked = jsonRegex.stringByReplacingMatches(
                    in: masked,
                    options: [],
                    range: range,
                    withTemplate: "$1***$3"
                )
            }
        }
        return masked
    }
}
