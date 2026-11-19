package com.audiocpp.ttsdemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.format.Formatter;

import java.io.File;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModelDownloadService extends Service {
    private static final String START = "download";
    private static final String CANCEL = "cancel-download";
    private static final String JOB_ID = "job-id";
    private static final String CHANNEL = "model-downloads";
    private static final int PROGRESS_NOTIFICATION = 1;
    private static final int RESULT_NOTIFICATION = 2;

    record State(String name, long copied, long total, boolean active, boolean cancelling,
                 File model, String message) {
        int progress() { return (int) (1000.0 * copied / total); }
    }

    private static final class Job {
        final int id;
        final ModelRepository.Source source;
        final ModelRepository.Entry entry;
        final AtomicBoolean cancelled = new AtomicBoolean();

        Job(int id, ModelRepository.Source source, ModelRepository.Entry entry) {
            this.id = id;
            this.source = source;
            this.entry = entry;
        }
    }

    // Shared by the service and the resumed Activity, only accessed on the main thread.
    private static Job pending;
    private static int nextId;
    private static State state;
    private static Runnable listener;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Job running;
    private PowerManager.WakeLock wakeLock;
    private long lastNotification;

    static State current() { return state; }

    static boolean canPause() { return pending != null && ModelRepository.canResume(pending.entry); }

    static void observe(Runnable observer) {
        listener = observer;
        observer.run();
    }

    static void removeObserver(Runnable observer) {
        if (listener == observer) listener = null;
    }

    static void consumeResult(Context context) {
        if (state == null || state.active()) return;
        state = null;
        context.getSystemService(NotificationManager.class).cancel(RESULT_NOTIFICATION);
    }

    static void start(Context context, ModelRepository.Source source, ModelRepository.Entry entry) {
        if (pending != null) return;
        Job job = new Job(++nextId, source, entry);
        pending = job;
        Intent intent = new Intent(context, ModelDownloadService.class).setAction(START).putExtra(JOB_ID, job.id);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException error) {
            pending = null;
            throw error;
        }
        context.getSystemService(NotificationManager.class).cancel(RESULT_NOTIFICATION);
        File path = new ModelLibrary(context.getFilesDir()).downloadPath(source, entry);
        publish(new State(entry.name(), ModelRepository.resumableBytes(entry, path), entry.bytes(),
                true, false, null, null));
    }

    static void requestCancel(Context context) {
        if (pending == null || pending.cancelled.get()) return;
        context.startService(new Intent(context, ModelDownloadService.class)
                .setAction(CANCEL).putExtra(JOB_ID, pending.id));
    }

    private static void cancel() {
        if (pending != null && !pending.cancelled.getAndSet(true)) {
            publish(new State(state.name(), state.copied(), state.total(), true, true, null, null));
        }
    }

    private static void publish(State next) {
        state = next;
        if (listener != null) listener.run();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || pending == null || intent.getIntExtra(JOB_ID, -1) != pending.id) {
            if (running == null) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (CANCEL.equals(intent.getAction())) {
            cancel();
            if (running != null) notifications().notify(PROGRESS_NOTIFICATION, progressNotification(running));
            return START_NOT_STICKY;
        }
        if (running != null || !START.equals(intent.getAction())) return START_NOT_STICKY;
        Job job = pending;
        running = job;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                notifications().createNotificationChannel(new NotificationChannel(CHANNEL,
                        getString(R.string.model_download_channel), NotificationManager.IMPORTANCE_LOW));
            }
            startForeground(PROGRESS_NOTIFICATION, progressNotification(job));
            PowerManager power = getSystemService(PowerManager.class);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audio.cpp:model-download");
            wakeLock.acquire(6 * 60 * 60_000L);
            worker.execute(() -> download(job));
        } catch (RuntimeException error) {
            finish(job, null, failureMessage(error));
        }
        return START_NOT_STICKY;
    }

    private void download(Job job) {
        File destination = null;
        try {
            if (job.cancelled.get()) throw new CancellationException();
            ModelLibrary library = new ModelLibrary(getFilesDir());
            destination = library.downloadTarget(job.source, job.entry);
            ModelRepository.download(job.source, job.entry, destination, job.cancelled::get,
                    (copied, total) -> main.post(() -> updateProgress(job, copied, total)));
            File completed = destination;
            main.post(() -> finish(job, completed, getString(R.string.model_download_completed)));
        } catch (Exception error) {
            if (destination != null) destination.getParentFile().delete();
            String message = job.cancelled.get() || error instanceof CancellationException
                    ? getString(ModelRepository.canResume(job.entry)
                            ? R.string.model_download_paused : R.string.model_cancelled)
                    : failureMessage(error);
            main.post(() -> finish(job, null, message));
        }
    }

    private String failureMessage(Exception error) {
        String detail = error.getMessage();
        return getString(R.string.model_download_failed, detail == null ? error.getClass().getSimpleName() : detail);
    }

    private void updateProgress(Job job, long copied, long total) {
        if (running != job || job.cancelled.get()) return;
        publish(new State(job.entry.name(), copied, total, true, false, null, null));
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotification >= 1000) {
            lastNotification = now;
            notifications().notify(PROGRESS_NOTIFICATION, progressNotification(job));
        }
    }

    private void finish(Job job, File model, String message) {
        if (running != job) return;
        releaseJob();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        if (listener == null && !message.equals(getString(R.string.model_cancelled))) {
            notifications().notify(RESULT_NOTIFICATION, notification()
                    .setContentTitle(message)
                    .setContentText(job.entry.name())
                    .setAutoCancel(true)
                    .build());
        }
        publish(new State(job.entry.name(), 0, job.entry.bytes(), false, false, model, message));
    }

    private void releaseJob() {
        running = null;
        pending = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private Notification.Builder notification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(content).setOnlyAlertOnce(true);
    }

    private Notification progressNotification(Job job) {
        boolean cancelling = job.cancelled.get();
        boolean canPause = ModelRepository.canResume(job.entry);
        Notification.Builder builder = notification().setOngoing(true)
                .setContentTitle(state.name())
                .setContentText(cancelling ? getString(canPause ? R.string.model_download_pausing : R.string.model_cancelling)
                        : getString(R.string.model_download_notification_progress,
                                Formatter.formatFileSize(this, state.copied()), Formatter.formatFileSize(this, state.total())))
                .setProgress(1000, state.progress(), state.copied() == 0);
        if (!cancelling) {
            Intent cancel = new Intent(this, ModelDownloadService.class).setAction(CANCEL).putExtra(JOB_ID, job.id);
            PendingIntent action = PendingIntent.getService(this, job.id, cancel, PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null,
                    getString(canPause ? R.string.model_download_pause : android.R.string.cancel), action).build());
        }
        return builder.build();
    }

    private NotificationManager notifications() { return getSystemService(NotificationManager.class); }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onTimeout(int startId, int fgsType) {
        if (running != null) {
            Job job = running;
            job.cancelled.set(true);
            finish(job, null, getString(R.string.model_download_interrupted));
        } else {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (running != null) {
            Job job = running;
            job.cancelled.set(true);
            releaseJob();
            publish(new State(job.entry.name(), 0, job.entry.bytes(), false, false,
                    null, getString(R.string.model_download_interrupted)));
        }
        worker.shutdownNow();
        super.onDestroy();
    }
}
