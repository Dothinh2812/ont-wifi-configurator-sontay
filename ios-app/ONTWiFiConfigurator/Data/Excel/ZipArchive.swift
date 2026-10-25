import Foundation
import zlib

public enum ZipArchiveError: LocalizedError {
    case invalidArchive(String)
    case unsafeEntryPath(String)
    case entryLimitExceeded(Int)
    case sizeLimitExceeded(String)
    case unsupportedCompressionMethod(UInt16)
    case zlibFailed(Int32)

    public var errorDescription: String? {
        switch self {
        case .invalidArchive(let msg):
            return "File ZIP/Excel không hợp lệ: \(msg)"
        case .unsafeEntryPath(let path):
            return "Đường dẫn trong file ZIP không an toàn: \(path)"
        case .entryLimitExceeded(let count):
            return "Số lượng tệp trong ZIP vượt giới hạn an toàn (\(count) > 128)"
        case .sizeLimitExceeded(let msg):
            return "Dung lượng giải nén vượt giới hạn an toàn: \(msg)"
        case .unsupportedCompressionMethod(let method):
            return "Phương thức nén ZIP không được hỗ trợ: \(method)"
        case .zlibFailed(let code):
            return "Lỗi nén/giải nén dữ liệu zlib (mã lỗi: \(code))"
        }
    }
}

public enum ZipArchive {

    public static let maxEntries = 128
    public static let maxSingleEntrySize = 16 * 1024 * 1024
    public static let maxTotalUncompressedSize = 32 * 1024 * 1024

    private static let eocdSignature: UInt32 = 0x06054b50
    private static let centralFileHeaderSignature: UInt32 = 0x02014b50
    private static let localFileHeaderSignature: UInt32 = 0x04034b50

