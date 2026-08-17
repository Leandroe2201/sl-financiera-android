package com.slfinanciera.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class SLFirebaseMessagingService extends FirebaseMessagingService {
    private static final String CHANNEL_ID = "sl_financiera_alertas";
    private static final String PREFS = "sl_financiera_push";
    private static final String PREF_FID = "firebase_installation_id";

    @Override
    public void onRegistered(String installationId) {
        super.onRegistered(installationId);
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_FID, installationId)
                .apply();
    }

    @Override
    public void onUnregistered(String installationId) {
        super.onUnregistered(installationId);
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .remove(PREF_FID)
                .apply();
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        String titulo = remoteMessage.getData().get("title");
        String cuerpo = remoteMessage.getData().get("body");
        String path = remoteMessage.getData().get("path");

        if (remoteMessage.getNotification() != null) {
            if (titulo == null || titulo.isEmpty())
                titulo = remoteMessage.getNotification().getTitle();
            if (cuerpo == null || cuerpo.isEmpty())
                cuerpo = remoteMessage.getNotification().getBody();
        }

        if (titulo == null || titulo.isEmpty()) titulo = "SL Financiera";
        if (cuerpo == null || cuerpo.isEmpty()) cuerpo = "Tenés una nueva notificación.";
        if (path == null || path.isEmpty()) path = "/mi-tarjeta";

        crearCanal();

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("push_path", path);

        PendingIntent pi = PendingIntent.getActivity(
                this,
                (int) System.currentTimeMillis(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_sl)
                .setContentTitle(titulo)
                .setContentText(cuerpo)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(cuerpo))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);

        try {
            NotificationManagerCompat.from(this).notify(
                    (int) (System.currentTimeMillis() % Integer.MAX_VALUE),
                    b.build()
            );
        } catch (SecurityException ignored) {}
    }

    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Alertas SL Financiera",
                    NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
