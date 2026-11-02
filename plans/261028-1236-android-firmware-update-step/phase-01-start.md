---
phase: 1
title: "Phase 1: Core Driver, Models & Validation Firmware Update Flow"
status: completed
priority: P1
effort: "1h"
dependencies: []
---

# Phase 1: Core Driver, Models & Validation Firmware Update Flow

## Overview
Mở rộng `GlobalSettings`, `OntInputValidator`, `MobileSetupResult` và `OntMobileDriver` để hỗ trợ tải lên và cập nhật Firmware qua LuCI CGI (`updatefwsessioncheck.json` $\rightarrow$ `imagesize.json` $\rightarrow$ `uploadsoftware.conf`) ở ngay trước bước cuối (bước đổi cấu hình Wi-Fi đang kết nối), đồng thời cung cấp hàm hỗ trợ đổi riêng băng tần Wi-Fi kết nối chính thủ công sau khi ONT khởi động lại xong.

## Requirements
- **Functional:**
  - Mở rộng `GlobalSettings` (`android-app/app/src/main/java/vn/vnpt/ontconfig/data/model/GlobalSettings.kt`):
    - Thêm `enableFirmwareUpdate: Boolean = false`
    - Thêm `firmwareFilePath: String = ""`
    - Thêm `firmwareFileName: String = ""`
    - Thêm `firmwareFileSize: Long = 0L`
    - Thêm `firmwareUploadTimeoutMs: Long = 120_000L`
  - Mở rộng `OntInputValidator` (`android-app/app/src/main/java/vn/vnpt/ontconfig/core/validation/OntInputValidator.kt`) (`[Red Team RT-04]`):
    - Bổ sung regex `Regex("""(["']?sessionKey["']?\s*[:=]\s*["']?)([^"'\s,&}\]]+)""", RegexOption.IGNORE_CASE)` vào `SENSITIVE_PATTERNS` để che `"sessionKey": "..."` dạng JSON (kể cả khi chuỗi JSON bị cắt ngắn ở `parseJsonOrThrow`) trong thông báo lỗi/log.
    - Thêm hằng số `MIN_FIRMWARE_SIZE_BYTES = 64 * 1024L` (64 KB), `MAX_FIRMWARE_SIZE_BYTES = 64 * 1024 * 1024L` (64 MB) và hàm `isValidFirmwareExtension(fileName: String): Boolean` chấp nhận các tên file có chứa `.signed` (ví dụ `*.signed`, `*.signed.*`) hoặc kết thúc bằng `.bin`, `.img`.
    - Thêm hàm `sanitizeFirmwareFilename(rawName: String): String` thay thế mọi ký tự ngoài `[a-zA-Z0-9_.\-]` bằng `_` (mặc định `"firmware.signed"` nếu rỗng) để chống ký tự lạ trong `Content-Disposition` multipart.
    - Trong `validateBeforeRun(record: SubscriberRecord, settings: GlobalSettings)`: nếu `settings.enableFirmwareUpdate == true`, chỉ kiểm tra trên bộ nhớ RAM (`settings.firmwareFilePath.isNotBlank()`, `isValidFirmwareExtension(settings.firmwareFileName)`, và `settings.firmwareFileSize in MIN_FIRMWARE_SIZE_BYTES..MAX_FIRMWARE_SIZE_BYTES`) để **không gọi syscall `File.isFile` / `File.length()` trên Main UI thread** mỗi lần gõ phím (`[Red Team RT-04]`).
  - Mở rộng `MobileSetupResult` (`OntMobileDriver.kt`):
    - Thêm các trường phục vụ hiển thị trạng thái sau khi nạp Firmware:
      - `val firmwareUpdated: Boolean = false`
      - `val firmwareFileName: String = ""`
      - `val manualWifiStepPending: Boolean = false`
      - `val manualWifiUnit: Int = 0`
      - `val manualWifiBandLabel: String = ""`
      - `val manualWifiSsid: String = ""`
      - `val manualWifiPass: String = ""`
      - `val configuredSecondaryBandLabel: String = ""`
      - `val configuredSecondarySsid: String = ""`
      - `val configuredSecondaryPass: String = ""`
  - Nâng cấp `OntMobileDriver.ensureGatewayReadyBeforeStep7()` (`[Red Team RT-02]`):
    - Thêm tham số `previousSessionKey: String = sessionKey`. Khi `wlrestart.json` ở Bước 6 làm phiên cũ bị lỗi hoặc trả về trang HTML và hàm gọi `loginAndChangePassword(...)` gặp `OntUserExistError`, thực hiện giải phóng `previousSessionKey` qua `logout()` rồi đăng nhập lại (tương tự `switchToSecondaryWifiAndAuthenticate`), đồng thời không nuốt lỗi `OntAccountLockoutError` / `OntAccountLockoutWarning` / `OntAuthenticationError` và chỉ trả về `true` khi phiên đã thực sự hợp lệ.
  - Cài đặt `OntMobileDriver.updateFirmware(firmwareFile: File, uploadTimeoutMs: Long = 120_000L): JSONObject` (`[Red Team RT-03, RT-04]`):
    1. Kiểm tra trên `Dispatchers.IO`: `firmwareFile.isFile`, `OntInputValidator.isValidFirmwareExtension(firmwareFile.name)`, và `val fileSize = firmwareFile.length()` nằm trong `MIN_FIRMWARE_SIZE_BYTES..MAX_FIRMWARE_SIZE_BYTES` (nếu sai ném `OntValidationError`).
    2. Chuẩn hóa tên file bằng `OntInputValidator.sanitizeFirmwareFilename(firmwareFile.name)`.
    3. Gọi `POST /cgi-bin/luci/updatefwsessioncheck.json` với body `"sessionKey=${OntInputValidator.urlEncodeFormValue(sessionKey)}"` và header `Referer: $cleanBaseUrl/update_software.html`, bọc `IOException` $\rightarrow$ `OntNetworkError` (`[Red Team RT-03]`). Xác nhận HTTP 200, xoay vòng `sessionKey` nếu JSON trả về `sessionKey` mới, và kiểm tra `status == "OK"`.
    4. Gọi `POST /cgi-bin/luci/imagesize.json` với body `"sessionKey=${OntInputValidator.urlEncodeFormValue(sessionKey)}"` và header `Referer: $cleanBaseUrl/update_software.html`, bọc `IOException` $\rightarrow$ `OntNetworkError`. Cập nhật `sessionKey` nếu JSON trả về `sessionKey` mới. Đọc `ramFree` (KB), kiểm tra là số nguyên dương và `fileSize / 1024.0 <= ramFreeKb` (nếu thiếu RAM hoặc `ramFree <= 0` ném `OntDriverException`).
    5. Tạo `MultipartBody` (`MultipartBody.FORM`) gồm 2 part theo đúng thứ tự của `update_software.html`:
       - Part 1: `name="file"`, `filename=safeFilename`, body bọc `firmwareFile.asRequestBody("application/octet-stream".toMediaType())` kèm cờ `allBytesFlushed = AtomicBoolean(false)` được bật khi byte cuối cùng của file đã ghi xong xuống socket (`[Red Team RT-03]`).
       - Part 2: `name="sessionKey"`, value `sessionKey`.
    6. Gửi `POST /cgi-bin/luci/uploadsoftware.conf` qua `buildUploadHttpClient(currentSocketFactory, uploadTimeoutMs)` (giữ `connectTimeout = timeoutMs` 5s, chỉ tăng `writeTimeout = readTimeout = uploadTimeoutMs` 120s `[Red Team RT-03]`) với headers `Referer: $cleanBaseUrl/update_software.html` và `Accept: application/json, text/javascript, */*; q=0.01`. Nếu ném `IOException` sau khi `allBytesFlushed.get() == true` và `isPostFlushRadioDrop(e)` là `true`, xóa ngay `sessionKey = ""` trước khi ném `OntNetworkError` để `finally { logout() }` không bắn `logout.cmd` vào ONT có thể đã bắt đầu ghi flash (`[Red Team RT-03]`).
    7. Parse JSON trả về, cập nhật `sessionKey` nếu có. Kiểm tra `status == "valid"`:
       - Nếu `status != "valid"`: ném `OntDriverException("File firmware không hợp lệ hoặc sai chữ ký (status=...).")` (giữ nguyên `sessionKey` để `finally { logout() }` giải phóng phiên).
       - Nếu `status == "valid"`: **xóa ngay `sessionKey = ""`** trước khi return để khối `finally { logout() }` tuyệt đối không gửi `logout.cmd` vào ONT đang bắt đầu ghi flash và khởi động lại.
  - Tích hợp vào `OntMobileDriver.executeMobileSetup()` (`[Red Team RT-02, RT-03, RT-05, RT-07, RT-08]`):
    - Snapshot `val doFwUpdate = settings.enableFirmwareUpdate` và `val fwFile = File(settings.firmwareFilePath.trim())` ở đầu hàm; truyền tham số `alreadyConfiguredOppositeBand: Boolean = false` (khi bấm Retry mà điện thoại đang bắt sóng Wi-Fi phụ vừa đổi ở lần chạy trước `[Red Team RT-07]`).
    - Đặt `val totalSteps = if (doFwUpdate) 8 else 7` và dùng `"Bước X/$totalSteps: ..."` cho toàn bộ tiêu đề từ Bước 1 đến Bước cuối (`[Red Team RT-08]`).
    - Khi `doFwUpdate == false`: giữ nguyên 100% quy trình 7 bước hiện tại (`totalSteps = 7`).
    - Khi `doFwUpdate == true`:
      - Kiểm tra sớm `fwFile` hợp lệ trước Bước 1.
      - Chạy Bước 1..5 với tiêu đề `"Bước 1/8"`..`"Bước 5/8"`.
      - Ở **Bước 6/8 (`Cấu hình Wi-Fi băng tần phụ`)**: lưu `val preStep6SessionKey = sessionKey`, gọi `configureWifiBand(unit = step6Unit, ssid = step6Ssid, password = step6Pass, isConnectedBandFinalStep = false)`. **Không gọi `logout()`** và **không chuyển sang sóng Wi-Fi phụ** sau Bước 6; nghỉ `settlingDelayMs` rồi kiểm tra `if (!ensureGatewayReadyBeforeStep7(settings = settings, bssid = bssid, previousSessionKey = preStep6SessionKey)) throw OntNetworkError(...)` (`[Red Team RT-02]`).
      - Ở **Bước 7/8 (`Tải lên & Cập nhật Firmware ONT — Trước bước cuối`)**:
        - Emit `RUNNING`: `"Đang tải lên ${fwFile.name} (${fwFile.length() / 1024} KB)..."`
        - Gọi `updateFirmware(fwFile, settings.firmwareUploadTimeoutMs)`. Nếu ném `OntDriverException`, bảo toàn đúng kiểu ngoại lệ con (`OntNetworkError`, `OntValidationError`, `OntDriverException`) với tiền tố `"Đã đổ xong cấu hình (Bước 1-6: WAN, SLID, LAN, Wi-Fi $step6BandLabel), nhưng lỗi Cập nhật Firmware (Bước 7): ${e.message}"` (`[Red Team RT-03]`).
        - Emit `SUCCESS`: `"Đã nạp Firmware hợp lệ (${fwFile.name}), ONT bắt đầu cập nhật & khởi động lại"`
      - Ở **Bước 8/8 (`Bước cuối: Đổi Wi-Fi $step7BandLabel kết nối chính — Thủ công sau khi ONT reboot`)**:
        - Gọi `onUnbindNetwork?.invoke()` (`unbindDone = true`) để khôi phục 4G/5G ngay lập tức.
        - Nếu `alreadyConfiguredOppositeBand == false`: giữ Bước 8 ở trạng thái chờ hoàn tất thủ công (hoặc hiển thị hướng dẫn rõ ràng) và trả về `MobileSetupResult` với `firmwareUpdated = true`, `manualWifiStepPending = true`, cùng thông tin băng tần chính cần đổi thủ công (`manualWifiUnit = step7Unit`, `manualWifiBandLabel = step7BandLabel`, `manualWifiSsid = step7Ssid`, `manualWifiPass = step7Pass`) (`[Red Team RT-05, RT-07]`).
  - Bổ sung hàm `OntMobileDriver.executeManualConnectedWifiOnly(record: SubscriberRecord, settings: GlobalSettings, targetUnit: Int, targetBandLabel: String, targetSsid: String, targetPass: String, bssid: String? = null): MobileSetupResult` (`[Red Team RT-06]`):
    - Khóa `probePauseMutex`, kiểm tra `probeGateway()` (xác nhận chữ ký HTML GW040-NSv2 `[Red Team RT-10]`), gọi `loginAndChangePassword(defaultPass = settings.adminDefaultPass, newPass = settings.adminNewPass, bssid = bssid, lastKnownAdminPass = settings.adminNewPass)` (tự thử cả `Passwd2@` và `admin` phòng trường hợp bản Firmware mới reset NVRAM), gọi `configureWifiBand(unit = targetUnit, ssid = targetSsid, password = targetPass, isConnectedBandFinalStep = true)` có xử lý `droppedDuringRadioReload` và giải phóng `logout()` + `onUnbindNetwork?.invoke()` + `probePauseMutex?.unlock()` trong `finally { withContext(NonCancellable) { ... } }`.