    /// Extract all file entries from a ZIP archive (.xlsx) using Central Directory and raw DEFLATE (windowBits = -MAX_WBITS = -15).
    public static func extractEntries(from archiveData: Data) throws -> [String: Data] {
        guard archiveData.count >= 22 else {
            throw ZipArchiveError.invalidArchive("Dung lượng file quá nhỏ (< 22 bytes)")
        }

        let eocdOffset = try findEOCD(in: archiveData)
        let totalEntries = Int(readUInt16LE(archiveData, at: eocdOffset + 10))
        let centralDirSize = Int(readUInt32LE(archiveData, at: eocdOffset + 12))
        let centralDirOffset = Int(readUInt32LE(archiveData, at: eocdOffset + 16))

        if totalEntries > maxEntries {
            throw ZipArchiveError.entryLimitExceeded(totalEntries)
        }
        guard centralDirOffset >= 0,
              centralDirSize >= 0,
              centralDirOffset + centralDirSize <= archiveData.count else {
            throw ZipArchiveError.invalidArchive("Central Directory nằm ngoài phạm vi tệp")
        }

        var entries: [String: Data] = [:]
        var totalUncompressedBytes = 0
        var cursor = centralDirOffset

        for _ in 0..<totalEntries {
            guard cursor + 46 <= archiveData.count else {
                throw ZipArchiveError.invalidArchive("Header Central Directory bị cắt cụt")
            }
            let sig = readUInt32LE(archiveData, at: cursor)
            guard sig == centralFileHeaderSignature else {
                throw ZipArchiveError.invalidArchive("Chữ ký Central Directory không hợp lệ")
            }

            let compressionMethod = readUInt16LE(archiveData, at: cursor + 10)
            let compressedSize = Int(readUInt32LE(archiveData, at: cursor + 20))
            let uncompressedSize = Int(readUInt32LE(archiveData, at: cursor + 24))
            let fileNameLen = Int(readUInt16LE(archiveData, at: cursor + 28))
            let extraLen = Int(readUInt16LE(archiveData, at: cursor + 30))
            let commentLen = Int(readUInt16LE(archiveData, at: cursor + 32))
            let localHeaderOffset = Int(readUInt32LE(archiveData, at: cursor + 42))

            let nameStart = cursor + 46
            let nameEnd = nameStart + fileNameLen
            guard nameEnd + extraLen + commentLen <= archiveData.count else {
                throw ZipArchiveError.invalidArchive("Tên tệp trong Central Directory vượt quá phạm vi")
            }

            let fileNameData = archiveData.subdata(in: nameStart..<nameEnd)
            let fileName = String(data: fileNameData, encoding: .utf8) ?? ""
            cursor = nameEnd + extraLen + commentLen

            // Reject path traversal
            if fileName.hasPrefix("/") || fileName.contains("..") || fileName.contains("\\") {
                throw ZipArchiveError.unsafeEntryPath(fileName)
            }
            if fileName.hasSuffix("/") || fileName.isEmpty {
                continue
            }

            if uncompressedSize > maxSingleEntrySize || compressedSize > maxSingleEntrySize {
                throw ZipArchiveError.sizeLimitExceeded("Entry '\(fileName)' vượt quá 16MB")
            }
            if totalUncompressedBytes + uncompressedSize > maxTotalUncompressedSize {
                throw ZipArchiveError.sizeLimitExceeded("Tổng dung lượng giải nén vượt quá 32MB")
            }

            guard localHeaderOffset + 30 <= archiveData.count else {
                throw ZipArchiveError.invalidArchive("Local File Header nằm ngoài phạm vi")
            }
            let localSig = readUInt32LE(archiveData, at: localHeaderOffset)
            guard localSig == localFileHeaderSignature else {
                throw ZipArchiveError.invalidArchive("Chữ ký Local File Header không hợp lệ")
            }

            let localNameLen = Int(readUInt16LE(archiveData, at: localHeaderOffset + 26))
            let localExtraLen = Int(readUInt16LE(archiveData, at: localHeaderOffset + 28))
            let dataStart = localHeaderOffset + 30 + localNameLen + localExtraLen
            let dataEnd = dataStart + compressedSize
            guard dataStart >= 0, dataEnd <= archiveData.count else {
                throw ZipArchiveError.invalidArchive("Dữ liệu nén của '\(fileName)' vượt quá phạm vi tệp")
            }

            let compressedSlice = archiveData.subdata(in: dataStart..<dataEnd)
            let extracted: Data
            switch compressionMethod {
            case 0: // STORED
                extracted = compressedSlice
            case 8: // DEFLATE (raw RFC 1951)
                extracted = try inflateRawDeflate(
                    compressedSlice,
                    expectedSize: uncompressedSize
                )
            default:
                throw ZipArchiveError.unsupportedCompressionMethod(compressionMethod)
            }

            totalUncompressedBytes += max(uncompressedSize, extracted.count)
            if totalUncompressedBytes > maxTotalUncompressedSize {
                throw ZipArchiveError.sizeLimitExceeded("Tổng dung lượng giải nén vượt quá 32MB")
            }
            entries[fileName] = extracted
        }

        return entries
    }

