package com.tunehub.app.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunehub.app.BuildConfig
import com.tunehub.app.TuneHubApp
import com.tunehub.app.data.ReleaseCheck
import com.tunehub.app.data.UPSTREAM_RELEASES_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Compose lint misses the downloads size producer's value assignment.
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val library = TuneHubApp.library
    val data by library.data.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var checkingVersion by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    HistoryDialog(confirm) { confirm = null }
    // measuring files is disk work: do it off the main thread
    val downloadsMb by produceState(0L, data.downloads.size) {
        value = withContext(Dispatchers.IO) { TuneHubApp.downloads.usedBytes() / (1024 * 1024) }
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
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
                if (data.isDarkTheme) "Pitch AMOLED extreme black with glass blur" else "Light theme with frosted glass surfaces",
                data.isDarkTheme,
                library::setDarkTheme,
            )

            // Accent Color Chooser
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("Accent color", style = MaterialTheme.typography.titleMedium)
                Text("Choose your preferred theme accent", style = MaterialTheme.typography.bodySmall, color = tuneColors.muted)
                Spacer(Modifier.size(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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

            HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = tuneColors.rule)

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
                    modifier = Modifier.background(tuneColors.raised),
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
                                    color = if (isSelected) tuneColors.accentText else tuneColors.text,
                                )
                            },
                            trailingIcon = if (isSelected) {
                                { Icon(Icons.Default.Check, null, tint = tuneColors.accentText, modifier = Modifier.size(18.dp)) }
                            } else null,
                            onClick = {
                                library.setFontPreference(key)
                                fontDropdownOpen = false
                            },
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = tuneColors.rule)

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
            ToggleRow("Hi-Res audio", "Stream the best quality YouTube offers. Off saves mobile data", !data.dataSaver) { PlayerConnection.setHiRes(it) }
            ToggleRow(
                "Look up lyrics automatically",
                "Sends the song title and artist to lrclib.net when Now Playing opens. Off: only when you tap Lyrics",
                data.autoLyrics,
                library::setAutoLyrics,
            )
            ActionRow("Equalizer & ULT Bass", "Bass boost, 5-band EQ, 3D sound and loudness") { nav.open(Route.Equalizer) }
        }

        val lastBackup by TuneHubApp.backups.lastBackupAt.collectAsStateWithLifecycle()
        val exportBackup = rememberExportAction()
        val restoreBackup = rememberRestoreAction()
        SettingsGroup("Backup & restore") {
            ToggleRow(
                "Back up automatically",
                if (!TuneHubApp.backups.autoSupported) "Needs Android 10 or newer. Use Export below instead"
                else "Keeps a copy in Download/TuneHub that survives uninstalling" +
                    if (lastBackup > 0) " · last saved just now" else "",
                data.autoBackup && TuneHubApp.backups.autoSupported,
                library::setAutoBackup,
            )
            ActionRow("Export backup", "Save your playlists, likes and settings to a file you choose") { exportBackup() }
            ActionRow("Restore from backup", "Pick a TuneHub backup file; it's merged with what's here") { restoreBackup() }
            ActionRow("Import a playlist", "From Spotify, YouTube Music, or a CSV / text file") { nav.open(Route.Import) }
        }

        SettingsGroup("Storage") {
            ActionRow("Downloads", "${data.downloads.size} songs • $downloadsMb MB") { nav.open(Route.Downloads) }
            ActionRow("Streaming cache", "Up to 512 MB of recently played audio, cleared automatically")
            ActionRow("Clear listening history", "${data.recent.size} songs in Recently played") {
                confirm = ConfirmRequest("Clear listening history?", "Recently played and Quick picks will start fresh.", "Clear history") {
                    library.clearRecent()
                }
            }
            ActionRow("Clear search history", "${data.searches.size} recent searches") {
                confirm = ConfirmRequest("Clear search history?", "Your recent searches will be removed.", "Clear history") {
                    library.clearSearches()
                }
            }
        }

        SettingsGroup("About") {
            ActionRow("Developer", "Rkjbad50") {
                openReleases(context, "https://github.com/Rkjbad50/velvet")
            }
            ActionRow(
                title = "Version",
                subtitle = if (checkingVersion) "Checking GitHub…" else "Check for a newer release",
                readout = version,
                enabled = !checkingVersion,
            ) {
                if (!checkingVersion) {
                    checkingVersion = true
                    scope.launch {
                        try {
                            val upstreamBuild = if (BuildConfig.APPLICATION_ID == "com.spitify.app") {
                                BuildConfig.VERSION_CODE
                            } else {
                                BuildConfig.UPSTREAM_BUILD_NUMBER
                            }
                            val newer = withContext(Dispatchers.IO) { ReleaseCheck.hasNewerBuild(TuneHubApp.http, upstreamBuild) }
                            if (newer) openReleases(context, UPSTREAM_RELEASES_URL)
                            else Toast.makeText(context, "Already on the latest version", Toast.LENGTH_SHORT).show()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            Toast.makeText(context, "Couldn't check for updates. Try again.", Toast.LENGTH_SHORT).show()
                        } finally {
                            checkingVersion = false
                        }
                    }
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
            "VELVET is a modern Material 3 Expressive music player powered by YouTube Music, designed and crafted by Rkjbad50. Features synchronized lyrics from LRCLIB, pure music streaming, lossless cache, and offline playback. Personal use only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
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
        HorizontalDivider(Modifier.weight(1f), color = tuneColors.rule)
    }
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().panel(RoundedCornerShape(14.dp)).padding(vertical = 4.dp),
        content = content,
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = tuneColors.accent, checkedThumbColor = tuneColors.onAccent),
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
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = tuneColors.muted)
        }
        if (readout != null) ConsoleReadout(readout, Modifier.padding(start = 8.dp))
        if (onClick != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = tuneColors.muted)
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
        containerColor = tuneColors.surface,
        title = {
            Text("Listening languages", style = MaterialTheme.typography.titleLarge, color = tuneColors.text, fontWeight = FontWeight.Bold)
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
                    color = tuneColors.muted,
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
                            color = if (isChecked) tuneColors.accentText else tuneColors.text,
                            fontWeight = if (isChecked) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { check ->
                                if (check) chosen.add(lang) else if (chosen.size > 1) chosen.remove(lang)
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = tuneColors.accent,
                                checkmarkColor = Color.White,
                                uncheckedColor = tuneColors.muted,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(chosen.toList()) }) {
                Text("Save", color = tuneColors.accentText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = tuneColors.muted)
            }
        },
    )
}