- **Non-functional:**
  - Truyền file Firmware qua `RequestBody` streaming từ `File` để tiêu thụ bộ nhớ RAM tối thiểu (`< 64 KB` buffer) ngay cả với image 20–35 MB.

## Architecture

```kotlin
// Trong OntMobileDriver.kt
fun updateFirmware(
    firmwareFile: File,
    uploadTimeoutMs: Long = 120_000L
): JSONObject {
    if (!firmwareFile.exists() || !firmwareFile.isFile) {
        throw OntValidationError("File firmware không tồn tại: '${firmwareFile.path}'")
    }
    if (!OntInputValidator.isValidFirmwareExtension(firmwareFile.name)) {
        throw OntValidationError("Định dạng file firmware không hợp lệ (chỉ hỗ trợ .signed, .bin, .img): '${firmwareFile.name}'")
    }
    val fileSize = firmwareFile.length()
    if (fileSize < OntInputValidator.MIN_FIRMWARE_SIZE_BYTES || fileSize > OntInputValidator.MAX_FIRMWARE_SIZE_BYTES) {
        throw OntValidationError("Dung lượng file firmware không hợp lệ ($fileSize bytes): '${firmwareFile.name}'")
    }
    val safeFilename = OntInputValidator.sanitizeFirmwareFilename(firmwareFile.name)
    val refererUrl = "$cleanBaseUrl/update_software.html"

    // 1. Check firmware update session
    val encKey1 = OntInputValidator.urlEncodeFormValue(sessionKey)
    val (chkCode, chkBody) = try {
        postFormWithReferer(
            path = "/cgi-bin/luci/updatefwsessioncheck.json",
            rawPayload = "sessionKey=$encKey1",
            referer = refererUrl
        )
    } catch (e: IOException) {
        throw OntNetworkError("Lỗi mạng khi kiểm tra phiên nâng cấp firmware: ${e.message}", e)
    }
    val chkData = parseJsonOrThrow("Kiểm tra phiên nâng cấp firmware (updatefwsessioncheck.json)", chkCode, chkBody)
    val chkRotatedKey = chkData.optString("sessionKey", "")
    if (chkRotatedKey.isNotEmpty()) {
        sessionKey = chkRotatedKey
    }
    if (chkData.optString("status") != "OK") {
        throw OntDriverException("Phiên nâng cấp firmware không hợp lệ (status=${chkData.optString("status")}).")
    }

    // 2. Query available RAM size (KB)
    val encKey2 = OntInputValidator.urlEncodeFormValue(sessionKey)
    val (imgCode, imgBody) = try {
        postFormWithReferer(
            path = "/cgi-bin/luci/imagesize.json",
            rawPayload = "sessionKey=$encKey2",
            referer = refererUrl
        )
    } catch (e: IOException) {
        throw OntNetworkError("Lỗi mạng khi kiểm tra dung lượng RAM trống của ONT: ${e.message}", e)
    }
    val imgData = parseJsonOrThrow("Kiểm tra dung lượng RAM trống (imagesize.json)", imgCode, imgBody)
    val rotatedKey = imgData.optString("sessionKey", "")
    if (rotatedKey.isNotEmpty()) {
        sessionKey = rotatedKey
    }
    val ramFreeStr = imgData.optString("ramFree", "").trim()
    val ramFreeKb = ramFreeStr.toLongOrNull()
        ?: throw OntDriverException("Không đọc được thông số ramFree hợp lệ từ ONT (ramFree='$ramFreeStr').")
    val fileSizeKb = fileSize / 1024.0
    if (ramFreeKb <= 0L || fileSizeKb > ramFreeKb) {
        throw OntDriverException(
            "Không đủ bộ nhớ RAM trống trên ONT ($ramFreeKb KB) để tải file firmware (${fileSizeKb.toLong()} KB)."
        )
    }

    // 3. Upload binary firmware via multipart/form-data with post-flush tracking
    val allBytesFlushed = AtomicBoolean(false)
    val baseFileBody = firmwareFile.asRequestBody("application/octet-stream".toMediaType())
    val trackingFileBody = object : RequestBody() {
        override fun contentType() = baseFileBody.contentType()
        override fun contentLength() = baseFileBody.contentLength()
        override fun writeTo(sink: okio.BufferedSink) {
            baseFileBody.writeTo(sink)
            allBytesFlushed.set(true)
        }
    }

    val multipartBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", safeFilename, trackingFileBody)
        .addFormDataPart("sessionKey", sessionKey)
        .build()

    val req = Request.Builder()
        .url("$cleanBaseUrl/cgi-bin/luci/uploadsoftware.conf")
        .post(multipartBody)
        .header("User-Agent", USER_AGENT)
        .header("Referer", refererUrl)
        .header("Accept", "application/json, text/javascript, */*; q=0.01")
        .header("Connection", "close")
        .build()

    val uploadHttpClient = buildUploadHttpClient(uploadTimeoutMs)
    val (upCode, upBody) = try {
        uploadHttpClient.newCall(req).execute().use { resp ->
            resp.code to resp.body?.string().orEmpty()
        }
    } catch (e: IOException) {
        if (allBytesFlushed.get() && isPostFlushRadioDrop(e)) {
            sessionKey = ""
        }
        throw OntNetworkError("Lỗi kết nối trong khi tải lên file firmware: ${e.message}", e)
    }

    val upData = parseJsonOrThrow("Tải lên file firmware (uploadsoftware.conf)", upCode, upBody)
    val upSessionKey = upData.optString("sessionKey", "")
    if (upSessionKey.isNotEmpty()) {
        sessionKey = upSessionKey
    }
    if (upData.optString("status") != "valid") {
        throw OntDriverException(
            "File firmware không hợp lệ hoặc sai chữ ký (status=${upData.optString("status", "unknown")})."
        )
    }
    // Crucial [RT-01 / RT-03]: Clear sessionKey immediately so finally { logout() } never sends logout.cmd while ONT flashes
    sessionKey = ""
    return upData
}
```

