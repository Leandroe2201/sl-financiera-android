package com.slfinanciera.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public class SLFirebaseMessagingService extends FirebaseMessagingService {

    public static final String CHANNEL_ID = "sl_financiera_alertas";
    public static final String PREFS = "sl_financiera_push";
    public static final String KEY_TOKEN = "fcm_token";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TOKEN, token).apply();
        android.util.Log.i("SL_PUSH", "onNewToken recibido. Longitud=" + (token == null ? 0 : token.length()));
        Intent refresh = new Intent("com.slfinanciera.app.FCM_TOKEN_REFRESHED");
        refresh.setPackage(getPackageName());
        sendBroadcast(refresh);
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        String title = "SL Financiera";
        String body = "Tenés una nueva notificación.";

        if (remoteMessage.getNotification() != null) {
            if (remoteMessage.getNotification().getTitle() != null) title = remoteMessage.getNotification().getTitle();
            if (remoteMessage.getNotification().getBody() != null) body = remoteMessage.getNotification().getBody();
        }

        Map<String, String> data = remoteMessage.getData();
        if (data.get("titulo") != null && !data.get("titulo").isEmpty()) title = data.get("titulo");
        if (data.get("detalle") != null && !data.get("detalle").isEmpty()) body = data.get("detalle");

        showNotification(title, body, data.get("url"));
    }

    private void showNotification(String title, String body, String url) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Alertas SL Financiera",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Transferencias, acreditaciones, saldo deudor y avisos de seguridad");
            channel.enableVibration(true);
            manager.createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (url != null) intent.putExtra("push_url", url);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 1001, intent, pendingFlags);

        android.app.Notification.Builder builder = new android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_sl)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(body))
                .setCategory(android.app.Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(android.app.Notification.DEFAULT_ALL);

        manager.notify((int) (System.currentTimeMillis() & 0x0FFFFFFF), builder.build());
    }
}
