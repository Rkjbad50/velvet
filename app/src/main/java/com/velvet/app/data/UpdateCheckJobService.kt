package com.velvet.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.velvet.app.BuildConfig
import com.velvet.app.R
import com.velvet.app.VelvetApp
import com.velvet.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class UpdateCheckJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        if (!VelvetApp.library.data.value.autoUpdateCheck) {
            return false
        }

        scope.launch {
            try {
                val upstreamBuild = if (BuildConfig.APPLICATION_ID == "com.spitify.app") {
                    BuildConfig.VERSION_CODE
                } else {
                    BuildConfig.UPSTREAM_BUILD_NUMBER
                }
                val info = ReleaseCheck.checkUpdate(
                    http = VelvetApp.http,
                    upstreamBuild = upstreamBuild,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    includePrereleases = VelvetApp.library.data.value.includePrereleases,
                )
                if (info.hasAppUpdate) {
                    showUpdateNotification(this@UpdateCheckJobService, info)
                }
            } catch (_: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        scope.cancel()
        return true
    }

    companion object {
        const val JOB_ID = 918273
        private const val CHANNEL_ID = "velvet_updates_silent"
        private const val NOTIFICATION_ID = 8821

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val component = ComponentName(context, UpdateCheckJobService::class.java)

            // Periodic 24 hours on unmetered network
            val intervalMs = 24 * 60 * 60 * 1000L
            val builder = JobInfo.Builder(JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPeriodic(intervalMs)
                .setPersisted(true)

            scheduler.schedule(builder.build())
        }

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            scheduler.cancel(JOB_ID)
        }

        fun showUpdateNotification(context: Context, info: UpdateInfo) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "App Updates",
                    NotificationManager.IMPORTANCE_LOW, // Silent, no sound or vibration
                ).apply {
                    description = "Notifications when a new version of VELVET is available"
                    enableVibration(false)
                    enableLights(false)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_velvet_mono)
                .setContentTitle("VELVET v${info.latestVersion} available")
                .setContentText("Tap to review the update and install.")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }
}
