package com.slfinanciera.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 1201;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1202;
    private static final String CHANNEL_ID = "sl_financiera_alertas";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingPermissionRequest;
    private String ultimoToken = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        crearCanalNotificaciones();
        requestNotificationPermission();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setUserAgentString(settings.getUserAgentString() + " SLFinanciera-App/2.0");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

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
                registrarTokenEnServidor(view);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    String html = "<html><body style='margin:0;background:#071e3a;color:#fff;font-family:Arial;padding:32px'>" +
                            "<h2>SL FINANCIERA</h2><h3>No se pudo conectar con el servidor</h3>" +
                            "<p>Verificá tu conexión a Internet y que el servidor esté disponible.</p>" +
                            "<button onclick='location.reload()' style='background:#2563eb;color:#fff;border:0;border-radius:8px;padding:14px 18px;font-weight:bold'>REINTENTAR</button>" +
                            "</body></html>";
                    view.loadDataWithBaseURL(BuildConfig.HOME_URL, html, "text/html", "UTF-8", null);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
                contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
                contentIntent.setType("*/*");
                Intent chooser = Intent.createChooser(contentIntent, "Seleccionar archivo");
                startActivityForResult(chooser, FILE_CHOOSER_REQUEST_CODE);
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                boolean camera = false;
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) camera = true;
                }
                if (camera && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    pendingPermissionRequest = request;
                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
                } else {
                    request.grant(request.getResources());
                }
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                try {
                    String filename = URLUtil.guessFileName(url, contentDisposition, mimetype);
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) req.addRequestHeader("Cookie", cookie);
                    req.addRequestHeader("User-Agent", userAgent);
                    req.setTitle(filename);
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                    ((DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "No se pudo iniciar la descarga.", Toast.LENGTH_LONG).show();
                }
            }
        });

        if (savedInstanceState == null) {
            String path = getIntent().getStringExtra("push_path");
            cargarRuta(path);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST_CODE);
        }
    }

    // MISMO MECANISMO QUE SEGURIDAD INT: Firebase obtiene token y el propio WebView
    // lo registra con fetch(), reutilizando la sesión/cookie del usuario logueado.
    private void registrarTokenEnServidor(WebView view) {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null || task.getResult().trim().isEmpty()) {
                return;
            }

            String token = task.getResult();
            ultimoToken = token;
            String modelo = Build.MANUFACTURER + " " + Build.MODEL;

            String js =
                    "(function(){fetch('/api/mi-tarjeta/push/register',{" +
                    "method:'POST'," +
                    "credentials:'same-origin'," +
                    "headers:{'Content-Type':'application/json'}," +
                    "body:JSON.stringify({" +
                    "token:" + JSONObject.quote(token) + "," +
                    "dispositivo:" + JSONObject.quote(modelo) +
                    "})" +
                    "}).catch(function(){});})();";

            view.evaluateJavascript(js, null);
        });
    }

    // SL Home es SPA. Además de onPageFinished, reintentamos periódicamente para que
    // el token se registre apenas el login haya creado la sesión, sin acción del cliente.
    private final Runnable pushRetry = new Runnable() {
        @Override public void run() {
            if (webView != null) registrarTokenEnServidor(webView);
            if (webView != null) webView.postDelayed(this, 8000);
        }
    };

    private void cargarRuta(String path) {
        String base = BuildConfig.HOME_URL.replaceAll("/+$", "");
        if (path == null || path.trim().isEmpty()) {
            webView.loadUrl(base);
        } else if (path.startsWith("http://") || path.startsWith("https://")) {
            webView.loadUrl(path);
        } else {
            if (!path.startsWith("/")) path = "/" + path;
            try {
                Uri b = Uri.parse(base);
                String origin = b.getScheme() + "://" + b.getAuthority();
                webView.loadUrl(origin + path);
            } catch (Exception e) {
                webView.loadUrl(base);
            }
        }
        webView.removeCallbacks(pushRetry);
        webView.postDelayed(pushRetry, 5000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String path = intent.getStringExtra("push_path");
        if (path != null && webView != null) cargarRuta(path);
    }

    private void crearCanalNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Alertas SL Financiera", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Transferencias, acreditaciones, saldos y avisos de SL Financiera");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE && filePathCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE && pendingPermissionRequest != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
            else pendingPermissionRequest.deny();
            pendingPermissionRequest = null;
        }
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
