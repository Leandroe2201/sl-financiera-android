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
import android.webkit.JavascriptInterface;
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

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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
    private boolean firebaseReady = false;
    private String pushDiagnostic = "INICIANDO";
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
        webView.addJavascriptInterface(new PushBridge(), "SLPushBridge");
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
        try {
            FirebaseApp app = FirebaseApp.initializeApp(this);
            firebaseReady = (app != null);
            if (!firebaseReady) {
                pushDiagnostic = "FIREBASE_CONFIG_NO_CARGADA";
                Toast.makeText(this, "Firebase no pudo inicializarse. Revisar google-services.json", Toast.LENGTH_LONG).show();
                android.util.Log.e("SL_PUSH", pushDiagnostic);
                return;
            }
            FirebaseMessaging.getInstance().setAutoInitEnabled(true);
            pushDiagnostic = "FIREBASE_OK";
        } catch (Exception e) {
            firebaseReady = false;
            pushDiagnostic = "FIREBASE_ERROR: " + e.getMessage();
            android.util.Log.e("SL_PUSH", pushDiagnostic, e);
            Toast.makeText(this, "Error Firebase: " + String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }

        String saved = getSharedPreferences(SLFirebaseMessagingService.PREFS, MODE_PRIVATE)
                .getString(SLFirebaseMessagingService.KEY_TOKEN, "");
        if (saved != null && !saved.isEmpty()) {
            fcmToken = saved;
            pushDiagnostic = "TOKEN_GUARDADO_OK";
        }

        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                Exception ex = task.getException();
                pushDiagnostic = "TOKEN_ERROR: " + (ex == null ? "desconocido" : ex.getMessage());
                android.util.Log.e("SL_PUSH", pushDiagnostic, ex);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "No se pudo obtener token PUSH: " + (ex == null ? "error desconocido" : ex.getMessage()),
                        Toast.LENGTH_LONG).show());
                return;
            }
            String token = task.getResult();
            if (token == null || token.length() < 30) {
                pushDiagnostic = "TOKEN_VACIO";
                android.util.Log.e("SL_PUSH", pushDiagnostic);
                return;
            }
            fcmToken = token;
            pushDiagnostic = "TOKEN_OK";
            getSharedPreferences(SLFirebaseMessagingService.PREFS, MODE_PRIVATE)
                    .edit().putString(SLFirebaseMessagingService.KEY_TOKEN, fcmToken).apply();
            android.util.Log.i("SL_PUSH", "FCM token obtenido. Longitud=" + fcmToken.length());
            pushRegistered = false;
            tryRegisterPushToken();
        });

        pushHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!pushRegistered) {
                    String savedNow = getSharedPreferences(SLFirebaseMessagingService.PREFS, MODE_PRIVATE)
                            .getString(SLFirebaseMessagingService.KEY_TOKEN, "");
                    if ((fcmToken == null || fcmToken.isEmpty()) && savedNow != null && !savedNow.isEmpty()) {
                        fcmToken = savedNow;
                    }
                    tryRegisterPushToken();
                }
                pushHandler.postDelayed(this, pushRegistered ? 300000 : 10000);
            }
        }, 5000);
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
        if (token == null || token.length() < 30) {
            android.util.Log.w("SL_PUSH", "Registro omitido: todavía no hay token FCM");
            return;
        }

        // v1.5: el registro se hace DESDE el propio WebView.
        // Así fetch() usa exactamente la misma sesión/cookie Flask que el Home Banking.
        // Evita depender de copiar cookies desde un hilo nativo separado.
        final String device = Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE;
        final String tokenJson = JSONObject.quote(token);
        final String deviceJson = JSONObject.quote(device);

        webView.post(() -> {
            try {
                String js = "(function(){" +
                        "try{" +
                        "fetch('/api/mi-tarjeta/push/register',{" +
                        "method:'POST',credentials:'same-origin'," +
                        "headers:{'Content-Type':'application/json','Accept':'application/json'}," +
                        "body:JSON.stringify({token:" + tokenJson + ",dispositivo:" + deviceJson + "})" +
                        "}).then(async function(r){var t=await r.text();" +
                        "window.SLPushBridge.onResult(r.status,t);" +
                        "}).catch(function(e){window.SLPushBridge.onError(String(e));});" +
                        "}catch(e){window.SLPushBridge.onError(String(e));}" +
                        "})();";
                webView.evaluateJavascript(js, null);
                android.util.Log.i("SL_PUSH", "Intentando registrar FCM desde la sesión WebView");
            } catch (Exception e) {
                pushRegistered = false;
                pushDiagnostic = "JS_INJECTION_ERROR: " + e.getMessage();
                android.util.Log.e("SL_PUSH", pushDiagnostic, e);
                Toast.makeText(MainActivity.this, "Error registrando PUSH: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private class PushBridge {
        @JavascriptInterface
        public void onResult(int status, String response) {
            android.util.Log.i("SL_PUSH", "push/register HTTP " + status + " -> " + response);
            if (status >= 200 && status < 300 && response != null && response.contains("\\\"ok\\\":true")) {
                boolean first = !pushRegistered;
                pushRegistered = true;
                pushDiagnostic = "REGISTRO_SL_OK";
                if (first) runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Notificaciones PUSH activadas correctamente", Toast.LENGTH_LONG).show());
            } else {
                pushRegistered = false;
                pushDiagnostic = "REGISTRO_HTTP_" + status;
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "PUSH no registrado (HTTP " + status + ")", Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public void onError(String error) {
            pushRegistered = false;
            pushDiagnostic = "FETCH_ERROR: " + error;
            android.util.Log.e("SL_PUSH", pushDiagnostic);
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Error PUSH: " + error, Toast.LENGTH_LONG).show());
        }
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
        settings.setUserAgentString(settings.getUserAgentString() + " SLFinancieraAndroid/1.5");

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
