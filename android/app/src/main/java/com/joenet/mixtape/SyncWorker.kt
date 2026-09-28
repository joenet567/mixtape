package com.joenet.mixtape

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * New songs arrive by themselves: while the phone charges on Wi-Fi, this checks the PC (the tray
 * sync server that install-autosync.ps1 starts with Windows) and pulls anything new, with lyrics.
 * Home then shows them on "New from your PC". A PC that's off is fine: it tries again later.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SyncManager.autoSync(applicationContext)
        return Result.success()
    }
}

object AutoSync {
    private const val WORK = "auto-sync"

    /** Keeps the periodic job in line with the setting; it starts once a PC has been synced by hand. */
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (!AppSettings.autoSync(context) || SyncManager.savedAddress(context).isEmpty()) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi: the PC is on the home network anyway
                    .setRequiresCharging(true)
                    .build()
            )
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
