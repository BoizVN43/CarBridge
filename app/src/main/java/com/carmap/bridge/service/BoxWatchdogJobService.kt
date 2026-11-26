package com.carmap.bridge.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.carmap.bridge.data.AppPreferences
import com.carmap.bridge.data.BridgeRepository

/**
 * OS-persisted JobScheduler watchdog that survives reboots & ACC sleep on Car Android Boxes.
 * Uses 3 triggers:
 *  1) Periodic 15m persisted watchdog
 *  2) Network-available persisted trigger (wakes immediately when Box joins Wi-Fi / Hotspot / 4G)
 *  3) Immediate one-shot recovery trigger (used after task removal or deferred boot FGS)
 */
class BoxWatchdogJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        runCatching {
            if (AppPreferences.shouldAutoStartOnBoot(this)) {
                runCatching { BridgeRepository.init(this) }
                if (!BoxReceiverService.isRunning) {
                    val trigger = when (params?.jobId) {
                        JOB_ID_NETWORK -> "NET_WAKE"
                        JOB_ID_IMMEDIATE -> "RECOVERY"
                        else -> "WATCHDOG"
                    }
                    Log.i("CarBridgeWatchdog", "Restarting BoxReceiverService ($trigger)")
                    BoxReceiverService.start(this)
                    AppPreferences.recordBootAutoStart(this, trigger)
                }
                schedule(this)
            }
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val JOB_ID_PERIODIC = 48299
        private const val JOB_ID_NETWORK = 48300
        private const val JOB_ID_IMMEDIATE = 48301

        fun schedule(context: Context) {
            runCatching {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                val component = ComponentName(context, BoxWatchdogJobService::class.java)
                if (scheduler.getPendingJob(JOB_ID_PERIODIC) == null) {
                    val periodicInfo = JobInfo.Builder(JOB_ID_PERIODIC, component)
                        .setPersisted(true)
                        .setPeriodic(15L * 60_000L)
                        .build()
                    scheduler.schedule(periodicInfo)
                }
                if (scheduler.getPendingJob(JOB_ID_NETWORK) == null) {
                    val netInfo = JobInfo.Builder(JOB_ID_NETWORK, component)
                        .setPersisted(true)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setMinimumLatency(3_000L)
                        .build()
                    scheduler.schedule(netInfo)
                }
            }
        }

        fun triggerImmediate(context: Context, delayMs: Long = 1_500L) {
            runCatching {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                val component = ComponentName(context, BoxWatchdogJobService::class.java)
                val info = JobInfo.Builder(JOB_ID_IMMEDIATE, component)
                    .setMinimumLatency(delayMs)
                    .setOverrideDeadline(delayMs + 4_000L)
                    .build()
                scheduler.schedule(info)
            }
        }

        fun cancel(context: Context) {
            runCatching {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                scheduler.cancel(JOB_ID_PERIODIC)
                scheduler.cancel(JOB_ID_NETWORK)
                scheduler.cancel(JOB_ID_IMMEDIATE)
            }
        }
    }
}
