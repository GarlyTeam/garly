package pro.garlyapp.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebStorage;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.UUID;

/**
 * Native bridge for sensors + install-scoped account isolation.
 * WebView/TWA sessions must never leak across Play reinstalls or tester switches.
 */
public class GarlyNativeBridge {
    private static final String PREFS = "garly_secure_prefs";
    private static final String KEY_INSTALL_ID = "install_id";
    private static final String KEY_INITIALIZED = "install_initialized";

    private final Context context;
    private final GarlyWebActivity activity;
    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private SensorManager sensorManager;
    private boolean sensorsAvailable = false;
    private boolean freshInstall = false;
    private float[] lastGyro = new float[] { 0f, 0f, 0f };
    private long lastPushMs = 0L;

    public GarlyNativeBridge(GarlyWebActivity activity, WebView webView) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.webView = webView;
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            sensorsAvailable = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) != null;
        }
        ensureInstallId();
    }

    private void ensureInstallId() {
        if (!prefs.getBoolean(KEY_INITIALIZED, false) || !prefs.contains(KEY_INSTALL_ID)) {
            freshInstall = true;
            prefs.edit()
                    .putBoolean(KEY_INITIALIZED, true)
                    .putString(KEY_INSTALL_ID, UUID.randomUUID().toString())
                    .apply();
        }
    }

    boolean consumeFreshInstallFlag() {
        boolean value = freshInstall;
        freshInstall = false;
        return value;
    }

    String installId() {
        return prefs.getString(KEY_INSTALL_ID, "");
    }

    /** Wipe cookies + WebView DOM storage before first paint on a brand-new install. */
    void clearWebDataNow() {
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.removeAllCookies(null);
            cookieManager.flush();
        } catch (Exception ignored) {
        }
        try {
            WebStorage.getInstance().deleteAllData();
        } catch (Exception ignored) {
        }
        if (webView != null) {
            webView.clearCache(true);
            webView.clearHistory();
            webView.clearFormData();
        }
    }

    @JavascriptInterface
    public boolean hasNativeSensors() {
        return sensorsAvailable;
    }

    @JavascriptInterface
    public String getInstallId() {
        return installId();
    }

    @JavascriptInterface
    public boolean isFreshInstall() {
        // After first page load the flag is consumed; JS should rely on URL param + installId.
        return false;
    }

    @JavascriptInterface
    public void clearWebData() {
        mainHandler.post(this::clearWebDataNow);
    }

    @JavascriptInterface
    public void startGoogleSignIn() {
        mainHandler.post(activity::startGoogleSignIn);
    }

    @JavascriptInterface
    public String sensorStatus() {
        try {
            JSONObject json = new JSONObject();
            json.put("listening", ProtectionService.isRunning(context));
            json.put("accelerometer", sensorsAvailable);
            json.put("installId", installId());
            return json.toString();
        } catch (Exception e) {
            return "{\"listening\":false}";
        }
    }

    @JavascriptInterface
    public void startMotionSensors() {
        mainHandler.post(() -> {
            activity.requestNotificationPermission();
            Intent intent = new Intent(context, ProtectionService.class)
                    .setAction(ProtectionService.ACTION_START);
            androidx.core.content.ContextCompat.startForegroundService(context, intent);
        });
    }

    @JavascriptInterface
    public void notifySosState(String state) {
        mainHandler.post(() -> {
            Intent intent = new Intent(context, ProtectionService.class)
                    .setAction(ProtectionService.ACTION_ALERT)
                    .putExtra("state", state == null ? "countdown" : state);
            if (ProtectionService.isRunning(context)) {
                context.startService(intent);
            } else {
                activity.requestNotificationPermission();
                androidx.core.content.ContextCompat.startForegroundService(context, intent);
            }
        });
    }

    @JavascriptInterface
    public void stopMotionSensors() {
        mainHandler.post(() -> {
            Intent intent = new Intent(context, ProtectionService.class)
                    .setAction(ProtectionService.ACTION_STOP);
            context.startService(intent);
        });
    }
}
