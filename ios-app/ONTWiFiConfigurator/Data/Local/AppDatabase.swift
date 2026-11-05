import Foundation
import SQLite3

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

public final class AppDatabase: @unchecked Sendable {

    public static let shared = AppDatabase()

    private let queue = DispatchQueue(label: "vn.vnpt.ontconfig.sqlite")
    private var db: OpaquePointer?
    public let databaseURL: URL

    public init(databaseURL: URL? = nil) {
        if let customURL = databaseURL {
            self.databaseURL = customURL
        } else {
            let fileManager = FileManager.default
            let appSupport = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? fileManager.temporaryDirectory
            var dirURL = appSupport.appendingPathComponent("ONTWiFiConfigurator", isDirectory: true)
            try? fileManager.createDirectory(
                at: dirURL,
                withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
            )
            var dirValues = URLResourceValues()
            dirValues.isExcludedFromBackup = true
            try? dirURL.setResourceValues(dirValues)

            self.databaseURL = dirURL.appendingPathComponent("ont_subscribers.sqlite")
        }

        queue.sync {
            openAndInitialize()
        }
    }

    deinit {
        if let handle = db {
            sqlite3_close(handle)
        }
    }

    private func openAndInitialize() {
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        let openPath = databaseURL.lastPathComponent == ":memory:" ? ":memory:" : databaseURL.path
        if sqlite3_open_v2(openPath, &db, flags, nil) == SQLITE_OK {
            var fileValues = URLResourceValues()
            fileValues.isExcludedFromBackup = true
            var mutableURL = databaseURL
            try? mutableURL.setResourceValues(fileValues)
            let parentDirPath = databaseURL.deletingLastPathComponent().path
            try? FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: parentDirPath
            )
            try? FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: databaseURL.path
            )

