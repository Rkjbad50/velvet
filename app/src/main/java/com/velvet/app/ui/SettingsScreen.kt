package com.velvet.app.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.text.font.FontFamily
import com.velvet.app.BuildConfig
import com.velvet.app.VelvetApp
import com.velvet.app.data.AppUpdater
import com.velvet.app.data.ReleaseCheck
import com.velvet.app.data.SemVer
import com.velvet.app.data.UPSTREAM_RELEASES_URL
import com.velvet.app.data.UpdateInfo
import com.velvet.app.data.UpdateInstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Compose lint misses the downloads size producer's value assignment.
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val library = VelvetApp.library
    val data by library.data.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var checkingVersion by remember { mutableStateOf(false) }
    val tapCounter = remember { VersionTapCounter() }
    var currentToast by remember { mutableStateOf<Toast?>(null) }
    val showToast = { text: String, duration: Int ->
        currentToast?.cancel()
        val toast = Toast.makeText(context, text, duration)
        currentToast = toast
        toast.show()
    }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateDialogInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadStatusText by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    HistoryDialog(confirm) { confirm = null }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        AppUpdater.checkInstallResult(context) { message ->
            showToast(message, Toast.LENGTH_LONG)
        }
    }

    LaunchedEffect(data.includePrereleases) {
        withContext(Dispatchers.IO) {
            val info = ReleaseCheck.checkUpdate(
                http = VelvetApp.http,
                installedVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME,
                includePrereleases = data.includePrereleases,
            )
            withContext(Dispatchers.Main) {
                updateInfo = info
            }
        }
    }
    // measuring files is disk work: do it off the main thread
    val downloadsMb by produceState(0L, data.downloads.size) {
        value = withContext(Dispatchers.IO) { VelvetApp.downloads.usedBytes() / (1024 * 1024) }
    }
    val version = remember {
        val raw = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "2.0"
        if (raw.startsWith("v")) raw else "v$raw"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsHeading("Settings", onBack)

        SettingsGroup("Appearance & Theme") {
            ToggleRow(
                "Dark theme",
                "",
                data.isDarkTheme,
                library::setDarkTheme,
            )

            // Accent Color Chooser (Horizontal scrollable with all 10 color swatches)
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("Accent color", style = MaterialTheme.typography.titleMedium)
                Text("Choose your preferred theme accent", style = MaterialTheme.typography.bodySmall, color = velvetColors.muted)
                Spacer(Modifier.size(10.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VelvetAccents.available.forEach { (name, color) ->
                        val isSelected = data.accentColor.equals(name, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .clickable { library.setAccentColor(name) }
                                .then(
                                    if (isSelected) Modifier.border(2.5.dp, Color.White, CircleShape) else Modifier,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }



            HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = velvetColors.rule)

            // Compact Font Chooser Dropdown
            var fontDropdownOpen by remember { mutableStateOf(false) }
            val fontOptions = listOf(
                "SYSTEM" to "System font (default)",
                "SPACE_GROTESK" to "Space Grotesk",
                "SOURCE_SERIF" to "Source Serif (Editorial serif)",
                "MONOSPACE" to "Monospace",
                "COOL_JAZZ" to "Cool Jazz",
                "CHOCO_COOKY" to "Choco Cooky",
                "ROSEMARY" to "Rosemary",
            )
            val currentFontLabel = fontOptions.firstOrNull { it.first.equals(data.fontPreference, ignoreCase = true) }?.second ?: "System font (default)"

            Box(Modifier.fillMaxWidth()) {
                ActionRow(
                    title = "Font style",
                    subtitle = currentFontLabel,
                    readout = "Select",
                ) {
                    fontDropdownOpen = true
                }

                DropdownMenu(
                    expanded = fontDropdownOpen,
                    onDismissRequest = { fontDropdownOpen = false },
                    modifier = Modifier.background(velvetColors.raised),
                ) {
                    fontOptions.forEach { (key, label) ->
                        val isSelected = data.fontPreference.equals(key, ignoreCase = true)
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    ),
                                    color = if (isSelected) velvetColors.accentText else velvetColors.text,
                                )
                            },
                            trailingIcon = if (isSelected) {
                                { Icon(Icons.Default.Check, null, tint = velvetColors.accentText, modifier = Modifier.size(18.dp)) }
                            } else null,
                            onClick = {
                                library.setFontPreference(key)
                                fontDropdownOpen = false
                            },
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = velvetColors.rule)

            // Seekbar Style selector (Minimal, Wide, DotMatrix)
            var seekbarDropdownOpen by remember { mutableStateOf(false) }
            val seekbarOptions = listOf(
                "MINIMAL" to "Minimal",
                "WIDE" to "Wide",
                "DOT_MATRIX" to "DotMatrix",
            )
            val currentSeekbarLabel = seekbarOptions.find { it.first == data.seekbarStyle }?.second ?: "Minimal"

            Box(Modifier.fillMaxWidth()) {
                ActionRow(
                    title = "Seekbar style",
                    subtitle = currentSeekbarLabel,
                ) {
                    seekbarDropdownOpen = true
                }

                DropdownMenu(
                    expanded = seekbarDropdownOpen,
                    onDismissRequest = { seekbarDropdownOpen = false },
                    modifier = Modifier.background(velvetColors.surface),
                ) {
                    seekbarOptions.forEach { (key, label) ->
                        val isSelected = (data.seekbarStyle == key)
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    ),
                                    color = if (isSelected) velvetColors.accentText else velvetColors.text,
                                )
                            },
                            trailingIcon = if (isSelected) {
                                { Icon(Icons.Default.Check, null, tint = velvetColors.accentText, modifier = Modifier.size(18.dp)) }
                            } else null,
                            onClick = {
                                library.setSeekbarStyle(key)
                                seekbarDropdownOpen = false
                            },
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = velvetColors.rule)

            // Multiple choice music listening languages
            var languagesDialogOpen by remember { mutableStateOf(false) }
            val currentLangs = data.musicLanguages.ifEmpty { listOf("Hindi", "English") }
            val langsSummary = currentLangs.joinToString(", ")

            ActionRow(
                title = "Listening languages",
                subtitle = langsSummary,
                readout = "${currentLangs.size} active",
            ) {
                languagesDialogOpen = true
            }

            if (languagesDialogOpen) {
                MusicLanguagesDialog(
                    selected = currentLangs,
                    onDismiss = { languagesDialogOpen = false },
                    onSave = { updated ->
                        library.setMusicLanguages(updated)
                        languagesDialogOpen = false
                    }
                )
            }
        }

        SettingsGroup("Playback") {
            ToggleRow("Autoplay", "Keep playing similar songs when your queue ends", data.autoplay, library::setAutoplay)
            ToggleRow(
                "Self-healing",
                "Automatically retry transient errors, re-resolve expired links, and skip broken songs",
                data.selfHealingEnabled,
                library::setSelfHealing
            )
            ToggleRow("Hi-Res audio", "Stream the best quality YouTube offers. Off saves mobile data", !data.dataSaver) { PlayerConnection.setHiRes(it) }
            ToggleRow(
                "Look up lyrics automatically",
                "Sends the song title and artist to lrclib.net when Now Playing opens. Off: only when you tap Lyrics",
                data.autoLyrics,
                library::setAutoLyrics,
            )

            ActionRow("Equalizer & ULT Bass", "Bass boost, 5-band EQ, 3D sound and loudness") { nav.open(Route.Equalizer) }
        }

        val lastBackup by VelvetApp.backups.lastBackupAt.collectAsStateWithLifecycle()
        val exportBackup = rememberExportAction()
        val restoreBackup = rememberRestoreAction()
        SettingsGroup("Backup & restore") {
            ToggleRow(
                "Back up automatically",
                if (!VelvetApp.backups.autoSupported) "Needs Android 10 or newer. Use Export below instead"
                else "Keeps a copy in Download/VELVET that survives uninstalling" +
                    if (lastBackup > 0) " · last saved just now" else "",
                data.autoBackup && VelvetApp.backups.autoSupported,
                library::setAutoBackup,
            )
            ActionRow("Export backup", "Save your playlists, likes and settings to a file you choose") { exportBackup() }
            ActionRow("Restore from backup", "Pick a VELVET backup file; it's merged with what's here") { restoreBackup() }
            ActionRow("Import a playlist", "From Spotify, YouTube Music, or a CSV / text file") { nav.open(Route.Import) }
        }

        SettingsGroup("Storage") {
            ActionRow("Downloads", "${data.downloads.size} songs • $downloadsMb MB") { nav.open(Route.Downloads) }
            ActionRow("Streaming cache", "Up to 512 MB of recently played audio, cleared automatically")
            ActionRow("Clear listening history", "${data.contextRecents.size.takeIf { it > 0 } ?: data.recent.size} items in Recently played") {
                confirm = ConfirmRequest("Clear listening history?", "Recently played and Quick picks will start fresh.", "Clear history") {
                    library.clearRecent()
                    library.clearContextRecents()
                }
            }
            ActionRow("Clear search history", "${data.searches.size} recent searches") {
                confirm = ConfirmRequest("Clear search history?", "Your recent searches will be removed.", "Clear history") {
                    library.clearSearches()
                }
            }
        }

        SettingsGroup("About") {
            if (updateInfo?.hasAppUpdate == true) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable {
                            updateDialogInfo = updateInfo
                            showUpdateDialog = true
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "New Version Available! ✨",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Version ${updateInfo?.latestVersion} is ready to install. Tap to review.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = {
                                updateDialogInfo = updateInfo
                                showUpdateDialog = true
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("Update")
                        }
                    }
                }
            }

            ToggleRow(
                "Check for updates automatically",
                "Checks once every 24 hours on Wi-Fi and notifies silently",
                data.autoUpdateCheck,
            ) { enabled ->
                library.setAutoUpdateCheck(enabled)
                if (enabled) {
                    com.velvet.app.data.UpdateCheckJobService.schedule(context)
                } else {
                    com.velvet.app.data.UpdateCheckJobService.cancel(context)
                }
            }

            ToggleRow(
                "Include pre-releases",
                "Check for alpha, beta, and release candidate builds",
                data.includePrereleases,
            ) { include ->
                library.setIncludePrereleases(include)
                ReleaseCheck.clearCache()
            }

            ActionRow("Developer", "Narayan") {
                openReleases(context, "https://github.com/Rkjbad50/velvet")
            }
            ActionRow(
                title = "Version",
                subtitle = "Velvet for Android",
                readout = version,
            ) {
                // Developer options unlock: tap version text 7 times
                when (val event = tapCounter.onVersionTapped(System.currentTimeMillis(), data.developerOptionsUnlocked)) {
                    is VersionTapEvent.Silent -> {}
                    is VersionTapEvent.StepsRemaining -> {
                        val steps = event.steps
                        showToast("You are now $steps step${if (steps > 1) "s" else ""} away from developer options", Toast.LENGTH_SHORT)
                    }
                    is VersionTapEvent.Unlocked -> {
                        library.setDeveloperOptions(true)
                        showToast("Developer options unlocked! 🚀", Toast.LENGTH_LONG)
                        nav.open(Route.Developer)
                    }
                    is VersionTapEvent.AlreadyEnabled -> {
                        showToast("Developer options are already enabled", Toast.LENGTH_SHORT)
                    }
                }
            }

            ActionRow(
                title = "Check for updates",
                subtitle = when {
                    checkingVersion -> "Checking GitHub…"
                    updateInfo?.hasAppUpdate == true -> "New update v${updateInfo?.latestVersion} available! Tap to review"
                    updateInfo?.isRateLimited == true -> "GitHub API rate limit reached (cached info shown)"
                    updateInfo?.isOffline == true -> "Offline: Check your internet connection"
                    updateInfo != null -> "Up to date"
                    else -> "Check for a newer release"
                },
                readout = if (updateInfo?.hasAppUpdate == true) "v${updateInfo?.latestVersion}" else null,
                enabled = !checkingVersion,
            ) {
                if (!checkingVersion) {
                    checkingVersion = true
                    scope.launch {
                        try {
                            val info = withContext(Dispatchers.IO) {
                                ReleaseCheck.checkUpdate(
                                    http = VelvetApp.http,
                                    installedVersionCode = BuildConfig.VERSION_CODE,
                                    currentVersionName = BuildConfig.VERSION_NAME,
                                    includePrereleases = data.includePrereleases,
                                    forceRefresh = true,
                                )
                            }
                            updateInfo = info
                            if (info.hasAppUpdate) {
                                updateDialogInfo = info
                                showUpdateDialog = true
                            } else {
                                showToast("You're on the latest version", Toast.LENGTH_SHORT)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            showToast("Couldn't check for updates. Try again.", Toast.LENGTH_SHORT)
                        } finally {
                            checkingVersion = false
                        }
                    }
                }
            }

            if (data.developerOptionsUnlocked) {
                ActionRow(
                    title = "Developer options",
                    subtitle = "Diagnostics, system versions, last error & log viewer",
                    readout = "Enabled",
                ) {
                    nav.open(Route.Developer)
                }
            }
            val releasesUrl = BuildConfig.RELEASES_URL
            if (releasesUrl.isNotBlank() && releasesUrl != UPSTREAM_RELEASES_URL && releasesUrl != "$UPSTREAM_RELEASES_URL/latest") {
                ActionRow("Project releases", "Opens the GitHub releases page") {
                    openReleases(context, releasesUrl)
                }
            }
        }

        Text(
            "VELVET is a modern Material 3 Expressive music player powered by YouTube Music, designed and crafted by Narayan. Features synchronized lyrics from LRCLIB, pure music streaming, lossless cache, and offline playback. Personal use only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }

    if (showUpdateDialog && updateDialogInfo != null) {
        val info = updateDialogInfo!!
        AlertDialog(
            onDismissRequest = {
                if (!isDownloading) {
                    showUpdateDialog = false
                }
            },
            title = {
                Text(
                    text = if (info.hasAppUpdate) "Update Available" else "App is Up to Date",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = velvetColors.text,
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    // Version comparison
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("VELVET App:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = velvetColors.text)
                        Text(
                            "v${info.bundledVersion} (build ${info.bundledVersionCode}) → v${info.latestVersion} (build ${info.latestVersionCode})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = velvetColors.accentText,
                        )
                    }

                    if (info.isRateLimited) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "GitHub API rate limit reached. Cached info shown.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE5A93C),
                        )
                    } else if (info.isOffline) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Network offline. Cached info shown.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE5A93C),
                        )
                    }

                    if (info.expectedSha256 != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "SHA-256: ${info.expectedSha256.take(16)}...",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = velvetColors.muted,
                        )
                    }

                    if (info.releaseNotes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Release Notes:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = velvetColors.text,
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = velvetColors.raised,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                        ) {
                            Text(
                                text = info.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                color = velvetColors.text,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }

                    if (isDownloading) {
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = velvetColors.accent,
                            trackColor = velvetColors.track,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = downloadStatusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = velvetColors.accentText,
                        )
                    }
                }
            },
            confirmButton = {
                if (info.hasAppUpdate) {
                    FilledTonalButton(
                        enabled = !isDownloading,
                        onClick = {
                            if (!info.downloadUrl.endsWith(".apk", ignoreCase = true)) {
                                openReleases(context, info.downloadUrl)
                                showUpdateDialog = false
                                return@FilledTonalButton
                            }
                            isDownloading = true
                            downloadProgress = 0f
                            downloadStatusText = "Connecting..."
                            scope.launch(Dispatchers.IO) {
                                val apkFile = AppUpdater.getUpdateFile(context)
                                try {
                                    AppUpdater.downloadApk(
                                        http = VelvetApp.http,
                                        downloadUrl = info.downloadUrl,
                                        destinationFile = apkFile,
                                    ) { downloaded, total ->
                                        if (total > 0) {
                                            downloadProgress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                            val mbDown = downloaded / (1024 * 1024f)
                                            val mbTot = total / (1024 * 1024f)
                                            downloadStatusText = "Downloading: %.1f MB / %.1f MB (%.0f%%)".format(mbDown, mbTot, downloadProgress * 100)
                                        } else {
                                            val mbDown = downloaded / (1024 * 1024f)
                                            downloadStatusText = "Downloading: %.1f MB".format(mbDown)
                                        }
                                    }
                                    downloadStatusText = "Verifying SHA-256..."
                                    AppUpdater.verifySha256(apkFile, info.expectedSha256)

                                    downloadStatusText = "Verifying signing certificate..."
                                    AppUpdater.verifySigningCertificate(context, apkFile)

                                    withContext(Dispatchers.Main) {
                                        downloadStatusText = "Installing..."
                                        when (val result = AppUpdater.installApk(context, apkFile, info.latestVersionCode)) {
                                            is UpdateInstallResult.PromptLaunched -> {
                                                showToast("Installing update...", Toast.LENGTH_SHORT)
                                                showUpdateDialog = false
                                            }
                                            is UpdateInstallResult.PermissionRequired -> {
                                                showToast("Please allow Velvet to install unknown apps, then tap Install again", Toast.LENGTH_LONG)
                                            }
                                            is UpdateInstallResult.Failure -> {
                                                showToast("Install error: ${result.reason}", Toast.LENGTH_LONG)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        showToast(e.message ?: "Update failed", Toast.LENGTH_LONG)
                                    }
                                } finally {
                                    withContext(Dispatchers.Main) {
                                        isDownloading = false
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = velvetColors.accent,
                            contentColor = Color.Black,
                        ),
                    ) {
                        Text(if (isDownloading) "Downloading..." else "Download & Install")
                    }
                } else {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text("OK", color = velvetColors.accentText)
                    }
                }
            },
            dismissButton = {
                if (info.hasAppUpdate) {
                    TextButton(
                        enabled = !isDownloading,
                        onClick = { showUpdateDialog = false }
                    ) {
                        Text("Later", color = velvetColors.muted)
                    }
                }
            }
        )
    }
}

private fun openReleases(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No browser found. Visit $url", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun SettingsGroup(text: String, content: @Composable ColumnScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 10.dp))
        HorizontalDivider(Modifier.weight(1f), color = velvetColors.rule)
    }
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().panel(RoundedCornerShape(14.dp)).padding(vertical = 4.dp),
        content = content,
    )
}

