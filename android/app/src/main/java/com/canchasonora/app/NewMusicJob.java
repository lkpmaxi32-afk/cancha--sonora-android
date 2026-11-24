package com.canchasonora.app;

import android.annotation.TargetApi;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Se despierta cuando MediaStore cambia (música copiada o descargada en el teléfono o la tarjeta)
 * y avisa con una notificación si la app no está a la vista.
 */
@TargetApi(24)
public class NewMusicJob extends JobService {
    static final int JOB_ID = 7001;
    static final String CHANNEL = "musica_nueva";
    static final int NOTIF = 4243;

    public static void schedule(Context c) {
        if (Build.VERSION.SDK_INT < 24) return;
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, NewMusicJob.class));
            b.addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS));
            b.setTriggerContentUpdateDelay(3000);
            b.setTriggerContentMaxDelay(15000);
            try { b.setPersisted(true); } catch (Exception ignored) { }
            js.schedule(b.build());
        } catch (Exception ignored) { }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try { check(); } catch (Exception ignored) { }
            schedule(getApplicationContext()); // los trabajos por contenido hay que volver a programarlos
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }

    private void check() {
        SharedPreferences sp = getSharedPreferences(MusicLibrary.PREFS, MODE_PRIVATE);
        long lastSeen = sp.getLong("lastSeen", -1);
        long lastNotified = sp.getLong("lastNotified", -1);
        long from = Math.max(lastSeen, lastNotified);
        if (lastSeen < 0) return; // todavía no se abrió la app: no hay referencia
        int n = 0;
        long max = from;
        Cursor c = null;
        try {
            c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Audio.Media.DATE_ADDED},
                MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " + MediaStore.Audio.Media.DATE_ADDED + " > ?",
                new String[]{String.valueOf(from)}, null);
            while (c != null && c.moveToNext()) {
                n++;
                long a = c.getLong(0);
                if (a > max) max = a;
            }
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        if (n == 0) return;
        if (MainActivity.visible) return; // con la app abierta, se ve con Actualizar
        sp.edit().putLong("lastNotified", max).apply();
        notifyNew(n);
    }

    private void notifyNew(int n) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Música nueva", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, fl);
        String text = n == 1 ? "Se agregó 1 canción nueva a tu música." : "Se agregaron " + n + " canciones nuevas a tu música.";
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle("¡Música nueva en la cancha!")
            .setContentText(text + " Tocá para escucharla.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        try { NotificationManagerCompat.from(this).notify(NOTIF, b.build()); } catch (SecurityException ignored) { }
    }
}
