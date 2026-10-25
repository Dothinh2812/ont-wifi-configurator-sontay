package vn.vnpt.ontconfig.core.validation

import vn.vnpt.ontconfig.data.model.GlobalSettings
import vn.vnpt.ontconfig.data.model.SubscriberRecord
import java.net.URLEncoder

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val canPrependZero24g: Boolean = false,
    val canPrependZero5g: Boolean = false
)

object OntInputValidator {

    /**
     * [Red Team RT-08] Complete set of 11 shell metacharacters forbidden by GW040-NSv2 LuCI CGI:
     * & | ; $ > < ` \ ! ' "
     */
    val UNSAFE_SHELL_METACHARS: Set<Char> = setOf(
        '&', '|', ';', '$', '>', '<', '`', '\\', '!', '\'', '"'
    )

    private val SLID_REGEX = Regex("^[a-zA-Z0-9_\\-\\.]+$")
    private val SEVEN_DIGITS_REGEX = Regex("^\\d{7}$")

    private val SENSITIVE_PATTERNS = listOf(
        Regex("(pppPassword=)([^&\\s]+)", RegexOption.IGNORE_CASE),
        Regex("(pass=)([^&\\s]+)", RegexOption.IGNORE_CASE),
        Regex("(newPass=)([^&\\s]+)", RegexOption.IGNORE_CASE),
        Regex("(oldPass=)([^&\\s]+)", RegexOption.IGNORE_CASE),
        Regex("(wl_wpa_psk=)([^&\\s]+)", RegexOption.IGNORE_CASE),
        Regex("(password=)([^&\\s]+)", RegexOption.IGNORE_CASE),
        Regex("(sessionKey=)([^&\\s]+)", RegexOption.IGNORE_CASE)
    )

    /**
     * Firmware encode_query reproduction: replaces '&' with '\u007f' (0x7f).
     */
    fun ontEncodeQuery(queryStr: String): String {
        return queryStr.replace("&", "\u007f")
    }