@Composable
private fun VelvetSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val isDark = velvetColors.surface.luminance() < 0.5f
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        thumbContent = {
            if (checked) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                    tint = velvetColors.onAccent,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (isDark) Color(0xFF6E6E7A) else Color(0xFF9E9EA8), CircleShape)
                )
            }
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = velvetColors.onAccent,
            checkedTrackColor = velvetColors.accent,
            checkedBorderColor = velvetColors.accent,
            checkedIconColor = velvetColors.accent,
            uncheckedThumbColor = if (isDark) Color(0xFFE2E2E8) else Color(0xFFFFFFFF),
            uncheckedTrackColor = if (isDark) Color(0xFF232328) else Color(0xFFD6D6DE),
            uncheckedBorderColor = if (isDark) Color(0xFF45454F) else Color(0xFFA8A8B5),
            uncheckedIconColor = if (isDark) Color(0xFF71717A) else Color(0xFF8E8E93),
        ),
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String = "", checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        VelvetSwitch(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    readout: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = velvetColors.muted)
        }
        if (readout != null) ConsoleReadout(readout, Modifier.padding(start = 8.dp))
        if (onClick != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = velvetColors.muted)
        }
    }
}

@Composable
private fun MusicLanguagesDialog(
    selected: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val allLanguages = listOf(
        "Hindi",
        "English",
        "Punjabi",
        "Bhojpuri",
        "Haryanvi",
        "Bengali",
        "Tamil",
        "Telugu",
        "Malayalam",
        "Marathi",
        "Gujarati",
        "Kannada",
    )
    val chosen = remember { mutableStateListOf<String>().apply { addAll(selected) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = velvetColors.surface,
        title = {
            Text("Listening languages", style = MaterialTheme.typography.titleLarge, color = velvetColors.text, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Choose one or more languages for your personalized 'More for you' recommendations:",
                    style = MaterialTheme.typography.bodySmall,
                    color = velvetColors.muted,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                allLanguages.forEach { lang ->
                    val isChecked = lang in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                if (isChecked) {
                                    if (chosen.size > 1) chosen.remove(lang)
                                } else {
                                    chosen.add(lang)
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            lang,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isChecked) velvetColors.accentText else velvetColors.text,
                            fontWeight = if (isChecked) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { check ->
                                if (check) chosen.add(lang) else if (chosen.size > 1) chosen.remove(lang)
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = velvetColors.accent,
                                checkmarkColor = Color.White,
                                uncheckedColor = velvetColors.muted,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(chosen.toList()) }) {
                Text("Save", color = velvetColors.accentText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = velvetColors.muted)
            }
        },
    )
}

