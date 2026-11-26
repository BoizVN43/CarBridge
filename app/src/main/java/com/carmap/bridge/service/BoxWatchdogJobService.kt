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
 * If the Box ROM kills BoxReceiverService during sleep, this restarts it automatically.
 */
class BoxWatchdogJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        runCatching {
            if (AppPreferences.shouldAutoStartOnBoot(this)) {
                BridgeRepository.init(this)
                if (!BridgeRepository.state.value.boxServiceRunning) {
                    Log.i("CarBridgeWatchdog", "Restarting BoxReceiverService from watchdog job")
                    BoxReceiverService.start(this)
                    AppPreferences.recordBootAutoStart(this, "WATCHDOG")
                }
            }
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val JOB_ID = 48299

        fun schedule(context: Context) {
            runCatching {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                val existing = scheduler.getPendingJob(JOB_ID)
                if (existing != null) return
                val component = ComponentName(context, BoxWatchdogJobService::class.java)
                val info = JobInfo.Builder(JOB_ID, component)
                    .setPersisted(true)
                    .setPeriodic(15L * 60_000L)
                    .build()
                scheduler.schedule(info)
            }
        }

        fun cancel(context: Context) {
            runCatching {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                scheduler.cancel(JOB_ID)
            }
        }
    }
}