    /**
     * Percent-encode individual form parameter values before joining key=value pairs.
     */
    fun urlEncodeFormValue(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    /**
     * Verify that text does not contain any of the 11 unsafe shell metacharacters.
     */
    fun sanitizeInputMetacharacters(text: String): String {
        for (ch in text) {
            if (ch in UNSAFE_SHELL_METACHARS) {
                throw IllegalArgumentException(
                    "Ký tự '$ch' không an toàn cho hệ thống LuCI CGI (chứa shell metacharacter)."
                )
            }
        }
        return text
    }

    /**
     * [Red Team RT-08] Check ONT admin password complexity per accesscontrol.js:
     * - 8..32 printable ASCII characters (0x21..0x7E, no spaces)
     * - At least 1 uppercase, 1 lowercase, 1 digit, 1 special character
     * - No unsafe shell metacharacters
     */
    fun validatePasswordComplexity(password: String): Boolean {
        if (password.length !in 8..32) return false
        if (password.any { it.code !in 0x21..0x7E }) return false
        if (password.any { it in UNSAFE_SHELL_METACHARS }) return false

        val hasUpper = password.any { it.isUpperCase() }
        val hasLower = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSpecial = password.any { !it.isLetterOrDigit() && !it.isWhitespace() }
        return hasUpper && hasLower && hasDigit && hasSpecial
    }

    fun validateAdminPassword(password: String): List<String> {
        val errors = mutableListOf<String>()
        if (password.length !in 8..32) {
            errors.add("Mật khẩu Admin mới phải dài từ 8..32 ký tự (hiện tại: ${password.length})")
        }
        if (password.any { it.code !in 0x21..0x7E }) {
            errors.add("Mật khẩu Admin mới chỉ chấp nhận ký tự ASCII không khoảng trắng")
        }
        for (ch in password) {
            if (ch in UNSAFE_SHELL_METACHARS) {
                errors.add("Mật khẩu Admin mới chứa ký tự shell không an toàn: '$ch'")
                break
            }
        }
        if (!validatePasswordComplexity(password) && errors.isEmpty()) {
            errors.add("Mật khẩu Admin mới phải có đủ chữ hoa, chữ thường, chữ số và ký tự đặc biệt")
        }
        return errors
    }

    /**
     * Validate GPON SLID / Mã MEN: 1..10 ASCII chars matching ^[a-zA-Z0-9_\-\.]+$.
     */
    fun validateSlid(slid: String): String {
        val trimmed = slid.trim()
        require(trimmed.isNotEmpty()) { "SLID / GPON Password không được để trống." }
        require(trimmed.length <= 10) {
            "SLID '$trimmed' vượt quá độ dài tối đa 10 ký tự (${trimmed.length} ký tự)."
        }
        require(SLID_REGEX.matches(trimmed)) {
            "SLID '$trimmed' chứa ký tự không hợp lệ cho GPON Password."
        }
        sanitizeInputMetacharacters(trimmed)
        return trimmed
    }

    /**
     * [Red Team RT-03] Check if a Wi-Fi password is a 7-digit numeric string (missing leading '0' from date in Excel).
     */
    fun canPrependLeadingZeroToWifiPass(password: String): Boolean {
        return SEVEN_DIGITS_REGEX.matches(password.trim())
    }

    /**
     * [Red Team RT-03] 1-tap action to prepend '0' to a 7-digit Wi-Fi password and optionally sync 5G.
     */
    fun prependLeadingZeroToWifiPass(record: SubscriberRecord, sync5g: Boolean = true): SubscriberRecord {
        val p24 = record.pass24g.trim()
        val p5 = record.pass5g.trim()
        val newPass24 = if (canPrependLeadingZeroToWifiPass(p24)) "0$p24" else record.pass24g
        val newPass5 = when {
            sync5g && canPrependLeadingZeroToWifiPass(p24) -> newPass24
            canPrependLeadingZeroToWifiPass(p5) -> "0$p5"
            else -> record.pass5g
        }
        val updated = record.copy(pass24g = newPass24, pass5g = newPass5)
        val importCheck = validateOnImport(updated)
        return updated.copy(
            isValid = importCheck.isValid,
            validationErrors = importCheck.errors
        )
    }

    /**
     * Stage 1 Validation — validateOnImport:
     * Synchronized 100% with desktop app/data/excel_manager.py:146-187.
     * Flags missing PPPoE password, missing SO_MAY/Account, invalid SLID (>10 chars or bad format),
     * and any of the 11 unsafe shell metacharacters.
     */
    fun validateOnImport(record: SubscriberRecord): ValidationResult {
        val errors = mutableListOf<String>()

        if (record.passPppoe.trim().isEmpty()) {
            errors.add("Thiếu mật khẩu PPPoE - Yêu cầu nhập tay (không dùng mật khẩu giả lập)")
        }
        if (record.soMay.trim().isEmpty()) {
            errors.add("Thiếu Số máy định danh")
        }
        if (record.accountPppoe.trim().isEmpty()) {
            errors.add("Thiếu Account PPPoE")
        }

        val slid = record.slid.trim()
        if (slid.isNotEmpty()) {
            if (slid.length > 10) {
                errors.add("SLID '$slid' vượt quá 10 ký tự (${slid.length} ký tự)")
            } else if (!SLID_REGEX.matches(slid)) {
                errors.add("SLID '$slid' chứa ký tự không hợp lệ cho GPON")
            }
        } else {
            errors.add("Thiếu thông tin SLID / Mã MEN")
        }

        val fieldsToCheck = listOf(
            "Số máy" to record.soMay,
            "SLID" to record.slid,
            "Account PPPoE" to record.accountPppoe,
            "Pass PPPoE" to record.passPppoe,
            "SSID 2.4G" to record.ssid24g,
            "Pass 2.4G" to record.pass24g,
            "SSID 5G" to record.ssid5g,
            "Pass 5G" to record.pass5g
        )

        for ((fieldName, fieldVal) in fieldsToCheck) {
            for (ch in fieldVal) {
                if (ch in UNSAFE_SHELL_METACHARS) {
                    errors.add("$fieldName chứa ký tự shell không an toàn: '$ch'")
                    break
                }
            }
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            canPrependZero24g = canPrependLeadingZeroToWifiPass(record.pass24g),
            canPrependZero5g = canPrependLeadingZeroToWifiPass(record.pass5g)
        )
    }

    /**
     * Stage 2 Validation — validateBeforeRun:
     * Executed realtime on ConfigReviewScreen before enabling the RUN button.
     * Enforces all Stage 1 rules PLUS:
     * - SSID 2.4G & 5G length 1..32 chars
     * - Wi-Fi Password 2.4G & 5G length 8..63 chars (catches the 5 7-digit passwords in file-mau.xlsx)
     * - New Admin Password complexity (8..32 printable ASCII, no spaces)
     */
    fun validateBeforeRun(
        record: SubscriberRecord,
        settings: GlobalSettings = GlobalSettings()
    ): ValidationResult {
        val importResult = validateOnImport(record)
        val errors = importResult.errors.toMutableList()

        val ssid24 = record.effectiveWifi24Ssid.trim()
        val pass24 = record.effectiveWifi24Pass.trim()
        val ssid5 = record.effectiveWifi5Ssid.trim()
        val pass5 = record.effectiveWifi5Pass.trim()

        if (ssid24.length !in 1..32) {
            errors.add("SSID Wi-Fi 2.4G phải dài từ 1..32 ký tự (hiện tại: ${ssid24.length})")
        }
        if (ssid5.length !in 1..32) {
            errors.add("SSID Wi-Fi 5G phải dài từ 1..32 ký tự (hiện tại: ${ssid5.length})")
        }

        val canFix24 = canPrependLeadingZeroToWifiPass(pass24)
        val canFix5 = canPrependLeadingZeroToWifiPass(pass5)

        if (pass24.length !in 8..63) {
            val hint = if (canFix24) " — Gợi ý: bấm 'Thêm số 0 ở đầu (0$pass24)'" else ""
            errors.add(
                "Mật khẩu Wi-Fi 2.4G (${pass24.length}/8 ký tự): chuẩn WPA2 yêu cầu từ 8..63 ký tự$hint"
            )
        }
        if (pass5.length !in 8..63) {
            errors.add(
                "Mật khẩu Wi-Fi 5G (${pass5.length}/8 ký tự): chuẩn WPA2 yêu cầu từ 8..63 ký tự"
            )
        }

        errors.addAll(validateAdminPassword(settings.adminNewPass))

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            canPrependZero24g = canFix24,
            canPrependZero5g = canFix5
        )
    }

    /**
     * [Red Team RT-05 & RT-09] Mask sensitive passwords and session tokens before logging or exporting.
     */
    fun maskSensitiveCredentials(text: String): String {
        var masked = text
        for (regex in SENSITIVE_PATTERNS) {
            masked = regex.replace(masked, "$1***")
        }
        return masked
    }
}
