package com.nas.messageredirect;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;

public final class UploadJobService extends JobService {
    static final int IMMEDIATE = 301, PERIODIC = 302;
    private final ConcurrentHashMap<Integer, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (!new SyncConfig(context).enabled()) { scheduler.cancel(IMMEDIATE); scheduler.cancel(PERIODIC); return; }
        ComponentName service = new ComponentName(context, UploadJobService.class);
        if (!hasJob(scheduler, PERIODIC))
            scheduler.schedule(new JobInfo.Builder(PERIODIC, service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15 * 60 * 1000L).build());
        if (!hasJob(scheduler, IMMEDIATE)) {
            try (SmsStore store = new SmsStore(context)) {
                if (store.pendingCount() > 0) scheduler.schedule(new JobInfo.Builder(IMMEDIATE, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                    .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
            }
        }
    }
    private static boolean hasJob(JobScheduler scheduler, int id) {
        for (JobInfo job : scheduler.getAllPendingJobs()) if (job.getId() == id) return true;
        return false;
    }
    @Override public boolean onStartJob(JobParameters params) {
        final FutureTask<?>[] holder = new FutureTask<?>[1];
        FutureTask<Void> task = new FutureTask<>(() -> {
            boolean retry = SmsUploader.upload(getApplicationContext());
            new Handler(Looper.getMainLooper()).post(() -> {
                if (tasks.remove(params.getJobId(), holder[0])) jobFinished(params, retry);
            });
            return null;
        });
        holder[0] = task; tasks.put(params.getJobId(), task); new Thread(task, "sms-upload").start(); return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        FutureTask<Void> task = tasks.remove(params.getJobId()); if (task != null) task.cancel(true);
        return new SyncConfig(this).enabled();
    }
}