            execSQL("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000;")
            let createTableSQL = """
            CREATE TABLE IF NOT EXISTS subscribers (
                rowIndex INTEGER PRIMARY KEY,
                stt INTEGER,
                toDiaBan TEXT NOT NULL,
                nvkt TEXT NOT NULL,
                tenTb TEXT NOT NULL,
                diachiTb TEXT NOT NULL,
                soDt TEXT NOT NULL,
                soDtNormalized TEXT NOT NULL,
                soMay TEXT NOT NULL,
                accountPppoe TEXT NOT NULL,
                passPppoe TEXT NOT NULL,
                maMen TEXT NOT NULL,
                slid TEXT NOT NULL,
                ssid24g TEXT NOT NULL,
                pass24g TEXT NOT NULL,
                ssid5g TEXT NOT NULL,
                pass5g TEXT NOT NULL,
                isValid INTEGER NOT NULL,
                validationErrorsJson TEXT NOT NULL,
                status TEXT NOT NULL,
                startTime TEXT NOT NULL,
                endTime TEXT NOT NULL,
                errorMessage TEXT NOT NULL,
                lastKnownAdminPass TEXT NOT NULL,
                rawRowJson TEXT NOT NULL
            );
            """
            execSQL(createTableSQL)
        }
    }

    @discardableResult
    private func execSQL(_ sql: String) -> Bool {
        guard let handle = db else { return false }
        return sqlite3_exec(handle, sql, nil, nil, nil) == SQLITE_OK
    }

    public func getAll() -> [SubscriberRecord] {
        return queue.sync {
            fetchAllUnlocked()
        }
    }

    public func update(record: SubscriberRecord) {
        queue.sync {
            insertOrReplaceUnlocked(record: record)
        }
    }

    /// Atomic smart merge inside BEGIN IMMEDIATE TRANSACTION ... COMMIT (with ROLLBACK on failure).
    /// Preserves SUCCESS status, lastKnownAdminPass, and manual KTV edits across Excel re-imports.
    @discardableResult
    public func upsertPreservingEdits(records incomingRecords: [SubscriberRecord]) -> [SubscriberRecord] {
        return queue.sync {
            let existing = fetchAllUnlocked()
            let merged = ExcelManager.mergeRecordsPreservingEdits(
                existingRecords: existing,
                incomingRecords: incomingRecords
            )

            guard execSQL("BEGIN IMMEDIATE TRANSACTION;") else {
                return merged
            }

            guard execSQL("DELETE FROM subscribers;") else {
                _ = execSQL("ROLLBACK;")
                return existing
            }

            for rec in merged {
                if !insertOrReplaceUnlocked(record: rec) {
                    _ = execSQL("ROLLBACK;")
                    return existing
                }
            }

            if !execSQL("COMMIT;") {
                _ = execSQL("ROLLBACK;")
                return existing
            }

            return merged
        }
    }

    // MARK: - Internal Serialized Operations

    private func fetchAllUnlocked() -> [SubscriberRecord] {
        guard let handle = db else { return [] }
        let query = """
        SELECT rowIndex, stt, toDiaBan, nvkt, tenTb, diachiTb, soDt, soMay, accountPppoe,
               passPppoe, maMen, slid, ssid24g, pass24g, ssid5g, pass5g, isValid,
               validationErrorsJson, status, startTime, endTime, errorMessage,
               lastKnownAdminPass, rawRowJson
        FROM subscribers
        ORDER BY rowIndex ASC;
        """
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, query, -1, &stmt, nil) == SQLITE_OK else {
            return []
        }
        defer { sqlite3_finalize(stmt) }

        var results: [SubscriberRecord] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            let rowIndex = Int(sqlite3_column_int(stmt, 0))
            let stt: Int? = sqlite3_column_type(stmt, 1) == SQLITE_NULL
                ? nil
                : Int(sqlite3_column_int(stmt, 1))
            let toDiaBan = columnText(stmt, 2)
            let nvkt = columnText(stmt, 3)
            let tenTb = columnText(stmt, 4)
            let diachiTb = columnText(stmt, 5)
            let soDt = columnText(stmt, 6)
            let soMay = columnText(stmt, 7)
            let accountPppoe = columnText(stmt, 8)
            let passPppoe = columnText(stmt, 9)
            let maMen = columnText(stmt, 10)
            let slid = columnText(stmt, 11)
            let ssid24g = columnText(stmt, 12)
            let pass24g = columnText(stmt, 13)
            let ssid5g = columnText(stmt, 14)
            let pass5g = columnText(stmt, 15)
            let isValid = sqlite3_column_int(stmt, 16) != 0
            let validationErrorsJson = columnText(stmt, 17)
            let statusStr = columnText(stmt, 18)
            let startTime = columnText(stmt, 19)
            let endTime = columnText(stmt, 20)
            let errorMessage = columnText(stmt, 21)
            let lastKnownAdminPass = columnText(stmt, 22)
            let rawRowJson = columnText(stmt, 23)

            let validationErrors: [String] = (try? JSONDecoder().decode([String].self, from: Data(validationErrorsJson.utf8))) ?? []
            let rawRowData: [String: String] = (try? JSONDecoder().decode([String: String].self, from: Data(rawRowJson.utf8))) ?? [:]
            let status = SubscriberStatus(rawValue: statusStr) ?? .pending
            let unifiedSlid = slid.isEmpty ? soMay : slid

            results.append(
                SubscriberRecord(
                    rowIndex: rowIndex,
                    stt: stt,
                    toDiaBan: toDiaBan,
                    nvkt: nvkt,
                    tenTb: tenTb,
                    diachiTb: diachiTb,
                    soDt: soDt,
                    soMay: unifiedSlid,
                    accountPppoe: accountPppoe,
                    passPppoe: passPppoe,
                    maMen: maMen,
                    slid: unifiedSlid,
                    ssid24g: ssid24g,
                    pass24g: pass24g,
                    ssid5g: ssid5g,
                    pass5g: pass5g,
                    isValid: isValid,
                    validationErrors: validationErrors,
                    status: status,
                    startTime: startTime,
                    endTime: endTime,
                    errorMessage: errorMessage,
                    lastKnownAdminPass: lastKnownAdminPass,
                    rawRowData: rawRowData
                )
            )
        }
        return results
    }

    @discardableResult
    private func insertOrReplaceUnlocked(record: SubscriberRecord) -> Bool {
        guard let handle = db else { return false }
        let sql = """
        INSERT OR REPLACE INTO subscribers (
            rowIndex, stt, toDiaBan, nvkt, tenTb, diachiTb, soDt, soDtNormalized,
            soMay, accountPppoe, passPppoe, maMen, slid, ssid24g, pass24g, ssid5g, pass5g,
            isValid, validationErrorsJson, status, startTime, endTime, errorMessage,
            lastKnownAdminPass, rawRowJson
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else {
            return false
        }
        defer { sqlite3_finalize(stmt) }

        let errorsJson = (try? String(data: JSONEncoder().encode(record.validationErrors), encoding: .utf8)) ?? "[]"
        let rawJson = (try? String(data: JSONEncoder().encode(record.rawRowData), encoding: .utf8)) ?? "{}"
        let soDtNormalized = ExcelManager.normalizePhoneForSearch(record.soDt)
        let unifiedSlid = record.slid.isEmpty ? record.soMay : record.slid

        sqlite3_bind_int(stmt, 1, Int32(record.rowIndex))
        if let stt = record.stt {
            sqlite3_bind_int(stmt, 2, Int32(stt))
        } else {
            sqlite3_bind_null(stmt, 2)
        }
        bindText(stmt, 3, record.toDiaBan)
        bindText(stmt, 4, record.nvkt)
        bindText(stmt, 5, record.tenTb)
        bindText(stmt, 6, record.diachiTb)
        bindText(stmt, 7, record.soDt)
        bindText(stmt, 8, soDtNormalized)
        bindText(stmt, 9, unifiedSlid)
        bindText(stmt, 10, record.accountPppoe)
        bindText(stmt, 11, record.passPppoe)
        bindText(stmt, 12, record.maMen)
        bindText(stmt, 13, unifiedSlid)
        bindText(stmt, 14, record.ssid24g)
        bindText(stmt, 15, record.pass24g)
        bindText(stmt, 16, record.ssid5g)
        bindText(stmt, 17, record.pass5g)
        sqlite3_bind_int(stmt, 18, record.isValid ? 1 : 0)
        bindText(stmt, 19, errorsJson)
        bindText(stmt, 20, record.status.rawValue)
        bindText(stmt, 21, record.startTime)
        bindText(stmt, 22, record.endTime)
        bindText(stmt, 23, record.errorMessage)
        bindText(stmt, 24, record.lastKnownAdminPass)
        bindText(stmt, 25, rawJson)

        return sqlite3_step(stmt) == SQLITE_DONE
    }

    private func bindText(_ stmt: OpaquePointer?, _ index: Int32, _ value: String) {
        sqlite3_bind_text(stmt, index, (value as NSString).utf8String, -1, SQLITE_TRANSIENT)
    }

    private func columnText(_ stmt: OpaquePointer?, _ index: Int32) -> String {
        guard let cStr = sqlite3_column_text(stmt, index) else { return "" }
        return String(cString: cStr)
    }
}
