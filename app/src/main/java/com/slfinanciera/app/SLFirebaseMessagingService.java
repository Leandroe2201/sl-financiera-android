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

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        getSharedPreferences("sl_financiera", MODE_PRIVATE)
                .edit().putString("fcm_token", token).apply();
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        String titulo = remoteMessage.getData().get("title");
        String cuerpo = remoteMessage.getData().get("body");
        String path = remoteMessage.getData().get("path");

        if (remoteMessage.getNotification() != null) {
            if (titulo == null || titulo.trim().isEmpty()) titulo = remoteMessage.getNotification().getTitle();
            if (cuerpo == null || cuerpo.trim().isEmpty()) cuerpo = remoteMessage.getNotification().getBody();
        }

        if (titulo == null || titulo.trim().isEmpty()) titulo = "SL Financiera";
        if (cuerpo == null || cuerpo.trim().isEmpty()) cuerpo = "Tenés una nueva notificación.";
        if (path == null || path.trim().isEmpty()) path = remoteMessage.getData().get("url");
        if (path == null || path.trim().isEmpty()) path = "/mi-tarjeta";

        crearCanal();

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("push_path", path);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                (int) System.currentTimeMillis(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_sl)
                .setContentTitle(titulo)
                .setContentText(cuerpo)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(cuerpo))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        try {
            NotificationManagerCompat.from(this).notify(
                    (int) (System.currentTimeMillis() % Integer.MAX_VALUE),
                    builder.build()
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
            channel.setDescription("Transferencias, acreditaciones, saldos y avisos de SL Financiera");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
