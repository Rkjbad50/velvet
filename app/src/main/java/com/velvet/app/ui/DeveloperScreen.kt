package com.velvet.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.velvet.app.BuildConfig
import com.velvet.app.VelvetApp
import com.velvet.app.data.ReleaseCheck
import com.velvet.app.data.StreamResolver
import com.velvet.app.data.UpdateInfo
import com.velvet.app.data.YouTube
import com.velvet.app.diagnostics.CheckResult
import com.velvet.app.diagnostics.DiagnosticRunner
import com.velvet.app.diagnostics.DiagnosticStep
import com.velvet.app.diagnostics.DiagnosticsLogger
import com.velvet.app.diagnostics.DiagnosticsVerdict
import com.velvet.app.diagnostics.LastErrorInfo
import com.velvet.app.diagnostics.LastErrorTracker
import com.velvet.app.diagnostics.LogEntry
import com.velvet.app.diagnostics.LogLevel
import com.velvet.app.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DeveloperScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val lastError by LastErrorTracker.lastError.collectAsStateWithLifecycle()
    val diagState by DiagnosticRunner.state.collectAsStateWithLifecycle()
    val logEntries by DiagnosticsLogger.entries.collectAsStateWithLifecycle()

    var activeLogFilter by remember { mutableStateOf<LogLevel?>(null) }
    var preferredClientName by remember {
        mutableStateOf(if (StreamResolver.forceFullExtractor) "NewPipeExtractor (Full)" else "Android Reel (Fast) + Fallback")
    }

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }

    fun runUpdateCheck(force: Boolean = false) {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        scope.launch(Dispatchers.IO) {
            try {
                val info = ReleaseCheck.checkUpdate(
                    http = VelvetApp.http,
                    installedVersionCode = BuildConfig.VERSION_CODE,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    includePrereleases = VelvetApp.library.data.value.includePrereleases,
                    forceRefresh = force,
                )
                withContext(Dispatchers.Main) {
                    updateInfo = info
                }
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main) {
                    isCheckingUpdate = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        runUpdateCheck(force = false)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(velvetColors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = velvetColors.text,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = "Developer Options",
                    style = MaterialTheme.typography.titleLarge,
                    color = velvetColors.text,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Diagnostics, system versions, logs & recovery",
                    style = MaterialTheme.typography.bodySmall,
                    color = velvetColors.muted,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // A1: Versions Panel
        DevSectionCard(title = "A1 • System & Build Versions") {
            val latestAppStr = updateInfo?.latestVersion?.let { " (latest: v$it)" } ?: if (isCheckingUpdate) " (checking...)" else ""
            val latestExtractorStr = updateInfo?.latestExtractorVersion?.let { " (latest: $it)" } ?: if (isCheckingUpdate) " (checking...)" else ""

            VersionRow("App Version", "${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})$latestAppStr")
            VersionRow("Git Commit", BuildConfig.GIT_COMMIT)
            VersionRow("Build Time", BuildConfig.BUILD_TIME)
            VersionRow("NewPipeExtractor", "${BuildConfig.NEWPIPE_VERSION}$latestExtractorStr")
            VersionRow("Media3 (ExoPlayer)", BuildConfig.MEDIA3_VERSION)
            VersionRow("Kotlin", BuildConfig.KOTLIN_VERSION)
            VersionRow("Compose BOM", BuildConfig.COMPOSE_VERSION)
            VersionRow("OkHttp", BuildConfig.OKHTTP_VERSION)
            VersionRow("SDK Targets", "Target API ${BuildConfig.TARGET_SDK_VERSION} • Compile ${BuildConfig.COMPILE_SDK_VERSION} • Min ${BuildConfig.MIN_SDK_VERSION}")
            VersionRow("Device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            VersionRow("Android Version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

            val statusText = when {
                isCheckingUpdate -> "Checking GitHub Releases..."
                updateInfo?.isRateLimited == true -> "API Rate Limited (cached info shown)"
                updateInfo?.isOffline == true -> "Network Offline (cached info shown)"
                updateInfo?.hasAppUpdate == true -> "Update Available (v${updateInfo?.latestVersion}, code ${updateInfo?.latestVersionCode})"
                updateInfo != null -> "Up to Date"
                else -> "Not checked"
            }
            VersionRow("Update Status", statusText)

            val activeAdvisories = updateInfo?.dependencyAdvisories?.filter { it.isNewer }.orEmpty()
            if (activeAdvisories.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                activeAdvisories.forEach { advisory ->
                    Text(
                        text = advisory.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE5A93C),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = { runUpdateCheck(force = true) },
                    enabled = !isCheckingUpdate,
                ) {
                    Text(if (isCheckingUpdate) "Checking..." else "Check for Updates")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // A2: Last Error Panel
        DevSectionCard(title = "A2 • Last Error") {
            if (lastError == null) {
                Text(
                    text = "No playback or extraction errors recorded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = velvetColors.muted,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                val err = lastError!!
                val timeStr = remember(err.timestamp) {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(err.timestamp))
                }
                VersionRow("Error Code", err.errorCodeName)
                VersionRow("Timestamp", timeStr)
                VersionRow("HTTP Status", err.httpStatus?.toString() ?: "None")
                VersionRow("URL Host (Only)", err.urlHost ?: "Unknown")
                VersionRow("Video ID", err.videoId ?: "None")
                VersionRow("Client Used", err.clientUsed ?: "Unknown")
                VersionRow("Resolve Latency", if (err.resolveDurationMs != null) "${err.resolveDurationMs} ms" else "N/A")

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Cause Chain:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = velvetColors.text,
                )
                err.causeChain.forEachIndexed { idx, cause ->
                    Text(
                        text = "  ${idx + 1}. $cause",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFFF8A80),
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        text = "Clear Last Error",
                        color = velvetColors.accentText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { LastErrorTracker.clear() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // A3: Run Diagnostics Panel
        DevSectionCard(title = "A3 • Diagnostics & Verdict") {
            Button(
                onClick = {
                    scope.launch { DiagnosticRunner.runDiagnostics(context) }
                },
                enabled = !diagState.isRunning,
                colors = ButtonDefaults.buttonColors(containerColor = velvetColors.accent),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                if (diagState.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Running Diagnostic Checks...", color = Color.White)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Run Diagnostics", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            // Verdict banner
            if (diagState.verdict != null) {
                Spacer(Modifier.height(12.dp))
                val isHealthy = diagState.verdict == DiagnosticsVerdict.HEALTHY
                val bannerBg = if (isHealthy) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(bannerBg)
                        .padding(12.dp),
                ) {
                    Column {
                        Text(
                            text = "VERDICT: ${diagState.verdict!!.plainText}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            text = when (diagState.verdict!!) {
                                DiagnosticsVerdict.HEALTHY -> "All 6 checks passed successfully. Audio pipeline is healthy."
                                DiagnosticsVerdict.NETWORK_PROBLEM -> "Cannot connect to network or resolve DNS. Check Wi-Fi/Mobile data."
                                DiagnosticsVerdict.URL_EXPIRED_OR_BLOCKED -> "Google video returned 403/410. Stream URL cache invalid or blocked."
                                DiagnosticsVerdict.EXTRACTOR_OUTDATED -> "NewPipeExtractor failed to parse YouTube response. YouTube likely changed format."
                                DiagnosticsVerdict.SONG_SPECIFIC -> "Song has age or geographic restrictions."
                                DiagnosticsVerdict.UNKNOWN_FAILURE -> "Diagnostics detected an unexpected failure."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFECEFF1),
                        )
                    }
                }
            }

            // Step results
            if (diagState.results.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                diagState.results.forEach { res ->
                    DiagnosticStepRow(res)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // A4: Actions Panel
        DevSectionCard(title = "A4 • Recovery & Developer Actions") {
            ActionTile(
                title = "Clear Stream Cache",
                subtitle = "Drops all cached googlevideo URLs and ExoPlayer cache",
                onClick = {
                    PlaybackService.clearStreamCache()
                    Toast.makeText(context, "Stream cache cleared", Toast.LENGTH_SHORT).show()
                },
            )

            HorizontalDivider(color = velvetColors.rule, thickness = 1.dp)

            ActionTile(
                title = "Re-resolve Current Song & Retry",
                subtitle = "Forces fresh resolution of currently playing track",
                onClick = {
                    PlaybackService.reResolveAndRetryCurrent()
                    Toast.makeText(context, "Re-resolving track...", Toast.LENGTH_SHORT).show()
                },
            )

            HorizontalDivider(color = velvetColors.rule, thickness = 1.dp)

            ActionTile(
                title = "Switch Preferred Client",
                subtitle = "Current: $preferredClientName",
                onClick = {
                    preferredClientName = StreamResolver.togglePreferredClient()
                    Toast.makeText(context, "Client switched to: $preferredClientName", Toast.LENGTH_SHORT).show()
                },
            )

            HorizontalDivider(color = velvetColors.rule, thickness = 1.dp)

            ActionTile(
                title = "Reset Extractor",
                subtitle = "Reinitializes NewPipeExtractor network engine",
                onClick = {
                    DiagnosticRunner.resetExtractor(context)
                    Toast.makeText(context, "Extractor reset", Toast.LENGTH_SHORT).show()
                },
            )

            HorizontalDivider(color = velvetColors.rule, thickness = 1.dp)

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val report = buildDiagnosticsReportText(lastError, diagState.results, diagState.verdict, logEntries)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("VELVET Diagnostics Report", report))
                        Toast.makeText(context, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Copy Report", color = velvetColors.text, fontSize = 13.sp)
                }

                FilledTonalButton(
                    onClick = {
                        val report = buildDiagnosticsReportText(lastError, diagState.results, diagState.verdict, logEntries)
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, report)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, "Share VELVET Diagnostics")
                        context.startActivity(shareIntent)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Export Report", fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // A5: Ring-Buffer Log Viewer
        DevSectionCard(title = "A5 • Ring-Buffer Log Viewer (500 lines)") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Filters
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip("ALL", activeLogFilter == null) { activeLogFilter = null }
                    FilterChip("INFO", activeLogFilter == LogLevel.INFO) { activeLogFilter = LogLevel.INFO }
                    FilterChip("WARN", activeLogFilter == LogLevel.WARN) { activeLogFilter = LogLevel.WARN }
                    FilterChip("ERROR", activeLogFilter == LogLevel.ERROR) { activeLogFilter = LogLevel.ERROR }
                }

                Text(
                    text = "Clear",
                    style = MaterialTheme.typography.labelSmall,
                    color = velvetColors.muted,
                    modifier = Modifier
                        .clickable { DiagnosticsLogger.clear() }
                        .padding(4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            val filteredEntries = remember(logEntries, activeLogFilter) {
                if (activeLogFilter == null) logEntries else logEntries.filter { it.level == activeLogFilter }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF101214))
                    .border(1.dp, velvetColors.rule, RoundedCornerShape(8.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                if (filteredEntries.isEmpty()) {
                    Text(
                        text = "No logs recorded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = velvetColors.muted,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    Column {
                        filteredEntries.forEach { entry ->
                            LogItem(entry)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun DevSectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = velvetColors.surface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = velvetColors.accentText,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun VersionRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = velvetColors.muted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = velvetColors.text,
        )
    }
}

@Composable
private fun DiagnosticStepRow(res: CheckResult) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (res.passed) Color(0xFF2E7D32) else Color(0xFFC62828)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (res.passed) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "${res.step.stepNumber}. ${res.step.title}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = velvetColors.text,
            )
            if (res.detail.isNotBlank()) {
                Text(
                    text = res.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (res.passed) velvetColors.muted else Color(0xFFFF8A80),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Text(
            text = "${res.latencyMs} ms",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = velvetColors.muted,
        )
    }
}

@Composable
private fun ActionTile(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = velvetColors.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = velvetColors.muted)
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) velvetColors.accent else velvetColors.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else velvetColors.text,
        )
    }
}

@Composable
private fun LogItem(entry: LogEntry) {
    val time = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestamp))
    }
    val levelColor = when (entry.level) {
        LogLevel.INFO -> Color(0xFF81C784)
        LogLevel.WARN -> Color(0xFFFFD54F)
        LogLevel.ERROR -> Color(0xFFE57373)
    }

    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF78909C),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.level.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = levelColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "[${entry.tag}]",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFB0BEC5),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFECEFF1),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

private fun buildDiagnosticsReportText(
    lastError: LastErrorInfo?,
    results: List<CheckResult>,
    verdict: DiagnosticsVerdict?,
    logs: List<LogEntry>,
): String = buildString {
    appendLine("=== VELVET DIAGNOSTICS REPORT ===")
    appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
    appendLine()
    appendLine("--- A1: VERSIONS ---")
    appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("Git commit: ${BuildConfig.GIT_COMMIT}")
    appendLine("Build time: ${BuildConfig.BUILD_TIME}")
    appendLine("NewPipeExtractor: ${BuildConfig.NEWPIPE_VERSION}")
    appendLine("Media3 (ExoPlayer): ${BuildConfig.MEDIA3_VERSION}")
    appendLine("Kotlin: ${BuildConfig.KOTLIN_VERSION}")
    appendLine("Compose: ${BuildConfig.COMPOSE_VERSION}")
    appendLine("OkHttp: ${BuildConfig.OKHTTP_VERSION}")
    appendLine("Target SDK: ${BuildConfig.TARGET_SDK_VERSION} | Compile: ${BuildConfig.COMPILE_SDK_VERSION} | Min: ${BuildConfig.MIN_SDK_VERSION}")
    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine()
    appendLine("--- A2: LAST ERROR ---")
    if (lastError != null) {
        appendLine("Error Code: ${lastError.errorCodeName}")
        appendLine("Timestamp: ${lastError.timestamp}")
        appendLine("HTTP Status: ${lastError.httpStatus ?: "N/A"}")
        appendLine("URL Host: ${lastError.urlHost ?: "N/A"}")
        appendLine("Video ID: ${lastError.videoId ?: "N/A"}")
        appendLine("Client Used: ${lastError.clientUsed ?: "N/A"}")
        appendLine("Resolve Latency: ${lastError.resolveDurationMs ?: "N/A"} ms")
        appendLine("Causes:")
        lastError.causeChain.forEach { appendLine("  - $it") }
    } else {
        appendLine("None recorded")
    }
    appendLine()
    appendLine("--- A3: DIAGNOSTICS ---")
    appendLine("VERDICT: ${verdict?.plainText ?: "Not run"}")
    results.forEach { res ->
        val mark = if (res.passed) "[PASS]" else "[FAIL]"
        appendLine("$mark Step ${res.step.stepNumber}: ${res.step.title} (${res.latencyMs} ms) - ${res.detail}")
    }
    appendLine()
    appendLine("--- A5: RECENT LOGS (Last ${logs.takeLast(50).size}) ---")
    logs.takeLast(50).forEach { entry ->
        appendLine("[${entry.level.name}] [${entry.tag}] ${entry.message}")
    }
}
