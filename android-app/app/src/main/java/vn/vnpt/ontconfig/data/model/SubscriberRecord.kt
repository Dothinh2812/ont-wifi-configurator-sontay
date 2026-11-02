package vn.vnpt.ontconfig.data.model

enum class SubscriberStatus(val label: String) {
    PENDING("Chờ cấu hình"),
    RUNNING("Đang chạy"),
    SUCCESS("Thành công"),
    FAILED("Lỗi cấu hình"),
    INVALID_DATA("Thiếu TT / Lỗi dữ liệu");

    override fun toString(): String = name
}

data class SubscriberRecord(
    val rowIndex: Int,
    val stt: Int? = null,
    val toDiaBan: String = "",
    val nvkt: String = "",
    val tenTb: String = "",
    val diachiTb: String = "",
    val soDt: String = "",
    val soMay: String = "",
    val accountPppoe: String = "",
    val passPppoe: String = "",
    val maMen: String = "",
    val slid: String = "",
    val ssid24g: String = "",
    val pass24g: String = "",
    val ssid5g: String = "",
    val pass5g: String = "",
    val isValid: Boolean = true,
    val validationErrors: List<String> = emptyList(),
    val status: SubscriberStatus = SubscriberStatus.PENDING,
    val startTime: String = "",
    val endTime: String = "",
    val errorMessage: String = "",
    val lastKnownAdminPass: String = "",
    val rawRowData: Map<String, String> = emptyMap()
) {
    val effectiveWifi24Ssid: String
        get() = ssid24g.trim().ifBlank { "VNPT_${slid.trim()}" }

    val effectiveWifi24Pass: String
        get() = pass24g.trim().ifBlank { "1234567890" }

    val effectiveWifi5Ssid: String
        get() = ssid5g.trim().ifBlank {
            val candidate = "${effectiveWifi24Ssid} 5G"
            if (candidate.length > 32) candidate.substring(0, 32).trim() else candidate
        }

    val effectiveWifi5Pass: String
        get() = pass5g.trim().ifBlank { effectiveWifi24Pass }

    fun effectiveWifi24Pass(secMode: String): String =
        if (secMode.trim() == WifiSecurityMode.OPEN.formValue) "" else effectiveWifi24Pass

    fun effectiveWifi5Pass(secMode: String): String =
        if (secMode.trim() == WifiSecurityMode.OPEN.formValue) "" else effectiveWifi5Pass

    fun effectiveWifi24PassForMode(secMode: String): String = effectiveWifi24Pass(secMode)

    fun effectiveWifi5PassForMode(secMode: String): String = effectiveWifi5Pass(secMode)
}
