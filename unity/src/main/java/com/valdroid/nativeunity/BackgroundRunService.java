package com.valdroid.nativeunity;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * "Keep running in the background" on the native engine (a hosted world): while the game is off
 * screen the player keeps running (NativeUnityActivity skips Unity's pause), and this foreground
 * service in the same ":unity" process keeps Android from freezing that process, with a partial wake
 * lock so the world and the network go on with the screen off. Started when the game leaves the
 * screen, stopped when it comes back.
 */
public class BackgroundRunService extends Service {
    private static final String CHANNEL = "valdroid_background";
    private static final String EXTRA_TEXT = "text";
    private static final int ID = 7301;
    private PowerManager.WakeLock wakeLock;

    static void start(Context ctx, String text) {
        Intent i = new Intent(ctx, BackgroundRunService.class).putExtra(EXTRA_TEXT, text);
        try {
            ctx.startForegroundService(i);
        } catch (RuntimeException e) {   // not allowed from the background: the game pauses as usual
            android.util.Log.w("NativeUnity", "background service not started", e);
        }
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, BackgroundRunService.class));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent != null ? intent.getStringExtra(EXTRA_TEXT) : null;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Game running in the background",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent back = PendingIntent.getActivity(this, 0,
                new Intent(this, NativeUnityActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(text != null ? text : "Valheim")
                .setContentIntent(back)
                .setOngoing(true)
                .build();
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ValDroid:background");
            wakeLock.acquire();
        }
        android.util.Log.i("NativeUnity", "running in the background");
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        android.util.Log.i("NativeUnity", "back on screen");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