    /// Create a valid .xlsx ZIP archive from entry dictionary in deterministic key order.
    public static func createArchive(entries: [String: Data]) throws -> Data {
        var output = Data()
        var centralDirectory = Data()
        let sortedNames = entries.keys.sorted()

        for name in sortedNames {
            guard !name.hasPrefix("/") && !name.contains("..") else {
                throw ZipArchiveError.unsafeEntryPath(name)
            }
            let uncompressed = entries[name] ?? Data()
            let compressed = try deflateRawDeflate(uncompressed)
            let crc = computeCRC32(uncompressed)
            let nameBytes = Data(name.utf8)
            let localHeaderOffset = UInt32(output.count)

            // Local file header (30 bytes + name)
            appendUInt32LE(&output, localFileHeaderSignature)
            appendUInt16LE(&output, 20) // version needed to extract (2.0)
            appendUInt16LE(&output, 0)  // general purpose bit flag
            appendUInt16LE(&output, 8)  // compression method: DEFLATE (8)
            appendUInt16LE(&output, 0)  // last mod file time
            appendUInt16LE(&output, 0)  // last mod file date
            appendUInt32LE(&output, crc)
            appendUInt32LE(&output, UInt32(compressed.count))
            appendUInt32LE(&output, UInt32(uncompressed.count))
            appendUInt16LE(&output, UInt16(nameBytes.count))
            appendUInt16LE(&output, 0)  // extra field length
            output.append(nameBytes)
            output.append(compressed)

            // Central directory file header (46 bytes + name)
            appendUInt32LE(&centralDirectory, centralFileHeaderSignature)
            appendUInt16LE(&centralDirectory, 20) // version made by
            appendUInt16LE(&centralDirectory, 20) // version needed to extract
            appendUInt16LE(&centralDirectory, 0)  // general purpose bit flag
            appendUInt16LE(&centralDirectory, 8)  // compression method: DEFLATE (8)
            appendUInt16LE(&centralDirectory, 0)  // last mod time
            appendUInt16LE(&centralDirectory, 0)  // last mod date
            appendUInt32LE(&centralDirectory, crc)
            appendUInt32LE(&centralDirectory, UInt32(compressed.count))
            appendUInt32LE(&centralDirectory, UInt32(uncompressed.count))
            appendUInt16LE(&centralDirectory, UInt16(nameBytes.count))
            appendUInt16LE(&centralDirectory, 0)  // extra field length
            appendUInt16LE(&centralDirectory, 0)  // file comment length
            appendUInt16LE(&centralDirectory, 0)  // disk number start
            appendUInt16LE(&centralDirectory, 0)  // internal file attributes
            appendUInt32LE(&centralDirectory, 0)  // external file attributes
            appendUInt32LE(&centralDirectory, localHeaderOffset)
            centralDirectory.append(nameBytes)
        }

        let centralDirOffset = UInt32(output.count)
        let centralDirSize = UInt32(centralDirectory.count)
        output.append(centralDirectory)

        // End of Central Directory (EOCD - 22 bytes)
        appendUInt32LE(&output, eocdSignature)
        appendUInt16LE(&output, 0) // number of this disk
        appendUInt16LE(&output, 0) // disk where central directory starts
        appendUInt16LE(&output, UInt16(sortedNames.count))
        appendUInt16LE(&output, UInt16(sortedNames.count))
        appendUInt32LE(&output, centralDirSize)
        appendUInt32LE(&output, centralDirOffset)
        appendUInt16LE(&output, 0) // comment length

        return output
    }

    // MARK: - Raw DEFLATE (-MAX_WBITS = -15) & CRC32 Helpers

    private static func inflateRawDeflate(_ compressed: Data, expectedSize: Int) throws -> Data {
        if compressed.isEmpty && expectedSize == 0 {
            return Data()
        }

        var stream = z_stream()
        // windowBits = -MAX_WBITS (-15) tells zlib to process raw RFC 1951 DEFLATE without zlib/gzip header
        let initStatus = inflateInit2_(
            &stream,
            -MAX_WBITS,
            ZLIB_VERSION,
            Int32(MemoryLayout<z_stream>.size)
        )
        guard initStatus == Z_OK else {
            throw ZipArchiveError.zlibFailed(initStatus)
        }
        defer {
            inflateEnd(&stream)
        }

        let chunkSize = max(expectedSize, 16384)
        var output = Data()
        output.reserveCapacity(max(expectedSize, 0))
        var buffer = [UInt8](repeating: 0, count: min(chunkSize, 65536))

        try compressed.withUnsafeBytes { rawInput in
            guard let baseAddress = rawInput.bindMemory(to: Bytef.self).baseAddress else {
                return
            }
            stream.next_in = UnsafeMutablePointer<Bytef>(mutating: baseAddress)
            stream.avail_in = uInt(compressed.count)

            while true {
                let status: Int32 = buffer.withUnsafeMutableBufferPointer { outPtr in
                    stream.next_out = outPtr.baseAddress
                    stream.avail_out = uInt(outPtr.count)
                    return inflate(&stream, Z_NO_FLUSH)
                }

                let produced = buffer.count - Int(stream.avail_out)
                if produced > 0 {
                    if output.count + produced > maxSingleEntrySize {
                        throw ZipArchiveError.sizeLimitExceeded("Entry vượt quá 16MB khi giải nén")
                    }
                    output.append(buffer, count: produced)
                }

                if status == Z_STREAM_END {
                    break
                }
                if status != Z_OK {
                    throw ZipArchiveError.zlibFailed(status)
                }
                if stream.avail_in == 0 && produced == 0 {
                    break
                }
            }
        }

        return output
    }

