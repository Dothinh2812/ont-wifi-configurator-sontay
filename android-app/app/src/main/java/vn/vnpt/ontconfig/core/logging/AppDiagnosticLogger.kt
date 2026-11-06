package vn.vnpt.ontconfig.core.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.vnpt.ontconfig.core.validation.OntInputValidator
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel {
    INFO,
    WARN,
    ERROR
}

data class DiagnosticLogEntry(
    val seq: Long,
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun formatLine(): String = "[$timestamp] [${level.name}] [$tag] $message"
}

/**
 * Thread-safe in-app diagnostic logger with synchronous 2-layer credential scrubbing
 * on the caller thread, a bounded 1,500-entry RAM ring buffer (`entriesFlow` decoupled
 * from `MainUiState`), and a serialized 512 KB x 2 rotating log file in `noBackupFilesDir/logs`.
 */
class AppDiagnosticLogger(
    private val logDir: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES
) : Closeable {

    constructor(
        context: Context,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES
    ) : this(
        logDir = File(context.applicationContext.noBackupFilesDir, "logs"),
        maxFileBytes = maxFileBytes
    )

    private val seqGenerator = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private val bufferLock = Any()
    private val secretsLock = Any()

    private val memoryBuffer = ArrayDeque<DiagnosticLogEntry>(MAX_MEMORY_ENTRIES)
    private val _entriesFlow = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val entriesFlow: StateFlow<List<DiagnosticLogEntry>> = _entriesFlow.asStateFlow()

    @Volatile
    private var activeSecretTokens: List<String> = emptyList()

    private val knownSubscriberIdentifiers = LinkedHashSet<String>()

    @Volatile
    private var subscriberMaskTokens: List<String> = emptyList()

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ont-diag-logger").apply { isDaemon = true }
    }

    fun registerActiveSecrets(
        secrets: Collection<String>,
        subscriberIdentifiers: Collection<String> = emptyList()
    ) {
        synchronized(secretsLock) {
            val secretVariants = LinkedHashSet<String>()
            for (raw in secrets) {
                if (raw.isEmpty()) continue
                if (raw.trim().lowercase() in RESERVED_SAFE_TOKENS) continue
                val candidates = listOf(
                    raw,
                    OntInputValidator.urlEncodeFormValue(raw),
                    OntInputValidator.ontEncodeQuery(raw)
                )
                for (candidate in candidates) {
                    if (
                        candidate.length >= MIN_UNKEYED_SECRET_LENGTH &&
                        candidate.trim().lowercase() !in RESERVED_SAFE_TOKENS
                    ) {
                        secretVariants.add(candidate)
                    }
                }
            }
            activeSecretTokens = secretVariants.sortedByDescending { it.length }

            for (rawId in subscriberIdentifiers) {
                val cleanId = rawId.trim()
                if (cleanId.length >= 4 && cleanId.lowercase() !in RESERVED_SAFE_TOKENS) {
                    if (knownSubscriberIdentifiers.size >= 128) {
                        val oldest = knownSubscriberIdentifiers.firstOrNull()
                        if (oldest != null) knownSubscriberIdentifiers.remove(oldest)
                    }
                    knownSubscriberIdentifiers.add(cleanId)
                }
            }
            subscriberMaskTokens = knownSubscriberIdentifiers.sortedByDescending { it.length }
        }
    }

    fun clearActiveSecrets() {
        synchronized(secretsLock) {
            activeSecretTokens = emptyList()
        }
    }

    fun log(level: LogLevel, tag: String, rawMessage: String) {
        val cleanTag = tag.trim().ifEmpty { "APP" }
        val scrubbedMessage = scrubSecretsSync(rawMessage)
        val entry = DiagnosticLogEntry(
            seq = seqGenerator.incrementAndGet(),
            timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER),
            level = level,
            tag = cleanTag,
            message = scrubbedMessage
        )

        synchronized(bufferLock) {
            while (memoryBuffer.size >= MAX_MEMORY_ENTRIES) {
                memoryBuffer.removeFirst()
            }
            memoryBuffer.addLast(entry)
            _entriesFlow.value = memoryBuffer.toList()
        }

        runCatching {
            val priority = when (level) {
                LogLevel.INFO -> Log.INFO
                LogLevel.WARN -> Log.WARN
                LogLevel.ERROR -> Log.ERROR
            }
            Log.println(priority, "ONT_$cleanTag", scrubbedMessage)
        }

        if (!closed.get()) {
            val formattedBytes = "${entry.formatLine()}\n".toByteArray(Charsets.UTF_8)
            try {
                executor.execute {
                    appendLineToDisk(formattedBytes)
                }
            } catch (_: RejectedExecutionException) {
            }
        }
    }

    fun flushForTest() {
        if (closed.get()) return
        runCatching {
            executor.submit(Callable { Unit }).get(IO_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    fun exportCombinedLogText(
        deviceHeader: String = "",
        maskSubscriberIdentifiers: Boolean = true
    ): String {
        val rawCombined = if (!closed.get()) {
            runCatching {
                executor.submit(Callable { readDiskOrMemoryLogsLocked() })
                    .get(IO_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrElse { readMemoryLogsSnapshot() }
        } else {
            readMemoryLogsSnapshot()
        }

        val sb = StringBuilder()
        if (deviceHeader.isNotBlank()) {
            sb.append(deviceHeader.trim()).append('\n')
            sb.append("==================================================\n")
        }
        sb.append(rawCombined)

        var sanitized = scrubSecretsSync(sb.toString())
        if (maskSubscriberIdentifiers) {
            sanitized = maskPiiAndMacMiddleOctets(sanitized)
        }
        return sanitized
    }

    fun clearLogs() {
        synchronized(bufferLock) {
            memoryBuffer.clear()
        }
        _entriesFlow.value = emptyList()
        synchronized(secretsLock) {
            if (activeSecretTokens.isEmpty()) {
                knownSubscriberIdentifiers.clear()
                subscriberMaskTokens = emptyList()
            }
        }
        if (!closed.get()) {
            runCatching {
                executor.submit(Callable {
                    runCatching { File(logDir, CURRENT_LOG_FILE).delete() }
                    runCatching { File(logDir, PREV_LOG_FILE).delete() }
                }).get(IO_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching {
                executor.shutdown()
                executor.awaitTermination(1, TimeUnit.SECONDS)
            }
        }
    }

    private fun scrubSecretsSync(rawText: String): String {
        var masked = OntInputValidator.maskSensitiveCredentials(rawText)
        val tokens = activeSecretTokens
        for (secret in tokens) {
            masked = masked.replace(secret, "***", ignoreCase = secret.contains('%'))
        }
        return masked
    }

    private fun maskPiiAndMacMiddleOctets(text: String): String {
        var masked = MAC_OCTETS_REGEX.replace(text, "$1:**:**:$2")
        val ids = subscriberMaskTokens
        for (identifier in ids) {
            val replacement = if (identifier.length >= 5) {
                "${identifier.take(2)}***${identifier.takeLast(2)}"
            } else {
                "${identifier.take(1)}**${identifier.takeLast(1)}"
            }
            masked = masked.replace(identifier, replacement)
        }
        return masked
    }

    private fun appendLineToDisk(lineBytes: ByteArray) {
        try {
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            val currentFile = File(logDir, CURRENT_LOG_FILE)
            if (currentFile.exists() && currentFile.length() + lineBytes.size > maxFileBytes) {
                val prevFile = File(logDir, PREV_LOG_FILE)
                if (prevFile.exists()) {
                    prevFile.delete()
                }
                if (!currentFile.renameTo(prevFile)) {
                    runCatching {
                        currentFile.copyTo(prevFile, overwrite = true)
                        currentFile.delete()
                    }
                }
            }
            FileOutputStream(currentFile, true).use { fos ->
                fos.write(lineBytes)
                fos.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun readDiskOrMemoryLogsLocked(): String {
        val prevFile = File(logDir, PREV_LOG_FILE)
        val currentFile = File(logDir, CURRENT_LOG_FILE)
        val sb = StringBuilder()
        if (prevFile.exists()) {
            runCatching { sb.append(prevFile.readText(Charsets.UTF_8)) }
        }
        if (currentFile.exists()) {
            runCatching { sb.append(currentFile.readText(Charsets.UTF_8)) }
        }
        return if (sb.isNotEmpty()) sb.toString() else readMemoryLogsSnapshot()
    }

    private fun readMemoryLogsSnapshot(): String =
        synchronized(bufferLock) {
            memoryBuffer.joinToString(separator = "\n", postfix = if (memoryBuffer.isEmpty()) "" else "\n") {
                it.formatLine()
            }
        }

    companion object {
        const val MAX_MEMORY_ENTRIES: Int = 1_500
        const val DEFAULT_MAX_FILE_BYTES: Long = 512L * 1024L
        private const val MIN_UNKEYED_SECRET_LENGTH: Int = 6
        private const val IO_SYNC_TIMEOUT_SECONDS: Long = 3L
        private const val CURRENT_LOG_FILE: String = "ont_debug.log"
        private const val PREV_LOG_FILE: String = "ont_debug.prev.log"

        private val TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        private val RESERVED_SAFE_TOKENS: Set<String> = setOf(
            "admin",
            "root",
            "open",
            "null",
            "true",
            "false",
            "192.168.1.1"
        )

        private val MAC_OCTETS_REGEX: Regex = Regex(
            "\\b([0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}):[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:([0-9A-Fa-f]{2})\\b"
        )
    }
}
