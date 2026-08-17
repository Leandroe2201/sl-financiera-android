package com.slfinanciera.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQ_NOTIF = 1201;
    private static final String PREFS = "sl_financiera_push";
    private static final String PREF_FID = "firebase_installation_id";
    private static final String CHANNEL_ID = "sl_financiera_alertas";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        crearCanal();
        pedirPermisoNotificaciones();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + " SLFinanciera-App/3.0-FID");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return false;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                registrarFidEnServidor();
            }
        });

        FirebaseMessaging.getInstance().setAutoInitEnabled(true);
        FirebaseMessaging.getInstance().register();

        if (savedInstanceState == null) {
            String path = getIntent().getStringExtra("push_path");
            cargarRuta(path);
        } else {
            webView.restoreState(savedInstanceState);
        }

        webView.postDelayed(pushRetry, 4000);
    }

    private final Runnable pushRetry = new Runnable() {
        @Override
        public void run() {
            try { FirebaseMessaging.getInstance().register(); } catch (Exception ignored) {}
            registrarFidEnServidor();
            if (webView != null) webView.postDelayed(this, 7000);
        }
    };

    private void registrarFidEnServidor() {
        if (webView == null) return;

        String fid = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_FID, "");

        if (fid == null || fid.trim().isEmpty()) return;

        String dispositivo = Build.MANUFACTURER + " " + Build.MODEL;

        String js =
                "(function(){" +
                "fetch('/api/mi-tarjeta/push/register',{" +
                "method:'POST'," +
                "credentials:'same-origin'," +
                "headers:{'Content-Type':'application/json'}," +
                "body:JSON.stringify({" +
                "token:" + JSONObject.quote(fid) + "," +
                "dispositivo:" + JSONObject.quote(dispositivo) +
                "})" +
                "}).catch(function(){});" +
                "})();";

        webView.evaluateJavascript(js, null);
    }

    private void cargarRuta(String path) {
        String base = BuildConfig.HOME_URL.replaceAll("/+$", "");
        if (path == null || path.trim().isEmpty()) {
            webView.loadUrl(base);
            return;
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            webView.loadUrl(path);
            return;
        }
        if (!path.startsWith("/")) path = "/" + path;
        try {
            Uri b = Uri.parse(base);
            webView.loadUrl(b.getScheme() + "://" + b.getAuthority() + path);
        } catch (Exception e) {
            webView.loadUrl(base);
        }
    }

    private void pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIF
            );
        }
    }

    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Alertas SL Financiera",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Transferencias, acreditaciones, saldos y avisos");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String path = intent.getStringExtra("push_path");
        if (path != null && webView != null) cargarRuta(path);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeCallbacks(pushRetry);
            webView.destroy();
        }
        super.onDestroy();
    }
}