    private static func deflateRawDeflate(_ uncompressed: Data) throws -> Data {
        var stream = z_stream()
        // windowBits = -MAX_WBITS (-15) produces raw RFC 1951 DEFLATE stream required by ZIP
        let initStatus = deflateInit2_(
            &stream,
            Z_DEFAULT_COMPRESSION,
            Z_DEFLATED,
            -MAX_WBITS,
            8,
            Z_DEFAULT_STRATEGY,
            ZLIB_VERSION,
            Int32(MemoryLayout<z_stream>.size)
        )
        guard initStatus == Z_OK else {
            throw ZipArchiveError.zlibFailed(initStatus)
        }
        defer {
            deflateEnd(&stream)
        }

        var output = Data()
        var buffer = [UInt8](repeating: 0, count: 32768)

        try uncompressed.withUnsafeBytes { rawInput in
            if let baseAddress = rawInput.bindMemory(to: Bytef.self).baseAddress {
                stream.next_in = UnsafeMutablePointer<Bytef>(mutating: baseAddress)
            }
            stream.avail_in = uInt(uncompressed.count)

            while true {
                let status: Int32 = buffer.withUnsafeMutableBufferPointer { outPtr in
                    stream.next_out = outPtr.baseAddress
                    stream.avail_out = uInt(outPtr.count)
                    return deflate(&stream, Z_FINISH)
                }

                let produced = buffer.count - Int(stream.avail_out)
                if produced > 0 {
                    output.append(buffer, count: produced)
                }

                if status == Z_STREAM_END {
                    break
                }
                if status != Z_OK {
                    throw ZipArchiveError.zlibFailed(status)
                }
            }
        }

        return output
    }

    private static func computeCRC32(_ data: Data) -> UInt32 {
        return data.withUnsafeBytes { rawPtr -> UInt32 in
            guard let base = rawPtr.bindMemory(to: Bytef.self).baseAddress else {
                return 0
            }
            let c = crc32(0, base, uInt(data.count))
            return UInt32(truncatingIfNeeded: c)
        }
    }

    private static func findEOCD(in data: Data) throws -> Int {
        let minEOCDSize = 22
        let maxCommentSize = 65535
        let startSearch = max(0, data.count - minEOCDSize - maxCommentSize)
        var offset = data.count - minEOCDSize
        while offset >= startSearch {
            if readUInt32LE(data, at: offset) == eocdSignature {
                return offset
            }
            offset -= 1
        }
        throw ZipArchiveError.invalidArchive("Không tìm thấy End of Central Directory (EOCD)")
    }

    private static func readUInt16LE(_ data: Data, at offset: Int) -> UInt16 {
        let b0 = UInt16(data[data.startIndex + offset])
        let b1 = UInt16(data[data.startIndex + offset + 1]) << 8
        return b0 | b1
    }

    private static func readUInt32LE(_ data: Data, at offset: Int) -> UInt32 {
        let b0 = UInt32(data[data.startIndex + offset])
        let b1 = UInt32(data[data.startIndex + offset + 1]) << 8
        let b2 = UInt32(data[data.startIndex + offset + 2]) << 16
        let b3 = UInt32(data[data.startIndex + offset + 3]) << 24
        return b0 | b1 | b2 | b3
    }

    private static func appendUInt16LE(_ data: inout Data, _ value: UInt16) {
        var le = value.littleEndian
        withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
    }

    private static func appendUInt32LE(_ data: inout Data, _ value: UInt32) {
        var le = value.littleEndian
        withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
    }
}
