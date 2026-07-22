package pro.garlyapp.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.ViewGroup;
import android.webkit.GeolocationPermissions;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import android.app.Activity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import org.json.JSONObject;

/**
 * Native WebView host for Garly with install-scoped privacy isolation.
 * Each Play install gets a new installId; WebView storage is wiped on first launch
 * so a previous tester's Garly account cannot reopen after reinstall.
 */
public class GarlyWebActivity extends Activity {
    private static final String APP_URL = "https://garlyapp.pro/app/index.html";
    private static final String APP_SCHEME = "https";
    private static final String APP_HOST = "garlyapp.pro";
    private static final String GOOGLE_WEB_CLIENT_ID = "815085989019-krjp7lvbpbelki37ui3mntogutf7olcu.apps.googleusercontent.com";
    private static final int REQ_LOCATION = 4201;
    private static final int REQ_NOTIFICATIONS = 4202;

    private WebView webView;
    private GarlyNativeBridge bridge;
    private CredentialManager credentialManager;
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private boolean motionReceiverRegistered;

    private final BroadcastReceiver motionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ProtectionService.ACTION_MOTION.equals(intent.getAction()) || !hasTrustedPage()) return;
            String payload = intent.getStringExtra("payload");
            if (payload == null || payload.isEmpty()) return;
            webView.evaluateJavascript(
                    "window.__garlyNativeMotion && window.__garlyNativeMotion(" + payload + ");",
                    null
            );
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Android 15 lays activities out edge-to-edge. Keep the web UI inside the
        // real status/navigation bar insets so the account menu is never hidden
        // behind the clock, camera cutout or gesture navigation area.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return windowInsets;
        });
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);
        ViewCompat.requestApplyInsets(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setGeolocationEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.setAllowFileAccessFromFileURLs(false);
            settings.setAllowUniversalAccessFromFileURLs(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        bridge = new GarlyNativeBridge(this, webView);
        credentialManager = CredentialManager.create(this);
        webView.addJavascriptInterface(bridge, "GarlyAndroid");

        // Critical privacy step: brand-new install must not inherit any prior web session.
        boolean freshInstall = bridge.consumeFreshInstallFlag();
        if (freshInstall) {
            bridge.clearWebDataNow();
        }

        webView.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = safeParseUri(url);
                if (isTrustedAppUri(uri)) return false;
                openExternalUri(uri);
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isTrustedAppUri(uri)) return false;
                openExternalUri(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!isTrustedAppUri(safeParseUri(url))) return;
                view.evaluateJavascript(
                        "window.__garlyHasNativeSensors=true;"
                                + "window.__garlyInstallId=" + jsonString(bridge.installId()) + ";"
                                + "window.dispatchEvent(new Event('garly-native-ready'));",
                        null
                );
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!isTrustedAppUri(safeParseUri(url))) {
                    view.stopLoading();
                    openExternalUri(safeParseUri(url));
                    return;
                }
                super.onPageStarted(view, url, favicon);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (!isTrustedAppUri(safeParseUri(origin))) {
                    callback.invoke(origin, false, false);
                    return;
                }
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                    return;
                }
                pendingGeoOrigin = origin;
                pendingGeoCallback = callback;
                ActivityCompat.requestPermissions(
                        GarlyWebActivity.this,
                        new String[] {
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                        },
                        REQ_LOCATION
                );
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // Garly currently does not require camera or microphone access.
                // Geolocation has its own origin-checked permission flow above.
                runOnUiThread(request::deny);
            }
        });

        String launch = APP_URL;
        if (getIntent() != null && getIntent().getData() != null) {
            Uri data = getIntent().getData();
            if (isTrustedAppUri(data)) launch = data.toString();
        }
        Uri uri = Uri.parse(launch).buildUpon()
                .appendQueryParameter("native", "1")
                .appendQueryParameter("v", "11")
                .appendQueryParameter("installId", bridge.installId())
                .appendQueryParameter("freshInstall", freshInstall ? "1" : "0")
                .build();
        webView.loadUrl(uri.toString());
    }

    void startGoogleSignIn() {
        if (credentialManager == null) {
            deliverGoogleError("Google sign-in is unavailable on this device.");
            return;
        }
        GetSignInWithGoogleOption googleOption = new GetSignInWithGoogleOption.Builder(GOOGLE_WEB_CLIENT_ID)
                .build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(googleOption)
                .build();
        credentialManager.getCredentialAsync(
                this,
                request,
                new CancellationSignal(),
                ContextCompat.getMainExecutor(this),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        Credential credential = result.getCredential();
                        if (!(credential instanceof CustomCredential)
                                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
                            deliverGoogleError("Google did not return a valid account credential.");
                            return;
                        }
                        try {
                            GoogleIdTokenCredential googleCredential = GoogleIdTokenCredential.createFrom(
                                    ((CustomCredential) credential).getData()
                            );
                            deliverGoogleToken(googleCredential.getIdToken());
                        } catch (Exception error) {
                            deliverGoogleError("Google sign-in could not be completed.");
                        }
                    }

                    @Override
                    public void onError(GetCredentialException error) {
                        deliverGoogleError("Google sign-in was cancelled or is unavailable.");
                    }
                }
        );
    }

    void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        ActivityCompat.requestPermissions(
                this,
                new String[] { Manifest.permission.POST_NOTIFICATIONS },
                REQ_NOTIFICATIONS
        );
    }

    private void deliverGoogleToken(String token) {
        if (!hasTrustedPage() || token == null || token.isEmpty()) return;
        webView.evaluateJavascript(
                "window.__garlyNativeGoogleCredential && window.__garlyNativeGoogleCredential("
                        + JSONObject.quote(token) + ");",
                null
        );
    }

    private void deliverGoogleError(String message) {
        if (!hasTrustedPage()) return;
        webView.evaluateJavascript(
                "window.__garlyNativeGoogleError && window.__garlyNativeGoogleError("
                        + JSONObject.quote(message) + ");",
                null
        );
    }

    private static String jsonString(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Uri safeParseUri(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Uri.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isTrustedAppUri(Uri uri) {
        if (uri == null) return false;
        return APP_SCHEME.equalsIgnoreCase(uri.getScheme())
                && APP_HOST.equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static boolean isAllowedExternalScheme(String scheme) {
        if (scheme == null) return false;
        return "https".equalsIgnoreCase(scheme)
                || "http".equalsIgnoreCase(scheme)
                || "mailto".equalsIgnoreCase(scheme)
                || "tel".equalsIgnoreCase(scheme)
                || "sms".equalsIgnoreCase(scheme)
                || "smsto".equalsIgnoreCase(scheme)
                || "whatsapp".equalsIgnoreCase(scheme);
    }

    private void openExternalUri(Uri uri) {
        if (uri == null || !isAllowedExternalScheme(uri.getScheme())) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
        }
    }

    private boolean hasTrustedPage() {
        return webView != null && isTrustedAppUri(safeParseUri(webView.getUrl()));
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION || pendingGeoCallback == null) return;
        boolean granted = false;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
        }
        pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
        pendingGeoCallback = null;
        pendingGeoOrigin = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerMotionReceiver();
        if (hasTrustedPage()) {
            webView.evaluateJavascript(
                    "window.__garlyHasNativeSensors=true;"
                            + "window.dispatchEvent(new Event('garly-native-ready'));",
                    null
            );
        }
    }

    @Override
    protected void onPause() {
        // Keep the receiver attached while the Activity is backgrounded. The
        // foreground service continues to publish sensor samples and the
        // WebView's existing detector must keep receiving them until the user
        // explicitly turns Protection off or the Activity is destroyed.
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterMotionReceiver();
        if (webView != null) {
            webView.removeJavascriptInterface("GarlyAndroid");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void registerMotionReceiver() {
        if (motionReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(ProtectionService.ACTION_MOTION);
        ContextCompat.registerReceiver(this, motionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        motionReceiverRegistered = true;
    }

    private void unregisterMotionReceiver() {
        if (!motionReceiverRegistered) return;
        try {
            unregisterReceiver(motionReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        motionReceiverRegistered = false;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }
}