## Related Code Files
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/data/model/GlobalSettings.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/validation/OntInputValidator.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/driver/OntMobileDriver.kt`

## Implementation Steps
1. Cập nhật `GlobalSettings.kt` bổ sung `enableFirmwareUpdate`, `firmwareFilePath`, `firmwareFileName`, `firmwareFileSize`, và `firmwareUploadTimeoutMs`.
2. Cập nhật `OntInputValidator.kt`:
   - Thêm mẫu regex che `"sessionKey": "..."` dạng JSON vào `SENSITIVE_PATTERNS`.
   - Thêm `sanitizeFirmwareFilename(rawName: String): String`.
   - Cập nhật `validateBeforeRun(record, settings)` để kiểm tra file Firmware hợp lệ khi `settings.enableFirmwareUpdate == true`.
3. Cập nhật `OntMobileDriver.kt`:
   - Mở rộng `MobileSetupResult` với các trường kết quả Firmware và thông số bước Wi-Fi kết nối chính cần làm thủ công.
   - Thêm `buildUploadHttpClient(sf: SocketFactory?, uploadTimeoutMs: Long)` và `postFormWithReferer(...)`.
   - Cài đặt `updateFirmware(firmwareFile: File, uploadTimeoutMs: Long = 120_000L): JSONObject`.
   - Cập nhật `executeMobileSetup()` để rẽ nhánh ở sau Bước 6 khi `settings.enableFirmwareUpdate == true`: không gọi `logout()` / không chuyển sang Wi-Fi phụ sau Bước 6, gọi `ensureGatewayReadyBeforeStep7()`, thực thi Bước 7 (`updateFirmware`), xóa `sessionKey = ""` ngay khi `status == "valid"`, giải phóng `onUnbindNetwork` và hoàn tất ở Bước 8 với hướng dẫn đổi Wi-Fi kết nối chính thủ công.
   - Thêm `executeManualConnectedWifiOnly(...)` để hỗ trợ KTV đổi nốt băng tần Wi-Fi chính sau khi ONT khởi động lại xong nếu muốn thực hiện nhanh ngay trong app.

## Todo
- [x] Mở rộng `GlobalSettings` thêm cấu hình nâng cấp Firmware (`GlobalSettings.kt`)
- [x] Bổ sung `sanitizeFirmwareFilename`, JSON `sessionKey` masking và kiểm tra file Firmware trong `validateBeforeRun` (`OntInputValidator.kt`)
- [x] Cài đặt `updateFirmware()`, nhánh 8 bước trong `executeMobileSetup()` và `executeManualConnectedWifiOnly()` (`OntMobileDriver.kt`)

## Success Criteria
- [x] `OntMobileDriver.updateFirmware()` gửi đúng 3 HTTP POST tuần tự (`updatefwsessioncheck.json` $\rightarrow$ `imagesize.json` $\rightarrow$ `uploadsoftware.conf` multipart) và xóa `sessionKey = ""` ngay khi `status == "valid"`.
- [x] Khi `enableFirmwareUpdate == true`, `executeMobileSetup()` nạp Firmware ở Bước 7 (ngay sau Bước 6 cấu hình Wi-Fi phụ và trước bước đổi Wi-Fi kết nối chính) mà không làm đứt kết nối Wi-Fi hiện tại trước khi upload xong.
- [x] Khi `enableFirmwareUpdate == false`, `executeMobileSetup()` giữ nguyên hành vi 7 bước như cũ.

## Risk Assessment
- **Rủi ro 1 (`wlrestart.json` ở Bước 6 làm gián đoạn tạm thời radio trước khi upload Firmware ở Bước 7):**
  - *Tín hiệu nhận biết:* Request `updatefwsessioncheck.json` ở đầu Bước 7 gặp lỗi kết nối hoặc trả về trang HTML login do phiên bị reset.
  - *Giải pháp:* Gọi `ensureGatewayReadyBeforeStep7(settings, bssid)` sau Bước 6 để tự động rebind `SocketFactory` và đăng nhập lại nếu cần trước khi gọi `updateFirmware()`.
- **Rủi ro 2 (`OutOfMemoryError` khi đọc file Firmware 25–35 MB trên điện thoại):**
  - *Tín hiệu nhận biết:* Ứng dụng văng khi tạo request multipart nếu đọc toàn bộ file vào `ByteArray`.
  - *Giải pháp:* Dùng `firmwareFile.asRequestBody("application/octet-stream".toMediaType())` của OkHttp để stream trực tiếp từ `File` trong bộ nhớ đệm nội bộ.
