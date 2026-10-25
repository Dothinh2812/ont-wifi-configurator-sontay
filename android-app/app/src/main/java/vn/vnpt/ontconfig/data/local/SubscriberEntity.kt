package vn.vnpt.ontconfig.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject
import vn.vnpt.ontconfig.data.model.SubscriberRecord
import vn.vnpt.ontconfig.data.model.SubscriberStatus

@Entity(tableName = "subscribers")
data class SubscriberEntity(
    @PrimaryKey val rowIndex: Int,
    val stt: Int?,
    val toDiaBan: String,
    val nvkt: String,
    val tenTb: String,
    val diachiTb: String,
    val soDt: String,
    val soDtNormalized: String,
    val soMay: String,
    val accountPppoe: String,
    val passPppoe: String,
    val maMen: String,
    val slid: String,
    val ssid24g: String,
    val pass24g: String,
    val ssid5g: String,
    val pass5g: String,
    val isValid: Boolean,
    val validationErrorsJson: String,
    val status: String,
    val startTime: String,
    val endTime: String,
    val errorMessage: String,
    val lastKnownAdminPass: String,
    val rawRowJson: String
) {
    fun toRecord(): SubscriberRecord {
        val errors = mutableListOf<String>()
        if (validationErrorsJson.isNotBlank()) {
            try {
                val arr = JSONArray(validationErrorsJson)
                for (i in 0 until arr.length()) {
                    errors.add(arr.optString(i))
                }
            } catch (_: Exception) {
            }
        }

        val rawMap = linkedMapOf<String, String>()
        if (rawRowJson.isNotBlank()) {
            try {
                val obj = JSONObject(rawRowJson)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    rawMap[k] = obj.optString(k, "")
                }
            } catch (_: Exception) {
            }
        }

        val parsedStatus = try {
            SubscriberStatus.valueOf(status)
        } catch (_: Exception) {
            SubscriberStatus.PENDING
        }

        return SubscriberRecord(
            rowIndex = rowIndex,
            stt = stt,
            toDiaBan = toDiaBan,
            nvkt = nvkt,
            tenTb = tenTb,
            diachiTb = diachiTb,
            soDt = soDt,
            soMay = soMay,
            accountPppoe = accountPppoe,
            passPppoe = passPppoe,
            maMen = maMen,
            slid = slid,
            ssid24g = ssid24g,
            pass24g = pass24g,
            ssid5g = ssid5g,
            pass5g = pass5g,
            isValid = isValid,
            validationErrors = errors,
            status = parsedStatus,
            startTime = startTime,
            endTime = endTime,
            errorMessage = errorMessage,
            lastKnownAdminPass = lastKnownAdminPass,
            rawRowData = rawMap
        )
    }

    companion object {
        fun fromRecord(record: SubscriberRecord): SubscriberEntity {
            val errorsArr = JSONArray()
            record.validationErrors.forEach { errorsArr.put(it) }

            val rawObj = JSONObject()
            for ((k, v) in record.rawRowData) {
                rawObj.put(k, v)
            }

            val normPhone = record.soDt.filter { it.isDigit() }.trimStart('0')

            return SubscriberEntity(
                rowIndex = record.rowIndex,
                stt = record.stt,
                toDiaBan = record.toDiaBan,
                nvkt = record.nvkt,
                tenTb = record.tenTb,
                diachiTb = record.diachiTb,
                soDt = record.soDt,
                soDtNormalized = normPhone,
                soMay = record.soMay,
                accountPppoe = record.accountPppoe,
                passPppoe = record.passPppoe,
                maMen = record.maMen,
                slid = record.slid,
                ssid24g = record.ssid24g,
                pass24g = record.pass24g,
                ssid5g = record.ssid5g,
                pass5g = record.pass5g,
                isValid = record.isValid,
                validationErrorsJson = errorsArr.toString(),
                status = record.status.name,
                startTime = record.startTime,
                endTime = record.endTime,
                errorMessage = record.errorMessage,
                lastKnownAdminPass = record.lastKnownAdminPass,
                rawRowJson = rawObj.toString()
            )
        }
    }
}
