package io.nekohasekai.sfa.bg

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class OOMReport(
    val id: String,
    val date: Date,
    val directory: File,
    val isRead: Boolean,
)

data class OOMReportFile(
    val kind: Kind,
    val displayName: String,
    val file: File,
) {
    enum class Kind {
        METADATA,
        CONFIG,
        GO_LOG,
        PROFILE,
    }
}

object OOMReportManager {
    private const val METADATA_FILE_NAME = "metadata.json"
    private const val CONFIG_FILE_NAME = "configuration.json"
    private const val GO_LOG_FILE_NAME = "go.log"
    private const val CMDLINE_FILE_NAME = "cmdline"
    private const val READ_MARKER_FILE_NAME = ".read"
    private const val OOM_REPORTS_DIR_NAME = "oom_reports"

    private lateinit var workingDir: File
    private val reportsMutex = Mutex()
    private val reportFilesMutex = Mutex()

    private val _reports = MutableStateFlow<List<OOMReport>>(emptyList())
    val reports: StateFlow<List<OOMReport>> = _reports
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount

    fun install(workingDir: File) {
        this.workingDir = workingDir
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        reportsMutex.withLock {
            publishReports(scanReports())
        }
    }

    private fun scanReports(): List<OOMReport> {
        val reportsDir = File(workingDir, OOM_REPORTS_DIR_NAME)
        if (!reportsDir.isDirectory) return emptyList()
        val directories = reportsDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        return directories.mapNotNull { dir ->
            val date = parseTimestamp(dir.name) ?: return@mapNotNull null
            OOMReport(
                id = dir.name,
                date = date,
                directory = dir,
                isRead = File(dir, READ_MARKER_FILE_NAME).exists(),
            )
        }.sortedByDescending { it.date }
    }

    fun availableFiles(report: OOMReport): List<OOMReportFile> {
        val files = mutableListOf<OOMReportFile>()
        val metadataFile = File(report.directory, METADATA_FILE_NAME)
        if (metadataFile.exists()) {
            files.add(OOMReportFile(OOMReportFile.Kind.METADATA, "Metadata", metadataFile))
        }
        val configFile = File(report.directory, CONFIG_FILE_NAME)
        if (configFile.exists()) {
            files.add(OOMReportFile(OOMReportFile.Kind.CONFIG, "Configuration", configFile))
        }
        val goLogFile = File(report.directory, GO_LOG_FILE_NAME)
        if (goLogFile.exists()) {
            files.add(OOMReportFile(OOMReportFile.Kind.GO_LOG, "Log", goLogFile))
        }
        report.directory.listFiles()?.filter { file ->
            file.isFile &&
                file.name != METADATA_FILE_NAME &&
                file.name != CONFIG_FILE_NAME &&
                file.name != GO_LOG_FILE_NAME &&
                file.name != CMDLINE_FILE_NAME &&
                file.name != READ_MARKER_FILE_NAME
        }?.sortedBy { it.name }?.forEach { file ->
            files.add(OOMReportFile(OOMReportFile.Kind.PROFILE, file.name, file))
        }
        return files
    }

    fun loadFileContent(file: OOMReportFile): String {
        if (!file.file.exists()) return ""
        val content = file.file.readText()
        if (file.kind == OOMReportFile.Kind.METADATA) {
            return runCatching {
                JSONObject(content).toString(2)
            }.getOrDefault(content)
        }
        return content
    }

    suspend fun markAsRead(report: OOMReport) = withContext(Dispatchers.IO) {
        reportsMutex.withLock {
            if (!report.directory.isDirectory) return@withLock
            val readMarker = File(report.directory, READ_MARKER_FILE_NAME)
            if (!readMarker.exists()) {
                readMarker.createNewFile()
            }
            if (_reports.value.none { it.id == report.id && !it.isRead }) return@withLock
            publishReports(
                _reports.value.map {
                    if (it.id == report.id) it.copy(isRead = true) else it
                },
            )
        }
    }

    suspend fun delete(report: OOMReport) = withContext(Dispatchers.IO) {
        reportFilesMutex.withLock {
            reportsMutex.withLock {
                report.directory.deleteRecursively()
                publishReports(_reports.value.filter { it.id != report.id })
            }
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        reportFilesMutex.withLock {
            reportsMutex.withLock {
                File(workingDir, OOM_REPORTS_DIR_NAME).deleteRecursively()
                publishReports(emptyList())
            }
        }
    }

    suspend fun createZipArchive(report: OOMReport, includeConfig: Boolean, includeLog: Boolean, useAgeEncryption: Boolean): File = withContext(Dispatchers.IO) {
        reportFilesMutex.withLock {
            val cacheDir = File(Application.application.cacheDir, OOM_REPORTS_DIR_NAME)
            cacheDir.mkdirs()
            val zipFile = File(cacheDir, if (useAgeEncryption) "${report.id}.zip.age" else "${report.id}.zip")
            zipFile.delete()
            val strippedDir = File(cacheDir, report.id)
            strippedDir.deleteRecursively()
            report.directory.copyRecursively(strippedDir, overwrite = true)
            File(strippedDir, READ_MARKER_FILE_NAME).delete()
            if (!includeConfig) {
                File(strippedDir, CONFIG_FILE_NAME).delete()
            }
            if (!includeLog) {
                File(strippedDir, GO_LOG_FILE_NAME).delete()
            }
            Libbox.createZipArchive(strippedDir.path, zipFile.path, useAgeEncryption)
            zipFile
        }
    }

    private fun parseTimestamp(name: String): Date? {
        val components = name.split("-")
        val baseName = if (components.size > 5 && components.last().toIntOrNull() != null) {
            components.dropLast(1).joinToString("-")
        } else {
            name
        }
        return try {
            newTimestampFormat().parse(baseName)
        } catch (_: ParseException) {
            null
        }
    }

    private fun publishReports(reports: List<OOMReport>) {
        _reports.value = reports
        _unreadCount.value = reports.count { !it.isRead }
    }

    private fun newTimestampFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}
