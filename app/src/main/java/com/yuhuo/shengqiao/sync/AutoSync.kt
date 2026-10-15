package com.yuhuo.shengqiao.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

object AutoSync {
    const val JOB_ID = 10601
    @Volatile private var current: Run? = null
    val running get() = current != null
    class Run {
        val cancelled = AtomicBoolean(false)
        @Volatile var abort: (() -> Unit)? = null
        @Volatile var manual = false
        fun cancel() { cancelled.set(true); abort?.invoke() }
    }
    fun enabled(context: Context) = context.getSharedPreferences("automatic", Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("automatic", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) current?.cancel()
        schedule(context)
    }
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!enabled(context)) { scheduler.cancel(JOB_ID); return }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, AutoSyncJobService::class.java))
            .setPeriodic(60 * 60_000L, 15 * 60_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setRequiresBatteryNotLow(true)
            .setPersisted(true).build()
        check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "系统未接受自动同步任务，请重新开启" }
    }
    @Synchronized fun begin(): Run? {
        if (current != null) return null
        return Run().also { current = it }
    }
    @Synchronized fun end(run: Run) { if (current === run) current = null }
    fun cancelForManual() { current?.let { it.manual = true; it.cancel() } }
}
