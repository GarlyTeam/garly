package pro.garlyapp.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.SensorManager;
import android.os.Build;
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
    private final GarlyBilling billing;
    private SensorManager sensorManager;
    private boolean sensorsAvailable = false;
    private boolean freshInstall = false;
    private float[] lastGyro = new float[] { 0f, 0f, 0f };
    private long lastPushMs = 0L;

    public GarlyNativeBridge(GarlyWebActivity activity, WebView webView, GarlyBilling billing) {
        this.billing = billing;
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

    /**
     * Walk mode. The page still draws the countdown, but the deadline that decides
     * whether anyone gets alerted is held by ProtectionService, so it survives the
     * screen locking or the WebView being paused.
     */
    @JavascriptInterface
    public boolean hasWalkMode() {
        return true;
    }

    @JavascriptInterface
    public void startWalkMode(double silenceMs) {
        sendWalkIntent(ProtectionService.ACTION_WALK_START, silenceMs);
    }

    /** Called on every sign of life; pushes the deadline back out. */
    @JavascriptInterface
    public void noteWalkReply(double silenceMs) {
        sendWalkIntent(ProtectionService.ACTION_WALK_PING, silenceMs);
    }

    @JavascriptInterface
    public void stopWalkMode() {
        sendWalkIntent(ProtectionService.ACTION_WALK_STOP, 0);
    }

    /** True once, after the native deadline passed, so the page can run the SOS. */
    @JavascriptInterface
    public boolean consumeWalkSilence() {
        return ProtectionService.consumeWalkSilence(context);
    }

    private void sendWalkIntent(String action, double silenceMs) {
        mainHandler.post(() -> {
            activity.requestNotificationPermission();
            Intent intent = new Intent(context, ProtectionService.class)
                    .setAction(action)
                    .putExtra("silence_ms", (long) silenceMs);
            androidx.core.content.ContextCompat.startForegroundService(context, intent);
        });
    }

    @JavascriptInterface
    public void notifySosState(String state) {
        notifySosStateWithEvent(state, "");
    }

    @JavascriptInterface
    public void notifySosStateWithEvent(String state, String eventId) {
        mainHandler.post(() -> {
            Intent intent = new Intent(context, ProtectionService.class)
                    .setAction(ProtectionService.ACTION_ALERT)
                    .putExtra("state", state == null ? "countdown" : state)
                    .putExtra("event_id", eventId == null ? "" : eventId);
            if (ProtectionService.isRunning(context)) {
                context.startService(intent);
            } else {
                activity.requestNotificationPermission();
                androidx.core.content.ContextCompat.startForegroundService(context, intent);
            }
        });
    }

    @JavascriptInterface
    public String consumeSosCancellation() {
        SharedPreferences protectionPrefs = context.getSharedPreferences(ProtectionService.PREFS, Context.MODE_PRIVATE);
        long cancelledAt = protectionPrefs.getLong(ProtectionService.KEY_CANCELLED_AT, 0L);
        if (cancelledAt <= 0L) return "null";
        try {
            JSONObject result = new JSONObject();
            result.put("eventId", protectionPrefs.getString(ProtectionService.KEY_CANCELLED_EVENT_ID, ""));
            result.put("cancelledAt", cancelledAt);
            protectionPrefs.edit()
                    .remove(ProtectionService.KEY_CANCELLED_EVENT_ID)
                    .remove(ProtectionService.KEY_CANCELLED_AT)
                    .apply();
            return result.toString();
        } catch (Exception ignored) {
            return "null";
        }
    }

    @JavascriptInterface
    public void stopMotionSensors() {
        mainHandler.post(() -> {
            Intent intent = new Intent(context, ProtectionService.class)
                    .setAction(ProtectionService.ACTION_STOP);
            context.startService(intent);
        });
    }

    /**
     * The Sound Lab and its calibration tooling. QA builds only - users get the
     * single "listen for danger sounds" toggle instead, see
     * {@link #hasAcousticProtection()}.
     */
    @JavascriptInterface
    public boolean hasAcousticDiagnostics() {
        return context.getResources().getBoolean(R.bool.useLocalQaPage);
    }

    @JavascriptInterface
    public void startAcousticDiagnostics(String contextJson, boolean recordingConsent) {
        if (!hasAcousticDiagnostics()) return;
        mainHandler.post(() -> activity.requestAcousticDiagnosticPermission(
                contextJson == null ? "{}" : contextJson,
                recordingConsent,
                false
        ));
    }

    /**
     * Starts the same engine in live mode: a confirmed event is delivered to the
     * web layer, which runs the ordinary SOS countdown instead of a simulation.
     * Protection mode owns the lifecycle, so this stops with protection.
     */
    @JavascriptInterface
    public boolean startAcousticProtection(String contextJson, boolean recordingConsent) {
        if (!hasAcousticProtection()) return false;
        mainHandler.post(() -> activity.requestAcousticDiagnosticPermission(
                contextJson == null ? "{}" : contextJson,
                recordingConsent,
                true
        ));
        return true;
    }

    /**
     * The shipping feature: one opt-in switch inside Protection Mode. Available
     * in every build, unlike the QA lab above.
     */
    @JavascriptInterface
    public boolean hasAcousticProtection() {
        return context.getResources().getBoolean(R.bool.acousticProtection);
    }

    /**
     * Whether this build can reach a clip that was already saved. The clip is
     * recorded on her phone, encrypted with a key only her phone holds, and
     * deleted after 24 hours. Wherever the engine is allowed to record one, she
     * is allowed to hear it, keep a copy or delete it early. The Sound Lab
     * around it stays internal, which is why this is not hasAcousticDiagnostics.
     */
    @JavascriptInterface
    public boolean hasAcousticClipAccess() {
        return hasAcousticProtection();
    }

    /** Stops the engine. Must work in production too, since it ends protection. */
    @JavascriptInterface
    public boolean stopAcousticDiagnostics() {
        if (!hasAcousticProtection()) return false;
        mainHandler.post(activity::stopAcousticDiagnosticService);
        return true;
    }

    @JavascriptInterface
    public boolean triggerAcousticQaTestEvent() {
        if (!hasAcousticDiagnostics()) return false;
        mainHandler.post(() -> activity.sendAcousticDiagnosticAction(
                "pro.garlyapp.app.action.TEST_ACOUSTIC_EVENT"
        ));
        return true;
    }

    @JavascriptInterface
    public boolean cancelAcousticQaAlert() {
        if (!hasAcousticDiagnostics()) return false;
        mainHandler.post(() -> activity.sendAcousticDiagnosticAction(
                "pro.garlyapp.app.action.CANCEL_ACOUSTIC_ALERT"
        ));
        return true;
    }

    @JavascriptInterface
    public boolean deleteAcousticQaClip() {
        if (!hasAcousticClipAccess()) return false;
        mainHandler.post(() -> activity.sendAcousticDiagnosticAction(
                "pro.garlyapp.app.action.DELETE_ACOUSTIC_CLIP"
        ));
        return true;
    }

    @JavascriptInterface
    public boolean playAcousticQaClip() {
        if (!hasAcousticClipAccess()) return false;
        mainHandler.post(() -> activity.sendAcousticDiagnosticAction(
                "pro.garlyapp.app.action.PLAY_ACOUSTIC_CLIP"
        ));
        return true;
    }

    @JavascriptInterface
    public boolean stopAcousticQaPlayback() {
        if (!hasAcousticClipAccess()) return false;
        mainHandler.post(() -> activity.sendAcousticDiagnosticAction(
                "pro.garlyapp.app.action.STOP_ACOUSTIC_PLAYBACK"
        ));
        return true;
    }

    @JavascriptInterface
    public boolean exportAcousticQaClip() {
        if (!hasAcousticClipAccess()) return false;
        mainHandler.post(() -> activity.sendAcousticDiagnosticAction(
                "pro.garlyapp.app.action.EXPORT_ACOUSTIC_CLIP"
        ));
        return true;
    }

    @JavascriptInterface
    public boolean startAcousticCalibration() {
        if (!hasAcousticDiagnostics()) return false;
        mainHandler.post(() -> activity.sendAcousticDiagnosticAction(
                "pro.garlyapp.app.action.CALIBRATION_START"));
        return true;
    }

    @JavascriptInterface
    public boolean stopAcousticCalibration() {
        if (!hasAcousticDiagnostics()) return false;
        mainHandler.post(() -> activity.sendAcousticDiagnosticAction(
                "pro.garlyapp.app.action.CALIBRATION_STOP"));
        return true;
    }

    @JavascriptInterface
    public boolean markAcousticCalibration(String marker) {
        if (!hasAcousticDiagnostics()) return false;
        final String safe = marker == null ? "" : marker;
        mainHandler.post(() -> activity.sendAcousticDiagnosticMarker(
                "pro.garlyapp.app.action.CALIBRATION_MARK", safe));
        return true;
    }

    @JavascriptInterface
    public boolean exportAcousticCalibration() {
        if (!hasAcousticDiagnostics()) return false;
        mainHandler.post(() -> activity.sendAcousticDiagnosticAction(
                "pro.garlyapp.app.action.CALIBRATION_EXPORT"));
        return true;
    }

    @JavascriptInterface
    public String acousticDiagnosticStatus() {
        try {
            SharedPreferences diagnosticPrefs = context.getSharedPreferences(
                    "garly_acoustic_diagnostics",
                    Context.MODE_PRIVATE
            );
            JSONObject json = new JSONObject();
            json.put("available", hasAcousticDiagnostics());
            json.put("running", diagnosticPrefs.getBoolean("running", false));
            json.put("status", diagnosticPrefs.getString("status", "stopped"));
            json.put("label", diagnosticPrefs.getString("label", ""));
            json.put("score", diagnosticPrefs.getFloat("score", 0f));
            json.put("secondLabel", diagnosticPrefs.getString("second_label", ""));
            json.put("secondScore", diagnosticPrefs.getFloat("second_score", 0f));
            json.put("rms", diagnosticPrefs.getFloat("rms", 0f));
            json.put("peak", diagnosticPrefs.getFloat("peak", 0f));
            json.put("captureFrames", diagnosticPrefs.getLong("capture_frames", 0L));
            json.put("captureSamples", diagnosticPrefs.getLong("capture_samples", 0L));
            json.put("lastRead", diagnosticPrefs.getInt("last_read", 0));
            json.put("zeroFrames", diagnosticPrefs.getInt("zero_frames", 0));
            json.put("audioSource", diagnosticPrefs.getString("audio_source", ""));
            json.put("audioDanger", diagnosticPrefs.getFloat("audio_danger", 0f));
            json.put("motionScore", diagnosticPrefs.getFloat("motion_score", 0f));
            json.put("fusionScore", diagnosticPrefs.getFloat("fusion_score", 0f));
            json.put("evidence", diagnosticPrefs.getString("evidence", ""));
            json.put("alertState", diagnosticPrefs.getString("alert_state", "idle"));
            json.put("alertReason", diagnosticPrefs.getString("alert_reason", ""));
            json.put("recording", diagnosticPrefs.getBoolean("recording", false));
            json.put("recordingUntil", diagnosticPrefs.getLong("recording_until", 0L));
            json.put("clipReady", diagnosticPrefs.getBoolean("clip_ready", false));
            json.put("clipCreatedAt", diagnosticPrefs.getLong("clip_created_at", 0L));
            json.put("clipSize", diagnosticPrefs.getLong("clip_size", 0L));
            json.put("playbackState", diagnosticPrefs.getString("playback_state", "idle"));
            json.put("playbackError", diagnosticPrefs.getString("playback_error", ""));
            json.put("playbackUpdatedAt", diagnosticPrefs.getLong("playback_updated_at", 0L));
            json.put("exportState", diagnosticPrefs.getString("export_state", ""));
            json.put("exportTarget", diagnosticPrefs.getString("export_target", ""));
            json.put("exportError", diagnosticPrefs.getString("export_error", ""));
            json.put("calibrating", diagnosticPrefs.getBoolean("calibrating", false));
            json.put("calibrationRows", diagnosticPrefs.getInt("calibration_rows", 0));
            json.put("updatedAt", diagnosticPrefs.getLong("updated_at", 0L));
            json.put("error", diagnosticPrefs.getString("error", ""));
            return json.toString();
        } catch (Exception error) {
            return "{\"available\":false,\"running\":false,\"status\":\"error\"}";
        }
    }

    @JavascriptInterface
    public void startJourneyHomeWatch(double latitude, double longitude, double radius, String homeName, String language, String sessionId, String uploadUrl, String uploadToken, String liveJourneyId) {
        mainHandler.post(() -> {
            activity.requestNotificationPermission();
            Intent intent = new Intent(context, JourneyService.class)
                    .setAction(JourneyService.ACTION_START)
                    .putExtra("home_latitude", latitude)
                    .putExtra("home_longitude", longitude)
                    .putExtra("home_radius", radius)
                    .putExtra("home_name", homeName == null ? "Home" : homeName)
                    .putExtra("language", language == null ? "en" : language)
                    .putExtra("session_id", sessionId == null ? "" : sessionId)
                    .putExtra("upload_url", uploadUrl == null ? "" : uploadUrl)
                    .putExtra("upload_token", uploadToken == null ? "" : uploadToken)
                    .putExtra("live_journey_id", liveJourneyId == null ? "" : liveJourneyId);
            androidx.core.content.ContextCompat.startForegroundService(context, intent);
        });
    }

    @JavascriptInterface
    public void startLiveJourneyShare(String uploadUrl, String uploadToken, String liveJourneyId, String language, double expiresAt) {
        mainHandler.post(() -> {
            activity.requestNotificationPermission();
            Intent intent = new Intent(context, JourneyService.class)
                    .setAction(JourneyService.ACTION_START)
                    .putExtra("upload_only", true)
                    .putExtra("upload_url", uploadUrl == null ? "" : uploadUrl)
                    .putExtra("upload_token", uploadToken == null ? "" : uploadToken)
                    .putExtra("live_journey_id", liveJourneyId == null ? "" : liveJourneyId)
                    .putExtra("language", language == null ? "en" : language)
                    .putExtra("share_expires_at", (long) expiresAt)
                    .putExtra("session_id", "share:" + (liveJourneyId == null ? "" : liveJourneyId));
            androidx.core.content.ContextCompat.startForegroundService(context, intent);
        });
    }

    @JavascriptInterface
    public void completeJourneyHomeWatch(String homeName, String language, String sessionId) {
        mainHandler.post(() -> {
            activity.requestNotificationPermission();
            Intent intent = new Intent(context, JourneyService.class)
                    .setAction(JourneyService.ACTION_COMPLETE)
                    .putExtra("home_name", homeName == null ? "Home" : homeName)
                    .putExtra("language", language == null ? "en" : language)
                    .putExtra("session_id", sessionId == null ? "" : sessionId);
            androidx.core.content.ContextCompat.startForegroundService(context, intent);
        });
    }

    @JavascriptInterface
    public String consumeJourneyCompletion() {
        return JourneyService.consumeCompletion(context);
    }

    @JavascriptInterface
    public void stopJourneyHomeWatch() {
        mainHandler.post(() -> context.startService(new Intent(context, JourneyService.class).setAction(JourneyService.ACTION_STOP)));
    }

    /* ---------------- Garly Core subscriptions ---------------- */

    /**
     * Whether this build can take a payment at all.
     *
     * The page asks before showing anything about subscribing, so a build
     * without a working billing connection shows no paywall rather than a
     * button that leads nowhere. A dead purchase button is worse than no
     * purchase button, in a review and in a person's hands alike.
     */
    @JavascriptInterface
    public boolean hasBilling() {
        return billing != null && billing.isAvailable();
    }

    /** Open Google's payment sheet. The result is collected, not pushed. */
    @JavascriptInterface
    public boolean startCorePurchase() {
        if (billing == null) return false;
        final boolean[] started = { false };
        mainHandler.post(() -> billing.startCorePurchase());
        started[0] = true;
        return started[0];
    }

    /**
     * The last purchase result as JSON, once, or null.
     *
     * Polled rather than pushed because a purchase can finish while the WebView
     * is backgrounded or has been rebuilt, and a callback into a page that no
     * longer exists loses somebody's payment.
     */
    @JavascriptInterface
    public String consumePurchaseResult() {
        return billing == null ? null : billing.consumeResult();
    }

    /** Ask Google what this account already owns, for a reinstall or a new phone. */
    @JavascriptInterface
    public void restorePurchases() {
        if (billing != null) mainHandler.post(billing::restorePurchases);
    }

}
