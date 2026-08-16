package com.slfinanciera.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.net.URI;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST = 2001;
    private static final int CAMERA_PERMISSION_REQUEST = 2002;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 2003;

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> fileChooserCallback;
    private PermissionRequest pendingWebPermissionRequest;
    private String allowedHost;
    private String fcmToken;
    private boolean pushRegistered = false;
    private final Handler pushHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(0xFF0B1B3A);
        getWindow().setNavigationBarColor(0xFF0B1B3A);

        try {
            allowedHost = URI.create(BuildConfig.HOME_URL).getHost();
        } catch (Exception e) {
            allowedHost = null;
        }

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        progressBar = new ProgressBar(this);

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(54, 54);
        progressParams.gravity = Gravity.CENTER;
        root.addView(progressBar, progressParams);

        setContentView(root);
        configureWebView();
        requestNotificationPermission();
        initPush();

        if (isUrlConfigured()) {
            webView.loadUrl(BuildConfig.HOME_URL);
        } else {
            showConfigurationMessage();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void initPush() {
        String saved = getSharedPreferences(SLFirebaseMessagingService.PREFS, MODE_PRIVATE)
                .getString(SLFirebaseMessagingService.KEY_TOKEN, "");
        if (saved != null && !saved.isEmpty()) fcmToken = saved;

        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null) return;
                fcmToken = task.getResult();
                getSharedPreferences(SLFirebaseMessagingService.PREFS, MODE_PRIVATE)
                        .edit().putString(SLFirebaseMessagingService.KEY_TOKEN, fcmToken).apply();
                pushRegistered = false;
                tryRegisterPushToken();
            });
        } catch (Exception ignored) { }

        pushHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!pushRegistered) tryRegisterPushToken();
                pushHandler.postDelayed(this, pushRegistered ? 300000 : 15000);
            }
        }, 7000);
    }

    private String apiBase() {
        try {
            URI uri = URI.create(BuildConfig.HOME_URL);
            int port = uri.getPort();
            return uri.getScheme() + "://" + uri.getHost() + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return "";
        }
    }

    private void tryRegisterPushToken() {
        final String token = fcmToken;
        if (token == null || token.length() < 30 || webView == null) return;

        // IMPORTANTE: el registro se hace DESDE el propio WebView.
        // Así fetch() usa automáticamente la misma cookie/sesión Flask con la que
        // el cliente inició sesión. Evita el fallo de la versión 1.1, donde el
        // HttpURLConnection separado podía no compartir correctamente la sesión.
        final String device = Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE;
        final String js = "(function(){" +
                "fetch('/api/mi-tarjeta/push/register',{" +
                "method:'POST'," +
                "credentials:'same-origin'," +
                "headers:{'Content-Type':'application/json'}," +
                "body:JSON.stringify({token:" + JSONObject.quote(token) + ",dispositivo:" + JSONObject.quote(device) + "})" +
                "}).then(async function(r){var t=await r.text();return JSON.stringify({status:r.status,ok:r.ok,body:t});})" +
                ".catch(function(e){return JSON.stringify({status:0,ok:false,body:String(e)});});" +
                "})()";

        runOnUiThread(() -> {
            try {
                webView.evaluateJavascript(js, result -> {
                    // evaluateJavascript devuelve el resultado como string JSON escapado.
                    // Para marcar registrado alcanza con detectar respuesta HTTP 2xx/ok=true.
                    if (result != null && (result.contains("\\"ok\\":true") || result.contains("\"ok\":true"))) {
                        pushRegistered = true;
                    } else {
                        pushRegistered = false;
                    }
                });
            } catch (Exception e) {
                pushRegistered = false;
            }
        });
    }

    private boolean isUrlConfigured() {
        return BuildConfig.HOME_URL.startsWith("https://")
                && !BuildConfig.HOME_URL.contains("TU-DOMINIO");
    }

    private void showConfigurationMessage() {
        progressBar.setVisibility(View.GONE);
        TextView message = new TextView(this);
        message.setText("SL FINANCIERA\n\nLa APK está correctamente instalada.\n\nFalta configurar la URL pública HTTPS del Home Banking en gradle.properties (SL_HOME_URL).");
        message.setGravity(Gravity.CENTER);
        message.setTextSize(18f);
        message.setPadding(40, 40, 40, 40);
        ((ViewGroup) webView.getParent()).addView(message, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        webView.setVisibility(View.GONE);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUserAgentString(settings.getUserAgentString() + " SLFinancieraAndroid/1.2");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isAllowedUri(uri)) return false;
                openExternal(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                pushRegistered = false;
                tryRegisterPushToken();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                Toast.makeText(MainActivity.this, "No se pudo validar la conexión segura.", Toast.LENGTH_LONG).show();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = filePathCallback;
                Intent intent;
                try {
                    intent = fileChooserParams.createIntent();
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this, "No hay un selector de archivos disponible.", Toast.LENGTH_LONG).show();
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                if (!isAllowedOrigin(request.getOrigin())) {
                    request.deny();
                    return;
                }
                boolean wantsCamera = false;
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                        wantsCamera = true;
                        break;
                    }
                }
                if (wantsCamera && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    pendingWebPermissionRequest = request;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
                    return;
                }
                request.grant(request.getResources());
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                if (!isAllowedUri(Uri.parse(url))) {
                    openExternal(Uri.parse(url));
                    return;
                }
                try {
                    String filename = URLUtil.guessFileName(url, contentDisposition, mimetype);
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimetype);
                    request.addRequestHeader("User-Agent", userAgent);
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) request.addRequestHeader("Cookie", cookie);
                    request.setTitle(filename);
                    request.setDescription("Descargando comprobante de SL Financiera");
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                    DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    manager.enqueue(request);
                    Toast.makeText(MainActivity.this, "Descarga iniciada", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "No se pudo iniciar la descarga.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private boolean isAllowedUri(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && allowedHost != null && allowedHost.equalsIgnoreCase(uri.getHost());
    }

    private boolean isAllowedOrigin(Uri origin) {
        return origin != null && "https".equalsIgnoreCase(origin.getScheme())
                && allowedHost != null && allowedHost.equalsIgnoreCase(origin.getHost());
    }

    private void openExternal(Uri uri) {
        if (uri == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            Toast.makeText(this, "No se pudo abrir el enlace.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileChooserCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST && pendingWebPermissionRequest != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingWebPermissionRequest.grant(pendingWebPermissionRequest.getResources());
            } else {
                pendingWebPermissionRequest.deny();
            }
            pendingWebPermissionRequest = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        pushHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }
}
