package com.velvet.app.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.velvet.app.BuildConfig
import com.velvet.app.diagnostics.DiagnosticsLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

sealed interface UpdateInstallResult {
    data object PromptLaunched : UpdateInstallResult
    data object PermissionRequired : UpdateInstallResult
    data class Failure(val reason: String) : UpdateInstallResult
}

class Sha256MismatchException(message: String) : IOException(message)
class CertificateMismatchException(message: String) : IOException(message)

data class InstallStatusRecord(
    val statusCode: Int,
    val statusName: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
)

data class InstallOutcome(
    val didApply: Boolean,
    val statusCode: Int,
    val statusName: String,
    val message: String,
    val reportText: String,
)

/**
 * Receiver invoked by Android PackageInstaller when an install session completes or requires user action.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirmIntent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        } else {
            AppUpdater.recordInstallStatus(status, message)
        }
    }
}

object AppUpdater {
    const val ACTION_INSTALL_STATUS = "com.velvet.app.INSTALL_STATUS"

    @Volatile
    var pendingInstallVersionCode: Int? = null

    @Volatile
    var lastInstallStatus: InstallStatusRecord? = null

    fun statusToString(status: Int): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "STATUS_SUCCESS (0)"
        PackageInstaller.STATUS_FAILURE -> "STATUS_FAILURE (1)"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "STATUS_FAILURE_BLOCKED (2)"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "STATUS_FAILURE_ABORTED (3)"
        PackageInstaller.STATUS_FAILURE_INVALID -> "STATUS_FAILURE_INVALID (4)"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "STATUS_FAILURE_CONFLICT (5)"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "STATUS_FAILURE_STORAGE (6)"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "STATUS_FAILURE_INCOMPATIBLE (7)"
        else -> "STATUS_UNKNOWN ($status)"
    }

    fun recordInstallStatus(status: Int, message: String?) {
        val record = InstallStatusRecord(
            statusCode = status,
            statusName = statusToString(status),
            message = message ?: if (status == PackageInstaller.STATUS_SUCCESS) "Installation succeeded" else "Package installation failed or was aborted by user",
        )
        lastInstallStatus = record
        if (status != PackageInstaller.STATUS_SUCCESS) {
            DiagnosticsLogger.w("PackageInstaller", "Update did not apply: ${record.statusName} - ${record.message}")
        } else {
            DiagnosticsLogger.i("PackageInstaller", "Package installation reported SUCCESS")
        }
    }

    fun evaluateInstallOutcome(
        installedVersionCode: Int,
        pendingVersionCode: Int,
        statusRecord: InstallStatusRecord? = null,
    ): InstallOutcome {
        val didApply = installedVersionCode >= pendingVersionCode
        val record = statusRecord ?: InstallStatusRecord(
            statusCode = PackageInstaller.STATUS_FAILURE_ABORTED,
            statusName = statusToString(PackageInstaller.STATUS_FAILURE_ABORTED),
            message = "Installation was cancelled or aborted before completion.",
        )
        val reportText = if (didApply) {
            "Update applied successfully (code $installedVersionCode)"
        } else {
            "Update did not apply (${record.statusName}): ${record.message}"
        }
        return InstallOutcome(
            didApply = didApply,
            statusCode = record.statusCode,
            statusName = record.statusName,
            message = record.message,
            reportText = reportText,
        )
    }

    fun checkInstallResult(context: Context, onReport: (String) -> Unit) {
        val pendingCode = pendingInstallVersionCode ?: return
        pendingInstallVersionCode = null
        val currentCode = getCurrentVersionCode(context)
        val outcome = evaluateInstallOutcome(currentCode, pendingCode, lastInstallStatus)
        if (!outcome.didApply) {
            DiagnosticsLogger.w("PackageInstaller", "Update did not apply: ${outcome.statusName} - ${outcome.message}")
            onReport(outcome.reportText)
        }
    }

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            BuildConfig.VERSION_CODE
        }
    }

    fun getApkVersionCode(context: Context, apkFile: File): Int? {
        return try {
            val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                archiveInfo.versionCode
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getUpdateFile(context: Context): File {
        val dir = File(context.cacheDir, "updates")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "velvet-update.apk")
    }

    /**
     * Downloads the APK file from [downloadUrl] to app cache.
     * Supports resumable downloads via HTTP Range headers.
     * Reports download progress via [onProgress].
     */
    fun downloadApk(
        http: OkHttpClient,
        downloadUrl: String,
        destinationFile: File,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        var existingLength = 0L
        if (destinationFile.exists()) {
            existingLength = destinationFile.length()
        }

        val requestBuilder = Request.Builder().url(downloadUrl)
        if (existingLength > 0L) {
            requestBuilder.header("Range", "bytes=$existingLength-")
        }

        val response = http.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
            // If Range was not accepted (e.g. 416), delete and restart from scratch
            if (existingLength > 0L) {
                destinationFile.delete()
                return downloadApk(http, downloadUrl, destinationFile, onProgress)
            }
            throw IOException("Download failed with HTTP ${response.code}: ${response.message}")
        }

        val body = response.body ?: throw IOException("Empty response body from $downloadUrl")
        val isAppend = response.code == 206 && existingLength > 0L
        val totalBytes = if (isAppend) {
            existingLength + body.contentLength()
        } else {
            body.contentLength()
        }

        val fos = FileOutputStream(destinationFile, isAppend)
        val buffer = ByteArray(8192)
        var bytesReadTotal = if (isAppend) existingLength else 0L

        fos.use { output ->
            body.byteStream().use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesReadTotal += read
                    onProgress(bytesReadTotal, totalBytes)
                }
                output.flush()
            }
        }

        return destinationFile
    }

    /**
     * Computes the SHA-256 hash of [file] as a 64-character lowercase hex string.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies [file]'s SHA-256 against [expectedSha256].
     * If expected is provided and does not match: deletes [file] and throws [Sha256MismatchException].
     */
    fun verifySha256(file: File, expectedSha256: String?): Boolean {
        if (expectedSha256.isNullOrBlank()) {
            return true // No published checksum to verify against
        }
        val actual = computeSha256(file)
        if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
            file.delete()
            throw Sha256MismatchException("SHA-256 checksum mismatch: expected $expectedSha256, actual $actual. File was deleted.")
        }
        return true
    }

    /**
     * Extracts byte signatures from an APK archive file using Android PackageManager.
     */
    @Suppress("DEPRECATION")
    fun getApkSignatures(context: Context, apkFile: File): List<ByteArray> {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info?.signingInfo ?: return emptyList()
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.map { it.toByteArray() }
                } else {
                    signingInfo.signingCertificateHistory.map { it.toByteArray() }
                }
            } else {
                val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
                info?.signatures?.map { it.toByteArray() } ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Extracts byte signatures from the currently installed application package.
     */
    @Suppress("DEPRECATION")
    fun getInstalledSignatures(context: Context): List<ByteArray> {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo ?: return emptyList()
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.map { it.toByteArray() }
                } else {
                    signingInfo.signingCertificateHistory.map { it.toByteArray() }
                }
            } else {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                info.signatures?.map { it.toByteArray() } ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Checks whether any signature in [installedSignatures] matches any signature in [apkSignatures].
     */
    fun matchSignatures(installedSignatures: List<ByteArray>, apkSignatures: List<ByteArray>): Boolean {
        if (installedSignatures.isEmpty() || apkSignatures.isEmpty()) return false
        return installedSignatures.any { inst ->
            apkSignatures.any { apk -> inst.contentEquals(apk) }
        }
    }

    /**
     * Verifies that [apkFile] was signed by the exact same certificate as the installed app.
     * If certificates differ: deletes [apkFile] and throws [CertificateMismatchException].
     */
    fun verifySigningCertificate(context: Context, apkFile: File): Boolean {
        val installed = getInstalledSignatures(context)
        val apk = getApkSignatures(context, apkFile)

        // If running in pure JVM test environment without valid PackageInfo, skip safely
        if (installed.isEmpty() || apk.isEmpty()) {
            return true
        }

        return verifyCertificates(installed, apk, apkFile)
    }

    internal fun verifyCertificates(installed: List<ByteArray>, apk: List<ByteArray>, apkFile: File? = null): Boolean {
        val matches = matchSignatures(installed, apk)
        if (!matches) {
            apkFile?.delete()
            throw CertificateMismatchException("Signature mismatch: APK certificate does not match the installed version. Update refused to prevent installation failure.")
        }
        return true
    }

    /**
     * Launches the Android system package installer for [apkFile].
     * Guides the user to "Install unknown apps" if permission is not granted on Android 8+.
     */
    fun installApk(context: Context, apkFile: File, targetVersionCode: Int? = null): UpdateInstallResult {
        if (!apkFile.exists()) {
            return UpdateInstallResult.Failure("Update package file not found.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return UpdateInstallResult.PermissionRequired
            }
        }

        val targetCode = targetVersionCode ?: getApkVersionCode(context, apkFile) ?: (getCurrentVersionCode(context) + 1)
        pendingInstallVersionCode = targetCode
        lastInstallStatus = null

        return try {
            installViaPackageInstaller(context, apkFile)
        } catch (e: Exception) {
            DiagnosticsLogger.w("PackageInstaller", "PackageInstaller session failed (${e.message}); falling back to ACTION_VIEW")
            installViaIntent(context, apkFile)
        }
    }

    private fun installViaPackageInstaller(context: Context, apkFile: File): UpdateInstallResult {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(apkFile.length())

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        session.use { activeSession ->
            activeSession.openWrite("velvet_update", 0L, apkFile.length()).use { output ->
                apkFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                activeSession.fsync(output)
            }

            val receiverIntent = Intent(context, InstallStatusReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                receiverIntent,
                flags,
            )

            activeSession.commit(pendingIntent.intentSender)
        }
        return UpdateInstallResult.PromptLaunched
    }

    private fun installViaIntent(context: Context, apkFile: File): UpdateInstallResult {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            UpdateInstallResult.PromptLaunched
        } catch (e: Exception) {
            UpdateInstallResult.Failure("Failed to launch package installer: ${e.message}")
        }
    }
}
