package pro.garlyapp.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.ViewGroup;
import android.webkit.GeolocationPermissions;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.URLUtil;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import android.app.Activity;
import androidx.annotation.RequiresApi;
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

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

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
    private static final int REQ_ACOUSTIC_DIAGNOSTICS = 4203;
    private static final int REQ_WEB_AUDIO_CAPTURE = 4204;
    // Declared as a literal because the acoustic service lives in a build
    // variant that this source set cannot reference directly.
    private static final String ACTION_ACOUSTIC_EVENT = "pro.garlyapp.app.action.ACOUSTIC_EVENT";

    private WebView webView;
    private GarlyNativeBridge bridge;
    private GarlyBilling billing;
    private CredentialManager credentialManager;
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private PermissionRequest pendingWebAudioRequest;
    private boolean motionReceiverRegistered;
    private String pendingAcousticContext = "{}";
    private boolean pendingAcousticRecordingConsent;
    private boolean pendingAcousticLiveMode;
    private boolean acousticStartPending;
    private volatile boolean servedLocalPage;

    private final BroadcastReceiver motionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!hasTrustedPage()) return;
            if (ProtectionService.ACTION_SOS_CANCELLED.equals(intent.getAction())) {
                String eventId = intent.getStringExtra("event_id");
                webView.evaluateJavascript(
                        "window.__garlyNativeSosCancelled && window.__garlyNativeSosCancelled("
                                + JSONObject.quote(eventId == null ? "" : eventId) + ");",
                        null
                );
                return;
            }
            if (ACTION_ACOUSTIC_EVENT.equals(intent.getAction())) {
                String acoustic = intent.getStringExtra("payload");
                if (acoustic == null || acoustic.isEmpty()) return;
                webView.evaluateJavascript(
                        "window.__garlyNativeAcousticEvent && window.__garlyNativeAcousticEvent(" + acoustic + ");",
                        null
                );
                return;
            }
            if (!ProtectionService.ACTION_MOTION.equals(intent.getAction())) return;
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

        // Crash reporting, first thing, so a crash during start-up is still
        // caught. Does nothing at all in a build without google-services.json.
        Diagnostics.install(this);

        // Android 15 lays activities out edge-to-edge. Keep the web UI inside the
        // real status/navigation bar insets so the account menu is never hidden
        // behind the clock, camera cutout or gesture navigation area.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // The 24-hour promise on the event recording screen used to be kept only
        // while the acoustic service happened to be running. Opening the app is
        // the moment that reliably happens, so the sweep runs here too.
        try {
            AcousticDiagnosticService.sweepClipsNow(getApplicationContext());
        } catch (Throwable ignored) {
            // A clip left one sweep longer must never stop the app from opening.
        }

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
        // QA only: without this a page-level fault on the phone is invisible and
        // can only be guessed at from screenshots. Never enabled in release.
        if (isQaBuild()) WebView.setWebContentsDebuggingEnabled(true);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);
        ViewCompat.requestApplyInsets(root);

        /* Un WebView senza DownloadListener butta via i download in silenzio.
           Nessun errore, nessun messaggio: il tasto sembra rotto, ed e' quello
           che succedeva a "Scarica campione" nel Sound Lab. Ora un indirizzo
           normale passa dal gestore di download di Android, e un blob - che
           Chromium non consegna qui in forma utilizzabile - almeno lo dice,
           indicando la strada che funziona davvero. */
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                if (url != null && url.startsWith("blob:")) {
                    runOnUiThread(() -> Toast.makeText(
                            this,
                            getString(R.string.download_blob_unsupported),
                            Toast.LENGTH_LONG
                    ).show());
                    return;
                }
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, contentDisposition, mimeType)
                );
                DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (manager != null) manager.enqueue(request);
            } catch (Throwable error) {
                noteQaFailure("download", error);
                runOnUiThread(() -> Toast.makeText(
                        this,
                        getString(R.string.download_failed),
                        Toast.LENGTH_LONG
                ).show());
            }
        });

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


        /* Nothing about buying a subscription is worth not opening a safety app
           for. A misconfigured billing client threw out of connect(), onCreate
           never finished, and Garly would not start at all: no SOS, no
           Protection, no contacts, on every launch. The person could not even be
           told why, because nothing of ours was running to tell them.

           Billing is a feature. Opening is not. */
        billing = new GarlyBilling(this);
        try {
            billing.connect();
        } catch (Throwable error) {
            Log.e("GarlyWebActivity", "Billing could not start; continuing without it", error);
        }
        bridge = new GarlyNativeBridge(this, webView, billing);
        /* Same rule as billing: this reaches Google Play services, which can be
           missing or disabled on a device we have never seen. Sign-in failing is
           a feature not working. Sign-in failing during onCreate would be Garly
           not opening. */
        try {
            credentialManager = CredentialManager.create(this);
        } catch (Throwable error) {
            Log.e("GarlyWebActivity", "Credential manager unavailable; sign-in will say so", error);
        }
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
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                // The web app rewrites its own URL as you navigate (/app/referrals
                // and friends). Matching only /app/index.html meant that after a
                // reload the QA build fetched the production page from the server
                // instead of the one inside the APK, losing the QA-only UI, and
                // /app/rewards is a 404 there, which paints a blank screen.
                String path = uri.getPath() == null ? "" : uri.getPath();
                boolean appRoute = "/app".equals(path) || "/app/".equals(path)
                        || (path.startsWith("/app/") && !path.substring(5).contains("."));
                if (getResources().getBoolean(R.bool.useLocalQaPage)
                        && "GET".equalsIgnoreCase(request.getMethod())
                        && isTrustedAppUri(uri)
                        && ("/app/index.html".equals(path) || appRoute)) {
                    try {
                        servedLocalPage = true;
                        InputStream localPage = getAssets().open("index.html");
                        WebResourceResponse response = new WebResourceResponse(
                                "text/html",
                                "UTF-8",
                                localPage
                        );
                        response.setStatusCodeAndReasonPhrase(200, "OK");
                        response.setResponseHeaders(Collections.singletonMap(
                                "Cache-Control",
                                "no-store"
                        ));
                        return response;
                    } catch (IOException ignored) {
                        // Fall back to the live page if the QA asset is unexpectedly absent.
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    noteQaFailure("page error " + request.getUrl() + "  "
                            + (error == null ? "?" : error.getDescription()), null);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                if (request != null && request.isForMainFrame() && response != null) {
                    noteQaFailure("http " + response.getStatusCode() + " on " + request.getUrl(), null);
                }
            }

            // The renderer dying leaves the window painted and frozen, which is
            // exactly what a blank coloured screen looks like.
            @Override
            @RequiresApi(Build.VERSION_CODES.O)
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                noteQaFailure("render process gone, crashed="
                        + (detail != null && detail.didCrash()), null);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!isTrustedAppUri(safeParseUri(url))) return;
                view.evaluateJavascript(
                        "window.__garlyHasNativeSensors=true;"
                                + "window.__garlyAcousticQa=" + (isQaBuild() ? "true" : "false") + ";"
                                + "window.__garlyInstallId=" + jsonString(bridge.installId()) + ";"
                                + "window.__garlyLocalPage=" + (servedLocalPage ? "true" : "false") + ";"
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
            public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                if (message != null && message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    noteQaFailure("JS " + message.sourceId() + ":" + message.lineNumber()
                            + "  " + message.message(), null);
                }
                return false;
            }

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
                runOnUiThread(() -> handleWebPermissionRequest(request));
            }
        });

        String launch = APP_URL;
        if (getIntent() != null && getIntent().getData() != null) {
            Uri data = getIntent().getData();
            if (isTrustedAppUri(data)) launch = data.toString();
        }
        Uri uri = Uri.parse(launch).buildUpon()
                .appendQueryParameter("native", "1")
                .appendQueryParameter("v", "12")
                .appendQueryParameter("acousticQa", isQaBuild() ? "1" : "0")
                .appendQueryParameter("qaBuild", isQaBuild()
                        ? String.valueOf(System.currentTimeMillis()) : "0")
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

                    /* One sentence covered cancelling, having no Google account
                       on the phone, and a configuration that needs changing,
                       which meant the three could not be told apart by the
                       person seeing them or by anyone trying to help. Each one
                       now says which it is, and anything unrecognised carries
                       its type so it can be looked up rather than guessed. */
                    @Override
                    public void onError(GetCredentialException error) {
                        String kind = error == null ? "" : error.getClass().getSimpleName();
                        if (kind.contains("Cancellation") || kind.contains("Interrupted")) {
                            deliverGoogleError("Google sign-in was cancelled.");
                        } else if (kind.contains("NoCredential")) {
                            deliverGoogleError("No Google account is available on this phone. "
                                    + "Add one in Settings, or sign in with an email address.");
                        } else if (kind.contains("ProviderConfiguration")
                                || kind.contains("Unsupported")) {
                            deliverGoogleError("Google Play services on this phone cannot complete "
                                    + "the sign-in. Signing in with an email address still works.");
                        } else {
                            deliverGoogleError("Google sign-in could not be completed ("
                                    + (kind.isEmpty() ? "unknown" : kind) + "). "
                                    + "Signing in with an email address still works.");
                        }
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

    void requestAcousticDiagnosticPermission(String contextJson, boolean recordingConsent, boolean liveMode) {
        if (!isAcousticBuild()) return;
        pendingAcousticContext = contextJson == null ? "{}" : contextJson;
        pendingAcousticRecordingConsent = recordingConsent;
        pendingAcousticLiveMode = liveMode;
        updateAcousticDiagnosticState("requesting_permission", "");
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startAcousticDiagnosticService(pendingAcousticContext, pendingAcousticRecordingConsent, pendingAcousticLiveMode);
            return;
        }
        ActivityCompat.requestPermissions(
                this,
                new String[] { Manifest.permission.RECORD_AUDIO },
                REQ_ACOUSTIC_DIAGNOSTICS
        );
    }

    private void startAcousticDiagnosticService(String contextJson, boolean recordingConsent, boolean liveMode) {
        if (!isAcousticBuild()) return;
        try {
            requestNotificationPermission();
            updateAcousticDiagnosticState("starting", "");
            Intent intent = new Intent()
                    .setClassName(this, "pro.garlyapp.app.AcousticDiagnosticService")
                    .setAction("pro.garlyapp.app.action.START_ACOUSTIC_DIAGNOSTIC")
                    .putExtra("qa_context", contextJson == null ? "{}" : contextJson)
                    .putExtra("recording_consent", recordingConsent)
                    .putExtra("live_mode", liveMode);
            ContextCompat.startForegroundService(this, intent);
        } catch (Throwable error) {
            noteQaFailure("startAcousticDiagnosticService", error);
            updateAcousticDiagnosticState(
                    "error",
                    "Android refused to start the microphone service: " + error.getClass().getSimpleName()
            );
        }
    }

    private void updateAcousticDiagnosticState(String status, String error) {
        if (!isAcousticBuild()) return;
        getSharedPreferences("garly_acoustic_diagnostics", MODE_PRIVATE)
                .edit()
                .putBoolean("running", false)
                .putString("status", status == null ? "stopped" : status)
                .putString("error", error == null ? "" : error)
                .apply();
    }

    /**
     * The four actions that reach a saved clip belong to the person whose phone
     * recorded it, not to the lab. Gating them behind the QA build meant the
     * shipping app could record an encrypted clip after an event and then offer
     * no way at all to hear it, keep it or delete it before the 24 hours ran
     * out. Everything else here really is internal and stays that way.
     */
    private boolean isShippingClipAction(String action) {
        return "pro.garlyapp.app.action.PLAY_ACOUSTIC_CLIP".equals(action)
                || "pro.garlyapp.app.action.STOP_ACOUSTIC_PLAYBACK".equals(action)
                || "pro.garlyapp.app.action.EXPORT_ACOUSTIC_CLIP".equals(action)
                || "pro.garlyapp.app.action.DELETE_ACOUSTIC_CLIP".equals(action);
    }

    void sendAcousticDiagnosticAction(String action) {
        if (action == null || action.isEmpty()) return;
        if (!isQaBuild() && !(isAcousticBuild() && isShippingClipAction(action))) return;
        Intent intent = new Intent()
                .setClassName(this, "pro.garlyapp.app.AcousticDiagnosticService")
                .setAction(action);
        startService(intent);
    }

    void sendAcousticDiagnosticMarker(String action, String marker) {
        if (!isQaBuild() || action == null || action.isEmpty()) return;
        startService(new Intent()
                .setClassName(this, "pro.garlyapp.app.AcousticDiagnosticService")
                .setAction(action)
                .putExtra("marker", marker == null ? "" : marker));
    }

    void stopAcousticDiagnosticService() {
        if (!isAcousticBuild()) return;
        Intent intent = new Intent()
                .setClassName(this, "pro.garlyapp.app.AcousticDiagnosticService")
                .setAction("pro.garlyapp.app.action.STOP_ACOUSTIC_DIAGNOSTIC");
        stopService(intent);
    }

    private void noteQaFailure(String label, Throwable error) {
        try {
            Class.forName("pro.garlyapp.app.QaCrashLog")
                    .getMethod("note", Context.class, String.class, Throwable.class)
                    .invoke(null, this, label, error);
        } catch (Throwable ignored) {
        }
    }

    /** QA tooling: local page, Sound Lab, calibration, simulated alerts. */
    private boolean isQaBuild() {
        return getResources().getBoolean(R.bool.useLocalQaPage);
    }

    /**
     * The acoustic engine itself, which ships to users. Kept separate from
     * {@link #isQaBuild()} so the lab stays internal while protection does not.
     */
    private boolean isAcousticBuild() {
        return getResources().getBoolean(R.bool.acousticProtection);
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

    /**
     * A link that arrives while Garly is already open.
     *
     * The activity is singleTask, so Android does not build it again: it hands
     * the link here instead. Without this the link was simply dropped, and the
     * only thing that ever noticed was Phantom coming back from a wallet
     * connection. The person approved in Phantom, Garly came to the front
     * showing the page it had before, and nothing connected.
     *
     * Only links to our own host are loaded, the same rule onCreate uses, so an
     * intent from anywhere else cannot steer the WebView.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null || webView == null) return;
        Uri data = intent.getData();
        if (data == null || !isTrustedAppUri(data)) return;
        webView.loadUrl(data.buildUpon()
                .appendQueryParameter("native", "1")
                .appendQueryParameter("installId", bridge.installId())
                .build()
                .toString());
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

    /**
     * WebView does not automatically translate getUserMedia() into Android's
     * runtime microphone permission. Grant audio capture only to Garly's
     * trusted HTTPS origin, only when audio is the sole requested resource,
     * and only after Android has granted RECORD_AUDIO.
     */
    private void handleWebPermissionRequest(PermissionRequest request) {
        if (request == null || !isTrustedAppUri(request.getOrigin())) {
            if (request != null) request.deny();
            return;
        }
        String[] resources = request.getResources();
        boolean requestsAudio = false;
        for (String resource : resources) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                requestsAudio = true;
            } else {
                // Camera, MIDI and protected-media access are never needed by
                // Sound Lab and must not be bundled into the microphone grant.
                request.deny();
                return;
            }
        }
        if (!requestsAudio) {
            request.deny();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[] { PermissionRequest.RESOURCE_AUDIO_CAPTURE });
            return;
        }
        if (pendingWebAudioRequest != null) {
            pendingWebAudioRequest.deny();
        }
        pendingWebAudioRequest = request;
        ActivityCompat.requestPermissions(
                this,
                new String[] { Manifest.permission.RECORD_AUDIO },
                REQ_WEB_AUDIO_CAPTURE
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WEB_AUDIO_CAPTURE) {
            PermissionRequest request = pendingWebAudioRequest;
            pendingWebAudioRequest = null;
            if (request == null) return;
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && hasTrustedPage()
                    && isTrustedAppUri(request.getOrigin());
            if (granted) {
                request.grant(new String[] { PermissionRequest.RESOURCE_AUDIO_CAPTURE });
            } else {
                request.deny();
            }
            return;
        }
        if (requestCode == REQ_ACOUSTIC_DIAGNOSTICS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Deferred to onResume: the activity is not visible yet here.
                acousticStartPending = true;
            } else {
                updateAcousticDiagnosticState(
                        "error",
                        "Microphone permission was not granted. Enable it in Android Settings > Apps > Garly Acoustic QA > Permissions."
                );
            }
            return;
        }
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
        /* Someone can subscribe, reinstall, or change phone. Asking Google what
           they already own on every resume means they are never asked to pay
           for something they have already bought. */
        if (billing != null) billing.restorePurchases();
        if (acousticStartPending) {
            acousticStartPending = false;
            startAcousticDiagnosticService(pendingAcousticContext,
                    pendingAcousticRecordingConsent, pendingAcousticLiveMode);
        }
        if (hasTrustedPage()) {
            webView.evaluateJavascript(
                    "window.__garlyHasNativeSensors=true;"
                            + "if(window.GarlyAndroid&&typeof window.GarlyAndroid.consumeSosCancellation==='function'){"
                            + "try{const c=JSON.parse(window.GarlyAndroid.consumeSosCancellation());"
                            + "if(c&&c.cancelledAt&&window.__garlyNativeSosCancelled)window.__garlyNativeSosCancelled(c.eventId||'');}catch(e){}}"
                            + "window.__garlyLocalPage=" + (servedLocalPage ? "true" : "false") + ";"
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
        if (billing != null) { billing.release(); billing = null; }
        if (pendingWebAudioRequest != null) {
            pendingWebAudioRequest.deny();
            pendingWebAudioRequest = null;
        }
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
        filter.addAction(ProtectionService.ACTION_SOS_CANCELLED);
        filter.addAction(ACTION_ACOUSTIC_EVENT);
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
