package com.hackathon.calico.coach

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hackathon.calico.CoachActivity
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Downloads the offline coach model outside any screen. WorkManager keeps it going when the user
 * leaves the coach, switches apps or locks the phone, restarts it after the process is killed,
 * and retries on network loss. It runs as a foreground data-sync job with a progress
 * notification so Android does not stop it. [CoachModel.download] resumes from the bytes already
 * written, so every retry continues where the last attempt stopped.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val target = inputData.getString(KEY_TARGET)?.let(Uri::parse) ?: return Result.failure()
        val model = CoachModel(applicationContext)
        promote(0f)
        // The old model (if any) is unloaded so the new file can replace it cleanly.
        CoachEngine.requestRelease()
        var lastPublished = 0L
        var lastPercent = -1
        return try {
            model.download(target) { fraction ->
                val percent = (fraction * 100).toInt()
                val now = SystemClock.elapsedRealtime()
                if (percent != lastPercent && now - lastPublished >= 500) {
                    lastPercent = percent; lastPublished = now
                    setProgress(workDataOf(KEY_PROGRESS to fraction))
                    promote(fraction)
                }
            }
            notifyFinished(applicationContext, "Offline coach ready" to "The model is downloaded and checked.")
            Result.success()
        } catch (e: CancellationException) {
            // Only an explicit cancel throws the partial file away; system stops keep it to resume.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) model.discard(target)
            throw e
        } catch (e: CoachModel.ModelMismatchException) {
            fail(e.message)
        } catch (e: IOException) {
            Log.w(TAG, "Model download interrupted (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else fail("Download kept failing. Check your connection and retry.")
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            fail(e.message ?: "Could not download the model. Please retry.")
        }
    }

    private fun fail(message: String?): Result {
        notifyFinished(applicationContext, "Offline coach download failed" to (message ?: "Please retry."))
        return Result.failure(workDataOf(KEY_ERROR to message))
    }

    /** Foreground with a progress notification; if Android refuses (background start), keep going as a normal job. */
    private suspend fun promote(fraction: Float) {
        try {
            val notification = progressNotification(applicationContext, fraction, WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            setForeground(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else ForegroundInfo(PROGRESS_ID, notification))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not run in the foreground; continuing as a background job", e)
        }
    }

    companion object {
        private const val TAG = "CalicoCoach"
        const val WORK_NAME = "coach-model-download"
        const val KEY_TARGET = "target"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val MAX_RETRIES = 8
        private const val CHANNEL = "coach_download"
        private const val PROGRESS_ID = 4101
        private const val DONE_ID = 4102

        /** Starts the download unless one is already running for the app. */
        fun start(context: Context, target: Uri) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_TARGET to target.toString()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

        private fun channel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Offline coach download", NotificationManager.IMPORTANCE_LOW))
        }

        private fun openCoach(context: Context) = PendingIntent.getActivity(context, 0,
            Intent(context, CoachActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        @Suppress("DEPRECATION")
        private fun builder(context: Context) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.app.Notification.Builder(context, CHANNEL)
            else android.app.Notification.Builder(context)

        private fun progressNotification(context: Context, fraction: Float, cancel: PendingIntent): android.app.Notification {
            channel(context)
            val percent = (fraction * 100).toInt()
            return builder(context)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading offline coach")
                .setContentText("$percent% · keeps going if you leave Calico or lock your phone")
                .setProgress(100, percent, fraction <= 0f)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openCoach(context))
                .addAction(android.app.Notification.Action.Builder(null, "Cancel", cancel).build())
                .build()
        }

        private fun notifyFinished(context: Context, message: Pair<String, String>) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            channel(context)
            context.getSystemService(NotificationManager::class.java).notify(DONE_ID, builder(context)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(message.first)
                .setContentText(message.second)
                .setAutoCancel(true)
                .setContentIntent(openCoach(context))
                .build())
        }
    }
}
