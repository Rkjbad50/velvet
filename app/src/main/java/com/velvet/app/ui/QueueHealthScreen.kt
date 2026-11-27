package com.velvet.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.velvet.app.diagnostics.CrashHandler
import com.velvet.app.diagnostics.QueueHealthMonitor
import java.io.File

/**
 * Read-only Developer Options > Queue Health screen displaying live runway state,
 * pool metrics, SessionVibe, rejection counts, source health, and the last 20 decisions.
 */
@Composable
fun QueueHealthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val snapshot by QueueHealthMonitor.state.collectAsStateWithLifecycle()
    val reportText = remember(snapshot) { QueueHealthMonitor.formatPlainText(snapshot) }

    Column(
        Modifier
            .fillMaxSize()
            .background(velvetColors.background)
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                        text = "Queue Health",
                        style = MaterialTheme.typography.titleLarge,
                        color = velvetColors.text,
                        fontWeight = FontWeight.Bold,
                    )
                    val readyPrefetch = snapshot.streamPrefetchEntries.count {
                        it.status == com.velvet.app.diagnostics.StreamPrefetchStatus.CACHED ||
                            it.status == com.velvet.app.diagnostics.StreamPrefetchStatus.DOWNLOADED
                    }
                    Text(
                        text = "Ahead: ${snapshot.aheadCount} • ${snapshot.runwayState} • Prefetch: $readyPrefetch/${snapshot.streamPrefetchEntries.size} • Stalls R/S: ${snapshot.refillStallCount}/${snapshot.streamResolveStallCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = velvetColors.muted,
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("VELVET Queue Health", reportText))
                    Toast.makeText(context, "Queue health copied", Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Copy", color = velvetColors.text, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = {
                val funnelText = QueueHealthMonitor.formatLastFunnelsPlainText(limit = 30)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("VELVET Last 30 Refill Funnels", funnelText))
                Toast.makeText(context, "Last 30 refill funnels copied", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = "Copy last 30 refill funnels (${snapshot.recentFunnels.size}/30)",
                color = velvetColors.accentText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF101214))
                .border(1.dp, velvetColors.rule, RoundedCornerShape(10.dp))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                text = reportText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Color(0xFFECEFF1),
            )
        }
    }
}

@Composable
internal fun DevQueueHealthEntryCard(onOpen: () -> Unit) {
    val snapshot by QueueHealthMonitor.state.collectAsStateWithLifecycle()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen),
        color = velvetColors.surface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "A0 • Queue Health (Runway Inspector)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = velvetColors.accentText,
            )
            Spacer(Modifier.height(6.dp))
            val readyPrefetch = snapshot.streamPrefetchEntries.count {
                it.status == com.velvet.app.diagnostics.StreamPrefetchStatus.CACHED ||
                    it.status == com.velvet.app.diagnostics.StreamPrefetchStatus.DOWNLOADED
            }
            Text(
                text = "Ahead: ${snapshot.aheadCount} • ${snapshot.runwayState} • Prefetch: $readyPrefetch/${snapshot.streamPrefetchEntries.size} • Stalls R/S: ${snapshot.refillStallCount}/${snapshot.streamResolveStallCount}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = velvetColors.text,
            )
            Text(
                text = "Last: ${snapshot.lastRefillResult} — Tap to inspect & copy",
                style = MaterialTheme.typography.bodySmall,
                color = velvetColors.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
internal fun DevLastCrashCard() {
    val context = LocalContext.current
    val persistedCrash by CrashHandler.lastCrashText.collectAsStateWithLifecycle()
    val crashText = persistedCrash ?: CrashHandler.readLastCrash(File(context.filesDir, "crash"))

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = velvetColors.surface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Last Crash (Uncaught)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = velvetColors.accentText,
            )
            Spacer(Modifier.height(8.dp))
            if (crashText.isNullOrBlank()) {
                Text(
                    text = "No uncaught crash recorded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = velvetColors.muted,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF101214))
                        .border(1.dp, velvetColors.rule, RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(
                        text = crashText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = Color(0xFFFFAB91),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(
                        onClick = {
                            CrashHandler.clearLastCrash(File(context.filesDir, "crash"))
                            Toast.makeText(context, "Crash log cleared", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text("Clear", color = velvetColors.muted)
                    }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("VELVET Last Crash", crashText))
                            Toast.makeText(context, "Crash trace copied", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Copy Crash Report", color = velvetColors.text, fontSize = 13.sp)
                    }
                }
            }
            HorizontalDivider(color = velvetColors.rule.copy(alpha = 0.35f), modifier = Modifier.padding(top = 6.dp))
        }
    }
}
